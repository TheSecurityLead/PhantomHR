<script lang="ts">
  let { status }: { status: 'idle' | 'advertising' | 'connected' | 'error' } = $props();

  const configs = {
    idle: {
      label: 'Idle',
      dot: 'bg-gray-500',
      badge: 'bg-gray-800 text-gray-400 border border-gray-700',
    },
    advertising: {
      label: 'Advertising',
      dot: 'bg-amber-400 animate-pulse',
      badge: 'bg-amber-950 text-amber-400 border border-amber-700',
    },
    connected: {
      label: 'Connected',
      dot: 'bg-green-400',
      badge: 'bg-green-950 text-green-400 border border-green-700',
    },
    error: {
      label: 'Error',
      dot: 'bg-red-400',
      badge: 'bg-red-950 text-red-400 border border-red-700',
    },
  } as const;

  const cfg = $derived(configs[status]);
</script>

<span class="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-medium {cfg.badge}">
  <span class="h-2 w-2 rounded-full {cfg.dot}"></span>
  {cfg.label}
</span>
