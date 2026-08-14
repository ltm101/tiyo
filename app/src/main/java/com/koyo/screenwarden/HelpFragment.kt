package com.koyo.screenwarden

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

/** "我的 → 新手帮助"：静态教程，教新用户配模型、语音、邮箱。 */
class HelpFragment : Fragment(R.layout.fragment_help) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.help_back_btn).setOnClickListener {
            activity?.onBackPressed()
        }
    }
}
