package com.phantomhr.plugin

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

private const val TAG = "PhantomHR"

/** Argument type for commands that accept HR configuration. */
@InvokeArg
data class HrConfig(var baseBpm: Int = 72, var variance: Int = 5)

@TauriPlugin
class BlePeripheralPlugin(private val activity: Activity) : Plugin(activity) {

    @Volatile private var gattServer: HeartRateGattServer? = null
    @Volatile private var currentState: BleState = BleState.Idle

    // -------------------------------------------------------------------------
    // Bluetooth adapter state receiver
    // -------------------------------------------------------------------------

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

            when (state) {
                BluetoothAdapter.STATE_OFF -> {
                    val server = gattServer ?: return
                    Log.d(TAG, "Bluetooth turned off — tearing down")
                    gattServer = null
                    server.destroy()

                    currentState = BleState.Error("Bluetooth turned off")
                    trigger("state-changed", currentState.toJSObject())
                    trigger("log", JSObject()
                        .put("message", "Stopped: Bluetooth turned off")
                        .put("timestamp", System.currentTimeMillis())
                        .put("level", "error"))
                }

                BluetoothAdapter.STATE_ON -> {
                    if (currentState is BleState.Error) {
                        Log.d(TAG, "Bluetooth restored — returning to idle")
                        currentState = BleState.Idle
                        trigger("state-changed", currentState.toJSObject())
                        trigger("log", JSObject()
                            .put("message", "Bluetooth restored")
                            .put("timestamp", System.currentTimeMillis())
                            .put("level", "info"))
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        activity.registerReceiver(btStateReceiver, filter)
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    @Command
    fun startAdvertising(invoke: Invoke) {
        val config = invoke.parseArgs(HrConfig::class.java)
        Log.d(TAG, "startAdvertising: baseBpm=${config.baseBpm}, variance=${config.variance}")

        // Runtime permission check for API 31+ (BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = listOf(
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                invoke.reject("Missing Bluetooth permissions: ${missing.joinToString()}")
                return
            }
        }

        // If already running, stop cleanly before restarting (supports rapid start/stop).
        gattServer?.let { old ->
            gattServer = null
            old.destroy()
        }

        val bluetoothAdapter = activity
            .getSystemService(BluetoothManager::class.java)
            .adapter
        if (!bluetoothAdapter.isEnabled) {
            invoke.reject("Bluetooth is disabled")
            return
        }

        val generator = HrDataGenerator(config)
        val server = HeartRateGattServer(
            activity = activity,
            generator = generator,
            onStateChanged = { state ->
                currentState = state
                trigger("state-changed", state.toJSObject())
            },
            onBpmTick = { bpm ->
                val payload = JSObject()
                    .put("bpm", bpm)
                    .put("timestamp", System.currentTimeMillis())
                trigger("bpm-tick", payload)
            },
            onLog = { message ->
                val payload = JSObject()
                    .put("message", message)
                    .put("timestamp", System.currentTimeMillis())
                    .put("level", "info")
                trigger("log", payload)
            }
        )
        gattServer = server
        val result = server.start(config)
        result.fold(
            onSuccess = {
                invoke.resolve()
            },
            onFailure = { error ->
                gattServer = null
                invoke.reject(error.message ?: "Failed to start BLE advertising")
            }
        )
    }

    @Command
    fun stopAdvertising(invoke: Invoke) {
        Log.d(TAG, "stopAdvertising")
        val server = gattServer
        gattServer = null
        server?.stop()
        invoke.resolve()
    }

    @Command
    fun updateConfig(invoke: Invoke) {
        val config = invoke.parseArgs(HrConfig::class.java)
        Log.d(TAG, "updateConfig: baseBpm=${config.baseBpm}, variance=${config.variance}")
        val running = currentState is BleState.Advertising || currentState is BleState.Connected
        if (!running) {
            invoke.reject("Simulator is not running")
            return
        }

        val server = gattServer
        if (server == null) {
            invoke.reject("Simulator is not running")
            return
        }

        server.updateConfig(config)
        invoke.resolve()
    }

    @Command
    fun getState(invoke: Invoke) {
        invoke.resolve(currentState.toJSObject())
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * App went to background — stop advertising to conserve resources and avoid
     * unreliable background BLE behaviour on Android.
     */
    override fun onStop() {
        val server = gattServer ?: return
        Log.d(TAG, "onStop: stopping BLE peripheral")
        gattServer = null
        currentState = BleState.Idle
        server.stop("Stopped: app backgrounded")
    }

    /** Activity is being destroyed — release all resources including the coroutine scope. */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy: destroying BLE peripheral")
        val server = gattServer
        gattServer = null
        currentState = BleState.Idle
        server?.destroy()

        try { activity.unregisterReceiver(btStateReceiver) } catch (_: Exception) { }
    }
}
