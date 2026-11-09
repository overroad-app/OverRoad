package com.hyperconnect.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** 충전소로 가는 여정: 지금 → 도착 → (대기) → 충전 시작 → 80% 완료. 시각은 epoch 밀리초. */
data class Trip(
    val station: String, val operator: String?, val rating: Double?, val ratingN: Int, val kw: Int,
    val free: Int, val stalls: Int, val socArrival: Int?, val known: Int, val estimated: Int,
    val startMs: Long, val etaMs: Long, val chargeStartMs: Long, val doneMs: Long,
)

/**
 * 충전 여정 화면 (EV rev4, 2026-11-08 사용자 스케치): 충전기를 꽂기 전, 목적지를 찍고 가는 동안의 화면.
 * 꽂은 뒤의 충전 상태는 Pleos 충전 화면이 맡는다.
 *  - [Mode.BAR]  중간 크기 창: 가로 시간 막대 (이동 · 대기 · 충전), 구간 길이와 시각, 지금 위치 점
 *  - [Mode.PILL] 작은 창: 이모트 바 위 알약 카드 — 같은 막대를 12시부터 **반시계 방향**으로 감은 링
 * 색: 이동 = 무채색(지나온 길은 진하게), 대기 = 앰버, 충전 = 청록 민트(Pleos 충전 초록 계열). 빨강은 경고로 읽혀 쓰지 않는다.
 * 링 가운데는 예상 도착 → 예상 대기 → 충전 완료를 1 : 1 : 3 시간 비율로 돌려 보여 준다 (가장 중요한 건 완료 시각). 지난 단계는 건너뛴다.
 */
class TripView(context: Context, private val mode: Mode) : View(context) {
    enum class Mode { BAR, PILL }

