import { invoke, addPluginListener } from '@tauri-apps/api/core';
import { writable } from 'svelte/store';

/** Configuration for the BPM data generator on the native side. */
export interface HrConfig {
  /** Center BPM value (40–200). The generator walks around this base. */
  baseBpm: number;
  /** Maximum deviation from baseBpm (0–20). */
  variance: number;
}

/**
 * Discriminated union representing the BLE peripheral state.
 * Mirrors `BleState` in Rust (`models.rs`) — field names match via
 * `#[serde(rename_all = "camelCase")]`.
 */
export type BleState =
  | { status: 'idle' }
  | { status: 'advertising' }
  | { status: 'connected'; deviceName: string | null; deviceAddress: string }
  | { status: 'error'; message: string };

/** Payload emitted by the native layer on each 1 Hz BPM tick. */
export interface BpmTick {
  bpm: number;
  /** Unix epoch milliseconds. */
  timestamp: number;
}

/** A log entry emitted by the native BLE layer. */
export interface LogEntry {
  message: string;
  /** Unix epoch milliseconds. */
  timestamp: number;
  /** Severity: "info", "warn", or "error". */
  level: string;
}

/** Reactive store for the current BLE peripheral state. Driven by native events. */
export const bleState = writable<BleState>({ status: 'idle' });

/** Reactive store for the most recent BPM value. Updated on each `bpm-tick` event. */
export const currentBpm = writable<number>(0);

/** Reactive store accumulating log entries from the native BLE layer. */
export const logEntries = writable<LogEntry[]>([]);

/**
 * Register plugin event listeners for the three native event channels.
 * Must be called once on component mount.
 *
 * Events emitted by the native layer (Kotlin `Plugin.trigger()` /
 * Swift `Plugin.trigger()`):
 * - `state-changed` — BLE state transitions (idle/advertising/connected/error)
 * - `bpm-tick` — 1 Hz heart rate value + timestamp
 * - `log` — human-readable log entries for the event log UI
 *
 * @returns A cleanup function that unregisters all listeners. Call on component destroy.
 */
export async function initListeners(): Promise<() => void> {
  const [stateListener, bpmListener, logListener] = await Promise.all([
    addPluginListener<BleState>('ble-peripheral', 'state-changed', (state) => {
      bleState.set(state);
    }),
    addPluginListener<BpmTick>('ble-peripheral', 'bpm-tick', ({ bpm, timestamp }) => {
      currentBpm.set(bpm);
      logEntries.update((entries) => [
        ...entries,
        { message: `\u2665 ${bpm} BPM`, timestamp, level: 'info' },
      ]);
    }),
    addPluginListener<LogEntry>('ble-peripheral', 'log', (entry) => {
      logEntries.update((entries) => [...entries, entry]);
    }),
  ]);

  return () => {
    stateListener.unregister();
    bpmListener.unregister();
    logListener.unregister();
  };
}

/**
 * Start BLE advertising with the given HR configuration.
 * Creates a GATT server, adds the HR service, and begins LE advertising.
 * If already advertising, the native layer stops the old session first.
 */
export async function startAdvertising(config: HrConfig): Promise<void> {
  await invoke('plugin:ble-peripheral|start_advertising', { config });
}

/** Stop BLE advertising and close the GATT server. No-op if already idle. */
export async function stopAdvertising(): Promise<void> {
  await invoke('plugin:ble-peripheral|stop_advertising');
}

/**
 * Update the BPM generator parameters while advertising.
 * The current BPM drifts toward the new base naturally — no abrupt jump.
 */
export async function updateConfig(config: HrConfig): Promise<void> {
  await invoke('plugin:ble-peripheral|update_config', { config });
}

/** Query the native layer for the current BLE peripheral state. */
export async function getState(): Promise<BleState> {
  return invoke<BleState>('plugin:ble-peripheral|get_state');
}
