package com.phantomhr.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "PhantomHR"

/**
 * Sealed representation of the BLE peripheral state, mirroring the Rust `BleState` enum.
 * [toJSObject] produces JSON that the Tauri bridge deserializes back to `BleState` in Rust.
 */
sealed class BleState {
    object Idle : BleState()
    object Advertising : BleState()
    data class Connected(val deviceName: String?, val deviceAddress: String) : BleState()
    data class Error(val message: String) : BleState()

    fun toJSObject(): app.tauri.plugin.JSObject = when (this) {
        is Idle -> app.tauri.plugin.JSObject().put("status", "idle")
        is Advertising -> app.tauri.plugin.JSObject().put("status", "advertising")
        is Connected -> app.tauri.plugin.JSObject()
            .put("status", "connected")
            .put("deviceName", deviceName ?: org.json.JSONObject.NULL)
            .put("deviceAddress", deviceAddress)
        is Error -> app.tauri.plugin.JSObject()
            .put("status", "error")
            .put("message", message)
    }
}

/**
 * Manages the full Android BLE peripheral stack: LE advertising + GATT server + HR notifications.
 *
 * Lifecycle:
 *   [start] → advertising → (central connects) → (central enables CCCD) → notifying
 *   [stop] or [destroy] → idle
 *
 * Thread safety: all public methods are safe to call from any thread. GATT callbacks arrive on
 * system binder threads. Notification coroutine runs on [Dispatchers.IO].
 *
 * Teardown safety: [teardown] nulls all resource references BEFORE closing them. Any callback
 * that fires synchronously during [BluetoothGattServer.close] will see null references and
 * return early, preventing use-after-close races.
 */
