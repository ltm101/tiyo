package com.koyo.screenwarden

/**
 * 预置驱动注册表。内置 car_koyo（可又小车）。
 *
 * custom 驱动由用户在控制页录入 driver 描述（JSON），本期由调用方
 * （PeripheralRepository）持久化后也按 id 查，注册表只提供统一查询入口。
 */
object DriverRegistry {

    private val presets = linkedMapOf<String, DriverDescriptor>()

    /** 按 id 查驱动 */
    fun get(id: String): DriverDescriptor? = presets[id]

    fun all(): List<DriverDescriptor> = presets.values.toList()

    init {
        presets["car_koyo"] = DriverDescriptor(
            id = "car_koyo",
            name = "tiyo 小车",
            kind = "preset",
            connection = DriverDescriptor.Connection(
                type = "lan",
                lan = DriverDescriptor.LanConnection(
                    discovery = DriverDescriptor.Discovery(
                        udpQuery = "KOYO_DISCOVER",
                        udpPort = 4210,
                        responsePrefix = "KOYO_CAR:http://"
                    ),
                    probe = DriverDescriptor.Probe(method = "GET", path = "/api/status")
                )
            ),
            actions = linkedMapOf(
                "forward" to DriverDescriptor.Action("GET", "/api/fwd"),
                "back" to DriverDescriptor.Action("GET", "/api/back"),
                "left" to DriverDescriptor.Action("GET", "/api/left"),
                "right" to DriverDescriptor.Action("GET", "/api/right"),
                "stop" to DriverDescriptor.Action("GET", "/api/stop"),
                "setSpeed" to DriverDescriptor.Action("GET", "/api/speed", param = "value"),
                "track" to DriverDescriptor.Action("GET", "/api/track", param = "color"),
                "emoji" to DriverDescriptor.Action(
                    "GET", "/api/emoji:", param = "emotion", paramPlacement = "path_suffix"
                ),
                "audioTest" to DriverDescriptor.Action("GET", "/api/audio/test"),
                "audioStop" to DriverDescriptor.Action("GET", "/api/audio/stop"),
                "oledIdle" to DriverDescriptor.Action("GET", "/api/oled/idle"),
                "oledListen" to DriverDescriptor.Action("GET", "/api/oled/listen"),
                "chat" to DriverDescriptor.Action("POST", "/api/chat", bodyKind = "text")
            ),
            telemetry = null,
            capabilities = listOf("direction", "speed", "camera", "oled", "audio", "voice")
        )
    }
}
