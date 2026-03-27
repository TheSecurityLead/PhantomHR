use tauri::{AppHandle, Manager, Runtime};

use crate::models::{BleState, HrConfig};

#[tauri::command]
pub fn start_advertising<R: Runtime>(app: AppHandle<R>, config: HrConfig) -> Result<(), String> {
    log::info!("start_advertising called");
    delegate(&app, "startAdvertising", &config)
}

#[tauri::command]
pub fn stop_advertising<R: Runtime>(app: AppHandle<R>) -> Result<(), String> {
    log::info!("stop_advertising called");
    delegate(&app, "stopAdvertising", &())
}

#[tauri::command]
pub fn update_config<R: Runtime>(app: AppHandle<R>, config: HrConfig) -> Result<(), String> {
    log::info!("update_config called");
    delegate(&app, "updateConfig", &config)
}

#[tauri::command]
pub fn get_state<R: Runtime>(app: AppHandle<R>) -> Result<BleState, String> {
    log::info!("get_state called");
    get_state_impl(&app)
}

/// On mobile: forward a command to the Kotlin/Swift plugin layer.
/// On desktop: no-op (commands are stubs; BLE requires a real device).
#[cfg(mobile)]
fn delegate<R: Runtime, A: serde::Serialize>(
    app: &AppHandle<R>,
    cmd: &str,
    args: &A,
) -> Result<(), String> {
    app.state::<crate::mobile::BlePeripheral<R>>()
        .0
        .run_mobile_plugin::<()>(cmd, args)
        .map_err(|e| e.to_string())
}

#[cfg(not(mobile))]
fn delegate<R: Runtime, A: serde::Serialize>(
    _app: &AppHandle<R>,
    _cmd: &str,
    _args: &A,
) -> Result<(), String> {
    Ok(())
}

/// On mobile: query the Kotlin layer for the current BleState.
/// On desktop: always returns Idle.
#[cfg(mobile)]
fn get_state_impl<R: Runtime>(app: &AppHandle<R>) -> Result<BleState, String> {
    app.state::<crate::mobile::BlePeripheral<R>>()
        .0
        .run_mobile_plugin::<BleState>("getState", ())
        .map_err(|e| e.to_string())
}

#[cfg(not(mobile))]
fn get_state_impl<R: Runtime>(_app: &AppHandle<R>) -> Result<BleState, String> {
    Ok(BleState::Idle)
}
