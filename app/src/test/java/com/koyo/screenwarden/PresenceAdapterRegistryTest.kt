package com.koyo.screenwarden

import android.content.Context
import com.koyo.screenwarden.presence.AdapterHealth
import com.koyo.screenwarden.presence.AdapterOutboundMessage
import com.koyo.screenwarden.presence.PresenceAdapter
import com.koyo.screenwarden.presence.PresenceAdapterLevel
import com.koyo.screenwarden.presence.PresenceAdapterRegistry
import com.koyo.screenwarden.presence.PresenceAvailability
import com.koyo.screenwarden.presence.PresenceCapability
import com.koyo.screenwarden.presence.PresenceChannel
import com.koyo.screenwarden.presence.PresenceChannelRegistry
import com.koyo.screenwarden.presence.PresenceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceAdapterRegistryTest {

    @Test
    fun registerAllowsOneAdapterPerChannel() {
        val adapter = FakeAdapter(PresenceChannel.DOUYIN)
        assertTrue(PresenceAdapterRegistry.register(adapter))
        assertFalse(PresenceAdapterRegistry.register(FakeAdapter(PresenceChannel.DOUYIN)))
        assertEquals(adapter, PresenceAdapterRegistry.get(PresenceChannel.DOUYIN))
        PresenceAdapterRegistry.unregister(PresenceChannel.DOUYIN)
        assertNull(PresenceAdapterRegistry.get(PresenceChannel.DOUYIN))
    }

    @Test
    fun healthAllReturnsAdapterHealthByChannel() {
        val adapter = FakeAdapter(PresenceChannel.WECHAT, healthy = false)
        PresenceAdapterRegistry.register(adapter)
        try {
            val health = PresenceAdapterRegistry.healthAll()[PresenceChannel.WECHAT]
            assertNotNull(health)
            assertFalse(health!!.healthy)
        } finally {
            PresenceAdapterRegistry.unregister(PresenceChannel.WECHAT)
        }
    }

    @Test
    fun channelRegistryExposesPlannedLevels() {
        assertEquals(
            PresenceAdapterLevel.NATIVE_ACCOUNT,
            PresenceChannelRegistry.forChannel(PresenceChannel.DOUYIN)?.level
        )
        assertEquals(
            PresenceAdapterLevel.NATIVE_ACCOUNT,
            PresenceChannelRegistry.forChannel(PresenceChannel.WECHAT)?.level
        )
        assertEquals(
            PresenceAdapterLevel.GENERIC_SHARE,
            PresenceChannelRegistry.forChannel(PresenceChannel.SYSTEM_SHARE)?.level
        )
        assertEquals(
            PresenceAdapterLevel.NATIVE_ACCOUNT,
            PresenceChannelRegistry.forChannel(PresenceChannel.TIYO)?.level
        )
    }

    @Test
    fun adapterInterfaceForbidsOwningBrainByContractShape() {
        val adapter = FakeAdapter(PresenceChannel.DOUYIN)
        assertEquals(PresenceChannel.DOUYIN, adapter.channel)
        assertEquals(PresenceAdapterLevel.NATIVE_ACCOUNT, adapter.level)
        assertTrue(PresenceCapability.NATIVE_IDENTITY in adapter.capabilities)
        assertEquals(PresenceAvailability.BRIDGE_READY, adapter.availability)
    }

    private class FakeAdapter(
        override val channel: PresenceChannel,
        private val healthy: Boolean = true
    ) : PresenceAdapter {
        override val level: PresenceAdapterLevel
            get() = PresenceChannelRegistry.forChannel(channel)?.level
                ?: PresenceAdapterLevel.GENERIC_SHARE
        override val capabilities: Set<PresenceCapability>
            get() = PresenceChannelRegistry.forChannel(channel)?.capabilities.orEmpty()
        override val availability: PresenceAvailability
            get() = PresenceChannelRegistry.forChannel(channel)?.availability
                ?: PresenceAvailability.PLANNED

        override fun start(context: Context) = Unit
        override fun stop(context: Context) = Unit
        override fun health(): AdapterHealth = AdapterHealth(healthy = healthy, detail = "test")

        override suspend fun send(reply: AdapterOutboundMessage): Boolean = true

        override fun onInboundMessage(context: Context, message: PresenceEvent) {
            // Intentionally not calling PresenceRouter in unit test: no Android context.
        }
    }
}
