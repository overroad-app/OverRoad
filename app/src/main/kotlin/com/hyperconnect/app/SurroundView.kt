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
    /** false = OverRoad 비연동 차량 (참여 차의 카메라·레이더가 본 물체, ID 없음) */
    val member: Boolean = true,
    /** "ahead"/"behind" = 이모트를 누르면 실제로 받을 바로 앞차·뒤차 (서버 emote_target) */
    val emoteTarget: String? = null,
    /** 대상 차에 지금 보낼 수 없을 때: -1 = 상대가 받지 않기, -2 = 내가 받지 않기(보낼 수도 없음), 양수 = 같은 차 3분 제한 남은 초. null = 보낼 수 있음 */
    val emoteBlock: Int? = null,
)

data class Surroundings(
    val myLane: Int?, val mySpeedKph: Double, val myDecel: Boolean, val cars: List<SurroundCar>, val connected: Int,
    val aheadM: Double, val behindM: Double, val errorM: Double,
    /** 그중 비연동 차량 수 */
    val observed: Int = 0,
)

/**
 * OverRoad 기본 화면 (주변 차량 인식, rev3 미니멀 · 2026-11-09): 앱의 어느 크기에서나 배경에 깔린다.
 * 내 차 뒤 위에서 앞을 내려다보는 구도로, 가까운 곳을 넓게(로그 눈금) 그리고 도로는 한 점으로 모인다.
 * 차 한 대는 캡슐 하나 — 차 모양은 흉내내지 않고 상태만 색과 선으로 보인다([drawCar]).
 *  - 감속 중인 차: 캡슐 아래쪽 가로 빨간 막대 (급제동 보고면 붉은 빛까지)
 *  - 깜빡이: 켠 쪽 옆 주황 꺾쇠 깜빡임, 차로 변경은 옆으로 미끄러지듯 이동
 *  - 긴급차량: 캡슐 가운데 세로 줄 하나 (구급 흰색 · 경찰 파란색 · 소방 빨간색). 가로 브레이크 막대와 방향으로 구별.
 *  - 비연동 차량(타사·미동의): 속이 빈 점선 캡슐. 참여 차의 센서가 본 물체라 위치는 정확(오차 타원 없음), 깜빡이·역할은 모른다.
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
            val x = laneX(c, s)
            val t = tracks[c.key]
            if (t == null) tracks[c.key] = Track(c.along, x, c, c.along, x)
            else { t.car = c; t.targetAlong = c.along; t.targetCross = x }
        }
        if (running) postInvalidateOnAnimation()
    }

    /** 차로를 알면 그 차로의 한가운데에 맞춘다 (차로를 바꾸는 중처럼 중앙에서 1.2m 넘게 벗어나면 잰 값 그대로). */
    private fun laneX(c: SurroundCar, s: Surroundings): Double {
        val lane = c.lane ?: return c.cross
        val my = s.myLane ?: return c.cross
        val x = (lane - my) * LANE_W
        return if (abs(c.cross - x) <= 1.2) x else c.cross
    }

    // ---------- 투영: 앞 7할은 원근으로 좁아지고, 뒤 3할은 가까워질수록 조금 넓어진다 ----------
    private val usable get() = height - insetTop - insetBottom
    private val ownY get() = insetTop + usable * 0.58f
    private val bottomY get() = height - insetBottom
    private val pxPerM get() = min(width / 30f, usable / 9f)

    // 가까운 곳에 화면을 더 쓴다: 앞쪽은 로그 눈금(10m까지가 넓고 멀수록 촘촘), 뒤쪽은 고르게
    private fun aheadT(z: Double, d: Surroundings) = (kotlin.math.ln(1 + z / NEAR_M) / kotlin.math.ln(1 + d.aheadM / NEAR_M)).toFloat()

    // 크기는 화면 높이에 비례 (지평선에서 0): 도로 가장자리가 곧은 선으로 한 점에 모인다
    private val horizonY get() = insetTop - usable * 0.32f
    private fun depth(z: Double): Float = (yOf(z) - horizonY) / (ownY - horizonY)

    private fun yOf(z: Double): Float {
        val d = data ?: return ownY
        return if (z >= 0) ownY - (ownY - insetTop - usable * 0.02f) * aheadT(z, d)
        else ownY + (bottomY - ownY) * behindT(z, d)
    }

    private fun behindT(z: Double, d: Surroundings) = (kotlin.math.ln(1 - z / NEAR_M) / kotlin.math.ln(1 + d.behindM / NEAR_M)).toFloat()

    private fun xOf(x: Double, z: Double) = width / 2f + (x * pxPerM * depth(z)).toFloat()

    // ---------- 그리기 ----------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fade = Paint()
    private val dash = android.graphics.DashPathEffect(floatArrayOf(10f, 7f), 0f)

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
        // 먼 곳은 배경색으로 흐리게 사라지게
        val fadeH = usable * 0.22f
        fade.shader = android.graphics.LinearGradient(0f, insetTop, 0f, insetTop + fadeH, BG, Color.argb(0, Color.red(BG), Color.green(BG), Color.blue(BG)), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, insetTop - 2f, width.toFloat(), insetTop + fadeH, fade)
        drawLinks(c, now)
        val ordered = tracks.values.sortedByDescending { it.along }
        for (t in ordered) if (t.along >= 0) drawCar(c, t.cross, t.along, t.car, d, now)
        drawCar(c, 0.0, 0.0, null, d, now)
        for (t in ordered) if (t.along < 0) drawCar(c, t.cross, t.along, t.car, d, now)
        drawPulses(c, now)
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

    /**
     * 차 한 대의 화면 모양: 차선 방향(소실점 쪽)으로 선 둥근 사다리꼴. 옆 차로의 차는 차선을 따라 비스듬히 선다.
     * (cx, yc) 가운데 · (ux, uy) 앞쪽 방향 단위 벡터 · (tx, ty) 앞면 가운데 · (bx, by) 뒷면 가운데 · hw 뒷면 반폭
     */
    private class Geo(val cx: Float, val yc: Float, val hw: Float, val ux: Float, val uy: Float,
                      val tx: Float, val ty: Float, val bx: Float, val by: Float)

    private fun geo(x: Double, z: Double): Geo {
        val s = depth(z)
        val hw = (CAR_W / 2 * pxPerM * s).toFloat() * 0.85f
        val cx = xOf(x, z); val yc = yOf(z)
        var ux = xOf(x, z + 3) - cx; var uy = yOf(z + 3) - yc
        val n = kotlin.math.sqrt(ux * ux + uy * uy).coerceAtLeast(0.001f)
        ux /= n; uy /= n
        val h = hw * 1.7f
        return Geo(cx, yc, hw, ux, uy, cx + ux * h * 0.55f, yc + uy * h * 0.55f, cx - ux * h * 0.45f, yc - uy * h * 0.45f)
    }

    /** 사다리꼴 경로. [grow]만큼 키운 같은 모양(빛·테두리용). */
    private fun carPath(g: Geo, grow: Float = 0f, dy: Float = 0f): Path {
        val wb = g.hw + grow; val wt = g.hw * 0.62f + grow
        val tx = g.tx + g.ux * grow; val ty = g.ty + g.uy * grow + dy
        val bx = g.bx - g.ux * grow; val by = g.by - g.uy * grow + dy
        return Path().apply {
            moveTo(tx - wt, ty); lineTo(tx + wt, ty); lineTo(bx + wb, by); lineTo(bx - wb, by); close()
        }
    }

    /**
     * 차 한 대 = 둥근 사다리꼴 하나 (미니멀, 2026-11-09). 차종 모양을 흉내내지 않고 앞으로 가는 원근과 상태만 보인다.
     *  - 다른 차: 흰 사다리꼴 + 옅은 테두리 · 내 차: 진한 사다리꼴 + 청록 빛 · 비연동 차: 속이 비치는 점선
     *  - 감속·급제동: 아래쪽 가로 빨간 막대 (급제동이면 붉은 빛까지)
     *  - 깜빡이: 켠 쪽 옆에 주황 꺾쇠(‹ ›)가 깜빡임
     *  - 긴급차량: 가운데 세로 줄 하나 (구급 흰색 · 경찰 파란색 · 소방 빨간색) — 가로 브레이크 막대와 방향으로 구별
     */
    private fun drawCar(c: Canvas, x: Double, z: Double, car: SurroundCar?, d: Surroundings, now: Long) {
        val mine = car == null
        val member = car?.member != false
        val s = depth(z)
        val px = pxPerM * s
        val g = geo(x, z)
        val halfW = g.hw
        val yc = g.yc
        val cx = g.cx
        val top = g.ty; val bot = g.by
        val h = bot - top
        val corner = android.graphics.CornerPathEffect(halfW * 0.38f)
        // GPS 오차: 아주 옅은 타원 (비연동 차는 센서가 잰 위치라 없음)
        if (!mine && member) {
            val ex = (d.errorM * px).toFloat() * 0.55f
            p.color = ERROR_FILL
            c.drawOval(RectF(cx - ex, yc - ex * 0.45f, cx + ex, yc + ex * 0.45f), p)
        }
        val braking = car?.braking == true
        val decel = if (mine) d.myDecel else car?.decel == true
        p.pathEffect = android.graphics.CornerPathEffect(halfW * 0.6f)
        if (braking) {
            p.color = Color.argb(60, 239, 68, 68)
            c.drawPath(carPath(g, halfW * 0.45f, halfW * 0.15f), p)
        }
        if (mine) {
            p.pathEffect = null
            val pulse = (now % 2400) / 2400f
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
            p.color = Color.argb((120 * (1 - pulse)).toInt(), 20, 184, 166)
            val rr = halfW * 1.4f + pulse * 140f
            c.drawOval(RectF(cx - rr, yc - rr * 0.4f, cx + rr, yc + rr * 0.4f), p)
            p.style = Paint.Style.FILL
            p.pathEffect = android.graphics.CornerPathEffect(halfW * 0.5f)
            p.color = Color.argb(70, 20, 184, 166)
            c.drawPath(carPath(g, 6f * s, 2f * s), p)
        }
        p.pathEffect = corner
        val body = carPath(g)
        if (!member) {
            p.color = Color.argb(150, 255, 255, 255)
            c.drawPath(body, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = max(2f, 2.6f * s); p.color = OBSERVED_EDGE
            p.pathEffect = android.graphics.ComposePathEffect(dash, corner)
            c.drawPath(body, p)
            p.style = Paint.Style.FILL
        } else {
            p.color = if (mine) OWN_TOP else CAR_TOP
            c.drawPath(body, p)
            if (!mine) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = CAR_EDGE
                c.drawPath(body, p); p.style = Paint.Style.FILL
            }
        }
        p.pathEffect = null
        // 긴급차량: 세로 줄 하나
        val stripe = when (car?.role) { "ambulance" -> Color.WHITE; "police" -> Color.rgb(37, 99, 235); "fire" -> Color.rgb(220, 38, 38); else -> null }
        if (stripe != null) {
            // 차선 방향 그대로 앞뒤로 긴 줄
            val sw = max(4f, halfW * 0.3f)
            val x0 = g.bx + (g.tx - g.bx) * 0.18f; val y0 = g.by + (g.ty - g.by) * 0.18f
            val x1 = g.bx + (g.tx - g.bx) * 0.82f; val y1 = g.by + (g.ty - g.by) * 0.82f
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            if (car?.role == "ambulance") { p.strokeWidth = sw + 3f; p.color = Color.rgb(150, 160, 174); c.drawLine(x0, y0, x1, y1, p) }
            p.strokeWidth = sw; p.color = stripe
            c.drawLine(x0, y0, x1, y1, p)
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        }
        // 감속: 아래쪽 가로 빨간 막대
        if (braking || decel) {
            val bh = max(4f, halfW * 0.2f)
            p.color = Color.rgb(239, 68, 68)
            val lx = g.bx + g.ux * bh * 1.8f; val ly = bot + g.uy * bh * 1.8f
            c.drawRoundRect(RectF(lx - halfW * 0.65f, ly - bh / 2, lx + halfW * 0.65f, ly + bh / 2), bh / 2, bh / 2, p)
        }
        // 깜빡이: 켠 쪽에 주황 꺾쇠
        val turn = if (mine) null else car?.turn
        if (turn != null && (now / 400) % 2 == 0L) {
            val dir = if (turn == "LEFT") -1f else 1f
            val bx = cx + dir * (halfW + 10f * s)
            val ah = h * 0.26f
            p.style = Paint.Style.STROKE; p.strokeWidth = max(3f, 5f * s); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = Color.rgb(245, 158, 11)
            path.reset()
            path.moveTo(bx, yc - ah); path.lineTo(bx + dir * ah * 0.7f, yc); path.lineTo(bx, yc + ah)
            c.drawPath(path, p)
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        }
        // 거리: 앞차만 작게
        if (!mine && z in 8.0..120.0) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = max(16f, 20f * s)
            text.typeface = Typeface.DEFAULT
            text.color = Color.rgb(120, 132, 148)
            c.drawText("${z.toInt()}m", g.tx, top - 10f * s, text)
        }
    }

    // ---------- 받은 이모트 카드 자리 ----------

    /**
     * 왼쪽 위 빈 곳에 놓을 수 있는 가장 큰 정사각형: 층위 칩 아래부터, 도로 왼쪽 가장자리와 아래 창(시트)에 닿지 않게.
     * 도로는 아래로 갈수록 왼쪽으로 넓어지므로, 정사각형의 아래 모서리에서 도로와 만나는 크기를 찾는다.
     */
    fun emoteBox(): RectF? {
        val d = data ?: return null
        if (width == 0 || height == 0) return null
        val (_, my) = lanesOf(d)
        val leftM = (1 - my) * LANE_W - LANE_W / 2
        val zs = generateSequence(-d.behindM) { it + 2.0 }.takeWhile { it <= d.aheadM }.toList()
        fun roadLeftAt(y: Float): Float {
            // 화면 y에서 도로 왼쪽 가장자리의 x (위로 갈수록 오른쪽)
            var best = xOf(leftM, d.aheadM)
            for (z in zs) if (yOf(z) >= y) { best = xOf(leftM, z) } else break
            return best
        }
        val margin = 28f
        val top = insetTop + (if (usable < 420) 24f else 124f)
        val bottomLimit = height - insetBottom - 16f
        var lo = 0f; var hi = min(bottomLimit - top, width * 0.5f)
        repeat(20) {
            val sz = (lo + hi) / 2
            if (margin + sz <= roadLeftAt(top + sz) - 18f) lo = sz else hi = sz
        }
        return if (lo < 140f) null else RectF(margin, top, margin + lo, top + lo)
    }

    // ---------- 이모트 대상 점선과 신호 전파 ----------

    private class Pulse(val ahead: Boolean, val outgoing: Boolean, val symbol: String, val startMs: Long)
    private val pulses = mutableListOf<Pulse>()
    private val linkSince = mutableMapOf<Boolean, Pair<Int, Long>>()  // 앞/뒤 → (대상 key, 새로 이어진 시각)
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** 이모트를 보냈거나(outgoing) 받았을 때: 점선을 따라 신호가 오간다. 대상이 화면에 없으면 그리지 않는다. */
    fun pulse(ahead: Boolean, outgoing: Boolean, symbol: String) {
        pulses += Pulse(ahead, outgoing, symbol, SystemClock.elapsedRealtime())
        if (running) postInvalidateOnAnimation()
    }

    /** 내 차 → 대상 차 연결선의 양 끝: 앞차면 내 앞면 정중앙 → 그 차 뒷면 정중앙, 뒤차면 내 뒷면 정중앙 → 그 차 앞면 정중앙. */
    private fun linkEnds(t: Track, ahead: Boolean): FloatArray {
        val me = geo(0.0, 0.0); val o = geo(t.cross, t.along)
        val gap = 6f
        return if (ahead) floatArrayOf(me.tx + me.ux * gap, me.ty + me.uy * gap, o.bx - o.ux * gap, o.by - o.uy * gap)
        else floatArrayOf(me.bx - me.ux * gap, me.by - me.uy * gap, o.tx + o.ux * gap, o.ty + o.uy * gap)
    }

    private fun drawLinks(c: Canvas, now: Long) {
        for (ahead in listOf(true, false)) {
            val t = tracks.values.firstOrNull { it.car.emoteTarget == (if (ahead) "ahead" else "behind") }
            if (t == null) { linkSince.remove(ahead); continue }
            val prev = linkSince[ahead]
            if (prev == null || prev.first != t.car.key) linkSince[ahead] = t.car.key to now  // 차로를 바꾸면 새 차와 다시 이어진다
            val age = now - linkSince.getValue(ahead).second
            val a = min(1f, age / 600f)
            val (x0, y0, x1, y1) = linkEnds(t, ahead).let { listOf(it[0], it[1], it[2], it[3]) }
            val block = t.car.emoteBlock
            // 새로 이어질 때는 내 차에서 대상 쪽으로 자라난다
            val ex = x0 + (x1 - x0) * a; val ey = y0 + (y1 - y0) * a
            linkPaint.strokeWidth = 3.2f
            if (block == null) {
                linkPaint.color = Color.argb((170 * a).toInt() + 40, 20, 184, 166)
                linkPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 11f), -((now / 40f) % 15f) * (if (ahead) 1 else -1))
            } else {
                // 지금은 보낼 수 없음: 흐르지 않는 회색 점선
                linkPaint.color = Color.argb((150 * a).toInt() + 30, 148, 160, 176)
                linkPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 11f), 0f)
            }
            c.drawLine(x0, y0, ex, ey, linkPaint)
            linkPaint.pathEffect = null
            // 내가 받지 않기를 켠 경우는 배지 없이 회색 선만 (쿨타임처럼 꺼진 상태)
            if (block != null && block != -2 && a >= 1f) drawBlockBadge(c, (x0 + x1) / 2, (y0 + y1) / 2, block)
            // 대상 차 둘레 옅은 표시
            if (a >= 1f && block == null) {
                linkPaint.strokeWidth = 2f; linkPaint.color = Color.argb(90, 20, 184, 166)
                val o = geo(t.cross, t.along)
                c.drawOval(RectF(o.cx - o.hw * 1.4f, o.yc - o.hw * 0.75f, o.cx + o.hw * 1.4f, o.yc + o.hw * 0.75f), linkPaint)
            }
        }
    }

    /** 점선 가운데의 작은 배지: 받지 않기면 🔕, 같은 차 제한이면 남은 시간을 줄어드는 원과 "2:41"로. */
    private fun drawBlockBadge(c: Canvas, x: Float, y: Float, block: Int) {
        val r = 26f
        p.color = Color.argb(235, 255, 255, 255)
        c.drawCircle(x, y, r + 4f, p)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD
        if (block < 0) {
            text.textSize = 26f
            c.drawText("🔕", x, y + 9f, text)
        } else {
            linkPaint.strokeWidth = 4f; linkPaint.color = Color.rgb(148, 160, 176)
            c.drawArc(RectF(x - r, y - r, x + r, y + r), -90f, -360f * block / 180f, false, linkPaint)
            text.textSize = 15f; text.color = Color.rgb(71, 85, 105)
            c.drawText("%d:%02d".format(block / 60, block % 60), x, y + 5f, text)
        }
        text.typeface = Typeface.DEFAULT
    }

    private fun drawPulses(c: Canvas, now: Long) {
        val it = pulses.iterator()
        while (it.hasNext()) {
            val pu = it.next()
            val age = now - pu.startMs
            if (age > PULSE_MS + 500) { it.remove(); continue }
            val t = tracks.values.firstOrNull { tr -> tr.car.emoteTarget == (if (pu.ahead) "ahead" else "behind") } ?: continue
            val e = linkEnds(t, pu.ahead)
            val f = min(1f, age / PULSE_MS.toFloat()).let { k -> k * k * (3 - 2 * k) }
            val (sx, sy, tx, ty) = if (pu.outgoing) listOf(e[0], e[1], e[2], e[3]) else listOf(e[2], e[3], e[0], e[1])
            if (age <= PULSE_MS) {
                // 지나간 자리를 밝히는 꼬리 + 움직이는 빛 + 이모지
                val px = sx + (tx - sx) * f; val py = sy + (ty - sy) * f
                linkPaint.strokeWidth = 6f; linkPaint.color = Color.argb(150, 20, 184, 166)
                val f0 = max(0f, f - 0.25f)
                c.drawLine(sx + (tx - sx) * f0, sy + (ty - sy) * f0, px, py, linkPaint)
                p.color = Color.argb(70, 20, 184, 166); c.drawCircle(px, py, 46f, p)
                p.color = Color.argb(140, 20, 184, 166); c.drawCircle(px, py, 36f, p)
                p.color = Color.WHITE; c.drawCircle(px, py, 30f, p)
                text.textAlign = Paint.Align.CENTER; text.textSize = 36f
                c.drawText(pu.symbol, px, py + 13f, text)
            } else {
                // 도착: 받는 쪽에서 퍼지는 고리
                val k = (age - PULSE_MS) / 500f
                linkPaint.strokeWidth = 4f; linkPaint.color = Color.argb((220 * (1 - k)).toInt(), 20, 184, 166)
                val rr = 30f + 80f * k
                c.drawOval(RectF(tx - rr, ty - rr * 0.5f, tx + rr, ty + rr * 0.5f), linkPaint)
                // 도착한 이모지가 위로 살짝 떠오르며 사라진다
                text.textAlign = Paint.Align.CENTER; text.textSize = 36f
                text.alpha = (255 * (1 - k)).toInt()
                c.drawText(pu.symbol, tx, ty - 30f - 30f * k, text)
                text.alpha = 255
            }
        }
    }

    private fun chip(c: Canvas, label: String, x: Float, y: Float, alignRight: Boolean, dot: Int?, fg: Int) {
        text.textAlign = Paint.Align.LEFT
        val w = text.measureText(label) + 36f + (if (dot != null) 22f else 0f)
        val left = if (alignRight) x - w else x
        p.color = Color.argb(215, 255, 255, 255)
        c.drawRoundRect(RectF(left, y - 30f, left + w, y + 14f), 22f, 22f, p)
        var tx = left + 18f
        if (dot != null) { p.color = dot; c.drawCircle(tx + 7f, y - 8f, 7f, p); tx += 22f }
        text.color = fg
        c.drawText(label, tx, y, text)
    }

    private fun drawHud(c: Canvas, d: Surroundings) {
        val y = insetTop + 58f
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 24f
        chip(c, "주변 ${d.connected}대 연결", width - 28f, y, true, Color.rgb(20, 184, 166), Color.rgb(15, 23, 42))
        if (d.observed > 0) {
            text.typeface = Typeface.DEFAULT; text.textSize = 20f
            chip(c, "비연동 ${d.observed}대 · 참여 차 센서 감지 (Tier 2 미리보기)", width - 28f, y + 52f, true, Color.rgb(148, 160, 176), Color.rgb(71, 85, 105))
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 24f
        }
        if (drivingPreview) {
            text.typeface = Typeface.DEFAULT; text.textSize = 19f
            chip(c, "주행 중 화면 · 상위 티어(SVS) 미리보기", width - 28f, y + if (d.observed > 0) 104f else 52f, true, null, MUTED)
        }
        if (usable < 420) return  // 중간 창 위 좁은 띠에서는 층위 표시를 빼서 도로를 가리지 않게
        // 식별 층위: 켜진 것만 진한 점
        text.typeface = Typeface.DEFAULT; text.textSize = 20f
        val layers = listOf("GPS 비콘" to true, "카메라" to false, "레이더·라이다" to false, "UWB" to false)
        val parts = layers.joinToString("   ") { it.first }
        val w = text.measureText(parts) + layers.size * 20f + 36f
        p.color = Color.argb(215, 255, 255, 255)
        c.drawRoundRect(RectF(28f, y - 30f, 28f + w, y + 46f), 22f, 22f, p)
        var x = 46f
        layers.forEach { (name, on) ->
            p.color = if (on) Color.rgb(20, 184, 166) else Color.rgb(203, 210, 219)
            c.drawCircle(x + 6f, y - 7f, 6f, p)
            text.color = if (on) Color.rgb(30, 41, 59) else Color.rgb(160, 168, 180)
            c.drawText(name, x + 18f, y, text)
            x += 18f + text.measureText(name + "   ") + 2f
        }
        text.textSize = 17f; text.color = Color.rgb(140, 150, 165)
        c.drawText("Tier 1 · 옅은 원 = GPS 위치 오차 ±${d.errorM.toInt()}m", 46f, y + 30f, text)
    }

    companion object {
        private const val LANE_W = 3.5
        private const val CAR_W = 1.9
        private const val CAR_L = 4.6
        private const val NEAR_M = 12.0
        private const val PULSE_MS = 1100L
        private val BG = Color.rgb(238, 241, 245)
        private val ROAD = Color.rgb(222, 227, 234)
        private val LINE = Color.WHITE
        private val CAR_TOP = Color.rgb(252, 253, 254)
        private val CAR_EDGE = Color.rgb(206, 213, 222)
        private val OBSERVED_EDGE = Color.rgb(120, 132, 150)
        private val OWN_TOP = Color.rgb(51, 65, 85)
        private val ERROR_FILL = Color.argb(16, 20, 184, 166)
        private val MUTED = Color.rgb(130, 140, 155)
    }
}
