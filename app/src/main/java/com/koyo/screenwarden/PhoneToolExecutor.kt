package com.koyo.screenwarden

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Executes the strictly bounded set of Android-side tools requested by the Rust agent. */
object PhoneToolExecutor {
    data class Outcome(
        val success: Boolean,
        val result: JSONObject? = null,
        val error: String? = null
    )

    private const val NOTIFICATION_CHANNEL = "tiyo_agent_phone_tools"
    private val notificationIds = AtomicInteger(41_000)

    fun executeAsync(
        context: Context,
        toolName: String,
        arguments: JSONObject,
        callback: (Outcome) -> Unit
    ) {
        val appContext = context.applicationContext
        thread(name = "tiyo-phone-tool", isDaemon = true) {
            val outcome = try {
                Outcome(true, execute(appContext, toolName, arguments))
            } catch (error: SecurityException) {
                Outcome(false, error = permissionError(toolName))
            } catch (error: IllegalArgumentException) {
                Outcome(false, error = error.message ?: "手机工具参数不正确")
            } catch (error: Exception) {
                Outcome(
                    false,
                    error = error.message?.takeIf { it.isNotBlank() } ?: "手机工具执行失败"
                )
            }
            callback(outcome)
        }
    }

    private fun execute(context: Context, toolName: String, args: JSONObject): JSONObject =
        when (toolName) {
            "phone_usage_stats" -> usageStats(context, args)
            "phone_steps" -> steps(context)
            "phone_battery" -> battery(context)
            "phone_notify" -> notify(context, args)
            "phone_open_app" -> openApp(context, args)
            "phone_clipboard_read" -> clipboardRead(context)
            "phone_clipboard_write" -> clipboardWrite(context, args)
            "phone_alarm_set" -> setAlarm(context, args)
            "phone_calendar_read" -> calendarRead(context)
            else -> throw IllegalArgumentException("不支持的手机工具：$toolName")
        }

    private fun usageStats(context: Context, args: JSONObject): JSONObject {
        if (!hasUsageAccess(context)) {
            throw SecurityException("PACKAGE_USAGE_STATS")
        }
        val start = startOfToday()
        val end = System.currentTimeMillis()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val packageManager = context.packageManager
        val requestedPackage = args.optString("package_name").trim()
        val requestedName = args.optString("app_name").trim()
        val items = manager
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .orEmpty()
            .groupBy { it.packageName }
            .map { (packageName, rows) ->
                val millis = rows.sumOf { it.totalTimeInForeground.coerceAtLeast(0L) }
                val label = runCatching {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(packageName)
                UsageItem(packageName, label, millis)
            }
            .filter { item ->
                item.millis > 0L &&
                    (requestedPackage.isEmpty() || item.packageName == requestedPackage) &&
                    (requestedName.isEmpty() ||
                        item.label.lowercase(Locale.getDefault()).contains(
                            requestedName.lowercase(Locale.getDefault())
                        ))
            }
            .sortedByDescending { it.millis }
            .take(100)

        val apps = JSONArray()
        items.forEach { item ->
            apps.put(
                JSONObject()
                    .put("package_name", item.packageName)
                    .put("app_name", item.label)
                    .put("foreground_ms", item.millis)
                    .put("foreground_minutes", item.millis / 60_000.0)
            )
        }
        if ((requestedPackage.isNotEmpty() || requestedName.isNotEmpty()) && items.isEmpty()) {
            return JSONObject()
                .put("found", false)
                .put("foreground_ms", 0)
                .put("foreground_minutes", 0)
                .put("apps", apps)
        }
        return JSONObject()
            .put("found", true)
            .put("from_ms", start)
            .put("to_ms", end)
            .put("total_foreground_ms", items.sumOf { it.millis })
            .put("apps", apps)
    }

    private fun steps(context: Context): JSONObject {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("ACTIVITY_RECOGNITION")
        }
        val value = runBlocking { StepCounterCollector.refreshAndGetSteps(context) }
        if (value < 0) throw IllegalStateException("这台手机没有可用的计步传感器")
        return JSONObject().put("steps", value).put("day_start_ms", startOfToday())
    }

