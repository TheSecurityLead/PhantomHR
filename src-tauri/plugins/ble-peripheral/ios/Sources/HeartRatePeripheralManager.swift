import CoreBluetooth
import os.log

private let hrLog = OSLog(subsystem: "com.phantomhr.app", category: "BLE")

// Standard Heart Rate Service UUIDs
private let HR_SERVICE_UUID = CBUUID(string: "180D")
private let HR_MEASUREMENT_UUID = CBUUID(string: "2A37")
private let BODY_SENSOR_LOCATION_UUID = CBUUID(string: "2A38")

private enum HeartRatePeripheralStartError: LocalizedError {
    case bluetoothPermissionDenied
    case invalidBaseBpm
    case invalidVariance

    var errorDescription: String? {
        switch self {
        case .bluetoothPermissionDenied:
            return "Bluetooth permission denied"
        case .invalidBaseBpm:
            return "Invalid base BPM (expected 40-200)"
        case .invalidVariance:
            return "Invalid variance (expected 0-20)"
        }
    }
}

/// Manages the full iOS BLE peripheral stack: advertising + HR service + notifications.
///
/// All CBPeripheralManager operations happen on a dedicated serial DispatchQueue.
/// The HrDataGenerator timer fires on the same queue, so no cross-thread state
/// synchronisation is required.
///
/// Backpressure: ``updateValue(_:for:onSubscribedCentrals:)`` returns false when
/// the transmit queue is full. When that happens, we stop sending until
/// ``peripheralManagerIsReady(toUpdateSubscribers:)`` fires, then resume with
/// the latest BPM value.
class HeartRatePeripheralManager: NSObject {

    private let bleQueue = DispatchQueue(label: "com.phantomhr.ble", qos: .userInitiated)

    private var peripheralManager: CBPeripheralManager?
    private var hrCharacteristic: CBMutableCharacteristic?
    private var generator: HrDataGenerator?
    private var subscribedCentral: CBCentral?
    private var subscribedCentralId: UUID?

    // Backpressure tracking
    private var readyToUpdate = true
    private var pendingBpm: Int?

    // Set when BT is toggled off while running — tells us to transition to Idle
    // (not re-advertise) when BT comes back on.
    private var awaitingBtRestore = false
    private var sessionId = 0
    private var activeSessionId = 0
    private var activeManagerId: ObjectIdentifier?

    // Callbacks to the plugin layer
    var onStateChanged: (([String: Any]) -> Void)?
    var onBpmTick: ((Int) -> Void)?
    var onLog: ((String) -> Void)?

    // MARK: - Public API

    func start(baseBpm: Int, variance: Int) -> Result<Void, Error> {
        if !(40...200).contains(baseBpm) {
            return .failure(HeartRatePeripheralStartError.invalidBaseBpm)
        }
        if !(0...20).contains(variance) {
            return .failure(HeartRatePeripheralStartError.invalidVariance)
        }
        switch CBPeripheralManager.authorization {
        case .denied, .restricted:
            return .failure(HeartRatePeripheralStartError.bluetoothPermissionDenied)
        default:
            break
        }

        sessionId += 1
        let currentSessionId = sessionId

        bleQueue.async { [self] in
            guard currentSessionId == sessionId else { return }
            generator = HrDataGenerator(baseBpm: baseBpm, variance: variance, queue: bleQueue)
            let pm = CBPeripheralManager(delegate: nil, queue: bleQueue)
            peripheralManager = pm
            activeSessionId = currentSessionId
            activeManagerId = ObjectIdentifier(pm)
            pm.delegate = self
            // peripheralManagerDidUpdateState will fire once the manager is ready.
        }
        return .success(())
    }

    func stop(logMessage: String = "Advertising stopped") {
        bleQueue.async { [self] in
            sessionId += 1
            teardown()
            onStateChanged?(["status": "idle"])
            onLog?(logMessage)
        }
    }

    func updateConfig(baseBpm: Int, variance: Int) {
        bleQueue.async { [self] in
            generator?.updateConfig(baseBpm: baseBpm, variance: variance)
        }
    }

    /// Full cleanup without emitting events. Called when the plugin is being destroyed.
    func destroy() {
        bleQueue.sync { [self] in
            sessionId += 1
            teardown()
        }
    }

    // MARK: - Private — teardown

    private func teardown() {
        // 1. Stop generator timer
        generator?.stop()
        generator = nil

        // 2. Clear subscription tracking
        subscribedCentral = nil
        subscribedCentralId = nil
        hrCharacteristic = nil
        readyToUpdate = true
        pendingBpm = nil
        awaitingBtRestore = false
        activeManagerId = nil
        activeSessionId = 0

        // 3. Stop advertising and remove services
        if let pm = peripheralManager {
            if pm.isAdvertising {
                pm.stopAdvertising()
            }
            pm.removeAllServices()
        }
        peripheralManager = nil
    }

    // MARK: - Private — service setup

    private func buildAndAddService() {
        let hrChar = CBMutableCharacteristic(
            type: HR_MEASUREMENT_UUID,
            properties: [.notify],
            value: nil, // dynamic value — delivered via notifications
            permissions: []
        )

        let bslChar = CBMutableCharacteristic(
            type: BODY_SENSOR_LOCATION_UUID,
            properties: [.read],
            value: Data([0x02]), // Wrist
            permissions: [.readable]
        )

        let service = CBMutableService(type: HR_SERVICE_UUID, primary: true)
        service.characteristics = [hrChar, bslChar]

        hrCharacteristic = hrChar
        peripheralManager?.add(service)
    }

    // MARK: - Private — notifications

