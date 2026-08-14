package com.koyo.screenwarden

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/** 聊天侧栏进入的自定义 MCP 管理页，直接写入本机 Agent 的配置目录 */
class McpSettingsFragment : Fragment(R.layout.fragment_mcp_settings) {

    private lateinit var nameInput: EditText
    private lateinit var endpointInput: EditText
    private lateinit var argsInput: EditText
    private lateinit var transportGroup: RadioGroup
    private lateinit var status: TextView
    private lateinit var serverList: LinearLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameInput = view.findViewById(R.id.mcp_name)
        endpointInput = view.findViewById(R.id.mcp_endpoint)
        argsInput = view.findViewById(R.id.mcp_args)
        transportGroup = view.findViewById(R.id.mcp_transport_group)
        status = view.findViewById(R.id.mcp_status)
        serverList = view.findViewById(R.id.mcp_server_list)

        view.findViewById<View>(R.id.mcp_back_btn).setOnClickListener { activity?.onBackPressed() }
        transportGroup.setOnCheckedChangeListener { _, _ -> updateFieldHints() }
        view.findViewById<View>(R.id.btn_mcp_save).setOnClickListener { saveServer() }
        updateFieldHints()
        renderServers()
    }

    private fun selectedTransport(): String = when (transportGroup.checkedRadioButtonId) {
        R.id.mcp_transport_stdio -> "stdio"
        R.id.mcp_transport_sse -> "sse"
        else -> "http"
    }

    private fun updateFieldHints() {
        val stdio = selectedTransport() == "stdio"
        endpointInput.hint = if (stdio) "命令，例如 npx" else "https://example.com/mcp"
        argsInput.visibility = if (stdio) View.VISIBLE else View.GONE
    }

    private fun saveServer() {
        val result = runCatching {
            TiyoExtensionStore.saveMcpServer(
                requireContext(),
                nameInput.text.toString(),
                selectedTransport(),
                endpointInput.text.toString(),
                argsInput.text.toString(),
            )
        }
        result.onFailure {
            status.text = it.message ?: "MCP 配置没有保存"
            return
        }
        status.text = "已保存，Agent 会在任务匹配时自动调用"
        nameInput.setText("")
        endpointInput.setText("")
        argsInput.setText("")
        renderServers()
        (activity as? MainActivity)?.reloadAgentRuntime()
        Toast.makeText(requireContext(), "MCP 已接入", Toast.LENGTH_SHORT).show()
    }

    private fun renderServers() {
        serverList.removeAllViews()
        val servers = TiyoExtensionStore.mcpServers(requireContext())
        if (servers.isEmpty()) {
            serverList.addView(rowText("还没有自定义 MCP", 14f, R.color.d_ink_3))
            return
        }
        servers.forEach { server ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val copy = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(rowText(server.name, 14f, R.color.d_ink))
                addView(rowText("${server.transport.uppercase()} · ${server.endpoint}", 12f, R.color.d_ink_3))
            }
            val remove = rowText("移除", 13f, R.color.d_accent).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), 0, dp(8))
                setOnClickListener { confirmRemove(server.name) }
            }
            row.addView(copy)
            row.addView(remove)
            serverList.addView(row)
        }
    }

    private fun confirmRemove(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("移除 $name？")
            .setMessage("只会移除这条 MCP 配置")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                TiyoExtensionStore.removeMcpServer(requireContext(), name)
                renderServers()
                (activity as? MainActivity)?.reloadAgentRuntime()
            }
            .show()
    }

    private fun rowText(value: String, size: Float, color: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(requireContext().getColor(color))
        maxLines = 2
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
