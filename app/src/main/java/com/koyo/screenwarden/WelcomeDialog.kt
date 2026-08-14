package com.koyo.screenwarden

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast

/**
 * 首次启动弹窗：可又开心表情 + 两步收集。
 * 第一步选年龄段（决定人格应对与表情包频率），第二步输称呼。
 * 必填，点击外面不关闭，确定后回调 [onConfirm]。
 */
class WelcomeDialog(
    private val context: Context,
    private val onConfirm: (name: String, ageGroup: UserPrefs.AgeGroup) -> Unit
) {
    private var selectedAge: UserPrefs.AgeGroup? = null

    fun show() {
        val dialog = Dialog(context)
        val content = LayoutInflater.from(context)
            .inflate(R.layout.dialog_welcome, null, false)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.4f }
        }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        // 顶部放一张可又开心表情
        content.findViewById<ImageView>(R.id.welcome_avatar)?.let { avatar ->
            try {
                context.assets.open("frames/koyo_happy_1.png").use { input ->
                    BitmapFactory.decodeStream(input)?.let { avatar.setImageBitmap(it) }
                }
            } catch (_: Exception) {
                // 素材读不到就留空，不阻塞弹窗
            }
        }

        val stepAge = content.findViewById<View>(R.id.welcome_step_age)
        val stepName = content.findViewById<View>(R.id.welcome_step_name)
        val startBtn = content.findViewById<View>(R.id.welcome_start)
        val input = content.findViewById<EditText>(R.id.welcome_name_input)

        // 第一步：点年龄段 → 存档并进入输称呼
        fun pickAge(group: UserPrefs.AgeGroup) {
            selectedAge = group
            stepAge.visibility = View.GONE
            stepName.visibility = View.VISIBLE
        }
        content.findViewById<View>(R.id.welcome_age_child).setOnClickListener { pickAge(UserPrefs.AgeGroup.CHILD) }
        content.findViewById<View>(R.id.welcome_age_youth).setOnClickListener { pickAge(UserPrefs.AgeGroup.YOUTH) }
        content.findViewById<View>(R.id.welcome_age_middle).setOnClickListener { pickAge(UserPrefs.AgeGroup.MIDDLE) }
        content.findViewById<View>(R.id.welcome_age_elder).setOnClickListener { pickAge(UserPrefs.AgeGroup.ELDER) }

        startBtn.setOnClickListener {
            val age = selectedAge
            if (age == null) {
                Toast.makeText(context, "先选一下年纪呀", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(context, "告诉我怎么称呼你呀", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirm(name, age)
        }
        dialog.show()
    }
}
