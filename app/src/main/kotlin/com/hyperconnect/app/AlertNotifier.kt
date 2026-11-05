package com.hyperconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    /** [key]가 같으면 같은 알림을 갱신한다 (예: 긴급차량 접근 거리). [timeoutMs]가 지나면 저절로 사라진다. */
    fun show(key: String, title: String, text: String, color: Int, timeoutMs: Long, onlyOnce: Boolean = false,
             icon: Int = android.R.drawable.ic_dialog_alert) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
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

    fun cancel(key: String) = manager.cancel(key, ID)

    companion object {
        private const val CHANNEL = "road_alerts"
        private const val ID = 100
    }
}
