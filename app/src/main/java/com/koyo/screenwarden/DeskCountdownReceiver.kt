package com.koyo.screenwarden

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class DeskCountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FINISH) return
        val finished = DeskCountdownStore.consumeFinished(context) ?: return
        val message = "${finished.label}结束了，先停一下，我陪你看看刚才做得怎么样"
        vibrate(context)
        TiyoSessionStore.appendAssistantMessage(
            context,
            finished.scope,
            TiyoSessionStore.activeId(context, finished.scope),
            message,
            "截止日期到了"
        )
        recordAgentEvent(context, finished.scope, finished.label)
        notifyUser(context, message, finished.scope)
    }

    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 180L, 110L, 180L, 110L, 260L), -1))
        }
    }

    private fun recordAgentEvent(context: Context, scope: CompanionScope, label: String) {
        val args = JSONObject()
            .put("name", "书桌倒计时结束-${System.currentTimeMillis()}")
            .put("description", "用户设置的${scope.displayName}书桌倒计时已经结束")
            .put("type", "event")
            .put("content", "用户的书桌倒计时“${label.take(40)}”刚刚结束，${scope.displayName}已经提醒用户休息并回顾刚才的进展")
        TiyoMemoryBridge.saveLocalMemory(context, scope, args)
        TiyoMemoryBridge.enqueueMemoryWrite(context, scope, args)
    }

    private fun notifyUser(context: Context, message: String, scope: CompanionScope) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "${scope.displayName}书桌计时",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "书桌倒计时结束提醒"
                    enableVibration(false)
                }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context,
            4128,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("${scope.displayName}提醒你")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    companion object {
        const val ACTION_FINISH = "com.koyo.screenwarden.action.DESK_COUNTDOWN_FINISH"
        private const val CHANNEL_ID = "tiyo_desk_countdown"
        private const val NOTIFICATION_ID = 4127
    }
}
