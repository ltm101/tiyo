package com.koyo.screenwarden.presence

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class WeixinOnboardingResult(
    val botToken: String,
    val botId: String,
    val userId: String,
    val baseUrl: String
)

object WeixinMobileOnboarding {
    class Handle internal constructor(private val cancelled: AtomicBoolean) {
        fun cancel() = cancelled.set(true)
    }
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tiyo-weixin-onboarding").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun start(
        context: Context,
        onQrReady: (String) -> Unit,
        onStatus: (String) -> Unit,
        onSuccess: (WeixinOnboardingResult) -> Unit,
        onError: (String) -> Unit
    ): Handle {
        val cancelled = AtomicBoolean(false)
        executor.execute {
            runCatching {
                if (cancelled.get()) return@execute
                val initial = get("https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3")
                var qrKey = initial.optString("qrcode").takeIf(String::isNotBlank)
                    ?: error("微信没有返回二维码")
                var qrContent = initial.optString("qrcode_img_content").takeIf(String::isNotBlank)
                    ?: error("微信没有返回二维码内容")
                main.post { onQrReady(qrContent) }
                val deadline = System.currentTimeMillis() + 8 * 60_000L
                var refreshed = 0
                while (!cancelled.get() && System.currentTimeMillis() < deadline) {
                    val status = get(
                        "https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status?qrcode=" +
                            java.net.URLEncoder.encode(qrKey, "UTF-8")
                    )
                    when (status.optString("status")) {
                        "confirmed" -> {
                            val result = WeixinOnboardingResult(
                                botToken = status.optString("bot_token"),
                                botId = status.optString("ilink_bot_id").ifBlank { "default" },
                                userId = status.optString("ilink_user_id"),
                                baseUrl = status.optString("baseurl")
                            )
                            require(result.botToken.isNotBlank()) { "微信没有返回连接令牌" }
                            main.post { onSuccess(result) }
                            return@execute
                        }
                        "scaned" -> main.post { onStatus("已扫码，请在微信里确认") }
                        "expired" -> {
                            if (++refreshed > 3) error("二维码多次过期，请重新开始")
                            val next = get("https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3")
                            qrKey = next.optString("qrcode")
                            qrContent = next.optString("qrcode_img_content")
                            require(qrKey.isNotBlank() && qrContent.isNotBlank()) { "二维码刷新失败" }
                            main.post { onQrReady(qrContent) }
                        }
                        else -> Unit
                    }
                    Thread.sleep(1_000L)
                }
                if (!cancelled.get()) error("等待微信确认超时")
            }.onFailure { error ->
                if (!cancelled.get()) {
                    main.post { onError(error.message?.takeIf(String::isNotBlank) ?: "微信连接失败") }
                }
            }
        }
        return Handle(cancelled)
    }

    private fun get(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("iLink-App-ClientVersion", "1")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("微信接口 ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }
}
