package com.koyo.screenwarden

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * 聊天头像设置（聊天页右上角 ≡ 菜单进入）。
 * 角色头像和用户头像都可以从相册选择；自定义角色优先使用生成头像。
 * 都用 persistable uri，重启不丢。
 */
class AvatarSettingsFragment : Fragment(R.layout.fragment_avatar_settings) {

    private lateinit var koyoGrid: GridLayout
    private lateinit var koyoCustomPreview: ImageView
    private lateinit var userPreview: ImageView

    private var pickTarget = "user"

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
            if (pickTarget == "companion") {
            AvatarStore.setCompanionCustomUri(ctx, uri.toString())
            renderKoyoGrid()
        } else {
            AvatarStore.setUserUri(ctx, uri.toString())
            renderUser()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        koyoGrid = view.findViewById(R.id.avatar_koyo_grid)
        koyoCustomPreview = view.findViewById(R.id.avatar_koyo_custom_preview)
        userPreview = view.findViewById(R.id.avatar_user_preview)

        view.findViewById<View>(R.id.avatar_back_btn).setOnClickListener {
            activity?.onBackPressed()
        }

        view.findViewById<View>(R.id.avatar_koyo_pick_btn).setOnClickListener {
            pickTarget = "companion"
            pickLauncher.launch(arrayOf("image/*"))
        }
        view.findViewById<View>(R.id.avatar_koyo_reset_btn).setOnClickListener {
            AvatarStore.setCompanionCustomUri(requireContext(), null)
            AvatarStore.setCompanionKey(requireContext(), "tiyo")
            renderKoyoGrid()
        }

        view.findViewById<View>(R.id.avatar_pick_btn).setOnClickListener {
            pickTarget = "user"
            pickLauncher.launch(arrayOf("image/*"))
        }
        view.findViewById<View>(R.id.avatar_reset_btn).setOnClickListener {
            AvatarStore.setUserUri(requireContext(), null)
            renderUser()
        }

        renderKoyoGrid()
        renderUser()
    }

    private fun renderKoyoGrid() {
        val ctx = context ?: return
        // 自定义预览：有相册图显示相册图，没有显示当前内置帧
        val custom = AvatarStore.loadCompanionBitmap(ctx)
        if (custom != null) {
            koyoCustomPreview.setImageBitmap(custom)
        } else {
            koyoCustomPreview.setImageResource(AvatarStore.companionRes(ctx))
        }

        koyoGrid.removeAllViews()
        val scope = CompanionScope.capture(ctx)
        if (!scope.isBuiltInCompanion) {
            // Generated companions own their portrait and may override it from
                // the gallery, but never borrow the guide role's bundled icon.
            koyoGrid.visibility = View.GONE
            return
        }
        koyoGrid.visibility = View.VISIBLE
        val selectedKey = if (AvatarStore.companionCustomUri(ctx) != null) null else AvatarStore.companionKey(ctx)
        val cell = (resources.displayMetrics.density * 84).toInt()
        val img = (resources.displayMetrics.density * 64).toInt()

        AvatarStore.companionOptions.forEach { (key, res) ->
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
            image.setImageResource(res)
            if (key == selectedKey) {
                image.setBackgroundResource(R.drawable.d_ring_accent)
                image.setPadding(4, 4, 4, 4)
            }
            frame.addView(image)

            frame.setOnClickListener {
                AvatarStore.setCompanionKey(ctx, key)
                renderKoyoGrid()
            }
            koyoGrid.addView(frame)
        }
    }

    private fun renderUser() {
        val ctx = context ?: return
        val bmp = AvatarStore.loadUserBitmap(ctx)
        if (bmp != null) {
            userPreview.setImageBitmap(bmp)
        } else {
            userPreview.setImageResource(R.drawable.d_ic_me)
        }
    }
}
