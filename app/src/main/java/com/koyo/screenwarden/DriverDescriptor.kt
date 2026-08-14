package com.koyo.screenwarden

import org.json.JSONObject

/**
 * 驱动描述：把"动作翻译成设备听得懂的指令、数据翻译成人话"的心智模型。
 *
 * 一个外设 = 一个连接实例 + 一个驱动描述，两者解耦可任意组合。
 * 动作执行引擎只读本描述发指令，不写死任何设备。
 */
data class DriverDescriptor(
    val id: String,
    val name: String,
    val kind: String,                 // preset | standard | custom
    val connection: Connection,
    val actions: Map<String, Action>,
    val telemetry: JSONObject?,       // 遥测轮询/解析描述，本期保留空
    val capabilities: List<String>
) {

    /** 该驱动期望的连接方式：lan | ble */
    data class Connection(
        val type: String,
        val lan: LanConnection? = null,
        val ble: BleConnection? = null
    )

    data class LanConnection(
        val discovery: Discovery?,
        val probe: Probe?
    )

    data class Discovery(
        val udpQuery: String,
        val udpPort: Int,
        val responsePrefix: String
    )

    data class Probe(
        val method: String,
        val path: String
    )

    data class BleConnection(
        val serviceUuid: String? = null,
        val writeChar: String? = null,
        val notifyChar: String? = null
    )

    /** 一个动作 = 一条指令模板 */
    data class Action(
        val method: String,
        val path: String,
        /** 需要用户输入/选择器时的参数名，如 setSpeed → value；空则无输入 */
        val param: String? = null,
        /** param 值拼进指令的方式：query → path?param=value；path_suffix → path+value */
        val paramPlacement: String = "query",
        /** body 类型：text | json；空则无 body */
        val bodyKind: String? = null
    )

    companion object {
        fun fromJson(json: JSONObject): DriverDescriptor {
            val connectionObj = json.getJSONObject("connection")
            val lan = connectionObj.optJSONObject("lan")?.let { lanJson ->
                LanConnection(
                    discovery = lanJson.optJSONObject("discovery")?.let {
                        Discovery(
                            it.getString("udpQuery"),
                            it.getInt("udpPort"),
                            it.getString("responsePrefix")
                        )
                    },
                    probe = lanJson.optJSONObject("probe")?.let {
                        Probe(it.optString("method", "GET"), it.getString("path"))
                    }
                )
            }
            val ble = connectionObj.optJSONObject("ble")?.let {
                BleConnection(
                    it.optString("serviceUuid").ifBlank { null },
                    it.optString("writeChar").ifBlank { null },
                    it.optString("notifyChar").ifBlank { null }
                )
            }
            val actionsJson = json.getJSONObject("actions")
            val actions = actionsJson.keys().asSequence().associateWith { key ->
                val a = actionsJson.getJSONObject(key)
                Action(
                    method = a.optString("method", "GET"),
                    path = a.getString("path"),
                    param = a.optString("param").ifBlank { null },
                    paramPlacement = a.optString("paramPlacement", "query"),
                    bodyKind = a.optString("bodyKind").ifBlank { null }
                )
            }
            val capabilities = json.optJSONArray("capabilities")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()
            return DriverDescriptor(
                id = json.getString("id"),
                name = json.optString("name", json.getString("id")),
                kind = json.optString("kind", "custom"),
                connection = Connection(
                    type = connectionObj.optString("type", "lan"),
                    lan = lan,
                    ble = ble
                ),
                actions = actions,
                telemetry = json.optJSONObject("telemetry"),
                capabilities = capabilities
            )
        }
    }
}
