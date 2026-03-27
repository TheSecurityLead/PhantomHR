<script lang="ts">
  let {
    baseBpm = $bindable(72),
    variance = $bindable(5),
  }: {
    baseBpm: number;
    variance: number;
  } = $props();
</script>

<div class="space-y-5">
  <div>
    <div class="mb-2 flex items-center justify-between">
      <label class="text-sm font-medium text-gray-400" for="base-bpm">Base BPM</label>
      <span class="font-mono text-sm text-white">{baseBpm}</span>
    </div>
    <input
      id="base-bpm"
      type="range"
      min="40"
      max="200"
      bind:value={baseBpm}
      class="w-full accent-blue-500"
    />
    <div class="mt-1 flex justify-between text-xs text-gray-600">
      <span>40</span>
      <span>200</span>
    </div>
  </div>

  <div>
    <div class="mb-2 flex items-center justify-between">
      <label class="text-sm font-medium text-gray-400" for="variance">Variance</label>
      <span class="font-mono text-sm text-white">±{variance}</span>
    </div>
    <input
      id="variance"
      type="range"
      min="0"
      max="20"
      bind:value={variance}
      class="w-full accent-blue-500"
    />
    <div class="mt-1 flex justify-between text-xs text-gray-600">
      <span>0</span>
      <span>20</span>
    </div>
  </div>
</div>
