package com.hyperconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.util.Log

/**
 * 내비를 가리지 않는 알림: 앱 화면을 앞으로 띄우지 않고, 플랫폼의 상단 알림(헤즈업)으로 경고를 보여 준다.
 *
 * Pleos 멀티 윈도우는 오른쪽 패널에 한 앱만 보여 주므로(다른 앱이 앞에 오면 내비가 숨겨진다)
 * 주행 중 HyperConnect는 뒤에서 동작하고, 경고만 화면 위쪽 알림으로 잠깐 띄운다.
 * 다른 앱 위에 그리기(SYSTEM_ALERT_WINDOW)는 signature·appop 권한이라 쓰지 않는다.
 * 소리는 앱이 오디오 포커스를 받아 직접 내므로(음성 경고 포함) 알림 채널은 무음이다.
 */
class AlertNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "도로 위험 경고", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "주변 차량이 알려 준 위험을 화면 위쪽에 잠깐 보여 줍니다"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** 상단 알림의 큰 아이콘(배지)과 같은 그림 — 앱이 앞에 있을 때 앱 안 흰 카드에도 같은 아이콘을 쓴다. */
    fun badgeIcon(title: String, color: Int, fg: Int = Color.WHITE, stripe: IntArray? = null, art: String? = null): Icon =
        if (art == ART_BRAKE) brakeBadge(color) else badge(color, fg, stripe, title.substringBefore(" "))

    /** [key]가 같으면 같은 알림을 갱신한다 (예: 긴급차량 접근 거리). [timeoutMs]가 지나면 저절로 사라진다. */
    fun show(key: String, title: String, text: String, color: Int, timeoutMs: Long, onlyOnce: Boolean = false,
             icon: Int = android.R.drawable.ic_dialog_alert, fg: Int = Color.WHITE, stripe: IntArray? = null, art: String? = null) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title.substringAfter(" "))  // 맨 앞 아이콘은 배지에 있으므로 제목에서는 뺀다
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setLargeIcon(when {
                art == ART_BRAKE -> brakeBadge(color)
                else -> badge(color, fg, stripe, title.substringBefore(" "))
            })
            .setColor(color)
            .setColorized(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOnlyAlertOnce(onlyOnce)  // 같은 알림의 갱신(긴급차량 거리)은 다시 튀어나오지 않게
            .setTimeoutAfter(timeoutMs)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(key, ID, n)
        } catch (e: SecurityException) {
            Log.w("HyperConnect", "알림 권한 없음: 상단 알림 생략", e)
        }
    }

    /**
     * Pleos 상단 알림은 흰 바탕에 제목·내용만 그린다 (색 지정과 직접 만든 레이아웃은 무시된다).
     * 그래서 앱 카드의 색·띠·아이콘을 담은 작은 배지 그림으로 알림 종류를 구분한다.
     */
    private fun badge(color: Int, fg: Int, stripe: IntArray?, symbol: String): Icon {
        val size = 128f
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val light = Color.luminance(color) > 0.8f
        p.color = color
        c.drawRoundRect(RectF(0f, 0f, size, size), 26f, 26f, p)
        if (light) {  // 밝은 카드(구급차)는 흰 알림 바탕과 구분되도록 테두리를 그린다
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = fg
            c.drawRoundRect(RectF(3f, 3f, size - 3f, size - 3f), 24f, 24f, p)
            p.style = Paint.Style.FILL
        }
        stripe?.takeIf { it.size >= 2 }?.let {
            p.color = it[0]; c.drawRoundRect(RectF(12f, 16f, 28f, 64f), 6f, 6f, p)
            p.color = it[1]; c.drawRoundRect(RectF(12f, 64f, 28f, 112f), 6f, 6f, p)
        }
        p.textSize = 66f
        p.textAlign = Paint.Align.CENTER
        p.color = Color.WHITE
        c.drawText(symbol, if (stripe != null) 78f else size / 2, 88f, p)
        drawMark(c, size)
        return Icon.createWithBitmap(bmp)
    }

    /** 급제동 전용 배지: 어두운 빨강 바탕에 앞차 뒷모습, 양쪽 브레이크등이 빛난다. */
    private fun brakeBadge(color: Int): Icon {
        val size = 128f
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        c.drawRoundRect(RectF(0f, 0f, size, size), 26f, 26f, p)
        // 브레이크등 빛 번짐
        p.color = Color.argb(90, 255, 59, 48)
        c.drawCircle(34f, 66f, 26f, p); c.drawCircle(94f, 66f, 26f, p)
        // 차 뒷모습: 지붕·유리·차체·범퍼·바퀴
        p.color = Color.rgb(30, 32, 38)
        c.drawRoundRect(RectF(30f, 30f, 98f, 60f), 14f, 14f, p)
        p.color = Color.rgb(120, 140, 160)
        c.drawRoundRect(RectF(38f, 36f, 90f, 54f), 8f, 8f, p)
        p.color = Color.rgb(30, 32, 38)
        c.drawRoundRect(RectF(18f, 54f, 110f, 92f), 14f, 14f, p)
        c.drawRoundRect(RectF(24f, 88f, 40f, 106f), 4f, 4f, p); c.drawRoundRect(RectF(88f, 88f, 104f, 106f), 4f, 4f, p)
        // 브레이크등 (빨강) + 가운데 보조 제동등
        p.color = Color.rgb(255, 59, 48)
        c.drawRoundRect(RectF(22f, 60f, 46f, 72f), 5f, 5f, p); c.drawRoundRect(RectF(82f, 60f, 106f, 72f), 5f, 5f, p)
        c.drawRoundRect(RectF(54f, 31f, 74f, 35f), 2f, 2f, p)
        // 번호판 자리
        p.color = Color.rgb(230, 230, 235)
        c.drawRoundRect(RectF(52f, 76f, 76f, 86f), 2f, 2f, p)
        drawMark(c, size)
        return Icon.createWithBitmap(bmp)
    }

    /**
     * HyperConnect 표시: 흰 상단 알림은 OS(Pleos) 공통 모양 그대로 쓰고, 이 알림이 HyperConnect가 띄운 것임을
     * 아이콘 오른쪽 아래 작은 초록 체크로만 알린다.
     */
    private fun drawMark(c: Canvas, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 21f; val cx = size - r - 2f; val cy = size - r - 2f
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r + 4f, p)
        p.color = Color.rgb(22, 163, 74)
        c.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 6f
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = android.graphics.Path().apply {
            moveTo(cx - 10f, cy + 1f); lineTo(cx - 3f, cy + 8f); lineTo(cx + 11f, cy - 7f)
        }
        c.drawPath(path, p)
    }

    fun cancel(key: String) = manager.cancel(key, ID)

    companion object {
        const val ART_BRAKE = "brake"
        private const val CHANNEL = "road_alerts"
        private const val ID = 100
    }
}