    private fun battery(context: Context): JSONObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw IllegalStateException("无法读取电池状态")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return JSONObject()
            .put("level_percent", if (level < 0) -1 else level * 100 / scale)
            .put("charging", charging)
            .put("plugged", plugged != 0)
    }

    private fun notify(context: Context, args: JSONObject): JSONObject {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("POST_NOTIFICATIONS")
        }
        val body = requiredString(args, "body")
        val companionName = CompanionProfileStore.activeName(context)
        val title = args.optString("title").trim().ifEmpty { companionName }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                "${companionName}的本地提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val id = notificationIds.incrementAndGet()
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
        return JSONObject().put("posted", true).put("notification_id", id)
    }

    private fun openApp(context: Context, args: JSONObject): JSONObject {
        val packageName = requiredString(args, "package_name")
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException("没有找到可启动的应用：$packageName")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return JSONObject().put("opened", true).put("package_name", packageName)
    }

    private fun clipboardRead(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        val limit = 20_000
        return JSONObject()
            .put("text", text.take(limit))
            .put("truncated", text.length > limit)
    }

    private fun clipboardWrite(context: Context, args: JSONObject): JSONObject {
        val text = requiredString(args, "text", allowEmpty = true)
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("tiyo", text))
        return JSONObject().put("written", true).put("length", text.length)
    }

    private fun setAlarm(context: Context, args: JSONObject): JSONObject {
        if (!args.has("hour") || !args.has("minute")) {
            throw IllegalArgumentException("设置闹钟需要 hour 和 minute")
        }
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            throw IllegalArgumentException("闹钟时间必须是 00:00 到 23:59")
        }
        val message = args.optString("message").trim().ifEmpty {
            "${CompanionProfileStore.activeName(context)}的提醒"
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, args.optBoolean("skip_ui", true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            throw IllegalStateException("手机上没有可处理闹钟的系统应用")
        }
        context.startActivity(intent)
        return JSONObject()
            .put("set", true)
            .put("hour", hour)
            .put("minute", minute)
            .put("message", message)
    }

    private fun calendarRead(context: Context): JSONObject {
        if (
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("READ_CALENDAR")
        }
        val start = startOfToday()
        val end = endOfToday()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, start)
            ContentUris.appendId(builder, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val events = JSONArray()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val locationIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            while (cursor.moveToNext() && events.length() < 100) {
                events.put(
                    JSONObject()
                        .put("title", cursor.getString(titleIndex).orEmpty())
                        .put("start_ms", cursor.getLong(beginIndex))
                        .put("end_ms", cursor.getLong(endIndex))
                        .put("all_day", cursor.getInt(allDayIndex) != 0)
                        .put("location", cursor.getString(locationIndex).orEmpty())
                )
            }
        }
        return JSONObject()
            .put("day_start_ms", start)
            .put("day_end_ms", end)
            .put("events", events)
    }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requiredString(
        args: JSONObject,
        key: String,
        allowEmpty: Boolean = false
    ): String {
        if (!args.has(key)) throw IllegalArgumentException("缺少参数：$key")
        val value = args.optString(key)
        if (!allowEmpty && value.isBlank()) throw IllegalArgumentException("参数 $key 不能为空")
        return value
    }

    private fun permissionError(toolName: String): String = when (toolName) {
        "phone_usage_stats" -> "未授予使用情况访问权限，请在系统设置里允许 tiyo 查看使用情况"
        "phone_steps" -> "未授予身体活动权限，请在系统设置里允许 tiyo 读取步数"
        "phone_notify" -> "未授予通知权限，请在系统设置里允许 tiyo 发送通知"
        "phone_calendar_read" -> "未授予日历权限，请在系统设置里允许 tiyo 读取日历后重试"
        else -> "系统没有授予执行该手机操作所需的权限"
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private data class UsageItem(
        val packageName: String,
        val label: String,
        val millis: Long
    )
}
