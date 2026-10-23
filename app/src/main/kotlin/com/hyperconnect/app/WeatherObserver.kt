package com.hyperconnect.app

import ai.pleos.playground.vehicle.constant.control.WiperSwitch

/** 한 차량이 관측한 기상·노면 상태. 서버가 여러 차량의 관측을 모아 위험 구간을 확정한다. */
data class WeatherObservation(
    val rain: Int,          // 0 없음 ~ 3 폭우 (와이퍼 단계)
    val fog: Boolean,       // 안개등 켜짐
    val slippery: Boolean,  // ABS·ESC 작동 경고등
    val defrost: Boolean,   // 앞유리 김서림 제거 (보조 신호)
    val lowBeam: Boolean,   // 전조등 (보조 신호)
) {
    val notable get() = rain > 0 || fog || slippery
}

/**
 * 차량 신호를 모아 기상·노면 관측을 만든다.
 * 관측이 바뀌면 바로, 위험 신호가 유지되는 동안은 [RESEND_MS]마다 다시 알린다.
 */
class WeatherObserver(private val onObservation: (WeatherObservation) -> Unit) {

    @Volatile var wiper: WiperSwitch? = null
    @Volatile var fogLamp = false
    @Volatile var defrost = false
    @Volatile var lowBeam = false
    @Volatile var absActive = false
    @Volatile var escActive = false

    private var last: WeatherObservation? = null
    private var lastSentMs = 0L

    @Synchronized
    fun evaluate(nowMs: Long) {
        val obs = WeatherObservation(
            rain = rainLevel(wiper),
            fog = fogLamp,
            slippery = absActive || escActive,
            defrost = defrost,
            lowBeam = lowBeam,
        )
        val changed = obs != last
        val resend = obs.notable && nowMs - lastSentMs >= RESEND_MS
        if (changed || resend) {
            last = obs
            lastSentMs = nowMs
            onObservation(obs)
        }
    }

    private fun rainLevel(w: WiperSwitch?) = when (w) {
        null, WiperSwitch.OFF, WiperSwitch.OTHER, WiperSwitch.SERVICE -> 0
        WiperSwitch.MIST, WiperSwitch.AUTO, WiperSwitch.INTERMITTENT_LEVEL_1, WiperSwitch.INTERMITTENT_LEVEL_2 -> 1
        WiperSwitch.INTERMITTENT_LEVEL_3, WiperSwitch.INTERMITTENT_LEVEL_4, WiperSwitch.INTERMITTENT_LEVEL_5,
        WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2 -> 2
        WiperSwitch.CONTINUOUS_LEVEL_3, WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> 3
    }

    companion object {
        private const val RESEND_MS = 30_000L
    }
}
