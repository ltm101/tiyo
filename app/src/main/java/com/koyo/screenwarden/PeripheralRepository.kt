package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已配对外设持久化。SharedPreferences JSON 存储，
 * 首启预置一台小车（car_koyo）。
 */
object PeripheralRepository {

    private const val PREFS = "peripherals"
    private const val KEY_LIST = "peripherals"
    private const val KEY_SEEDED = "seeded"

    /** 一个外设 = 一个连接实例 + 一个驱动描述（driverId 可空 = 已连接但无驱动） */
    data class Peripheral(
        val id: String,
        val name: String,
        val connectorType: String,      // LAN | BLE
        val driverId: String?,           // null = 已连接但无驱动
        val status: String,              // DISCONNECTED | CONNECTED | ERROR
        val config: JSONObject           // 地址 / 服务UUID / 特征UUID 等连接参数
    )

    fun loadAll(context: Context): List<Peripheral> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val loaded = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        val migrated = loaded.map { peripheral ->
            if (peripheral.id == "car_koyo" && peripheral.name == "Tiyo小车") {
                peripheral.copy(name = "tiyo 小车")
            } else {
                peripheral
            }
        }
        if (migrated != loaded) saveAll(context, migrated)
        return migrated
    }

    fun saveAll(context: Context, list: List<Peripheral>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun update(context: Context, target: Peripheral) {
        val list = loadAll(context).toMutableList()
        val index = list.indexOfFirst { it.id == target.id }
        if (index >= 0) list[index] = target else list += target
        saveAll(context, list)
    }

    fun remove(context: Context, id: String) {
        saveAll(context, loadAll(context).filterNot { it.id == id })
    }

    /** 首启预置：一台Tiyo小车 */
    fun seedIfEmpty(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        if (loadAll(context).isEmpty()) {
            saveAll(context, listOf(
                Peripheral(
                    id = "car_koyo",
                    name = "tiyo 小车",
                    connectorType = "LAN",
                    driverId = "car_koyo",
                    status = "DISCONNECTED",
                    config = JSONObject().put("baseUrl", "http://koyo-car.local")
                )
            ))
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun toJson(p: Peripheral): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("connectorType", p.connectorType)
        .put("driverId", p.driverId ?: JSONObject.NULL)
        .put("status", p.status)
        .put("config", p.config)

    private fun fromJson(j: JSONObject): Peripheral = Peripheral(
        id = j.getString("id"),
        name = j.optString("name", j.getString("id")),
        connectorType = j.optString("connectorType", "LAN"),
        driverId = j.optString("driverId").ifBlank { null },
        status = j.optString("status", "DISCONNECTED"),
        config = j.optJSONObject("config") ?: JSONObject()
    )
}
