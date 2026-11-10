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
    /** 지금 주변 인식에 쓰이는 식별 층위: "gps_beacon"(Tier 1) + 주변 참여 차가 보고한 "camera"·"radar"·"lidar"(Tier 2) · "uwb"(Tier 3) */
    val sources: Set<String> = setOf("gps_beacon"),
    /** 화면 범위 밖이지만 연결된 차(1km 안)의 위치: (앞뒤 m, 좌우 m). 차는 그리지 않고 그 방향으로 흐려지는 점선만 그린다 */
    val far: List<Pair<Double, Double>> = emptyList(),
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

    /** 사다리꼴 경로 (옆 변은 차선과 나란하다). [grow]만큼 키운 같은 모양(빛·테두리용). */
    private fun carPath(g: Geo, grow: Float = 0f, dy: Float = 0f): Path {
        // 옆 변이 차선과 나란하도록: 차선처럼 소실점을 향해 모이는 비율(화면 높이에 비례)로 앞·뒤 폭과 중심을 잡는다
        val mid = width / 2f
        val kt = (g.ty - horizonY) / (g.yc - horizonY); val kb = (g.by - horizonY) / (g.yc - horizonY)
        val wb = g.hw * kb + grow; val wt = g.hw * kt + grow
        val tx = mid + (g.cx - mid) * kt + g.ux * grow; val ty = g.ty + g.uy * grow + dy
        val bx = mid + (g.cx - mid) * kb - g.ux * grow; val by = g.by - g.uy * grow + dy
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
                // 바로 앞차·뒤차(이모트를 받을 차)는 청록 테두리로 구분한다. 지금 보낼 수 없으면(3분 제한·받지 않기) 회색 굵은 테두리
                val target = car?.emoteTarget != null
                p.style = Paint.Style.STROKE
                p.strokeWidth = if (target) max(3.2f, 4.6f * s) else 1.6f
                p.color = if (!target) CAR_EDGE else if (car?.emoteBlock == null) TEAL else Color.rgb(148, 160, 176)
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

    /**
     * 선을 따라 오가는 신호 하나.
     *  - 이모트: [ahead]로 받을 앞차·뒤차(서버 emote_target)를 매 프레임 찾는다.
     *  - 경고·안내: 보낸 차가 화면에 있으면 그 차([key]), 없으면 그 방향의 화면 끝([vAlong], [vCross])에서 온다.
     * 실제 신호는 서버를 거쳐 오지만, 화면에서는 보낸 차와 내 차를 바로 잇는 선으로 보여 준다.
     */
    private class Pulse(val ahead: Boolean?, val key: Int?, val vAlong: Double, val vCross: Double,
                        val outgoing: Boolean, val symbol: String, val tint: Int, val startMs: Long)
    private val pulses = mutableListOf<Pulse>()
    private val linkSince = mutableMapOf<Int, Long>()  // 차량 key → 연결선이 새로 이어진 시각
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dots = android.graphics.DashPathEffect(floatArrayOf(3f, 10f), 0f)

    /** 이모트를 보냈거나(outgoing) 받았을 때: 그 차와 완전히 이어진 점선이 잠깐 나타나고, 방향을 따라 켜지며 이모티콘이 오간다. */
    fun pulse(ahead: Boolean, outgoing: Boolean, symbol: String) {
        pulses += Pulse(ahead, null, 0.0, 0.0, outgoing, symbol, TEAL, SystemClock.elapsedRealtime())
        if (running) postInvalidateOnAnimation()
    }

    /**
     * 경고·안내를 받았을 때: 보낸 차에서 내 차로 신호가 선을 타고 온다.
     * [alongM] 내 차 기준 앞(+)·뒤(−) 거리, [crossM] 옆 거리(모르면 null → 내 차로).
     * 그 위치 가까이에 참여 차량이 보이면 그 차에서, 화면 밖이면 그 방향의 화면 끝에서 출발한다.
     */
    fun signal(alongM: Double, crossM: Double?, symbol: String, tint: Int) {
        val d = data ?: return
        val tol = max(12.0, abs(alongM) * 0.25)
        val src = tracks.values.filter { it.car.member && abs(it.along - alongM) <= tol && (crossM == null || abs(it.cross - crossM) <= 2.5) }
            .minByOrNull { abs(it.along - alongM) + (if (crossM == null) abs(it.cross) else 0.0) }
        val va = alongM.coerceIn(-d.behindM * 0.97, d.aheadM * 0.97)
        pulses += Pulse(null, src?.car?.key, va, crossM ?: 0.0, false, symbol, tint, SystemClock.elapsedRealtime())
        if (running) postInvalidateOnAnimation()
    }

    /**
     * 지나간 긴급차량이 보낸 감사 인사: 바로 앞차·뒤차가 아니라 그 긴급차량에서 내 차로 온다.
     * 화면에 긴급차량(역할이 있는 차)이 보이면 가장 가까운 그 차에서, 이미 화면 밖으로 나갔으면 앞쪽 화면 끝에서 출발한다.
     */
    fun pulseFromEmergency(symbol: String) {
        val d = data ?: return
        val src = tracks.values.filter { it.car.member && it.car.role != null }.minByOrNull { abs(it.along) + abs(it.cross) }
        pulses += Pulse(null, src?.car?.key, d.aheadM * 0.97, src?.cross ?: 0.0, false, symbol, TEAL, SystemClock.elapsedRealtime())
        if (running) postInvalidateOnAnimation()
    }

    /** 신호의 상대 쪽 끝점(화면 좌표). 대상이 사라졌으면 null. */
    private fun pulseFar(pu: Pulse): Pair<Float, Float>? {
        val t = when {
            pu.ahead != null -> tracks.values.firstOrNull { it.car.emoteTarget == (if (pu.ahead) "ahead" else "behind") } ?: return null
            pu.key != null -> tracks[pu.key]
            else -> null
        }
        val g = if (t != null) geo(t.cross, t.along) else geo(pu.vCross, pu.vAlong)
        return g.cx to g.yc
    }

    /**
     * 평소의 연결 표시: 연결된 모든 참여 차량 쪽으로 내 차 정중앙에서 회색 점선이 뻗는다 ("너를 인지하고 있다").
     * 차마다 끝까지 이어 두면 교통량이 많을 때 그물처럼 되므로, 선은 내 차에서 멀어질수록 흐려져 사라진다.
     *  - 가까운 차(앞 60m · 뒤 40m 안): 진하고 짧다. 가상의 원([FADE_RADIUS]) 안에서만 보인다.
     *  - 먼 차(화면 안의 먼 차, 화면 밖 1km 안의 차): 멀수록 옅어지되 더 길게, 그 차가 있는 쪽으로 뻗다가 사라진다
     *    → 차는 안 보여도 "멀지만 연결돼 있다"는 것이 방향으로 보인다.
     * 신호가 오가는 동안에는 그 차와의 선만 [drawPulses]가 끝까지 이어진 점선으로 그린다.
     */
    private fun drawLinks(c: Canvas, now: Long) {
        val d = data ?: return
        val members = tracks.values.filter { it.car.member }
        linkSince.keys.retainAll(members.map { it.car.key }.toSet())
        if (members.isEmpty() && d.far.isEmpty()) return
        val me = geo(0.0, 0.0)
        val busy = pulses.mapNotNull { pu -> if (pu.ahead != null) tracks.values.firstOrNull { it.car.emoteTarget == (if (pu.ahead) "ahead" else "behind") }?.car?.key else pu.key }.toSet()
        val nearR = me.hw * FADE_RADIUS
        linkPaint.pathEffect = dots
        linkPaint.color = Color.WHITE   // 페인트 알파가 셰이더에 곱해지므로 불투명으로 둔다

        fun link(along: Double, cross: Double, appear: Float) {
            val dist = kotlin.math.hypot(along, cross)
            val near = along <= NEAR_AHEAD_M && along >= -NEAR_BEHIND_M && abs(cross) <= NEAR_SIDE_M
            // 화면 밖의 차는 그 방향의 화면 끝을 향한다
            val o = geo(cross.coerceIn(-SIDE_CLAMP_M, SIDE_CLAMP_M), along.coerceIn(-d.behindM * 0.97, d.aheadM * 0.97))
            val len = kotlin.math.hypot(o.cx - me.cx, o.yc - me.yc)
            if (len < 2f) return
            // 거리별 진하기: 60m까지 1.0 → 200m 0.62 → 1km 0.34 (로그로 줄어든다)
            val strength = if (near) 1f else (1f - 0.235f * kotlin.math.ln((dist / 60.0).coerceAtLeast(1.0)).toFloat()).coerceIn(0.3f, 1f)
            // 사라지는 반경: 가까운 차는 가상의 원, 먼 차는 그 차 쪽으로 가는 길의 85%까지 (원보다 짧아지지는 않게)
            val radius = if (near) nearR else max(nearR, len * 0.85f)
            val a = appear * strength
            linkPaint.strokeWidth = if (near) 2.6f else 2.2f
            linkPaint.shader = android.graphics.RadialGradient(me.cx, me.yc, radius,
                intArrayOf(Color.argb((170 * a).toInt(), 148, 160, 176), Color.argb((115 * a).toInt(), 148, 160, 176),
                    Color.argb((45 * a).toInt(), 148, 160, 176), Color.argb(0, 148, 160, 176)),
                floatArrayOf(0f, 0.3f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawLine(me.cx, me.yc, o.cx, o.yc, linkPaint)
        }

        for (t in members) {
            if (t.car.key in busy) continue
            val since = linkSince.getOrPut(t.car.key) { now }
            link(t.along, t.cross, min(1f, (now - since) / 600f))
        }
        for ((along, cross) in d.far) link(along, cross, 1f)
        linkPaint.shader = null
        linkPaint.pathEffect = null
    }

    private fun drawPulses(c: Canvas, now: Long) {
        val me = geo(0.0, 0.0)
        val it = pulses.iterator()
        while (it.hasNext()) {
            val pu = it.next()
            val age = now - pu.startMs
            if (age > PULSE_MS + 700) { it.remove(); continue }
            val far = pulseFar(pu) ?: continue
            val f = min(1f, age / PULSE_MS.toFloat()).let { k -> k * k * (3 - 2 * k) }
            val (sx, sy, tx, ty) = if (pu.outgoing) listOf(me.cx, me.yc, far.first, far.second) else listOf(far.first, far.second, me.cx, me.yc)
            val r = Color.red(pu.tint); val g = Color.green(pu.tint); val b = Color.blue(pu.tint)
            // 신호가 오가는 동안만: 흐려지는 선 대신 끝까지 이어진 점선, 지나온 구간은 방향을 따라 켜진다. 도착 뒤 서서히 꺼진다
            val lit = if (age <= PULSE_MS) 1f else 1f - (age - PULSE_MS) / 700f
            linkPaint.pathEffect = dots
            linkPaint.strokeWidth = 3f
            linkPaint.color = Color.argb((190 * lit).toInt().coerceIn(0, 255), 148, 160, 176)
            c.drawLine(sx, sy, tx, ty, linkPaint)
            linkPaint.strokeWidth = 4.4f
            linkPaint.color = Color.argb((240 * lit).toInt().coerceIn(0, 255), r, g, b)
            c.drawLine(sx, sy, sx + (tx - sx) * f, sy + (ty - sy) * f, linkPaint)
            linkPaint.pathEffect = null
            if (age <= PULSE_MS) {
                // 움직이는 빛 + 기호
                val px = sx + (tx - sx) * f; val py = sy + (ty - sy) * f
                p.color = Color.argb(70, r, g, b); c.drawCircle(px, py, 46f, p)
                p.color = Color.argb(140, r, g, b); c.drawCircle(px, py, 36f, p)
                p.color = Color.WHITE; c.drawCircle(px, py, 30f, p)
                text.textAlign = Paint.Align.CENTER; text.textSize = 36f
                c.drawText(pu.symbol, px, py + 13f, text)
            } else {
                // 도착: 받는 쪽에서 퍼지는 고리
                val k = min(1f, (age - PULSE_MS) / 500f)
                linkPaint.strokeWidth = 4f; linkPaint.color = Color.argb((220 * (1 - k)).toInt(), r, g, b)
                val rr = 30f + 80f * k
                c.drawOval(RectF(tx - rr, ty - rr * 0.5f, tx + rr, ty + rr * 0.5f), linkPaint)
                // 도착한 기호가 위로 살짝 떠오르며 사라진다
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
        val src = d.sources
        val layers = listOf("GPS 비콘" to true, "카메라" to ("camera" in src), "레이더·라이다" to ("radar" in src || "lidar" in src), "UWB" to ("uwb" in src))
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
        val tier = if ("uwb" in src) "Tier 3 미리보기" else if (src.size > 1) "Tier 2 미리보기" else "Tier 1"
        c.drawText("$tier · 옅은 원 = GPS 위치 오차 ±${d.errorM.toInt()}m", 46f, y + 30f, text)
    }

    companion object {
        private const val LANE_W = 3.5
        private const val CAR_W = 1.9
        private const val CAR_L = 4.6
        private const val NEAR_M = 12.0
        private const val FADE_RADIUS = 6.5f      // 평소 연결 표시가 사라지는 가상의 원: 내 차 반폭의 몇 배
        private val TEAL = Color.rgb(20, 184, 166)
        private const val SIDE_CLAMP_M = 14.0   // 화면 밖 옆쪽 차를 향한 선이 도로 그림을 벗어나 눕지 않게
        private const val NEAR_AHEAD_M = 60.0
        private const val NEAR_BEHIND_M = 40.0
        private const val NEAR_SIDE_M = 8.0      // 옆으로 두 차로
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
