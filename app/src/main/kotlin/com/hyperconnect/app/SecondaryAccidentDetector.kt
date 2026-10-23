package com.hyperconnect.app

/**
 * 2차 사고 위험 감지: 도로 위에 멈춰 선 차량과 사고 차량을 찾는다.
 *
 * - crash           : 에어백 경고등이 켜지면 즉시
 * - stopped_vehicle : 주행 기어 상태로 [STOP_KPH] 미만에 [STOPPED_MS] 이상 머무르고,
 *                     비상등이 켜져 있거나 직전([RECENT_BRAKE_MS] 안)에 급제동이 있었으면
 *
 * 속도 콜백은 값이 바뀔 때만 오므로 [tick]을 주기적으로 호출해 정차 시간을 판단한다.
 */
class SecondaryAccidentDetector(private val onEvent: (type: String) -> Unit) {

    @Volatile var hazardOn = false
    @Volatile var inPark = true
    private var airbagOn = false
    private var speedKph = 0f
    private var stoppedSinceMs = 0L
    private var lastHardBrakeMs = 0L
    private var stoppedReported = false
    private var lastCrashMs = 0L

    @Synchronized
    fun onAirbag(on: Boolean, nowMs: Long) {
        if (on && !airbagOn && nowMs - lastCrashMs > CRASH_COOLDOWN_MS) {
            lastCrashMs = nowMs
            onEvent(EVENT_CRASH)
        }
        airbagOn = on
    }

    @Synchronized
    fun onHardBrake(nowMs: Long) {
        lastHardBrakeMs = nowMs
    }

    @Synchronized
    fun onSpeed(kph: Float, nowMs: Long) {
        speedKph = kph
        if (kph >= STOP_KPH) stoppedSinceMs = 0L else if (stoppedSinceMs == 0L) stoppedSinceMs = nowMs
        if (kph >= MOVING_AGAIN_KPH) stoppedReported = false
        tick(nowMs)
    }

    @Synchronized
    fun tick(nowMs: Long) {
        if (stoppedReported || inPark || stoppedSinceMs == 0L) return
        val longEnough = nowMs - stoppedSinceMs >= STOPPED_MS
        val suspicious = hazardOn || (lastHardBrakeMs != 0L && nowMs - lastHardBrakeMs <= RECENT_BRAKE_MS)
        if (longEnough && suspicious) {
            stoppedReported = true
            onEvent(EVENT_STOPPED)
        }
    }

    companion object {
        const val EVENT_CRASH = "crash"
        const val EVENT_STOPPED = "stopped_vehicle"
        private const val STOP_KPH = 5f
        private const val MOVING_AGAIN_KPH = 15f
        private const val STOPPED_MS = 3000L
        private const val RECENT_BRAKE_MS = 15_000L
        private const val CRASH_COOLDOWN_MS = 60_000L
    }
}
