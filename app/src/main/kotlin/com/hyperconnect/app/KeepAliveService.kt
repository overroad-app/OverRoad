package com.hyperconnect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 앱이 화면 뒤로 숨겨져도(내비가 앞에 있어도) 프로세스가 유지되도록 하는 포그라운드 서비스.
 * 실제 감지·수신은 MainActivity가 하고, 이 서비스는 프로세스 우선순위만 올린다.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "OverRoad 동작 상태", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("OverRoad 동작 중")
            .setContentText("주변 차량의 위험 경고를 받고 있어요")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "keep_alive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = context.startForegroundService(Intent(context, KeepAliveService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, KeepAliveService::class.java))
    }
}
