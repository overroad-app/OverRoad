package com.hyperconnect.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 서버 `/surroundings`가 준 주변 차량 한 대 (내 차 기준 앞뒤 [along] m, 오른쪽 [cross] m). */
data class SurroundCar(
    val key: Int, val along: Double, val cross: Double, val lane: Int?, val speedKph: Double,
    val turn: String?, val role: String?, val braking: Boolean, val decel: Boolean,
)

data class Surroundings(
    val myLane: Int?, val mySpeedKph: Double, val myDecel: Boolean, val cars: List<SurroundCar>, val connected: Int,
    val aheadM: Double, val behindM: Double, val errorM: Double,
)

/**
 * 연결망 시각화 (HyperConnect 전체 화면 배경, rev2 미니멀): 내 차 뒤 위에서 앞을 내려다보는 구도로,
 * 화면 위 7할에 앞차들, 아래 3할에 뒤차들을 그린다. 색은 회색 계열 하나로 줄이고, 정보가 있을 때만 색을 쓴다.
 *  - 감속 중인 차: 뒷면 아래 브레이크등 (급제동 보고면 더 밝게)
 *  - 깜빡이: 옆면 주황 점 깜빡임, 차로 변경은 옆으로 미끄러지듯 이동
 *  - 긴급차량: 지붕 위 앞뒤 방향 줄 하나 (구급 흰색 · 경찰 파란색 · 소방 빨간색). 가로로 놓이는 테일램프와 헷갈리지 않게 세로 줄로만.
 *
 * Tier 1은 GPS 비콘만 쓴다 → 차마다 옅은 오차 원(±[Surroundings.errorM]m)으로 "대략 위치"임을 보여 주고,
 * 왼쪽 위 작은 층위 표시로 카메라·레이더·라이다(Tier 2), UWB(Tier 3)가 더해지는 확장 경로를 보여 준다.
 *
 * 서버 값은 1초마다 오므로, 그 사이는 속도 차이로 앞뒤 위치를 이어 그리고 새 값으로 부드럽게 옮겨 간다.
 * 그리기는 화면에 보일 때만, 주행 중에는 초당 약 20장으로 줄인다(주행 중 CPU 기준).
 */
class SurroundView(context: Context) : View(context) {

    private class Track(var along: Double, var cross: Double, var car: SurroundCar, var targetAlong: Double, var targetCross: Double)

    private val tracks = mutableMapOf<Int, Track>()
    private var data: Surroundings? = null
    private var lastFrameMs = 0L
    private var travelled = 0.0   // 차선 점선이 내 속도로 흘러가게
    var running = false
        set(v) {
            field = v
            if (v) { lastFrameMs = 0L; postInvalidateOnAnimation() }
        }

    /** 주행 중에도 이 화면을 보여 줄 때(상위 티어 미리보기) 위쪽에 작게 표시한다. */
    var drivingPreview = false

    /** 위쪽 제목 줄·아래쪽 이모트 바에 가리지 않도록 그림을 그 사이에 둔다. */
    var insetTop = 0f
    var insetBottom = 0f

    fun update(s: Surroundings) {
        data = s
        val keys = s.cars.map { it.key }.toSet()
        tracks.keys.retainAll(keys)
        for (c in s.cars) {
            val t = tracks[c.key]
            if (t == null) tracks[c.key] = Track(c.along, c.cross, c, c.along, c.cross)
            else { t.car = c; t.targetAlong = c.along; t.targetCross = c.cross }
        }
        if (running) postInvalidateOnAnimation()
    }

    // ---------- 투영: 앞 7할은 원근으로 좁아지고, 뒤 3할은 가까워질수록 조금 넓어진다 ----------
    private val usable get() = height - insetTop - insetBottom
    private val ownY get() = insetTop + usable * 0.70f
    private val bottomY get() = height - insetBottom
    private val pxPerM get() = width / 26f

    private fun depth(z: Double): Float {
        val d = data ?: return 1f
        return if (z >= 0) (1f / (1f + 2.6f * (z / d.aheadM).toFloat())) else (1f + 0.35f * (-z / d.behindM).toFloat())
    }

    private fun yOf(z: Double): Float {
        val d = data ?: return ownY
        return if (z >= 0) {
            val t = (z / d.aheadM).toFloat()
            ownY - (ownY - insetTop - usable * 0.02f) * (t * 3.6f / (1f + 2.6f * t))
        } else ownY + (bottomY - ownY) * (-z / d.behindM).toFloat()
    }

    private fun xOf(x: Double, z: Double) = width / 2f + (x * pxPerM * depth(z)).toFloat()