    private func beginNotifications() {
        generator?.start { [weak self] bpm in
            guard let self = self else { return }
            self.sendNotification(bpm: bpm)
            self.onBpmTick?(bpm)
        }
    }

    private func sendNotification(bpm: Int) {
        guard let pm = peripheralManager, let char = hrCharacteristic, let central = subscribedCentral else { return }
        // HR Measurement payload: flags 0x00 (UINT8 format) + BPM byte
        let payload = Data([0x00, UInt8(clamping: bpm)])

        if readyToUpdate {
            let sent = pm.updateValue(payload, for: char, onSubscribedCentrals: [central])
            if !sent {
                // Transmit queue full — wait for peripheralManagerIsReady callback
                readyToUpdate = false
                pendingBpm = bpm
            }
        } else {
            // Still waiting for the queue to drain; keep only the latest value
            pendingBpm = bpm
        }
    }
}

// MARK: - CBPeripheralManagerDelegate

extension HeartRatePeripheralManager: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let currentSessionId = activeSessionId
        guard currentSessionId == self.sessionId else { return }
        guard activeManagerId == ObjectIdentifier(peripheral) else { return }

        os_log("peripheralManagerDidUpdateState: %d", log: hrLog, type: .info, peripheral.state.rawValue)

        guard peripheral.state == .poweredOn else {
            let msg: String
            switch peripheral.state {
            case .unauthorized:
                msg = "Bluetooth permission denied"
            case .poweredOff:
                msg = "Bluetooth is powered off"
            case .unsupported:
                msg = "BLE peripheral not supported on this device"
            default:
                msg = "Bluetooth unavailable (state: \(peripheral.state.rawValue))"
            }

            // If we were running (have resources), clean up but keep the
            // peripheralManager alive so we can detect BT being restored.
            let wasRunning = generator != nil || subscribedCentral != nil || hrCharacteristic != nil
            if wasRunning {
                generator?.stop()
                generator = nil
                subscribedCentral = nil
                subscribedCentralId = nil
                hrCharacteristic = nil
                readyToUpdate = true
                pendingBpm = nil
                awaitingBtRestore = true
                // Don't call stopAdvertising/removeAllServices — BT is off, they are no-ops.
            }

            onStateChanged?(["status": "error", "message": msg])
            return
        }

        // BT is powered on.
        if awaitingBtRestore {
            // BT was toggled back on after going off while running.
            // Return to Idle — user must explicitly tap Start again.
            awaitingBtRestore = false
            peripheral.removeAllServices()
            peripheralManager = nil
            onStateChanged?(["status": "idle"])
            onLog?("Bluetooth restored")
            return
        }

        // Normal startup — build service and begin advertising.
        buildAndAddService()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        let currentSessionId = activeSessionId
        guard currentSessionId == self.sessionId else { return }
        guard activeManagerId == ObjectIdentifier(peripheral) else { return }

        if let error = error {
            os_log("Failed to add service: %{public}@", log: hrLog, type: .error, error.localizedDescription)
            onStateChanged?(["status": "error", "message": "Failed to add HR service: \(error.localizedDescription)"])
            return
        }

        os_log("HR service added, starting advertising", log: hrLog, type: .info)
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [HR_SERVICE_UUID],
            CBAdvertisementDataLocalNameKey: "PhantomHR"
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        let currentSessionId = activeSessionId
        guard currentSessionId == self.sessionId else { return }
        guard activeManagerId == ObjectIdentifier(peripheral) else { return }

        if let error = error {
            os_log("Advertising failed: %{public}@", log: hrLog, type: .error, error.localizedDescription)
            teardown()
            onStateChanged?(["status": "error", "message": "Advertising failed: \(error.localizedDescription)"])
            return
        }

        os_log("Advertising started", log: hrLog, type: .info)
        onStateChanged?(["status": "advertising"])
        onLog?("Advertising as \"PhantomHR\"\u{2026}")
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        let currentSessionId = activeSessionId
        guard currentSessionId == self.sessionId else { return }
        guard activeManagerId == ObjectIdentifier(peripheral) else { return }

        if let existingId = subscribedCentralId, existingId != central.identifier {
            os_log("Ignoring additional central subscription: %{public}@", log: hrLog, type: .info, central.identifier.uuidString)
            return
        }
        if subscribedCentralId == central.identifier {
            return
        }

        os_log("Central subscribed: %{public}@", log: hrLog, type: .info, central.identifier.uuidString)
        subscribedCentral = central
        subscribedCentralId = central.identifier

        // iOS does not expose central device names
        onStateChanged?([
            "status": "connected",
            "deviceName": NSNull(),
            "deviceAddress": central.identifier.uuidString
        ])
        onLog?("Central connected: \(central.identifier.uuidString)")

        beginNotifications()
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        let currentSessionId = activeSessionId
        guard currentSessionId == self.sessionId else { return }
        guard activeManagerId == ObjectIdentifier(peripheral) else { return }
        guard subscribedCentralId == central.identifier else { return }

        os_log("Central unsubscribed: %{public}@", log: hrLog, type: .info, central.identifier.uuidString)

        generator?.stop()
        subscribedCentral = nil
        subscribedCentralId = nil
        readyToUpdate = true
        pendingBpm = nil

        // Return to advertising state if the manager is still alive
        if peripheralManager != nil {
            onStateChanged?(["status": "advertising"])
            onLog?("Central disconnected. Waiting for new connection\u{2026}")
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        let currentSessionId = activeSessionId
        guard currentSessionId == self.sessionId else { return }
        guard activeManagerId == ObjectIdentifier(peripheral) else { return }

        // Transmit queue drained — resume sending
        readyToUpdate = true
        if let bpm = pendingBpm {
            pendingBpm = nil
            sendNotification(bpm: bpm)
        }
    }
}
