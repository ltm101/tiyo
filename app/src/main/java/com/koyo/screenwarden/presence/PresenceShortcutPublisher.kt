package com.koyo.screenwarden.presence

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.koyo.screenwarden.CompanionProfileStore
import com.koyo.screenwarden.MainActivity
import com.koyo.screenwarden.R

/** 让当前陪伴者以“联系人”身份进入 Android Direct Share，而不只是一个 App 图标。 */
object PresenceShortcutPublisher {
    const val SHARE_CATEGORY = "com.koyo.screenwarden.category.COMPANION_SHARE"
    private const val SHORTCUT_ID = "active_companion"

    fun publish(context: Context) {
        val appContext = context.applicationContext
        val name = CompanionProfileStore.activeName(appContext).ifBlank { "可又" }
        val person = Person.Builder()
            .setKey("tiyo_active_companion")
            .setName(name)
            .setImportant(true)
            .build()
        val shortcut = ShortcutInfoCompat.Builder(appContext, SHORTCUT_ID)
            .setShortLabel(name)
            .setLongLabel("分享给$name")
            .setIcon(IconCompat.createWithResource(appContext, R.mipmap.ic_launcher))
            .setCategories(setOf(SHARE_CATEGORY))
            .setPerson(person)
            .setLongLived(true)
            .setIntent(
                Intent(appContext, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
            )
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(appContext, shortcut) }
    }
}
