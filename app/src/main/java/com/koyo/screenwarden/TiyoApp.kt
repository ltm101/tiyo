package com.koyo.screenwarden

import android.app.Application
import android.content.Context
import android.util.Log
import com.koyo.screenwarden.enuman.EnuManRhythmWorker
import com.koyo.screenwarden.events.ScreenEventReceiver
import com.koyo.screenwarden.presence.PresenceShortcutPublisher
import com.koyo.screenwarden.presence.PresenceAdapterRegistry
import com.koyo.screenwarden.presence.FeishuNativePresenceAdapter
import com.koyo.screenwarden.presence.QqNativePresenceAdapter
import com.koyo.screenwarden.presence.TiyoPresenceService
import com.koyo.screenwarden.presence.WeComNativePresenceAdapter
import com.koyo.screenwarden.presence.WeixinNativePresenceAdapter

class TiyoApp : Application() {

    companion object {
        /** 全局 Application context，供无 Context 参数的对象（MailConfig 等）使用 */
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // 全局异常捕获，方便调试
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("tiyo", "Crash on thread ${thread.name}: ${throwable.message}", throwable)
            // 写出到文件方便查看
            val log = "tiyo crash:\n${throwable.stackTraceToString()}"
            openFileOutput("crash_log.txt", MODE_PRIVATE).use {
                it.write(log.toByteArray())
            }
            // 仍然让进程退出
            thread.uncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }

        ThemeManager.init(this)
        PresenceShortcutPublisher.publish(this)
        PresenceAdapterRegistry.register(FeishuNativePresenceAdapter())
        PresenceAdapterRegistry.register(WeComNativePresenceAdapter())
        PresenceAdapterRegistry.register(QqNativePresenceAdapter())
        PresenceAdapterRegistry.register(WeixinNativePresenceAdapter())
        PresenceAdapterRegistry.startAll(this)
        TiyoPresenceService.refresh(this)
        ScreenEventReceiver.register(this)
        RealtimeStateWorker.schedule(this)
        EnuManRhythmWorker.schedule(this)
    }
}
