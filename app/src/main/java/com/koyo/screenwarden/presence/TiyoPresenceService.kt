package com.koyo.screenwarden.presence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.koyo.screenwarden.R

/** Keeps phone-native bot connections alive without relying on a computer process. */
class TiyoPresenceService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.d_ic_me)
                .setContentTitle("tiyo 正在连接其他应用")
                .setContentText("机器人消息由手机上的同一个可又处理")
                .setOngoing(true)
                .setSilent(true)
                .build()
        )
        startNativeAdapters()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startNativeAdapters()
        if (MobilePresenceConfig.enabledChannels(this).isEmpty()) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        MobilePresenceConfig.supportedChannels.forEach { channel ->
            PresenceAdapterRegistry.get(channel)?.stop(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "其他应用连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持 Tiyo 的平台机器人连接" }
        )
    }

    private fun startNativeAdapters() {
        MobilePresenceConfig.enabledChannels(this).forEach { channel ->
            PresenceAdapterRegistry.get(channel)?.start(this)
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "tiyo_presence_connections"
        private const val NOTIFICATION_ID = 4207

        fun refresh(context: Context) {
            val app = context.applicationContext
            if (MobilePresenceConfig.enabledChannels(app).isEmpty()) {
                app.stopService(Intent(app, TiyoPresenceService::class.java))
            } else {
                androidx.core.content.ContextCompat.startForegroundService(
                    app,
                    Intent(app, TiyoPresenceService::class.java)
                )
            }
        }
    }
}
