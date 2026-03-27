# Reference Implementations

This directory contains Git submodules of open-source React Native BLE libraries. These are **reference material only** — they are not dependencies of PhantomHR and are not included in any build.

## Contents

### react-native-ble-plx

[dotintent/react-native-ble-plx](https://github.com/dotintent/react-native-ble-plx) — A comprehensive React Native BLE library. Referenced during development for its approach to:

- BLE peripheral scanning and connection management
- GATT service/characteristic discovery patterns
- Cross-platform (Android/iOS) BLE abstraction design

### react-native-health-connect

[matinzd/react-native-health-connect](https://github.com/matinzd/react-native-health-connect) — A React Native wrapper for Google Health Connect. Referenced for its approach to:

- Health data type definitions and serialization
- Android permission handling patterns for health-related APIs
- Tauri/React Native plugin architecture comparison

## Why these are here

PhantomHR exists to test BLE client implementations without physical hardware. These reference libraries represent the kind of client-side code that PhantomHR is designed to test against. Studying their BLE interaction patterns informed PhantomHR's GATT server design and HR service conformance.

## Cloning

These submodules are not required to build PhantomHR. To clone them:

```bash
git submodule update --init --recursive
```
