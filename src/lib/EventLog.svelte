<script lang="ts">
  import { tick } from 'svelte';

  let {
    entries,
  }: {
    entries: Array<{ message: string; timestamp: number; level: string }>;
  } = $props();

  let container: HTMLDivElement;

  $effect(() => {
    const n = entries.length;
    tick().then(() => {
      if (container) container.scrollTop = container.scrollHeight;
    });
  });

  function formatTime(ts: number): string {
    return new Date(ts).toLocaleTimeString('en-US', {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  }

  const levelColor: Record<string, string> = {
    info: 'text-gray-400',
    success: 'text-green-400',
    warn: 'text-amber-400',
    error: 'text-red-400',
  };
</script>

<div
  bind:this={container}
  class="h-48 overflow-y-auto rounded-lg bg-black/40 p-3 font-mono text-xs ring-1 ring-gray-800"
>
  {#if entries.length === 0}
    <p class="text-gray-600">No events yet.</p>
  {:else}
    {#each entries as entry}
      <div class="flex gap-2 py-px">
        <span class="shrink-0 text-gray-600">{formatTime(entry.timestamp)}</span>
        <span class={levelColor[entry.level] ?? 'text-gray-400'}>{entry.message}</span>
      </div>
    {/each}
  {/if}
</div>
