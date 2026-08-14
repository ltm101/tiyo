package com.koyo.screenwarden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/**
 * "我的 → 表情包管理"。
 * 用户可导入自己的表情包（命名 + 打标签），可给任何表情包补充标签，可删除自己的表情包。
 * 用户库存 filesDir/stickers，优先于内置 assets 同名图，StickerStore 统一读取。
 */
class StickerManagerFragment : Fragment(R.layout.fragment_sticker_manager) {

    private lateinit var userGrid: GridLayout
    private lateinit var builtinGrid: GridLayout
    private lateinit var userEmpty: TextView

    /** 导入选图 */
    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        val bmp = decodeImage(ctx, uri)
        if (bmp == null) {
            toast("读不到这张图")
            return@registerForActivityResult
        }
        showImportDialog(bmp, guessName(uri))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userGrid = view.findViewById(R.id.sticker_user_grid)
        builtinGrid = view.findViewById(R.id.sticker_builtin_grid)
        userEmpty = view.findViewById(R.id.sticker_user_empty)

        view.findViewById<View>(R.id.sticker_back_btn).setOnClickListener {
            activity?.onBackPressed()
        }
        view.findViewById<View>(R.id.sticker_import_btn).setOnClickListener {
            pickLauncher.launch(arrayOf("image/*"))
        }

        render()
    }

    private fun render() {
        val ctx = context ?: return
        userGrid.removeAllViews()
        builtinGrid.removeAllViews()

        val users = StickerStore.all(ctx).filter { StickerStore.isUserSticker(ctx, it) }
        val builtins = StickerStore.all(ctx).filter { !StickerStore.isUserSticker(ctx, it) }

        userEmpty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE

        users.forEach { addCell(userGrid, it) }
        builtins.forEach { addCell(builtinGrid, it) }
    }

    private fun addCell(grid: GridLayout, name: String) {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val cell = (density * 78).toInt()
        val img = (density * 56).toInt()

        val frame = FrameLayout(ctx)
        val lp = GridLayout.LayoutParams().apply {
            width = 0
            height = cell
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        }
        frame.layoutParams = lp

        val image = ImageView(ctx)
        val ilp = FrameLayout.LayoutParams(img, img)
        ilp.gravity = android.view.Gravity.CENTER
        image.layoutParams = ilp
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        StickerStore.loadBitmap(ctx, name)?.let { image.setImageBitmap(it) }
        frame.addView(image)

        // 用户表情包左上角加个小圆点标记
        if (StickerStore.isUserSticker(ctx, name)) {
            val dot = View(ctx)
            val dlp = FrameLayout.LayoutParams(
                (density * 8).toInt(), (density * 8).toInt()
            )
            dlp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            dlp.setMargins((density * 6).toInt(), (density * 6).toInt(), 0, 0)
            dot.layoutParams = dlp
            dot.background = ctx.getDrawable(R.drawable.d_ring_accent)
            frame.addView(dot)
        }

        frame.setOnClickListener { showEditDialog(name) }
        grid.addView(frame)
    }

    /** 导入弹窗：输入名字 + 标签（逗号分隔） */
    private fun showImportDialog(bmp: Bitmap, defaultName: String) {
        val ctx = context ?: return
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            (resources.displayMetrics.density * 20).toInt(),
            0,
            (resources.displayMetrics.density * 20).toInt(),
            0
        )

        val nameInput = EditText(ctx)
        nameInput.hint = "名字（${CompanionProfileStore.activeName(requireContext())}靠它认这张图，如：晚安）"
        nameInput.setText(defaultName)
        root.addView(nameInput)

        val tagInput = EditText(ctx)
        tagInput.hint = "标签，逗号分隔，如：晚安,睡觉,温柔"
        val prevTags = StickerStore.tags(ctx, defaultName)
        if (prevTags.isNotEmpty()) tagInput.setText(prevTags.joinToString(","))
        val tagLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tagLp.topMargin = (resources.displayMetrics.density * 8).toInt()
        root.addView(tagInput, tagLp)

        AlertDialog.Builder(ctx)
            .setTitle("导入表情包")
            .setView(root)
            .setPositiveButton("导入") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank() || name.contains("/") || name.contains("\\")) {
                    toast("名字不能为空，也不能带斜杠")
                    return@setPositiveButton
                }
                val tags = tagInput.text.toString().split(",", "，", " ")
                    .map { it.trim() }.filter { it.isNotBlank() }
                if (StickerStore.importSticker(ctx, bmp, name, tags)) {
                    toast("已导入「$name」")
                    render()
                } else {
                    toast("导入失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 编辑弹窗：改名 + 改标签 + 删除（仅用户表情包） */
    private fun showEditDialog(name: String) {
        val ctx = context ?: return
        val isUser = StickerStore.isUserSticker(ctx, name)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            (resources.displayMetrics.density * 20).toInt(),
            0,
            (resources.displayMetrics.density * 20).toInt(),
            0
        )

        val tagInput = EditText(ctx)
        tagInput.hint = "标签，逗号分隔，如：晚安,睡觉,温柔"
        tagInput.setText(StickerStore.tags(ctx, name).joinToString(","))
        root.addView(tagInput)

        val builder = AlertDialog.Builder(ctx)
            .setTitle("表情包「$name」")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                val tags = tagInput.text.toString().split(",", "，", " ")
                    .map { it.trim() }.filter { it.isNotBlank() }
                StickerStore.saveTags(ctx, name, tags)
                toast("标签已保存")
                render()
            }
            .setNegativeButton("取消", null)
        if (isUser) {
            builder.setNeutralButton("删除", null)
        }
        val dialog = builder.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            StickerStore.deleteSticker(ctx, name)
            toast("已删除「$name」")
            dialog.dismiss()
            render()
        }
    }

    private fun decodeImage(ctx: android.content.Context, uri: Uri): Bitmap? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                // 先读尺寸，避免大图 OOM
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                val target = 512
                var sample = 1
                while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.contentResolver.openInputStream(uri)?.use { input2 ->
                    BitmapFactory.decodeStream(input2, null, opts)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessName(uri: Uri): String {
        val seg = uri.lastPathSegment ?: return ""
        return seg.substringAfterLast('/').substringBefore('.').take(12)
    }

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
