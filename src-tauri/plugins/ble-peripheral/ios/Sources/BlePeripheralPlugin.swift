import SwiftRs
import Tauri
import UIKit
import WebKit

// MARK: - Argument types

struct HrConfigArgs: Decodable {
    let baseBpm: Int
    let variance: Int
}

// MARK: - Plugin

class BlePeripheralPlugin: Plugin {

    private var manager: HeartRatePeripheralManager?
    private var currentState: [String: Any] = ["status": "idle"]
    private var backgroundObserver: NSObjectProtocol?

    override func load(webview: WKWebView) {
        // Stop advertising when the app moves to background — BLE peripheral
        // advertising is unreliable in background on iOS.
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self, self.manager != nil else { return }
            let mgr = self.manager
            self.manager = nil
            self.currentState = ["status": "idle"]
            mgr?.stop(logMessage: "Stopped: app backgrounded")
        }
    }

    deinit {
        if let obs = backgroundObserver {
            NotificationCenter.default.removeObserver(obs)
        }
        manager?.destroy()
    }

    // MARK: - Commands

    @objc public func startAdvertising(_ invoke: Invoke) throws {
        let args = try invoke.parseArgs(HrConfigArgs.self)

        // If already running, stop cleanly before restarting (supports rapid start/stop).
        if let old = manager {
            manager = nil
            old.destroy()
        }

        let mgr = HeartRatePeripheralManager()

        mgr.onStateChanged = { [weak self] state in
            guard let self = self else { return }
            self.currentState = state
            self.trigger("state-changed", data: state)
        }

        mgr.onBpmTick = { [weak self] bpm in
            guard let self = self else { return }
            let payload: [String: Any] = [
                "bpm": bpm,
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            self.trigger("bpm-tick", data: payload)
        }

        mgr.onLog = { [weak self] message in
            guard let self = self else { return }
            let payload: [String: Any] = [
                "message": message,
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000),
                "level": "info"
            ]
            self.trigger("log", data: payload)
        }

        switch mgr.start(baseBpm: args.baseBpm, variance: args.variance) {
        case .success:
            manager = mgr
            invoke.resolve()
        case .failure(let error):
            invoke.reject(error.localizedDescription)
        }
    }

    @objc public func stopAdvertising(_ invoke: Invoke) throws {
        let mgr = manager
        manager = nil
        mgr?.stop()
        invoke.resolve()
    }

    @objc public func updateConfig(_ invoke: Invoke) throws {
        let args = try invoke.parseArgs(HrConfigArgs.self)
        let status = currentState["status"] as? String ?? "idle"
        let running = status == "advertising" || status == "connected"
        guard running, let mgr = manager else {
            invoke.reject("Simulator is not running")
            return
        }
        mgr.updateConfig(baseBpm: args.baseBpm, variance: args.variance)
        invoke.resolve()
    }

    @objc public func getState(_ invoke: Invoke) throws {
        invoke.resolve(currentState)
    }
}

// MARK: - Plugin registration

// The function name must match the Rust binding:
//   tauri::ios_plugin_binding!(init_plugin_ble_peripheral);
@_cdecl("init_plugin_ble_peripheral")
func initPlugin() -> Plugin {
    return BlePeripheralPlugin()
}
