package com.koyo.screenwarden.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** SCREEN_ON/OFF 只能动态注册，Application 存活期间持续采集。 */
class ScreenEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> TiyoEvent(TiyoEventType.SCREEN_ON, "屏幕刚刚亮起")
            Intent.ACTION_SCREEN_OFF -> TiyoEvent(TiyoEventType.SCREEN_OFF, "屏幕刚刚熄灭")
            else -> null
        } ?: return
        EventBus.publish(context, event)
    }

    companion object {
        @Volatile private var receiver: ScreenEventReceiver? = null

        fun register(context: Context) {
            if (receiver != null) return
            synchronized(this) {
                if (receiver != null) return
                val value = ScreenEventReceiver()
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    value,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiver = value
            }
        }
    }
}
