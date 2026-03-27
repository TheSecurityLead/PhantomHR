use serde::de::DeserializeOwned;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::models::HrConfig;

#[cfg(target_os = "android")]
const PLUGIN_IDENTIFIER: &str = "com.phantomhr.plugin";

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_ble_peripheral);

/// Initialise the platform plugin and return a handle for issuing commands.
/// Called only when cfg(mobile) is active (Android or iOS).
pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    api: PluginApi<R, C>,
) -> Result<BlePeripheral<R>, Box<dyn std::error::Error>> {
    #[cfg(target_os = "android")]
    let handle = api.register_android_plugin(PLUGIN_IDENTIFIER, "BlePeripheralPlugin")?;
    #[cfg(target_os = "ios")]
    let handle = api.register_ios_plugin(init_plugin_ble_peripheral)?;
    Ok(BlePeripheral(handle))
}

/// Wrapper around `PluginHandle` for issuing commands to the native layer.
/// Methods are called from Phase 4 native BLE logic; the generic `delegate`
/// helper in commands.rs uses the inner handle directly during Phase 3.
#[allow(dead_code)]
pub struct BlePeripheral<R: Runtime>(pub PluginHandle<R>);

impl<R: Runtime> BlePeripheral<R> {
    pub fn start_advertising(&self, config: &HrConfig) -> Result<(), String> {
        self.0
            .run_mobile_plugin::<()>("startAdvertising", config)
            .map_err(|e| e.to_string())
    }

    pub fn stop_advertising(&self) -> Result<(), String> {
        self.0
            .run_mobile_plugin::<()>("stopAdvertising", ())
            .map_err(|e| e.to_string())
    }

    pub fn update_config(&self, config: &HrConfig) -> Result<(), String> {
        self.0
            .run_mobile_plugin::<()>("updateConfig", config)
            .map_err(|e| e.to_string())
    }
}
