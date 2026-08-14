package com.koyo.screenwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 wttr.in 免费 API 获取天气，无需注册、无需 Key。
 * 坐标通过 Config 配置，全自动英文→中文映射。
 */
object WeatherFetcher {

    // &m 强制公制单位（摄氏度）。不加的话 wttr.in 会按请求来源 IP 猜单位，可能返回华氏度。
    private fun url(): String? {
        if (!MailConfig.isWeatherReady()) return null
        return "https://wttr.in/${MailConfig.weatherLat()},${MailConfig.weatherLon()}?format=\"%C+%t\"&m"
    }

    private val zh = mapOf(
        "Sunny" to "晴",
        "Clear" to "晴",
        "Partly cloudy" to "多云",
        "Partly Cloudy" to "多云",
        "Cloudy" to "阴",
        "Overcast" to "阴",
        "Mist" to "薄雾",
        "Fog" to "雾",
        "Freezing fog" to "冻雾",
        "Light drizzle" to "毛毛雨",
        "Patchy light drizzle" to "局部毛毛雨",
        "Light rain" to "小雨",
        "Light Rain" to "小雨",
        "Moderate rain" to "中雨",
        "Moderate rain at times" to "间歇中雨",
        "Heavy rain" to "大雨",
        "Heavy rain at times" to "间歇大雨",
        "Torrential rain" to "暴雨",
        "Patchy light rain" to "局部小雨",
        "Patchy rain possible" to "可能有雨",
        "Patchy rain nearby" to "附近有雨",
        "Thunderstorm" to "雷阵雨",
        "Thundery outbreaks possible" to "可能有雷",
        "Light snow" to "小雪",
        "Moderate snow" to "中雪",
        "Heavy snow" to "大雪",
        "Blizzard" to "暴风雪",
        "Ice pellets" to "冰粒",
        "Light rain shower" to "小阵雨",
        "Moderate or heavy rain shower" to "大阵雨",
        "Light sleet" to "小雨夹雪",
        "Moderate or heavy sleet" to "大雨夹雪",
        "Light snow showers" to "小阵雪",
        "Moderate or heavy snow showers" to "大阵雪",
        "Blowing snow" to "吹雪",
        "Hail" to "冰雹"
    )

    suspend fun fetch(): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = url() ?: return@withContext ""
                val target = java.net.URL(url)
                val conn = target.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "tiyo/1.0")

                val raw = conn.inputStream.bufferedReader().readText().trim()
                conn.disconnect()

                // 格式: "Partly cloudy +28°C" → "多云 28°C"
                val cleaned = raw.replace("\"", "").trim()
                val parts = cleaned.split("+", limit = 2)
                if (parts.size == 2) {
                    val condition = parts[0].trim()
                    val temp = parts[1].trim()
                    val zhCondition = zh[condition] ?: condition
                    "$zhCondition $temp"
                } else {
                    cleaned.replace("+", " ")
                }
            } catch (_: Exception) {
                ""
            }
        }
    }
}
