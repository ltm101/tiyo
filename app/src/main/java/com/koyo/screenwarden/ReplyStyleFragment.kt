package com.koyo.screenwarden

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/** 回复性格设置：编辑性格描述 / 一键克隆（粘贴或导入聊天样本 → 分析生成）。 */
class ReplyStyleFragment : Fragment(R.layout.fragment_reply_style) {

    private lateinit var styleInput: EditText
    private lateinit var sampleInput: EditText
    private lateinit var statusText: TextView

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readSample(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        styleInput = view.findViewById(R.id.style_input)
        sampleInput = view.findViewById(R.id.sample_input)
        statusText = view.findViewById(R.id.style_status)

        styleInput.setText(ReplyStyleManager.load(requireContext()))

        view.findViewById<TextView>(R.id.btn_style_save).setOnClickListener {
            ReplyStyleManager.save(requireContext(), styleInput.text.toString())
            toast("性格已保存")
        }
        view.findViewById<TextView>(R.id.btn_style_reset).setOnClickListener {
            ReplyStyleManager.reset(requireContext())
            styleInput.setText(ReplyStyleManager.defaultStyle(requireContext()))
            toast("已重置为默认")
        }
        view.findViewById<TextView>(R.id.btn_style_import).setOnClickListener {
            openDoc.launch(arrayOf("text/plain", "text/markdown", "*/*"))
        }
        view.findViewById<TextView>(R.id.btn_style_clone).setOnClickListener {
            val sample = sampleInput.text.toString().trim()
            if (sample.isEmpty()) {
                toast("先粘贴或导入样本")
                return@setOnClickListener
            }
            runClone(sample)
        }
    }

    private fun readSample(uri: Uri) {
        Thread {
            val text = runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                }
            }.getOrNull()
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (text.isNullOrBlank()) {
                    toast("读不到文件内容")
                } else {
                    sampleInput.setText(text.take(6000))
                    toast("已读入样本")
                }
            }
        }.start()
    }

    private fun runClone(sample: String) {
        statusText.text = "分析中…"
        StyleCloner.analyze(
            requireContext(),
            sample,
            onResult = { style ->
                styleInput.setText(style)
                statusText.text = "已生成，点保存生效"
                toast("克隆完成")
            },
            onError = { err ->
                statusText.text = "分析失败：$err"
            }
        )
    }

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
