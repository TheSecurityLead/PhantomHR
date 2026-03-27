import Foundation

/// Generates realistic 1 Hz heart rate data using a smoothed random walk.
///
/// Algorithm per tick:
/// - 70%: small step from {-1, 0, +1}
/// - 30%: larger step from {-2, -1, 0, +1, +2}
/// Result is clamped to [baseBpm - variance, baseBpm + variance].
///
/// When ``updateConfig(baseBpm:variance:)`` is called mid-session, currentBpm
/// drifts toward the new baseBpm naturally via the clamp — no abrupt jump.
///
/// All methods and timer callbacks run on the caller-provided serial DispatchQueue,
/// so no locking is needed.
class HrDataGenerator {

    private var baseBpm: Int
    private var variance: Int
    private var currentBpm: Int

    private var timer: DispatchSourceTimer?
    private let queue: DispatchQueue

    init(baseBpm: Int, variance: Int, queue: DispatchQueue) {
        self.baseBpm = baseBpm
        self.variance = variance
        self.currentBpm = baseBpm
        self.queue = queue
    }

    /// Begin emitting BPM values at 1 Hz. `onTick` fires on `queue`.
    func start(onTick: @escaping (Int) -> Void) {
        stop()
        currentBpm = baseBpm

        let src = DispatchSource.makeTimerSource(queue: queue)
        src.schedule(deadline: .now() + 1.0, repeating: 1.0)
        src.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.currentBpm = self.nextBpm(self.currentBpm)
            onTick(self.currentBpm)
        }
        src.resume()
        timer = src
    }

    /// Cancel the timer. No-op if already stopped.
    func stop() {
        timer?.cancel()
        timer = nil
    }

    /// Update generation parameters. Current BPM drifts toward new base via clamping.
    func updateConfig(baseBpm: Int, variance: Int) {
        self.baseBpm = baseBpm
        self.variance = variance
    }

    // MARK: - Private

    private func nextBpm(_ current: Int) -> Int {
        let step: Int
        if Float.random(in: 0..<1) < 0.7 {
            step = Int.random(in: -1...1)
        } else {
            step = Int.random(in: -2...2)
        }
        let lo = max(40, baseBpm - variance)
        let hi = min(200, baseBpm + variance)
        return min(hi, max(lo, current + step))
    }
}
