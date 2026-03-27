use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

mod commands;
mod models;
pub use models::*;

#[cfg(mobile)]
mod mobile;

/// Initialise and return the BLE peripheral plugin.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("ble-peripheral")
        .setup(|app, api| {
            #[cfg(mobile)]
            {
                let ble = mobile::init(app, api)?;
                app.manage(ble);
            }
            #[cfg(not(mobile))]
            {
                // Suppress unused-variable warnings on desktop.
                let _ = (app, api);
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::start_advertising,
            commands::stop_advertising,
            commands::update_config,
            commands::get_state,
        ])
        .build()
}