// Permissions are checked in BlePeripheralPlugin.startAdvertising() before any
// HeartRateGattServer method is called, and start() catches SecurityException
// for the runtime-revocation case. Lint cannot see cross-class permission flow.
@SuppressLint("MissingPermission")
class HeartRateGattServer(
    private val activity: Activity,
    private val generator: HrDataGenerator,
    private val onStateChanged: (BleState) -> Unit,
    private val onBpmTick: (Int) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val bluetoothManager = activity.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter get() = bluetoothManager.adapter

    // Coroutine scope for all BLE work. Cancelled only in destroy().
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Resource references — nulled before closing to prevent use-after-close.
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var hrCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var notifyJob: Job? = null
    @Volatile private var originalAdapterName: String? = null
    @Volatile private var sessionId: Long = 0L

    // Track whether CCCD notifications are currently enabled.
    @Volatile private var cccdEnabled = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Open the GATT server and begin advertising. Emits [BleState.Advertising] on success
     * or [BleState.Error] on failure.
     */
    fun start(config: HrConfig): Result<Unit> {
        sessionId += 1L
        val capturedSessionId = sessionId
        generator.updateConfig(config)

        try {
            if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
                val message = "BLE peripheral advertising not supported on this device"
                onStateChanged(BleState.Error(message))
                return Result.failure(IllegalStateException(message))
            }

            openGattServer(capturedSessionId).onFailure { error ->
                val message = error.message ?: "Failed to open GATT server"
                teardown()
                onStateChanged(BleState.Error(message))
                onLog("Stopped: $message")
                return Result.failure(error)
            }

            startAdvertising().onFailure { error ->
                val message = error.message ?: "Failed to start advertising"
                teardown()
                onStateChanged(BleState.Error(message))
                onLog("Stopped: $message")
                return Result.failure(error)
            }
            return Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "start: permission revoked", e)
            teardown()
            onStateChanged(BleState.Error("Bluetooth permission revoked"))
            onLog("Stopped: permission revoked")
            return Result.failure(IllegalStateException("Bluetooth permission revoked", e))
        }
    }

    /**
     * Stop advertising, disconnect any central, and emit [BleState.Idle].
     * [logMessage] lets callers distinguish user-initiated stops from lifecycle stops.
     */
    fun stop(logMessage: String = "Advertising stopped") {
        teardown()
        onStateChanged(BleState.Idle)
        onLog(logMessage)
    }

    /** Update the BPM generation parameters mid-session. */
    fun updateConfig(config: HrConfig) {
        generator.updateConfig(config)
    }

    /**
     * Like [stop] but also cancels the coroutine scope. Call this on plugin destroy
     * or when the server instance will never be reused.
     * Does NOT emit events — the caller handles state transitions.
     */
    fun destroy() {
        teardown()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Private — setup
    // -------------------------------------------------------------------------

    private fun openGattServer(capturedSessionId: Long): Result<Unit> {
        val server = bluetoothManager.openGattServer(activity, createGattServerCallback(capturedSessionId))
        if (server == null) {
            return Result.failure(IllegalStateException("Failed to open GATT server"))
        }

        val service = BluetoothGattService(
            BleConstants.HR_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // HR Measurement: NOTIFY only. GATT spec requires PERMISSION_READ on notify characteristics.
        val hrChar = BluetoothGattCharacteristic(
            BleConstants.HR_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        hrChar.addDescriptor(cccd)
        service.addCharacteristic(hrChar)

        // Body Sensor Location: READ, value = Wrist (0x02).
        val bslChar = BluetoothGattCharacteristic(
            BleConstants.BODY_SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(bslChar)

        if (!server.addService(service)) {
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "openGattServer: close after addService failure: $it") }
            return Result.failure(IllegalStateException("Failed to add Heart Rate service"))
        }
        hrCharacteristic = hrChar
        gattServer = server

        Log.d(TAG, "GATT server opened")
        return Result.success(Unit)
    }

    private fun startAdvertising(): Result<Unit> {
        val adv = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            closeGattAfterStartupFailure()
            return Result.failure(IllegalStateException("BLE LE advertiser not available"))
        }

        // Set adapter name so centrals see "PhantomHR" in scan results.
        originalAdapterName = bluetoothAdapter.name
        bluetoothAdapter.name = "PhantomHR"

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BleConstants.HR_SERVICE_UUID))
            .build()

        return try {
            advertiser = adv
            adv.startAdvertising(settings, data, advertiseCallback)
            Result.success(Unit)
        } catch (e: Exception) {
            advertiser = null
            closeGattAfterStartupFailure()
            Result.failure(IllegalStateException("Failed to start advertising: ${e.message}", e))
        }
    }

    private fun closeGattAfterStartupFailure() {
        val server = gattServer
        gattServer = null
        hrCharacteristic = null
        connectedDevice = null
        runCatching { server?.close() }
            .onFailure { Log.w(TAG, "closeGattAfterStartupFailure: $it") }
    }

    // -------------------------------------------------------------------------
    // Private — teardown
    // -------------------------------------------------------------------------

    /**
     * Tears down all BLE resources in the correct order:
     * 1. Cancel notification coroutine (never accesses gattServer after this)
     * 2. Stop advertiser
     * 3. Null all resource references BEFORE closing GATT server
     * 4. Close GATT server (may fire onConnectionStateChange synchronously — safe now)
     */
    private fun teardown() {
        // Step 1: cancel notification coroutine and generator
        val job = notifyJob
        notifyJob = null
        cccdEnabled = false
        job?.cancel()
        generator.stop()

        // Step 2: stop advertiser and restore original adapter name
        val adv = advertiser
        advertiser = null
        try {
            adv?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: $e")
        }
        originalAdapterName?.let { name ->
            try { bluetoothAdapter.name = name } catch (_: Exception) { }
            originalAdapterName = null
        }

        // Step 3: capture refs and null them BEFORE closing
        val server = gattServer
        val device = connectedDevice
        gattServer = null
        hrCharacteristic = null
        connectedDevice = null

        // Step 4: close GATT server (callbacks may fire synchronously — they check gattServer and
        // find it null, so they return early without accessing freed resources)
        device?.let {
            try { server?.cancelConnection(it) } catch (e: Exception) { /* ignore */ }
        }
        try {
            server?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gattServer.close: $e")
        }
    }

    // -------------------------------------------------------------------------
    // Private — notifications
    // -------------------------------------------------------------------------

    private fun beginNotifications(device: BluetoothDevice) {
        generator.start(scope)
        notifyJob = scope.launch {
            generator.bpmFlow.collect { bpm ->
                // Check refs inside collector — teardown may have nulled them
                val server = gattServer ?: return@collect
                val char = hrCharacteristic ?: return@collect
                sendHrNotification(server, device, char, bpm)
                onBpmTick(bpm)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendHrNotification(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        bpm: Int
    ) {
        // Payload: flags byte 0x00 (UINT8 format, no contact bit) + BPM byte
        val payload = byteArrayOf(0x00, (bpm and 0xFF).toByte())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = server.notifyCharacteristicChanged(device, char, false, payload)
                if (status != 0) {
                    Log.w(TAG, "notifyCharacteristicChanged failed: status=$status")
                }
            } else {
                // Pre-API-33 path: set value on characteristic, then notify (both deprecated in 33+)
                char.value = payload
                if (!server.notifyCharacteristicChanged(device, char, false)) {
                    Log.w(TAG, "notifyCharacteristicChanged returned false")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification failed: permission revoked", e)
        }
    }

    private fun safeSendResponse(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        runCatching { server.sendResponse(device, requestId, status, offset, value) }
            .onFailure { Log.w(TAG, "sendResponse failed: $it") }
    }

    // -------------------------------------------------------------------------
    // Advertise callback
    // -------------------------------------------------------------------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started")
            onStateChanged(BleState.Advertising)
            onLog("Advertising as \"PhantomHR\"\u2026")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed: error $errorCode")
            teardown()
            onStateChanged(BleState.Error("Advertising failed (code $errorCode)"))
        }
    }

    // -------------------------------------------------------------------------
    // GATT server callback
    // -------------------------------------------------------------------------

    private fun createGattServerCallback(capturedSessionId: Long) = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (capturedSessionId != sessionId) return
            if (gattServer == null) return

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Accept only the first central; ignore subsequent connections.
                    if (connectedDevice == null) {
                        connectedDevice = device
                        val name = try { device.name } catch (_: SecurityException) { null }
                        Log.d(TAG, "Central connected: ${device.address}")
                        onStateChanged(BleState.Connected(name, device.address))
                        onLog("Central connected: ${name ?: "Unknown"} \u00b7 ${device.address}")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device.address) {
                        Log.d(TAG, "Central disconnected: ${device.address}")

                        // Cancel notifications before clearing state.
                        val job = notifyJob
                        notifyJob = null
                        cccdEnabled = false
                        job?.cancel()
                        generator.stop()
                        connectedDevice = null

                        // Only go back to advertising if the server is still running
                        // (gattServer == null means teardown() was already called).
                        if (gattServer != null) {
                            onStateChanged(BleState.Advertising)
                            onLog("Central disconnected. Waiting for new connection\u2026")
                        }
                    }
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (capturedSessionId != sessionId) return

            // Capture server ref — may be null if teardown() raced with this callback.
            val server = gattServer
            if (server == null) {
                // Teardown already started; can't sendResponse safely — benign race, ignore.
                return
            }

            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                if (offset != 0) {
                    safeSendResponse(server, device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
                    return
                }

                if (preparedWrite) {
                    safeSendResponse(server, device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    return
                }

                if (value.size != 2) {
                    safeSendResponse(server, device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
                    return
                }

                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        safeSendResponse(server, device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                        if (!cccdEnabled) {
                            cccdEnabled = true
                            Log.d(TAG, "CCCD: notifications enabled by ${device.address}")
                            beginNotifications(device)
                        }
                    }

                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        safeSendResponse(server, device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                        if (cccdEnabled) {
                            cccdEnabled = false
                            Log.d(TAG, "CCCD: notifications disabled by ${device.address}")
                            val job = notifyJob
                            notifyJob = null
                            job?.cancel()
                            generator.stop()
                        }
                    }

                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> {
                        safeSendResponse(server, device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    }

                    else -> {
                        safeSendResponse(server, device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    }
                }
                return
            }

            safeSendResponse(server, device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (capturedSessionId != sessionId) return
            val server = gattServer ?: return
            val response = if (cccdEnabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            safeSendResponse(server, device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (capturedSessionId != sessionId) return
            val server = gattServer ?: return
            if (characteristic.uuid == BleConstants.BODY_SENSOR_LOCATION_UUID) {
                // Body Sensor Location: Wrist = 0x02
                safeSendResponse(server, device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0x02))
            } else {
                safeSendResponse(server, device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            }
        }
    }
}
