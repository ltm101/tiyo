package com.koyo.screenwarden

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import androidx.core.content.ContextCompat

object ThemeManager {

    data class RoomPalette(
        val wallTop: Int,
        val wallBottom: Int,
        val window: Int,
        val frame: Int,
        val lightBeam: Int,
        val furniture: Int,
        val shelfLine: Int,
        val floor: Int
    )

    private const val PREFS_NAME = "screen_warden_theme"
    private const val KEY_THEME = "selected_theme"
    private const val KEY_CUSTOM_URI = "custom_bg_uri"
    const val THEME_CUSTOM = "custom"

    /** 非聊天页统一的默认底色（暖橘猫主题的底色） */
    val DEFAULT_BG_COLOR: Int = Theme.WARM_CAT.bgColor.toInt()

    enum class Theme(val id: String, val label: String, val bgColor: Long, val accentColor: Long) {
        WARM_CAT("warm_cat", "暖橘猫", 0xFFFFF8F0, 0xFFE8976B),
        FOREST("forest", "森林绿", 0xFFF0F7F4, 0xFF6B9E7A),
        OCEAN("ocean", "深海蓝", 0xFFF0F4F8, 0xFF6B8BAB),
        LAVENDER("lavender", "薰衣草", 0xFFF5F0F8, 0xFF9B7AB5),
        MONO("mono", "极简灰", 0xFFF5F5F5, 0xFF666666),
        SAKURA("sakura", "樱花粉", 0xFFFFF5F5, 0xFFE8978B),
        CUSTOM(THEME_CUSTOM, "自定义图片", 0xFFFFF8F0, 0xFFE8976B)
    }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentTheme(): Theme {
        val id = prefs?.getString(KEY_THEME, Theme.WARM_CAT.id) ?: Theme.WARM_CAT.id
        return Theme.values().find { it.id == id } ?: Theme.WARM_CAT
    }

    fun isCustom(): Boolean = getCurrentTheme().id == THEME_CUSTOM

    fun setTheme(theme: Theme) {
        prefs?.edit()?.putString(KEY_THEME, theme.id)?.apply()
    }

    fun getCustomUri(): Uri? {
        val str = prefs?.getString(KEY_CUSTOM_URI, null) ?: return null
        return try { Uri.parse(str) } catch (e: Exception) { null }
    }

    fun setCustomUri(uri: Uri) {
        prefs?.edit()
            ?.putString(KEY_THEME, THEME_CUSTOM)
            ?.putString(KEY_CUSTOM_URI, uri.toString())
            ?.apply()
    }

    fun getThemeBgColor(): Int {
        val theme = getCurrentTheme()
        return if (theme == Theme.CUSTOM) Theme.WARM_CAT.bgColor.toInt()
        else theme.bgColor.toInt()
    }

    fun getThemeAccentColor(): Int {
        val theme = getCurrentTheme()
        return if (theme == Theme.CUSTOM) Theme.WARM_CAT.accentColor.toInt()
        else theme.accentColor.toInt()
    }

    fun getAllThemes(): List<Theme> = Theme.values().toList()

