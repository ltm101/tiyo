package com.koyo.screenwarden

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 远程动作执行器。CommandCheckWorker 收到动作指令后调这里。
 * 每个方法返回一句人话结果，用于邮件回执。
 */
object ActionExecutor {

    private const val TAG = "ActionExecutor"
    // v2：带声音+震动，触发横幅弹窗（通道属性建后不可改，故换新 id 让其重建）
    private const val CHANNEL_ID = "tiyo_agent_v2"

    private var ringtone: Ringtone? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 响铃找手机 ──────────────────────────────────────
    fun ring(ctx: Context): String {
        return try {
            // ALARM 流拉到最大，绕过静音
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            stopRing()  // 先停掉上一次
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                play()
            }

            vibrate(ctx)

            // 30 秒后自动停
            mainHandler.postDelayed({ stopRing() }, 30_000)
            Log.i(TAG, "ring started")
            "已响铃 + 震动，30 秒后自动停"
        } catch (e: Exception) {
            Log.e(TAG, "ring failed", e)
            "响铃失败: ${e.message}"
        }
    }

    fun stopRing() {
        ringtone?.let { if (it.isPlaying) it.stop() }
        ringtone = null
    }

    private fun vibrate(ctx: Context) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 400, 800, 400, 800, 400, 800)
        vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // ── 推送通知 ────────────────────────────────────────
    fun notify(ctx: Context, text: String, openChat: Boolean = false): String {
        return try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(CompanionProfileStore.activeName(ctx))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            if (openChat) {
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                }
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        ctx,
                        text.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            nm.notify(text.hashCode(), builder.build())
            "已推送通知"
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
            "推送失败: ${e.message}"
        }
    }

    // ── 建议回复（半自动回微信：弹通知给用户，他点复制去粘贴）──────
    fun suggestReply(
        ctx: Context, contact: String, text: String,
        targetPkg: String = "com.tencent.mm", message: String = "", source: String = "",
        friendshipKey: String = "",
        companionScope: CompanionScope = CompanionScope.capture(ctx)
    ): String {
        return try {
            ensureChannel(ctx, companionScope)
            // 追溯：每次弹建议都记一条历史，通知按钮用 historyId 回写状态
            val historyId = AutoReplyHistory.add(
                ctx,
                contact,
                message,
                text,
                source.ifBlank { "弹通知" },
                companionScope
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifId = "suggest_${companionScope.companionId}_$contact".hashCode()
            val appLabel = if (targetPkg == "com.tencent.mobileqq") "QQ" else "微信"

            // 「复制」→ 只复制到剪贴板
            val copyIntent = Intent(ctx, ReplyActionReceiver::class.java).apply {
                action = ReplyActionReceiver.ACTION_COPY
                putExtra(ReplyActionReceiver.EXTRA_REPLY_TEXT, text)
                putExtra(ReplyActionReceiver.EXTRA_HISTORY_ID, historyId)
                putExtra(ReplyActionReceiver.EXTRA_FRIENDSHIP_KEY, friendshipKey)
                putExtra(ReplyActionReceiver.EXTRA_COMPANION_ID, companionScope.companionId)
                putExtra(ReplyActionReceiver.EXTRA_COMPANION_NAME, companionScope.displayName)
            }
            val copyPi = PendingIntent.getBroadcast(
                ctx, notifId, copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("${companionScope.displayName}帮你回 · $contact")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, "复制", copyPi)

            // 「复制并打开」→ 复制 + 拉起对应 App，用户去粘贴发送
            val copyOpenIntent = Intent(ctx, ReplyActionReceiver::class.java).apply {
                action = ReplyActionReceiver.ACTION_COPY_AND_OPEN
                putExtra(ReplyActionReceiver.EXTRA_REPLY_TEXT, text)
                putExtra(ReplyActionReceiver.EXTRA_TARGET_PKG, targetPkg)
                putExtra(ReplyActionReceiver.EXTRA_HISTORY_ID, historyId)
                putExtra(ReplyActionReceiver.EXTRA_FRIENDSHIP_KEY, friendshipKey)
                putExtra(ReplyActionReceiver.EXTRA_COMPANION_ID, companionScope.companionId)
                putExtra(ReplyActionReceiver.EXTRA_COMPANION_NAME, companionScope.displayName)
            }
            val copyOpenPi = PendingIntent.getBroadcast(
                ctx, notifId + 1, copyOpenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "复制并打开$appLabel", copyOpenPi)

            // 点通知主体 → 直接打开对应 App
            val targetIntent = ctx.packageManager.getLaunchIntentForPackage(targetPkg)
            if (targetIntent != null) {
                targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val targetPi = PendingIntent.getActivity(
                    ctx, notifId + 2, targetIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(targetPi)
            }

            nm.notify(notifId, builder.build())
            "已把给 $contact 的建议回复弹到手机"
        } catch (e: Exception) {
            Log.e(TAG, "suggestReply failed", e)
            "建议回复弹出失败: ${e.message}"
        }
    }

    // ── 打开 App（后台启动受限，降级为点击通知打开）──────────
    fun launchApp(ctx: Context, pkg: String): String {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "找不到 App: $pkg"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val appName = try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }

        // 无论如何先发一条可点击通知作为兜底
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, pkg.hashCode(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("打开 $appName")
            .setContentText("点我打开 $appName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)   // 锁屏/系统允许时可直接拉起
            .setAutoCancel(true)
            .build()
        nm.notify(pkg.hashCode(), n)

        // 再尝试直接启动（前台或系统豁免时会成功）
        return try {
            ctx.startActivity(launch)
            "已打开 $appName"
        } catch (e: Exception) {
            "已发通知，点击可打开 $appName（系统限制后台直接启动）"
        }
    }

    // ── 发短信 ──────────────────────────────────────────
    fun sendSms(ctx: Context, number: String, text: String): String {
        return try {
            @Suppress("DEPRECATION")
            val sms = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }) ?: return "发短信失败：拿不到短信服务（可能无 SIM 卡）"
            val parts = sms.divideMessage(text)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, text, null, null)
            }
            "短信已发往 $number"
        } catch (e: SecurityException) {
            "发短信失败：缺少短信权限"
        } catch (e: Exception) {
            Log.e(TAG, "sms failed", e)
            "发短信失败: ${e.message}"
        }
    }

    private fun ensureChannel(
        ctx: Context,
        scope: CompanionScope = CompanionScope.capture(ctx)
    ) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val name = scope.displayName
        val ch = NotificationChannel(
                CHANNEL_ID, "${name}的提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
                description = "$name 主动发来的预警和提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                enableLights(true)
                // 显式给默认提示音，系统更倾向于弹横幅
                val sound = android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_NOTIFICATION
                )
                val attrs = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(sound, attrs)
        }
        nm.createNotificationChannel(ch)
    }
}
