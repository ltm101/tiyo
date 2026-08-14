package com.koyo.screenwarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes

/**
 * 聊天头像存储：角色头像 + 用户头像（相册图，persistable uri）。
 * 只改视觉绑定，不碰聊天逻辑。
 */
object AvatarStore {

    private const val PREFS = "tiyo_avatars"
    private const val KEY_COMPANION = "companion_key"
    private const val KEY_COMPANION_URI = "companion_uri"
    private const val KEY_USER_URI = "user_uri"

    /** Built-in guide avatar. Generated roles provide their own portrait. */
    val companionOptions: List<Pair<String, Int>> = listOf(
        "tiyo" to R.mipmap.ic_launcher
    )

    private fun prefs(context: Context, scope: CompanionScope = CompanionScope.capture(context)) =
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)

    fun companionKey(context: Context, scope: CompanionScope = CompanionScope.capture(context)): String =
        prefs(context, scope).getString(KEY_COMPANION, "tiyo") ?: "tiyo"

    @DrawableRes
    fun companionRes(context: Context, scope: CompanionScope = CompanionScope.capture(context)): Int {
        val key = companionKey(context, scope)
        return companionOptions.firstOrNull { it.first == key }?.second ?: R.mipmap.ic_launcher
    }

    fun setCompanionKey(
        context: Context,
        key: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ) {
        // 选内置帧时清掉相册自定义
        prefs(context, scope).edit().putString(KEY_COMPANION, key).remove(KEY_COMPANION_URI).apply()
    }

    /** User-selected companion portrait, preferred over the built-in guide icon. */
    fun companionCustomUri(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): String? = prefs(context, scope).getString(KEY_COMPANION_URI, null)

    fun setCompanionCustomUri(
        context: Context,
        uri: String?,
        scope: CompanionScope = CompanionScope.capture(context)
    ) {
        prefs(context, scope).edit().putString(KEY_COMPANION_URI, uri).apply()
    }

    fun loadCompanionBitmap(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Bitmap? {
        // A user-selected portrait always wins, including for a generated role.
        val raw = companionCustomUri(context, scope)
        if (raw != null) decode(context, raw)?.let { return it }
        CompanionAssetPack.file(context, scope.companionId, CompanionAssetRole.CHAT_PORTRAIT)
            ?.takeUnless { scope.isBuiltInCompanion }
            ?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        return null
    }

    fun userUri(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USER_URI, null)

    fun setUserUri(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_USER_URI, uri).apply()
    }

    /** 解码用户头像，降采样到 ~192px，失败返回 null */
    fun loadUserBitmap(context: Context): Bitmap? {
        val raw = userUri(context) ?: return null
        return decode(context, raw)
    }

    private fun decode(context: Context, raw: String): Bitmap? {
        return try {
            val uri = Uri.parse(raw)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 192) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })
            }
        } catch (_: Exception) { null }
    }
}
