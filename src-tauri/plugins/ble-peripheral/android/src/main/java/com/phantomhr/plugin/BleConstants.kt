package com.phantomhr.plugin

import java.util.UUID

/**
 * Standard Bluetooth SIG UUIDs for the Heart Rate Service (Assigned Number 0x180D).
 *
 * Full 128-bit UUIDs use the Bluetooth Base UUID:
 *   `0000XXXX-0000-1000-8000-00805f9b34fb` where XXXX is the 16-bit assigned number.
 */
internal object BleConstants {
    /** Heart Rate Service — primary service UUID. */
    val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    /** Heart Rate Measurement — NOTIFY characteristic carrying the BPM payload. */
    val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    /** Body Sensor Location — READ characteristic, static value 0x02 (Wrist). */
    val BODY_SENSOR_LOCATION_UUID: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration Descriptor — enables/disables notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
