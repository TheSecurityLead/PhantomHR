package com.phantomhr.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Generates realistic 1 Hz heart rate data using a smoothed random walk.
 *
 * Algorithm per tick:
 * - 70%: small step: random pick from {-1, 0, +1}
 * - 30%: larger step: random pick from {-2, -1, 0, +1, +2}
 * Result is clamped to [baseBpm - variance, baseBpm + variance].
 *
 * When [updateConfig] is called mid-session, currentBpm drifts toward the new
 * baseBpm naturally via the clamp — no abrupt jump.
 */
class HrDataGenerator(config: HrConfig) {

    @Volatile private var baseBpm: Int = config.baseBpm
    @Volatile private var variance: Int = config.variance

    // Not volatile — only accessed from the single coroutine launched in start()
    private var currentBpm: Int = config.baseBpm

    private var job: Job? = null

    private val _bpmFlow = MutableSharedFlow<Int>(replay = 1)
    val bpmFlow: SharedFlow<Int> = _bpmFlow.asSharedFlow()

    /** Begin emitting BPM values at 1 Hz on [scope]. Safe to call only once per instance. */
    fun start(scope: CoroutineScope) {
        currentBpm = baseBpm
        job = scope.launch {
            while (isActive) {
                delay(1000L)
                currentBpm = nextBpm(currentBpm)
                _bpmFlow.emit(currentBpm)
            }
        }
    }

    /** Cancel the emission coroutine. No-op if already stopped. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Update generation parameters. The current BPM drifts toward the new
     * [newConfig.baseBpm] via clamping; it does not jump immediately.
     */
    fun updateConfig(newConfig: HrConfig) {
        baseBpm = newConfig.baseBpm
        variance = newConfig.variance
    }

    private fun nextBpm(current: Int): Int {
        val step = if (Random.nextFloat() < 0.7f) {
            Random.nextInt(-1, 2)  // -1, 0, or +1
        } else {
            Random.nextInt(-2, 3)  // -2, -1, 0, +1, or +2
        }
        val lo = max(40, baseBpm - variance)
        val hi = min(200, baseBpm + variance)
        return (current + step).coerceIn(lo, hi)
    }
}
