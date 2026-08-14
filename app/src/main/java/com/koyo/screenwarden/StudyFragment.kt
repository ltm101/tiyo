package com.koyo.screenwarden

import android.app.Activity
import android.content.Context
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 学习模式：知识库问答 + 苏格拉底引导 + 出题诊断（文字版）。 */
class StudyFragment : Fragment(R.layout.fragment_study) {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var studyScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var chipQa: TextView
    private lateinit var chipSocratic: TextView
    private lateinit var chipQuiz: TextView

    private val messages = mutableListOf<StudyMessage>()
    private var mode = "qa" // qa / socratic / quiz
    private var busy = false
    private var pendingQuiz: JSONArray? = null
    private lateinit var engine: DeepSeekStudyEngine

    private var typingView: View? = null

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val importFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val destination = TiyoWorkspace.study(requireContext())
        Thread {
            var imported = 0
            var failed = 0
            uris.forEach { uri ->
                runCatching {
                    val name = queryDisplayName(uri)
                    val target = uniqueTarget(destination, FileManager.sanitizeName(name))
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use(input::copyTo)
                    } ?: error("无法读取 $name")
                    // PDF/Office 等格式导入后立即用 Runtime 里的 mdconv 转成 markdown，
                    // 成功则只保留可读的 .md，失败保留原文件并计入失败数
                    if (StudyFileConverter.isConvertible(target.name)) {
                        val md = StudyFileConverter.convert(requireContext(), target)
                        if (md != null) target.delete() else error("无法解析为文字")
                    }
                    imported++
                }.onFailure { failed++ }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(
                    when {
                        imported > 0 && failed > 0 -> "已导入 $imported 个，$failed 个暂无法解析"
                        imported > 0 -> "已导入 $imported 个文件到学习资料库"
                        else -> "没有文件导入成功"
                    }
                )
            }
        }.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        messagesContainer = view.findViewById(R.id.study_messages)
        studyScroll = view.findViewById(R.id.study_scroll)
        input = view.findViewById(R.id.study_input)
        sendButton = view.findViewById(R.id.btn_study_send)
        chipQa = view.findViewById(R.id.chip_study_qa)
        chipSocratic = view.findViewById(R.id.chip_study_socratic)
        chipQuiz = view.findViewById(R.id.chip_study_quiz)
        engine = DeepSeekStudyEngine(requireContext())

        view.findViewById<TextView>(R.id.btn_study_settings).setOnClickListener {
            TiyoAgentSettingsDialog(requireContext()) {
                refreshStatus()
            }.show()
        }
        view.findViewById<TextView>(R.id.btn_study_import).setOnClickListener {
            importFiles.launch(arrayOf("text/plain", "text/markdown", "application/pdf", "*/*"))
        }
        view.findViewById<TextView>(R.id.btn_study_files).setOnClickListener { showStudyFiles() }
        view.findViewById<TextView>(R.id.btn_study_weak).setOnClickListener { showWeakPoints() }
        view.findViewById<TextView>(R.id.btn_study_clear).setOnClickListener { confirmClear() }

        chipQa.setOnClickListener { setMode("qa") }
        chipSocratic.setOnClickListener { setMode("socratic") }
        chipQuiz.setOnClickListener { setMode("quiz") }

        sendButton.setOnClickListener { dispatchInput() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                dispatchInput()
                true
            } else false
        }

        refreshStatus()
        renderHistory()
        setMode("qa")
    }

    private fun setMode(next: String) {
        mode = next
        updateModeChips()
        input.hint = when (next) {
            "socratic" -> "问个问题，我不直接答，带你一步步想"
            "quiz" -> if (pendingQuiz == null) "出题模式，发消息开始出题" else "把你的答案发给我批改"
            else -> "基于学习资料提问"
        }
        refreshStatus()
    }

    private fun updateModeChips() {
        val accent = requireContext().getColor(R.color.d_accent_deep)
        val muted = requireContext().getColor(R.color.d_ink_3)
        fun paint(chip: TextView, active: Boolean) {
            chip.setBackgroundResource(if (active) R.drawable.d_pill_active_bg else android.R.color.transparent)
            chip.setTextColor(if (active) accent else muted)
        }
        paint(chipQa, mode == "qa")
        paint(chipSocratic, mode == "socratic")
        paint(chipQuiz, mode == "quiz")
    }

    private fun refreshStatus() {
        val status = view?.findViewById<TextView>(R.id.study_status) ?: return
        val files = KbRetriever.listStudyFiles(requireContext()).size
        val keyOk = TiyoAgentConfig.isConfigured(requireContext())
        status.text = buildString {
            append("资料 $files")
            if (!keyOk) append(" · 未配Key")
        }
        status.setTextColor(
            requireContext().getColor(if (keyOk) R.color.d_ink_3 else R.color.tiyo_accent_dark)
        )
    }

    // ---------- 发送 ----------

    private fun dispatchInput() {
        val text = input.text.toString().trim()
        if (text.isBlank() || busy) return
        input.text.clear()
        addMessage("user", "text", text, persist = true)
        when (mode) {
            "socratic" -> askQuestion(text, socratic = true)
            "quiz" -> handleQuizInput(text)
            else -> askQuestion(text, socratic = false)
        }
    }

    private fun askQuestion(question: String, socratic: Boolean) {
        showTyping()
        busy = true
        val kb = KbRetriever.buildContext(requireContext(), question)
        val system = if (socratic) StudyPrompts.socratic(kb) else StudyPrompts.system(kb)
        val history = messages.takeLast(10).mapNotNull {
            when (it.role) {
                "user" -> ChatMsg("user", it.text)
                "assistant" -> ChatMsg("assistant", it.text)
                else -> null
            }
        }
        val msgs = buildList {
            add(ChatMsg("system", system))
            addAll(history)
            add(ChatMsg("user", question))
        }
        engine.chat(
            msgs,
            jsonMode = false,
            onResult = { reply ->
                busy = false
                hideTyping()
                addMessage("assistant", "text", reply, persist = true)
            },
            onError = { error ->
                busy = false
                hideTyping()
                addSystemMessage("出错了：$error")
            }
        )
    }

    // ---------- 出题 / 诊断 ----------

    private fun handleQuizInput(text: String) {
        if (pendingQuiz == null) {
            generateQuiz()
        } else {
            diagnoseAnswers(text)
        }
    }

    private fun generateQuiz() {
        showTyping()
        busy = true
        val kb = KbRetriever.buildContext(requireContext(), "出题测试")
        val weak = TiyoStudyStore.loadWeakPoints(requireContext())
        val msgs = listOf(ChatMsg("system", StudyPrompts.quiz(kb, weak)))
        engine.chat(
            msgs,
            jsonMode = true,
            onResult = { reply ->
                busy = false
                hideTyping()
                val quiz = parseQuizJson(reply)
                if (quiz == null) {
                    // 解析失败，降级纯文本展示
                    addMessage("assistant", "quiz", reply, persist = true)
                    addSystemMessage("（题目已按原文展示，直接回复答案我来批改）")
                    pendingQuiz = null
                    return@chat
                }
                pendingQuiz = quiz
                for (i in 0 until quiz.length()) {
                    val q = quiz.optJSONObject(i) ?: continue
                    addMessage("assistant", "quiz", formatQuestion(i + 1, q), persist = true)
                }
                addSystemMessage("回复你的答案（多题用序号或逗号分隔）")
                input.hint = "把你的答案发给我批改"
            },
            onError = { error ->
                busy = false
                hideTyping()
                addSystemMessage("出题失败：$error")
            }
        )
    }

    private fun diagnoseAnswers(userAnswer: String) {
        val quiz = pendingQuiz ?: return
        val prompt = buildString {
            for (i in 0 until quiz.length()) {
                val q = quiz.optJSONObject(i) ?: continue
                appendLine("题${i + 1}：${q.optString("q")}")
                appendLine("正确答案：${q.optString("answer")}")
            }
        }
        showTyping()
        busy = true
        val msgs = listOf(ChatMsg("system", StudyPrompts.diagnose(prompt, "", userAnswer)))
        engine.chat(
            msgs,
            jsonMode = true,
            onResult = { reply ->
                busy = false
                hideTyping()
                pendingQuiz = null
                val diag = parseDiagnoseJson(reply)
                if (diag == null) {
                    addMessage("assistant", "diagnosis", reply, persist = true)
                } else {
                    val correct = diag.optBoolean("correct", false)
                    val explain = diag.optString("explain", reply)
                    val weakArray = diag.optJSONArray("weak_points")
                    val weak = buildList {
                        if (weakArray != null) {
                            for (i in 0 until weakArray.length()) {
                                add(weakArray.optString(i, ""))
                            }
                        }
                    }.filter { it.isNotBlank() }
                    if (weak.isNotEmpty()) {
                        TiyoStudyStore.addWeakPoints(requireContext(), weak)
                    }
                    val suggestion = diag.optString("suggestion", "")
                    val text = buildString {
                        appendLine(if (correct) "✅ 答对了" else "❌ 还差一点")
                        appendLine()
                        appendLine(explain)
                        if (suggestion.isNotBlank()) {
                            appendLine()
                            appendLine("下一步：$suggestion")
                        }
                        if (weak.isNotEmpty()) {
                            appendLine()
                            appendLine("薄弱点：${weak.joinToString("、")}（已记下，下次出题优先考）")
                        }
                    }
                    addMessage("assistant", "diagnosis", text.trim(), persist = true)
                }
                addSystemMessage("想再来一组，在出题模式发条消息就行")
                input.hint = "出题模式，发消息开始出题"
            },
            onError = { error ->
                busy = false
                hideTyping()
                pendingQuiz = quiz
                addSystemMessage("批改失败：$error")
            }
        )
    }

    private fun formatQuestion(index: Int, q: JSONObject): String {
        return buildString {
            val point = q.optString("point")
            appendLine("📝 第 $index 题${if (point.isNotBlank()) " · $point" else ""}")
            appendLine(q.optString("q"))
            val options = q.optJSONArray("options")
            if (options != null) {
                for (i in 0 until options.length()) {
                    appendLine(options.optString(i, ""))
                }
            }
        }.trim()
    }

    private fun parseQuizJson(raw: String): JSONArray? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return try {
            JSONObject(cleaned).optJSONArray("questions")
        } catch (_: Exception) {
            try {
                JSONArray(cleaned)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseDiagnoseJson(raw: String): JSONObject? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return try {
            JSONObject(cleaned)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 消息渲染 ----------

    private fun renderHistory() {
        messagesContainer.removeAllViews()
        messages.clear()
        messages.addAll(TiyoStudyStore.loadHistory(requireContext()))
        messages.forEach { renderMessage(it) }
        scrollToBottom()
    }

    private fun addMessage(role: String, type: String, text: String, persist: Boolean) {
        val msg = StudyMessage(role, type, text, System.currentTimeMillis())
        messages.add(msg)
        while (messages.size > 60) messages.removeAt(0)
        renderMessage(msg)
        if (persist) TiyoStudyStore.saveHistory(requireContext(), messages)
    }

    private fun renderMessage(message: StudyMessage): View {
        val layout = when (message.role) {
            "user" -> R.layout.item_chat_user
            "system" -> R.layout.item_chat_system
            else -> R.layout.item_chat_koyo
        }
        val row = LayoutInflater.from(requireContext())
            .inflate(layout, messagesContainer, false)
        val display = if (message.role == "user") message.text else cleanStudyText(message.text)
        row.findViewById<TextView>(R.id.chat_message_text).text = display
        row.findViewById<TextView>(R.id.chat_message_time)?.text =
            timeFormatter.format(Date(message.timestamp))
        // Tiyo气泡头像跟随聊天里选的头像
        if (message.role != "user") {
            row.findViewById<android.widget.ImageView>(R.id.chat_avatar)?.let {
                it.tag = message.role
                bindKoyoAvatar(it)
            }
        }
        messagesContainer.addView(row)
        scrollToBottom()
        return row
    }

    private fun bindKoyoAvatar(avatar: android.widget.ImageView) {
        val custom = AvatarStore.loadCompanionBitmap(requireContext())
        if (custom != null) avatar.setImageBitmap(custom)
        else avatar.setImageResource(AvatarStore.companionRes(requireContext()))
    }

    /** 把模型输出的 Markdown 符号剥掉，变成微信聊天那样的纯文本。 */
    private fun cleanStudyText(text: String): String {
        var t = text
        // 代码块包裹标记
        t = t.replace(Regex("```[a-zA-Z]*\n?"), "")
        // 行首标题 #
        t = t.replace(Regex("(?m)^#{1,6}\\s*"), "")
        // 行首列表符 - * +（题目选项 A. 之类不受影响）
        t = t.replace(Regex("(?m)^[\\s]*[-*+][\\s]+"), "")
        // 分隔线 --- *** ___
        t = t.replace(Regex("(?m)^[\\s]*([-*_])\\1{2,}[\\s]*$"), "")
        // 行内加粗 / 斜体符号
        t = t.replace("**", "").replace("__", "")
        t = t.replace(Regex("\\*([^*]+)\\*"), "$1")
        // 行内代码反引号
        t = t.replace("`", "")
        // 数学公式包裹符 \[ \] \( \) $$ $
        t = t.replace("\\[", "").replace("\\]", "")
            .replace("\\(", "").replace("\\)", "")
            .replace("$$", "").replace("$", "")
        // 链接 [text](url) → text
        t = t.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        return t.trim()
    }

    private fun showTyping() {
        hideTyping()
        val msg = StudyMessage("assistant", "text", "正在想…", System.currentTimeMillis())
        val row = renderMessage(msg)
        typingView = row
    }

    private fun hideTyping() {
        typingView?.let { messagesContainer.removeView(it) }
        typingView = null
    }

    private fun addSystemMessage(text: String) {
        val row = renderMessage(StudyMessage("system", "text", text, System.currentTimeMillis()))
        // 系统提示不持久化，但给它一条划线可点样式即可
        row.isClickable = false
    }

    private fun scrollToBottom() {
        studyScroll.post { studyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- 资料 / 设置 ----------

    private fun showStudyFiles() {
        val files = KbRetriever.listStudyFiles(requireContext())
        if (files.isEmpty()) {
            toast("学习资料库还是空的，点\"导入资料\"添加")
            return
        }
        val labels = files.map { "${it.name}  ·  ${FileManager.formatSize(it.length())}" }
        AlertDialog.Builder(requireContext())
            .setTitle("学习资料库（${files.size}）")
            .setItems(labels.toTypedArray()) { _, which ->
                viewFileContent(files[which])
            }
            .setNegativeButton("去工作台") { _, _ ->
                (activity as? MainActivity)?.openFilesWorkspace()
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 查看资料内容：文本文件直接弹可滚动对话框；二进制提示去工作台。 */
    private fun viewFileContent(file: File) {
        if (!KbRetriever.isReadableText(file)) {
            toast("这个文件不是可读文本，去工作台查看")
            return
        }
        val text = runCatching { file.readText().trim() }.getOrElse { "" }
        if (text.isEmpty()) {
            toast("文件内容为空")
            return
        }
        val body = TextView(requireContext()).apply {
            setTextColor(0xFF292722.toInt())
            textSize = 14f
            setLineSpacing(0f, 1.3f)
            setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12))
            setText(text)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(ScrollView(requireContext()).apply { addView(body) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun dp2px(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showWeakPoints() {
        val weak = TiyoStudyStore.loadWeakPoints(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("已记录的薄弱点（${weak.size}）")
            .setMessage(if (weak.isEmpty()) "还没有薄弱点。做完题，我会把暴露的问题记在这里，下次出题优先考。" else weak.joinToString("\n"))
            .setNegativeButton("清空") { _, _ ->
                TiyoStudyStore.clearWeakPoints(requireContext())
                toast("薄弱点已清空")
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空学习对话")
            .setMessage("历史记录会清除，学习资料不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                TiyoStudyStore.saveHistory(requireContext(), emptyList())
                pendingQuiz = null
                renderHistory()
            }
            .show()
    }

    private fun queryDisplayName(uri: Uri): String {
        return requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
            ?: "import-${System.currentTimeMillis()}"
    }

    private fun uniqueTarget(directory: File, requestedName: String): File {
        val safe = requestedName.ifBlank { "file" }
        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "")
        var target = File(directory, safe)
        var suffix = 2
        while (target.exists()) {
            target = File(directory, if (extension.isBlank()) "$stem-$suffix" else "$stem-$suffix.$extension")
            suffix++
        }
        return target
    }

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshAvatars()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshStatus()
            refreshAvatars()
        }
    }

    private fun refreshAvatars() {
        if (!::messagesContainer.isInitialized) return
        for (i in 0 until messagesContainer.childCount) {
            val avatar = messagesContainer.getChildAt(i)
                .findViewById<android.widget.ImageView>(R.id.chat_avatar) ?: continue
            if (avatar.tag != "user") bindKoyoAvatar(avatar)
        }
    }
}
