package com.koyo.screenwarden.presence

import android.content.Context
import com.koyo.screenwarden.TiyoSecureStore

data class MobileChannelCredentials(
    val primaryId: String,
    val secret: String,
    val allowFrom: Set<String> = emptySet(),
    val secondaryId: String = ""
) {
    val configured: Boolean get() = primaryId.isNotBlank() && secret.isNotBlank()
    fun allows(userId: String): Boolean = allowFrom.isEmpty() || userId in allowFrom
}

object MobilePresenceConfig {
    private const val PREFS = "mobile_presence_v1"
    private const val KEY_ENABLED = "enabled_channels"

    fun enabledChannels(context: Context): Set<PresenceChannel> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED, emptySet()).orEmpty()
            .mapNotNull { runCatching { PresenceChannel.valueOf(it) }.getOrNull() }
            .filter { it in supportedChannels }
            .toSet()

    fun setEnabled(context: Context, channel: PresenceChannel, enabled: Boolean) {
        val next = enabledChannels(context).toMutableSet().apply {
            if (enabled) add(channel) else remove(channel)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ENABLED, next.map(PresenceChannel::name).toSet())
            .apply()
    }

    fun credentials(context: Context, channel: PresenceChannel): MobileChannelCredentials {
        val prefix = channel.name.lowercase()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MobileChannelCredentials(
            primaryId = prefs.getString("${prefix}_primary_id", "").orEmpty(),
            secret = TiyoSecureStore.get(context, "presence_${prefix}_secret"),
            allowFrom = prefs.getStringSet("${prefix}_allow_from", emptySet()).orEmpty()
                .map(String::trim).filter(String::isNotBlank).toSet(),
            secondaryId = prefs.getString("${prefix}_secondary_id", "").orEmpty()
        )
    }

    fun saveCredentials(
        context: Context,
        channel: PresenceChannel,
        primaryId: String,
        secret: String,
        allowFrom: Set<String>,
        secondaryId: String = ""
    ) {
        require(channel in supportedChannels)
        val prefix = channel.name.lowercase()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${prefix}_primary_id", primaryId.trim().take(240))
            .putString("${prefix}_secondary_id", secondaryId.trim().take(240))
            .putStringSet("${prefix}_allow_from", allowFrom.map(String::trim).filter(String::isNotBlank).toSet())
            .apply()
        if (secret.isNotBlank()) TiyoSecureStore.put(context, "presence_${prefix}_secret", secret.trim())
    }

    fun isReady(context: Context, channel: PresenceChannel): Boolean =
        channel in enabledChannels(context) && credentials(context, channel).configured

    /** Empty allowlist is trust-on-first-use, then becomes fail-closed for later senders. */
    fun allowsOrBindFirst(context: Context, channel: PresenceChannel, userId: String): Boolean = synchronized(this) {
        val safeUser = userId.trim().take(240).takeIf(String::isNotBlank) ?: return false
        val current = credentials(context, channel)
        if (current.allowFrom.isNotEmpty()) return safeUser in current.allowFrom
        val prefix = channel.name.lowercase()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("${prefix}_allow_from", setOf(safeUser))
            .apply()
        true
    }

    val supportedChannels = setOf(
        PresenceChannel.FEISHU,
        PresenceChannel.WECOM,
        PresenceChannel.QQ,
        PresenceChannel.WECHAT
    )
}

internal fun stablePresenceId(prefix: String, raw: String): String {
    val safe = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    return "${prefix}_${safe}".take(80)
}