    /** 六个内置主题对应六种房间光线；自定义图只替换窗外底景，房间结构仍由原生层绘制 */
    fun roomPalette(): RoomPalette = when (getCurrentTheme()) {
        Theme.FOREST -> RoomPalette(0xFFF0E8D7.toInt(), 0xFFD5D9BE.toInt(), 0xFFD7E8C9.toInt(), 0xFFA98667.toInt(), 0x32E8F3C6, 0xFF8B735E.toInt(), 0xFF5C6048.toInt(), 0xFF927050.toInt())
        Theme.OCEAN -> RoomPalette(0xFFECE8E1.toInt(), 0xFFC8D6DF.toInt(), 0xFFD7EDF5.toInt(), 0xFF8A7D76.toInt(), 0x2ED9F2FF, 0xFF687887.toInt(), 0xFF455967.toInt(), 0xFF7D6E67.toInt())
        Theme.LAVENDER -> RoomPalette(0xFFF0E7EA.toInt(), 0xFFD8CBDD.toInt(), 0xFFE9DDF5.toInt(), 0xFF987F91.toInt(), 0x30F1DFFF, 0xFF897281.toInt(), 0xFF5F5062.toInt(), 0xFF88706D.toInt())
        Theme.MONO -> RoomPalette(0xFFF0ECE6.toInt(), 0xFFD8D4CE.toInt(), 0xFFF2F1ED.toInt(), 0xFF8D8984.toInt(), 0x2AFFFFFF, 0xFF807A73.toInt(), 0xFF5D5954.toInt(), 0xFF79736E.toInt())
        Theme.SAKURA -> RoomPalette(0xFFFFEEE8.toInt(), 0xFFE7CFCB.toInt(), 0xFFFFE7E6.toInt(), 0xFFAE8179.toInt(), 0x35FFE9D5, 0xFF9A756F.toInt(), 0xFF72504D.toInt(), 0xFF9B776C.toInt())
        Theme.CUSTOM, Theme.WARM_CAT -> RoomPalette(0xFFF6E9DC.toInt(), 0xFFDCC4B3.toInt(), 0xFFFFF1D5.toInt(), 0xFFA68167.toInt(), 0x3AFFF0C9, 0xFF94735E.toInt(), 0xFF6C5141.toInt(), 0xFF9A765A.toInt())
    }

    fun buildRoomBackground(context: Context): Drawable {
        if (isCustom()) {
            roomWindowBitmap(context)?.let { return BitmapDrawable(context.resources, it) }
        }
        val palette = roomPalette()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.wallTop, palette.wallBottom)
        )
    }

    fun roomWindowBitmap(context: Context): Bitmap? {
        if (!isCustom()) return null
        val uri = getCustomUri() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)?.let { decodeCropToScreen(context, it) }
            }
        }.getOrNull()
    }

    /**
     * 生成聊天页背景 drawable：底层为主题纯色（或自定义图片裁剪成屏幕大小），
     * 顶层叠加 tiyo_chat_background 的半透明暖橘椭圆装饰。
     */
    fun buildChatBackground(context: Context): Drawable {
        val ovals: Drawable = ContextCompat.getDrawable(context, R.drawable.tiyo_chat_background)
            ?: ColorDrawable(Color.TRANSPARENT)
        val base: Drawable = if (isCustom()) {
            getCustomUri()?.let { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        BitmapFactory.decodeStream(s)?.let { bmp ->
                            decodeCropToScreen(context, bmp)?.let {
                                CenterCropBitmapDrawable(context.resources, it)
                            }
                        }
                    }
                }.getOrNull()
            } ?: GradientDrawable().apply { setColor(getThemeBgColor()) }
        } else {
            GradientDrawable().apply { setColor(getThemeBgColor()) }
        }
        return LayerDrawable(arrayOf(base, ovals))
    }

    /** 按屏幕尺寸等比缩放居中裁剪，与旧 applyTheme 行为一致 */
    private fun decodeCropToScreen(context: Context, bmp: Bitmap): Bitmap? {
        val dm = context.resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val bw = bmp.width
        val bh = bmp.height
        if (bw <= 0 || bh <= 0) return null
        val scale = maxOf(sw.toFloat() / bw, sh.toFloat() / bh)
        val targetW = (bw * scale).toInt()
        val targetH = (bh * scale).toInt()
        val cropX = (targetW - sw) / 2
        val cropY = (targetH - sh) / 2
        val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        val cropped = Bitmap.createBitmap(scaled, cropX, cropY, sw, sh)
        if (scaled != cropped) scaled.recycle()
        return cropped
    }

    /**
     * 背景位图按当前 view 尺寸居中裁剪绘制，不做拉伸。
     * 解决键盘弹出导致聊天区域变矮时背景被压缩变形的问题。
     */
    class CenterCropBitmapDrawable(res: Resources, src: Bitmap) : BitmapDrawable(res, src) {
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
            val scale = maxOf(
                b.width() / bitmap.width.toFloat(),
                b.height() / bitmap.height.toFloat()
            )
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = b.left + (b.width() - w) / 2f
            val top = b.top + (b.height() - h) / 2f
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
        }
    }
}
