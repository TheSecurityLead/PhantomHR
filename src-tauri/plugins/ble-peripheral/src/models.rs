use serde::{Deserialize, Serialize};

/// Configuration passed from the frontend to control BPM generation.
/// Fields are camelCase to match the TypeScript interface.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HrConfig {
    pub base_bpm: u32,
    pub variance: u32,
}

/// Current state of the BLE peripheral.
/// Serialized with an internally-tagged "status" field in camelCase
/// to match the TypeScript discriminated union.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum BleState {
    Idle,
    Advertising,
    Connected {
        device_name: Option<String>,
        device_address: String,
    },
    Error {
        message: String,
    },
}
