# PhantomHR

**A BLE Heart Rate peripheral simulator for developers.**

PhantomHR is a cross-platform mobile app that turns your phone into a Bluetooth Low Energy (BLE) Heart Rate peripheral. It advertises the standard [Heart Rate Service (0x180D)](https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/) with realistic, variable BPM data — no physical sensor required.

Built for developers testing BLE client implementations, PhantomHR is the spiritual successor to the archived [WebBluetoothCG/ble-test-peripheral-android](https://github.com/WebBluetoothCG/ble-test-peripheral-android), rebuilt from scratch with modern tooling and iOS support.

## Stack

| Layer | Technology |
|-------|-----------|
| Framework | [Tauri v2](https://v2.tauri.app/) (mobile targets) |
| Frontend | [Svelte 5](https://svelte.dev/) + TypeScript + [TailwindCSS v4](https://tailwindcss.com/) |
| Android BLE | Kotlin — `BluetoothLeAdvertiser` + `BluetoothGattServer` |
| iOS BLE | Swift — CoreBluetooth `CBPeripheralManager` |
| Bridge | Tauri plugin system (Rust commands + native mobile plugins) |

No third-party BLE libraries. Android SDK and CoreBluetooth only.

## Prerequisites

- [Node.js](https://nodejs.org/) >= 18
- [Rust](https://rustup.rs/) (stable toolchain)
- **Android:** [Android Studio](https://developer.android.com/studio) with SDK 26+ and NDK 27
- **iOS:** [Xcode](https://developer.apple.com/xcode/) 15+ with iOS 16+ SDK (macOS only)

## Build

```bash
# Install frontend dependencies
npm install

# Android debug APK (hot reload)
npm run tauri android dev

# Android release APK
npm run tauri android build -- --debug

# iOS development (macOS only)
npm run tauri ios init   # first time only
npm run tauri ios dev

# iOS release
npm run tauri ios build

# Browser preview (no BLE — UI only)
npm run tauri dev
```

## Usage

1. Build and install the APK/IPA on a physical device (BLE peripheral requires real hardware — emulators don't support it).
2. Launch PhantomHR.
3. Adjust **Base BPM** (40–200) and **Variance** (0–20) as desired.
4. Tap **Start Advertising**.
5. On a second device, open a BLE scanner such as [nRF Connect](https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile), [LightBlue](https://punchthrough.com/lightblue/), or [Vinthr](https://github.com/nicoryne/vinthr) and scan for **PhantomHR**.
6. Connect and subscribe to the Heart Rate Measurement characteristic — BPM values will stream at 1 Hz.
7. Adjust sliders while connected to see the BPM drift in real time.
8. Tap **Stop Advertising** or background the app to stop.

## BLE Profile

PhantomHR implements the Bluetooth SIG [Heart Rate Profile](https://www.bluetooth.com/specifications/specs/heart-rate-profile-1-0/):

| Attribute | UUID | Description |
|-----------|------|-------------|
| Heart Rate Service | `0x180D` | Primary service |
| Heart Rate Measurement | `0x2A37` | NOTIFY — BPM data at 1 Hz |
| Body Sensor Location | `0x2A38` | READ — returns `0x02` (Wrist) |
| CCCD | `0x2902` | Descriptor on HR Measurement |

### HR Measurement Payload

```
Byte 0: Flags   = 0x00  (UINT8 BPM format, no contact/energy/RR fields)
Byte 1: BPM     = 40–200 (variable)
```

## Architecture

```
Svelte Frontend (UI + invoke/listen)
        │
        ▼
Rust Plugin Layer (command routing)
        │
   ┌────┴────┐
   ▼         ▼
Kotlin     Swift
(Android)  (iOS)
   │         │
   ▼         ▼
BLE Radio (over the air) → Central Device
```

The frontend is purely UI — all BLE logic lives in the native plugin layer. Communication between layers uses:

- **Frontend → Native:** Tauri `invoke()` commands
- **Native → Frontend:** Tauri plugin events (`state-changed`, `bpm-tick`, `log`)

See [architecture.md](architecture.md) for the full layer diagram, state machine, data flow, threading model, and platform-specific BLE lifecycles.

## Screenshots

<!-- TODO: Add screenshots -->
| Idle | Advertising | Connected |
|------|-------------|-----------|
| ![Idle](screenshots/idle.png) | ![Advertising](screenshots/advertising.png) | ![Connected](screenshots/connected.png) |

## Reference Implementations

The `reference/` directory contains Git submodules of open-source React Native BLE libraries used as reference material during development. These are **not dependencies** of PhantomHR.

See [reference/README.md](reference/README.md) for details.

## License

[Apache-2.0](LICENSE) — matching the [original project](https://github.com/WebBluetoothCG/ble-test-peripheral-android).
