package com.koyo.screenwarden

import android.app.Application
import android.content.Context
import android.util.Log
import com.koyo.screenwarden.events.ScreenEventReceiver

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
        ScreenEventReceiver.register(this)
        RealtimeStateWorker.schedule(this)
    }
}
