package com.koyo.screenwarden.presence

import android.content.Context

/**
 * 运行中的 Presence Adapter 注册表。
 *
 * 元信息（能力/传输/可用性）仍在 [PresenceChannelRegistry]；
 * 这里只保存真正可启动的 Adapter 实例，并按 channel 唯一注册。
 */
object PresenceAdapterRegistry {
    private val lock = Any()
    private val adapters = mutableMapOf<PresenceChannel, PresenceAdapter>()

    /** 注册一个 Adapter。同一 channel 只允许一个实例。 */
    fun register(adapter: PresenceAdapter): Boolean = synchronized(lock) {
        if (adapters.containsKey(adapter.channel)) {
            false
        } else {
            adapters[adapter.channel] = adapter
            true
        }
    }

    fun unregister(channel: PresenceChannel): Boolean = synchronized(lock) {
        adapters.remove(channel) != null
    }

    fun get(channel: PresenceChannel): PresenceAdapter? = synchronized(lock) {
        adapters[channel]
    }

    fun all(): List<PresenceAdapter> = synchronized(lock) {
        adapters.values.toList()
    }

    fun isRegistered(channel: PresenceChannel): Boolean = synchronized(lock) {
        adapters.containsKey(channel)
    }

    fun startAll(context: Context) {
        all().forEach { adapter ->
            runCatching { adapter.start(context.applicationContext) }
        }
    }

    fun stopAll(context: Context) {
        all().forEach { adapter ->
            runCatching { adapter.stop(context.applicationContext) }
        }
    }

    fun healthAll(): Map<PresenceChannel, AdapterHealth> = synchronized(lock) {
        adapters.mapValues { (_, adapter) ->
            runCatching { adapter.health() }.getOrDefault(
                AdapterHealth(healthy = false, detail = "health check failed")
            )
        }
    }
}
