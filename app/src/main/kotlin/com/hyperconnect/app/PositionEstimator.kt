package com.hyperconnect.app

import kotlin.math.cos
import kotlin.math.sin

/**
 * 차량 위치 추정: 내비 위치를 기준점으로 삼고, 차량 속도를 진행 방향으로 적분한다(추측항법).
 *
 * 에뮬레이터의 내비 위치는 고정되어 있어, 시연에서는 이 방식으로 차량을 이동시킨다.
 * [startOffsetM]은 기준점에서 진행 방향으로의 시작 거리(음수면 뒤쪽), [rightOffsetM]은 오른쪽 거리다.
 */
class PositionEstimator(private val startOffsetM: Double, private var rightOffsetM: Double = 0.0) {

    /** 차로를 바꾸면 옆 방향 위치만 옮긴다. */
    @Synchronized
    fun setRightOffset(meters: Double) {
        rightOffsetM = meters
    }

    private var baseLat: Double? = null
    private var baseLon = 0.0
    private var headingDeg = 0.0
    private var travelledM = 0.0
    private var lastSpeedMs = 0L

    val hasFix get() = baseLat != null

    /** 내비 위치를 받으면 첫 위치를 기준점으로 고정한다. */
    @Synchronized
    fun onNaviPosition(p: VehiclePosition) {
        if (baseLat == null) {
            baseLat = p.lat
            baseLon = p.lon
            headingDeg = p.heading
        }
    }

    @Synchronized
    fun onSpeed(speedMps: Float, timeMs: Long) {
        if (lastSpeedMs != 0L) travelledM += speedMps * (timeMs - lastSpeedMs) / 1000.0
        lastSpeedMs = timeMs
    }

    /** 현재 추정 위치 (위도, 경도, 방향). 기준점이 없으면 null. */
    @Synchronized
    fun current(): Triple<Double, Double, Double>? {
        val lat0 = baseLat ?: return null
        val d = startOffsetM + travelledM
        val rad = Math.toRadians(headingDeg)
        val north = d * cos(rad) - rightOffsetM * sin(rad)
        val east = d * sin(rad) + rightOffsetM * cos(rad)
        val lat = lat0 + north / METERS_PER_DEG_LAT
        val lon = baseLon + east / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat0)))
        return Triple(lat, lon, headingDeg)
    }

    companion object {
        private const val METERS_PER_DEG_LAT = 111_320.0
    }
}
