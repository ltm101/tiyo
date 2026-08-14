package com.koyo.screenwarden

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*

class StatsFragment : Fragment(R.layout.fragment_stats) {

    private lateinit var totalText: TextView
    private lateinit var detailText: TextView
    private var loadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        totalText = view.findViewById(R.id.stats_total)
        detailText = view.findViewById(R.id.stats_detail)
        // 子 Fragment 首次 replace 进容器时不会触发 onHiddenChanged(false)，
        // 必须在视图创建完成后主动加载，否则会永远停在布局默认的“加载中”
        loadStats()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
    }

    fun loadStats() {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            totalText.text = "加载中…"
            detailText.text = ""

            val report = withContext(Dispatchers.IO) {
                try {
                    ScreenUsageCollector(requireContext()).collectDailyUsage()
                } catch (e: Exception) {
                    "无法获取数据\n请确认已授权使用时间统计权限"
                }
            }

            val lines = report.lines()
            val totalLine = lines.find { it.startsWith("Total:") }

            if (totalLine != null) {
                totalText.text = "今日总计：${totalLine.removePrefix("Total: ").trim()}"
            } else {
                totalText.text = "今日总计：--"
            }

            val appLines = lines.filter {
                it.contains(": ") && (it.contains("h ") || it.contains("min"))
            }

            detailText.text = if (appLines.isEmpty()) {
                "暂无数据\n\n请确认已授权使用时间统计权限"
            } else {
                appLines.joinToString("\n")
            }
        }
    }
}
