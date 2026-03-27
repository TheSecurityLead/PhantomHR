<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import {
    bleState,
    currentBpm,
    logEntries,
    startAdvertising,
    stopAdvertising,
    updateConfig,
    initListeners,
    type BleState,
  } from './ble.js';
  import StatusBadge from './StatusBadge.svelte';
  import BpmControls from './BpmControls.svelte';
  import EventLog from './EventLog.svelte';

  let baseBpm = $state(72);
  let variance = $state(5);
  let isBusy = $state(false);

  // Mirror stores into local reactive state
  let status = $state<BleState>({ status: 'idle' });
  let bpm = $state(72);
  let entries = $state<Array<{ message: string; timestamp: number; level: string }>>([]);

  const unsubState = bleState.subscribe((s) => {
    status = s;
    // Reset displayed BPM to baseBpm when peripheral goes idle
    if (s.status === 'idle' || s.status === 'error') {
      bpm = baseBpm;
    }
  });
  const unsubBpm = currentBpm.subscribe((b) => {
    if (b > 0) bpm = b;
  });
  const unsubLog = logEntries.subscribe((l) => {
    entries = l;
  });

  let cleanupListeners: (() => void) | null = null;
  let mounted = false;

  const isRunning = $derived(status.status === 'advertising' || status.status === 'connected');

  // Forward config changes to the native layer while advertising.
  // `mounted` guard prevents the initial effect run from firing before the plugin is ready.
  $effect(() => {
    const b = baseBpm;
    const v = variance;
    if (mounted && isRunning) {
      updateConfig({ baseBpm: b, variance: v }).catch((e) =>
        console.error('updateConfig failed:', e)
      );
    }
  });

  onMount(async () => {
    cleanupListeners = await initListeners();
    mounted = true;
  });

  async function handleToggle() {
    if (isBusy) return;
    isBusy = true;
    try {
      if (!isRunning) {
        await startAdvertising({ baseBpm, variance });
      } else {
        await stopAdvertising();
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.error('BLE command failed:', msg);
      bleState.set({ status: 'error', message: msg });
      logEntries.update((l) => [
        ...l,
        { message: `Error: ${msg}`, timestamp: Date.now(), level: 'error' },
      ]);
    } finally {
      isBusy = false;
    }
  }

  onDestroy(() => {
    unsubState();
    unsubBpm();
    unsubLog();
    cleanupListeners?.();
  });
</script>

<div class="min-h-screen bg-gray-950 px-4 py-6 text-white">
  <div class="mx-auto max-w-sm space-y-5">

    <!-- Header -->
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-lg font-bold tracking-wide text-white">PhantomHR</h1>
        <p class="text-xs text-gray-600">BLE Heart Rate Simulator</p>
      </div>
      <StatusBadge status={status.status} />
    </div>

    <!-- BPM Display -->
    <div class="rounded-xl bg-gray-900 px-6 py-8 text-center ring-1 ring-gray-800">
      <div
        class="font-mono text-8xl font-bold tabular-nums leading-none transition-colors duration-300
          {isRunning ? 'text-white' : 'text-gray-700'}"
      >
        {bpm}
      </div>
      <div class="mt-2 text-xs font-semibold uppercase tracking-widest text-gray-500">BPM</div>

      {#if status.status === 'advertising'}
        <p class="mt-4 text-xs text-amber-500">Waiting for central to connect&hellip;</p>
      {:else if status.status === 'connected'}
        <p class="mt-4 text-xs text-green-500">
          {status.deviceName ?? 'Unknown Device'} &middot; {status.deviceAddress}
        </p>
      {:else if status.status === 'error'}
        <p class="mt-4 text-xs text-red-500">{status.message}</p>
      {:else}
        <p class="mt-4 text-xs text-gray-700">Not advertising</p>
      {/if}
    </div>

    <!-- Configuration -->
    <div class="rounded-xl bg-gray-900 p-5 ring-1 ring-gray-800">
      <h2 class="mb-4 text-xs font-semibold uppercase tracking-widest text-gray-500">
        Configuration
      </h2>
      <BpmControls bind:baseBpm bind:variance />
    </div>

    <!-- Start / Stop -->
    <button
      onclick={handleToggle}
      disabled={isBusy}
      class="w-full rounded-xl py-4 text-base font-semibold transition-colors duration-150
        disabled:cursor-not-allowed disabled:opacity-50
        {isRunning
          ? 'bg-red-700 text-white hover:bg-red-600 active:bg-red-800'
          : 'bg-blue-600 text-white hover:bg-blue-500 active:bg-blue-700'}"
    >
      {#if isBusy && !isRunning}
        Starting&hellip;
      {:else if isRunning}
        Stop Advertising
      {:else}
        Start Advertising
      {/if}
    </button>

    <!-- Event Log -->
    <div>
      <h2 class="mb-2 text-xs font-semibold uppercase tracking-widest text-gray-500">
        Event Log
      </h2>
      <EventLog entries={entries} />
    </div>

  </div>
</div>
