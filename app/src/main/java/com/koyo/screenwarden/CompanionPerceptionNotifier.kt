package com.koyo.screenwarden

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object CompanionPerceptionNotifier {
    private const val CHANNEL_ID = "tiyo_companion_perception"
    private const val NOTIFICATION_ID = 0x4B09

    fun show(context: Context, target: CompanionAppTarget) {
        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = CompanionProfileStore.activeName(ctx)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "$name 陪伴感知", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示$name 正在陪你看选定应用，原始截图不会保存"
                    setShowBadge(false)
                }
            )
        }
        val openIntent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = Intent(ctx, CompanionPerceptionReceiver::class.java).apply {
            action = CompanionPerceptionReceiver.ACTION_PAUSE
        }
        val pausePending = PendingIntent.getBroadcast(
            ctx, NOTIFICATION_ID + 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${CompanionProfileStore.activeName(context)}正在陪你看 · ${target.label}")
            .setContentText("只取经过筛选的单帧，原图不保存")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "暂停感知", pausePending)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun hide(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}

class CompanionPerceptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        CompanionPerceptionPrefs.setEnabled(context, false)
        CompanionPerceptionNotifier.hide(context)
    }

    companion object {
        const val ACTION_PAUSE = "com.koyo.screenwarden.ACTION_PAUSE_COMPANION_PERCEPTION"
    }
}