    // ---------- 그리기 ----------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.0 else (now - lastFrameMs) / 1000.0
        lastFrameMs = now
        c.drawColor(BG)
        val d = data
        if (d == null) {
            text.textSize = 30f; text.color = MUTED; text.textAlign = Paint.Align.CENTER
            c.drawText("연결망에 접속하는 중…", width / 2f, height / 2f, text)
            scheduleNext(false)
            return
        }
        travelled += d.mySpeedKph / 3.6 * dt
        for (t in tracks.values) {
            val rel = (t.car.speedKph - d.mySpeedKph) / 3.6
            t.along += rel * dt; t.targetAlong += rel * dt
            val k = min(1.0, dt * 4)
            t.along += (t.targetAlong - t.along) * k
            t.cross += (t.targetCross - t.cross) * k
        }
        drawRoad(c, d)
        val ordered = tracks.values.sortedByDescending { it.along }
        for (t in ordered) if (t.along >= 0) drawCar(c, t.cross, t.along, t.car, d, now)
        drawCar(c, 0.0, 0.0, null, d, now)
        for (t in ordered) if (t.along < 0) drawCar(c, t.cross, t.along, t.car, d, now)
        drawHud(c, d)
        scheduleNext(d.mySpeedKph > 5)
    }

    private fun scheduleNext(driving: Boolean) {
        if (!running) return
        if (driving) postInvalidateDelayed(50) else postInvalidateOnAnimation()
    }

    private fun lanesOf(d: Surroundings): Pair<Int, Int> {
        val lanes = (d.cars.mapNotNull { it.lane } + listOfNotNull(d.myLane))
        val n = max(3, lanes.maxOrNull() ?: 3)
        return n to (d.myLane ?: (n + 1) / 2)
    }

    private fun drawRoad(c: Canvas, d: Surroundings) {
        val (n, my) = lanesOf(d)
        val left = (1 - my) * LANE_W - LANE_W / 2
        val right = (n - my) * LANE_W + LANE_W / 2
        val zs = generateSequence(-d.behindM) { it + 4.0 }.takeWhile { it <= d.aheadM }.toList() + d.aheadM
        path.reset()
        zs.forEachIndexed { i, z -> if (i == 0) path.moveTo(xOf(left, z), yOf(z)) else path.lineTo(xOf(left, z), yOf(z)) }
        zs.reversed().forEach { z -> path.lineTo(xOf(right, z), yOf(z)) }
        path.close()
        p.style = Paint.Style.FILL; p.color = ROAD
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.color = LINE
        for (edge in listOf(left, right)) {
            for (i in 0 until zs.size - 1) {
                p.strokeWidth = 3f * depth(zs[i])
                c.drawLine(xOf(edge, zs[i]), yOf(zs[i]), xOf(edge, zs[i + 1]), yOf(zs[i + 1]), p)
            }
        }
        // 차선 점선: 내가 달린 거리만큼 아래로 흘러간다
        val phase = travelled % 15.0
        for (l in 1 until n) {
            val x = (l - my) * LANE_W + LANE_W / 2
            var z = -d.behindM - phase
            while (z < d.aheadM) {
                val z1 = max(z, -d.behindM); val z2 = min(z + 6, d.aheadM)
                if (z2 > z1) {
                    p.strokeWidth = 2.5f * depth(z1)
                    c.drawLine(xOf(x, z1), yOf(z1), xOf(x, z2), yOf(z2), p)
                }
                z += 15
            }
        }
        p.style = Paint.Style.FILL
    }

    private fun drawCar(c: Canvas, x: Double, z: Double, car: SurroundCar?, d: Surroundings, now: Long) {
        val mine = car == null
        val s = depth(z)
        val px = pxPerM * s
        val halfW = (CAR_W / 2 * px).toFloat()
        val yFront = yOf(z + CAR_L / 2); val yBack = yOf(z - CAR_L / 2)
        val cx = xOf(x, z)
        val sF = depth(z + CAR_L / 2) / s
        val topW = halfW * sF
        val bodyH = 0.75f * px.toFloat() * 0.7f
        val rearTop = yBack - bodyH
        val frontTop = yFront - bodyH * sF
        // GPS 오차 원 (옅은 테두리만)
        if (!mine) {
            val ex = (d.errorM * px).toFloat()
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = ERROR_RING
            c.drawOval(RectF(cx - ex, yOf(z + d.errorM) - bodyH, cx + ex, yOf(z - d.errorM)), p)
            p.style = Paint.Style.FILL
        }
        // 그림자
        p.color = SHADOW
        c.drawOval(RectF(cx - halfW * 1.1f, yBack - (yBack - yFront) * 0.1f - 4f * s, cx + halfW * 1.1f, yBack + 6f * s), p)
        // 윗면 (차 길이 방향)
        path.reset()
        path.moveTo(cx - halfW, rearTop); path.lineTo(cx + halfW, rearTop)
        path.lineTo(cx + topW, frontTop); path.lineTo(cx - topW, frontTop); path.close()
        p.color = if (mine) OWN_TOP else CAR_TOP
        c.drawPath(path, p)
        // 긴급차량: 지붕 위 앞뒤 방향 줄 하나
        val stripe = when (car?.role) { "ambulance" -> Color.WHITE; "police" -> Color.rgb(37, 99, 235); "fire" -> Color.rgb(220, 38, 38); else -> null }
        if (stripe != null) {
            val w0 = halfW * 0.22f; val w1 = topW * 0.22f
            path.reset()
            path.moveTo(cx - w0, rearTop - 1f); path.lineTo(cx + w0, rearTop - 1f)
            path.lineTo(cx + w1, frontTop + 1f); path.lineTo(cx - w1, frontTop + 1f); path.close()
            p.color = stripe
            c.drawPath(path, p)
            if (car?.role == "ambulance") {  // 흰 줄은 회색 윗면 위에서도 보이게 가는 테두리
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = Color.rgb(160, 170, 182)
                c.drawPath(path, p); p.style = Paint.Style.FILL
            }
        }
        // 뒷면
        p.color = if (mine) OWN_REAR else CAR_REAR
        c.drawRoundRect(RectF(cx - halfW, rearTop, cx + halfW, yBack), 5f * s, 5f * s, p)
        // 브레이크등: 감속 중일 때만 (평소에는 등을 그리지 않는다)
        val braking = car?.braking == true
        val decel = if (mine) d.myDecel else car?.decel == true
        if (braking || decel) {
            val lampH = max(3f, bodyH * 0.22f)
            val ly = rearTop + bodyH * 0.25f
            if (braking) {
                p.color = Color.argb(70, 239, 68, 68)
                c.drawRoundRect(RectF(cx - halfW * 1.4f, rearTop - bodyH * 0.6f, cx + halfW * 1.4f, yBack + bodyH * 0.4f), 20f * s, 20f * s, p)
            }
            p.color = Color.rgb(239, 68, 68)
            c.drawRect(cx - halfW * 0.9f, ly, cx - halfW * 0.45f, ly + lampH, p)
            c.drawRect(cx + halfW * 0.45f, ly, cx + halfW * 0.9f, ly + lampH, p)
        }
        // 깜빡이: 옆면 주황 점
        val turn = if (mine) null else car?.turn
        if (turn != null && (now / 400) % 2 == 0L) {
            p.color = Color.rgb(245, 158, 11)
            val sx = if (turn == "LEFT") cx - halfW * 1.08f else cx + halfW * 1.08f
            c.drawCircle(sx, rearTop + bodyH * 0.45f, max(4f, 7f * s), p)
        }
        // 내 차: 옅은 신호 원 (항상 연결됨)
        if (mine) {
            val pulse = (now % 2400) / 2400f
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            p.color = Color.argb((110 * (1 - pulse)).toInt(), 13, 148, 136)
            val r = halfW * 1.4f + pulse * 120f
            c.drawOval(RectF(cx - r, yBack - bodyH - r * 0.35f, cx + r, yBack + r * 0.35f), p)
            p.style = Paint.Style.FILL
        }
        // 거리 (가까운 차만, 작게)
        if (!mine && (z in 8.0..120.0 || z in -120.0..-16.0)) {  // 내 차 바로 뒤 차는 숫자가 내 차와 겹치므로 뺀다
            text.textAlign = Paint.Align.CENTER
            text.textSize = max(16f, 21f * s)
            text.color = MUTED
            text.typeface = Typeface.DEFAULT
            c.drawText(if (z >= 0) "${z.toInt()}m" else "${(-z).toInt()}m", cx, frontTop - 8f * s, text)
        }
    }

    private fun drawHud(c: Canvas, d: Surroundings) {
        val y = insetTop + 34f
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 22f; text.color = Color.rgb(15, 118, 110)
        c.drawText("● 주변 ${d.connected}대 연결", width - 40f, y, text)
        if (drivingPreview) {
            text.textSize = 19f; text.color = MUTED
            c.drawText("주행 중 화면 · 상위 티어(SVS) 미리보기", width - 40f, y + 28f, text)
        }
        // 식별 층위: 한 줄로 작게 (켜진 것만 진한 점)
        text.textAlign = Paint.Align.LEFT
        var x = 40f
        val layers = listOf("GPS 비콘" to true, "카메라" to false, "레이더·라이다" to false, "UWB" to false)
        text.textSize = 19f
        layers.forEach { (name, on) ->
            p.color = if (on) Color.rgb(13, 148, 136) else Color.rgb(203, 210, 219)
            c.drawCircle(x + 6f, y - 6f, 6f, p)
            text.color = if (on) Color.rgb(55, 65, 81) else Color.rgb(160, 168, 180)
            c.drawText(name, x + 18f, y, text)
            x += 30f + text.measureText(name)
        }
        text.color = Color.rgb(160, 168, 180)
        c.drawText("Tier 1 GPS 사용 중 · 원 = 위치 오차 ±${d.errorM.toInt()}m", 40f, y + 28f, text)
    }

    companion object {
        private const val LANE_W = 3.5
        private const val CAR_W = 1.9
        private const val CAR_L = 4.6
        private val BG = Color.rgb(242, 244, 247)
        private val ROAD = Color.rgb(226, 230, 235)
        private val LINE = Color.WHITE
        private val CAR_TOP = Color.rgb(205, 212, 220)
        private val CAR_REAR = Color.rgb(184, 193, 204)
        private val OWN_TOP = Color.rgb(71, 85, 105)
        private val OWN_REAR = Color.rgb(51, 65, 85)
        private val SHADOW = Color.argb(28, 15, 23, 42)
        private val ERROR_RING = Color.argb(55, 13, 148, 136)
        private val MUTED = Color.rgb(130, 140, 155)
    }
}
