package com.hyperconnect.app

/**
 * 속도 샘플로 급제동을 감지한다.
 *
 * 최근 [WINDOW_MS] 동안의 최고 속도 대비 현재 속도의 감속도가 [DECEL_THRESHOLD_MPS2] 이상이고,
 * 출발 속도가 [MIN_SPEED_KPH] 이상이며 브레이크를 밟고 있으면 급제동으로 본다.
 */
class HardBrakeDetector(private val onHardBrake: (speedKph: Float, decelMps2: Float) -> Unit) {

    private data class Sample(val timeMs: Long, val speedMps: Float)

    private val samples = ArrayDeque<Sample>()
    private var lastFiredMs = 0L

    @Volatile
    var brakePressed = false

    fun onSpeed(speedMps: Float, timeMs: Long) {
        samples.addLast(Sample(timeMs, speedMps))
        while (samples.isNotEmpty() && timeMs - samples.first().timeMs > WINDOW_MS) samples.removeFirst()

        val peak = samples.maxByOrNull { it.speedMps } ?: return
        val dtSec = (timeMs - peak.timeMs) / 1000f
        if (dtSec < MIN_DT_SEC) return

        val decel = (peak.speedMps - speedMps) / dtSec
        val fromKph = peak.speedMps * 3.6f
        if (decel >= DECEL_THRESHOLD_MPS2 && fromKph >= MIN_SPEED_KPH && brakePressed &&
            timeMs - lastFiredMs > COOLDOWN_MS
        ) {
            lastFiredMs = timeMs
            onHardBrake(fromKph, decel)
        }
    }

    companion object {
        const val DECEL_THRESHOLD_MPS2 = 4f  // 약 0.4g
        const val MIN_SPEED_KPH = 30f
        private const val WINDOW_MS = 2000L
        private const val MIN_DT_SEC = 0.3f
        private const val COOLDOWN_MS = 10_000L
    }
}
