package com.koyo.screenwarden.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.koyo.screenwarden.CommandCheckWorker
import com.koyo.screenwarden.RealtimeStateWorker
import com.koyo.screenwarden.UsageReportWorker
import com.koyo.screenwarden.presence.TiyoPresenceService

/** 进程外也能收到的电源和开机事件。 */
class DeviceEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> EventBus.publish(
                context,
                TiyoEvent(TiyoEventType.POWER_CONNECTED, "设备刚刚接上充电")
            )
            Intent.ACTION_POWER_DISCONNECTED -> EventBus.publish(
                context,
                TiyoEvent(TiyoEventType.POWER_DISCONNECTED, "设备刚刚断开充电")
            )
            Intent.ACTION_BOOT_COMPLETED -> {
                UsageReportWorker.schedule(context)
                CommandCheckWorker.schedule(context)
                RealtimeStateWorker.schedule(context)
                TiyoPresenceService.refresh(context)
                EventBus.publish(context, TiyoEvent(TiyoEventType.TIME_ANCHOR, "设备开机后的兜底心跳"))
            }
        }
    }
}