    var trip: Trip? = null
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // 1초마다 다시 그린다 (지나온 이동이 차오르고, 링 가운데 글자가 돌아간다). 화면에 붙어 있을 때만.
        post(object : Runnable {
            override fun run() {
                if (isAttachedToWindow && visibility == VISIBLE && trip != null) invalidate()
                postDelayed(this, 1000)
            }
        })
    }

    override fun onDraw(c: Canvas) {
        val tr = trip ?: return
        val now = System.currentTimeMillis()
        if (mode == Mode.BAR) drawBar(c, tr, now) else drawPill(c, tr, now)
    }

    // ---------- 중간 화면: 가로 막대 ----------
    private fun drawBar(c: Canvas, tr: Trip, now: Long) {
        val w = width.toFloat()
        val x0 = 8f; val x1 = w - 8f
        var y = 40f
        t.typeface = Typeface.DEFAULT_BOLD; t.textSize = 32f; t.color = INK; t.textAlign = Paint.Align.LEFT
        c.drawText(tr.station, x0, y, t)
        var x = x0 + t.measureText(tr.station) + 14f
        tr.operator?.let { t.typeface = Typeface.DEFAULT; t.textSize = 22f; t.color = MUTED; c.drawText(it, x, y, t) }
        // 오른쪽 배지: 급속 kW · 빈 충전기 (Pleos 표기)
        val chip = "급속 ${tr.kw}kW · ${tr.free}/${tr.stalls} 사용 가능"
        t.textSize = 21f; t.typeface = Typeface.DEFAULT_BOLD
        val cw = t.measureText(chip) + 36f
        p.color = CHIP_BG; c.drawRoundRect(RectF(x1 - cw, y - 30f, x1, y + 10f), 20f, 20f, p)
        t.color = CHIP_FG; t.textAlign = Paint.Align.CENTER; c.drawText(chip, x1 - cw / 2, y - 3f, t)
        // 별 5개 채우기
        y += 40f
        drawStars(c, x0, y, 26f, tr.rating)
        t.textAlign = Paint.Align.LEFT; t.typeface = Typeface.DEFAULT; t.textSize = 22f; t.color = MUTED
        c.drawText(tr.rating?.let { "%.1f".format(it) + (if (tr.ratingN > 0) " (${tr.ratingN})" else "") } ?: "평가 없음", x0 + 5 * 30f + 10f, y, t)
        // 막대
        val total = max(1L, tr.doneMs - tr.startMs).toFloat()
        fun xs(ms: Long) = x0 + (x1 - x0) * ((ms - tr.startMs) / total).coerceIn(0f, 1f)
        val xa = xs(tr.etaMs); val xc = xs(tr.chargeStartMs); val xn = xs(now)
        y += 58f
        val bh = 20f
        t.textSize = 21f; t.textAlign = Paint.Align.CENTER
        val driveMin = ((tr.etaMs - tr.startMs) / 60000).toInt(); val waitMin = ((tr.chargeStartMs - tr.etaMs) / 60000).toInt()
        val chargeMin = ((tr.doneMs - tr.chargeStartMs) / 60000).toInt()
        t.color = MUTED; c.drawText("이동 ${driveMin}분", (x0 + xa) / 2, y - 14f, t)
        if (waitMin > 0) { t.color = WAIT_DARK; c.drawText("대기 ${waitMin}분", (xa + xc) / 2, y - 14f, t) }
        t.color = CHARGE_DARK
        c.drawText("충전 ${chargeMin}분" + (tr.socArrival?.let { " · $it% → 80%" } ?: ""), (xc + x1) / 2, y - 14f, t)
        segment(c, x0, xa, y, bh, DRIVE_LEFT, DRIVE_DONE, xn)
        if (xc > xa + 2) segment(c, xa + 3f, xc, y, bh, WAIT, WAIT_DARK, xn)
        segment(c, xc + 3f, x1, y, bh, CHARGE, CHARGE_DARK, xn)
        // 지금 위치 점
        p.color = Color.WHITE; c.drawCircle(xn, y + bh / 2, 13f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = DRIVE_DONE; c.drawCircle(xn, y + bh / 2, 13f, p); p.style = Paint.Style.FILL
        // 눈금과 시각
        val ty = y + bh + 36f
        fun tick(xx: Float, top: String, sub: String, color: Int, big: Boolean = false, align: Paint.Align = Paint.Align.CENTER) {
            p.color = color; c.drawRect(xx - 1.5f, y - 6f, xx + 1.5f, y + bh + 6f, p)
            t.textAlign = align; t.typeface = if (big) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            t.textSize = if (big) 28f else 23f; t.color = INK; c.drawText(top, xx, ty, t)
            t.typeface = Typeface.DEFAULT; t.textSize = 19f; t.color = MUTED; c.drawText(sub, xx, ty + 26f, t)
        }
        tick(xa, hm(tr.etaMs), "도착", DRIVE_DONE)
        if (xc > xa + 60) tick(xc, hm(tr.chargeStartMs), "충전 시작", CHARGE_DARK)
        tick(x1, hm(tr.doneMs), "80% 완료", CHARGE_DARK, big = true, align = Paint.Align.RIGHT)
        t.textAlign = Paint.Align.LEFT; t.textSize = 18f; t.color = FAINT; t.typeface = Typeface.DEFAULT
        val basis = listOfNotNull(
            if (tr.known > 0) "HyperConnect ${tr.known}대 정확" else null,
            if (tr.estimated > 0) "타사 ${tr.estimated}대 추정 (공공 충전기 상태·통계)" else null,
        ).joinToString(" + ").ifEmpty { "충전소에 다른 차 없음" }
        c.drawText("예측 근거: $basis", x0, ty + 62f, t)
    }

    private fun segment(c: Canvas, a: Float, b: Float, y: Float, h: Float, light: Int, dark: Int, now: Float) {
        if (b <= a) return
        p.color = light; c.drawRoundRect(RectF(a, y, b, y + h), h / 2, h / 2, p)
        val e = min(b, now)
        if (e > a) { p.color = dark; c.drawRoundRect(RectF(a, y, e, y + h), h / 2, h / 2, p) }
    }

    private fun drawStars(c: Canvas, x: Float, y: Float, size: Float, rating: Double?) {
        t.textSize = size; t.textAlign = Paint.Align.LEFT; t.typeface = Typeface.DEFAULT
        for (i in 0 until 5) {
            val fill = ((rating ?: 0.0) - i).coerceIn(0.0, 1.0)
            t.color = if (fill >= 0.5) STAR else STAR_EMPTY
            c.drawText("★", x + i * 30f, y, t)
        }
    }

    // ---------- 작은 화면: 알약 카드 + 반시계 링 ----------
    private fun drawPill(c: Canvas, tr: Trip, now: Long) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = Color.argb(245, 255, 255, 255)
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.rgb(226, 232, 240)
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), h / 2, h / 2, p); p.style = Paint.Style.FILL
        val r = h / 2 - 16f
        val cx = h / 2; val cy = h / 2
        drawRing(c, cx, cy, r, 9f, tr, now, p)
        val (label, value, color) = centerText(tr, now)
        t.textAlign = Paint.Align.CENTER; t.typeface = Typeface.DEFAULT; t.textSize = 14f; t.color = MUTED
        c.drawText(label, cx, cy - 6f, t)
        t.typeface = Typeface.DEFAULT_BOLD; t.textSize = 22f; t.color = color
        c.drawText(value, cx, cy + 18f, t)
        // 오른쪽: 충전소 · 대기와 완료
        val tx = h + 6f
        t.textAlign = Paint.Align.LEFT; t.typeface = Typeface.DEFAULT_BOLD; t.textSize = 23f; t.color = INK
        c.drawText(tr.station, tx, cy - 8f, t)
        val wait = ((tr.chargeStartMs - max(now, tr.etaMs)) / 60000).toInt()
        t.typeface = Typeface.DEFAULT; t.textSize = 20f
        t.color = if (wait > 0) WAIT_DARK else CHARGE_DARK
        val line = (if (wait > 0) "대기 ${wait}분" else "바로 충전") + " · 완료 ${hm(tr.doneMs)}"
        c.drawText(line, tx, cy + 22f, t)
    }

    companion object {
        val INK = Color.rgb(30, 41, 59)
        val MUTED = Color.rgb(100, 116, 139)
        val FAINT = Color.rgb(148, 163, 184)
        val DRIVE_LEFT = Color.rgb(203, 200, 192)
        val DRIVE_DONE = Color.rgb(68, 68, 65)
        val WAIT = Color.rgb(250, 199, 117)
        val WAIT_DARK = Color.rgb(186, 117, 23)
        val CHARGE = Color.rgb(93, 202, 165)
        val CHARGE_DARK = Color.rgb(15, 110, 86)
        val TRACK = Color.rgb(233, 231, 225)
        val STAR = Color.rgb(239, 159, 39)
        val STAR_EMPTY = Color.rgb(211, 209, 199)
        val CHIP_BG = Color.rgb(225, 245, 238)
        val CHIP_FG = Color.rgb(8, 80, 65)
        private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
        fun hm(ms: Long): String = clock.format(Date(ms))

        /** 링 가운데: 예상 도착 → 예상 대기 → 충전 완료를 1 : 1 : 3 비율(한 바퀴 10초)로 돌린다. 지난 단계는 뺀다. */
        fun centerText(tr: Trip, now: Long): Triple<String, String, Int> {
            val wait = ((tr.chargeStartMs - max(now, tr.etaMs)) / 60000).toInt()
            val steps = buildList {
                if (now < tr.etaMs) add(Triple("예상 도착", hm(tr.etaMs), INK) to 1)
                if (now < tr.chargeStartMs) add(Triple("예상 대기", if (wait > 0) "${wait}분" else "없음", WAIT_DARK) to 1)
                add(Triple("충전 완료", hm(tr.doneMs), CHARGE_DARK) to 3)
            }
            val unit = 2000L
            val cycle = steps.sumOf { it.second } * unit
            var at = now % cycle
            for ((txt, weight) in steps) {
                if (at < weight * unit) return txt
                at -= weight * unit
            }
            return steps.last().first
        }

        /** 같은 시간 막대를 12시부터 반시계 방향으로 감은 링. 지나온 부분은 각 구간의 진한 색. */
        fun drawRing(c: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, tr: Trip, now: Long, p: Paint) {
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.strokeCap = Paint.Cap.BUTT
            p.color = TRACK; c.drawArc(oval, 0f, 360f, false, p)
            val total = max(1L, tr.doneMs - tr.startMs).toFloat()
            fun f(ms: Long) = ((ms - tr.startMs) / total).coerceIn(0f, 1f)
            val fa = f(tr.etaMs); val fc = f(tr.chargeStartMs); val fn = f(now)
            val gap = 0.008f
            fun arc(a: Float, b: Float, color: Int) {
                if (b - a <= 0.001f) return
                p.color = color
                // 반시계: 12시(-90°)에서 음의 방향으로 쓴다
                c.drawArc(oval, -90f - a * 360f, -(b - a) * 360f, false, p)
            }
            fun seg(a: Float, b: Float, light: Int, dark: Int) {
                val aa = a + gap; val bb = b - gap
                arc(aa, bb, light)
                arc(aa, min(bb, fn), dark)
            }
            seg(0f, fa, DRIVE_LEFT, DRIVE_DONE)
            if (fc > fa + 0.01f) seg(fa, fc, WAIT, WAIT_DARK)
            seg(fc, 1f, CHARGE, CHARGE_DARK)
            p.style = Paint.Style.FILL
        }
    }
}
