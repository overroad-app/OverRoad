package com.hyperconnect.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/**
 * 충전소 지도 (EV rev3): 내 위치와 주변 충전소를 한 장에 그린다. 글 대신 색·별점·완료 시각으로 한눈에 고르게 한다.
 * - 핀 색: 이 충전소로 가면 기다리는 시간 (초록 바로 · 노랑 ~15분 · 빨강 그 이상)
 * - 핀 위: ★별점(평가 수), 아래: 80% 충전 완료 시각
 * - 목적지 충전소는 굵은 테두리. 정차 중에만 보인다 (주행 중에는 한 줄 카드와 음성).
 * 지도 타일은 쓰지 않는 개략도다. 실제 지도 위 표시는 내비 연동(Tier 2) 과제.
 */
class ChargerMapView(context: Context) : View(context) {
    var data: ChargerMap? = null
        set(v) { field = v; invalidate() }

    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val grid = Paint().apply { color = Color.rgb(34, 40, 52); strokeWidth = 2f }
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 60, 76); strokeWidth = 18f; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 208, 220); textSize = 26f; textAlign = Paint.Align.CENTER }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 204, 21); textSize = 30f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 170, 185); textSize = 22f }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(22, 26, 34))
        val w = width.toFloat(); val h = height.toFloat()
        var x = 0f; while (x < w) { canvas.drawLine(x, 0f, x, h, grid); x += 80f }
        var y = 0f; while (y < h) { canvas.drawLine(0f, y, w, y, grid); y += 80f }
        val d = data ?: return
        val pts = d.pins.map { it.lat to it.lon } + listOfNotNull(d.myLat?.let { it to d.myLon!! })
        if (pts.isEmpty()) return
        val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
        val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = max((maxLon - minLon) * kx, 1e-4); val spanY = max(maxLat - minLat, 1e-4)
        // 위: 별점 글자, 아래: 이름·완료 시각과 범례가 들어갈 자리를 남긴다
        val padX = 160f; val padTop = 100f; val padBottom = 190f
        val s = minOf((w - 2 * padX) / spanX, (h - padTop - padBottom) / spanY).toFloat()
        val cx = w / 2 - ((minLon + maxLon) / 2 * kx * s).toFloat()
        val cy = padTop + (h - padTop - padBottom) / 2 + ((minLat + maxLat) / 2 * s).toFloat()
        fun px(lat: Double, lon: Double) = (cx + (lon * kx * s).toFloat()) to (cy - (lat * s).toFloat())

        val me = d.myLat?.let { px(it, d.myLon!!) }
        // 내 위치에서 각 충전소로 가는 길 (개략)
        me?.let { (mx, my) -> d.pins.forEach { p -> val (tx, ty) = px(p.lat, p.lon); canvas.drawLine(mx, my, tx, ty, road) } }

        for (p in d.pins) {
            val (tx, ty) = px(p.lat, p.lon)
            fill.color = when {
                p.waitMin == null -> Color.rgb(100, 116, 139)
                p.waitMin <= 2 -> Color.rgb(34, 197, 94)
                p.waitMin <= 15 -> Color.rgb(234, 179, 8)
                else -> Color.rgb(239, 68, 68)
            }
            canvas.drawCircle(tx, ty, 34f, fill)
            if (p.isDest) canvas.drawCircle(tx, ty, 44f, ring)
            canvas.drawText("⚡", tx, ty + 11f, text)
            canvas.drawText(p.rating?.let { "★%.1f (%d)".format(it, p.ratingN) } ?: "★ 평가 없음", tx, ty - 58f, star)
            canvas.drawText(p.name, tx, ty + 82f, text)
            val wait = p.waitMin?.let { if (it <= 2) "바로 충전" else "대기 ${it}분" } ?: ""
            canvas.drawText(listOfNotNull(p.doneMs?.let { "${clock.format(Date(it))} 완료" }, wait.ifEmpty { null }).joinToString(" · "),
                tx, ty + 114f, sub)
        }
        me?.let { (mx, my) ->
            fill.color = Color.rgb(59, 130, 246)
            canvas.drawCircle(mx, my, 22f, fill)
            canvas.drawCircle(mx, my, 22f, ring)
            canvas.drawText("나", mx, my - 34f, text)
        }
        // 범례
        val ly = h - 18f
        listOf(Color.rgb(34, 197, 94) to "바로", Color.rgb(234, 179, 8) to "~15분", Color.rgb(239, 68, 68) to "15분+").forEachIndexed { i, (c, label) ->
            fill.color = c
            canvas.drawCircle(28f + i * 120f, ly - 8f, 10f, fill)
            canvas.drawText(label, 46f + i * 120f, ly, legend)
        }
        canvas.drawText("○ 굵은 테두리 = 목적지 · 별점은 HyperConnect 운전자가 충전 후 남긴 평가", 400f, ly, legend)
    }
}
