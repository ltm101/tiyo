package com.koyo.screenwarden

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * "我的 → 邮箱与天气"。
 * 邮件遥控/报告 + 天气坐标全部由使用者自己填，未配置时相关功能静默关闭。
 */
class MailSettingsFragment : Fragment(R.layout.fragment_mail_settings) {

    private lateinit var qqEmailInput: EditText
    private lateinit var qqAuthInput: EditText
    private lateinit var agentInput: EditText
    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var status: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        qqEmailInput = view.findViewById(R.id.mail_qq_email)
        qqAuthInput = view.findViewById(R.id.mail_qq_auth)
        agentInput = view.findViewById(R.id.mail_agent_email)
        latInput = view.findViewById(R.id.mail_lat)
        lonInput = view.findViewById(R.id.mail_lon)
        status = view.findViewById(R.id.mail_save_status)

        view.findViewById<View>(R.id.mail_back_btn).setOnClickListener {
            activity?.onBackPressed()
        }

        qqEmailInput.setText(MailConfig.qqEmail())
        qqAuthInput.setText(MailConfig.qqAuth())
        agentInput.setText(MailConfig.agentEmail())
        latInput.setText(MailConfig.weatherLat())
        lonInput.setText(MailConfig.weatherLon())

        view.findViewById<View>(R.id.btn_mail_save).setOnClickListener { saveMail() }
    }

    private fun saveMail() {
        MailConfig.save(
            qqEmail = qqEmailInput.text.toString().trim(),
            qqAuth = qqAuthInput.text.toString().trim(),
            agentEmail = agentInput.text.toString().trim(),
            lat = latInput.text.toString().trim(),
            lon = lonInput.text.toString().trim()
        )
        status.text = "已保存"
        Toast.makeText(requireContext(), "邮箱设置已保存", Toast.LENGTH_SHORT).show()
    }
}
