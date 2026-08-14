package com.koyo.screenwarden

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast

/** 随时修改可又对用户的称呼，不触碰年龄档或其他人格内容。 */
class UserNameDialog(
    private val context: Context,
    private val onConfirm: (name: String) -> Unit
) {
    fun show() {
        val dialog = Dialog(context)
        val content = LayoutInflater.from(context)
            .inflate(R.layout.dialog_user_name, null, false)
        CompanionUiText.applyRecursively(context, content)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.4f }
        }
        dialog.setCanceledOnTouchOutside(true)

        val input = content.findViewById<EditText>(R.id.user_name_input)
        input.setText(UserPrefs.getName(context))
        input.setSelection(input.text.length)

        content.findViewById<android.view.View>(R.id.user_name_cancel).setOnClickListener {
            dialog.dismiss()
        }
        content.findViewById<android.view.View>(R.id.user_name_save).setOnClickListener {
            val name = UserPrefs.normalizeName(input.text.toString())
            if (name.isBlank()) {
                Toast.makeText(context, "先告诉我想怎么叫你", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onConfirm(name)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.post {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }
}
