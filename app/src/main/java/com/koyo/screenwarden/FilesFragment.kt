package com.koyo.screenwarden

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import java.io.File

class FilesFragment : Fragment(R.layout.fragment_files) {

    private lateinit var pathText: TextView
    private lateinit var scopeText: TextView
    private lateinit var emptyText: TextView
    private lateinit var fileList: ListView

    private lateinit var currentDir: File
    private var currentFiles: List<File> = emptyList()
    private var pendingExport: File? = null
    private var tasksRows: LinearLayout? = null

    private val importFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val destination = currentDir
        Thread {
            var imported = 0
            uris.forEach { uri ->
                runCatching {
                    val name = queryDisplayName(uri)
                    val target = uniqueTarget(destination, FileManager.sanitizeName(name))
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use(input::copyTo)
                    } ?: error("无法读取 $name")
                    imported++
                }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                toast(if (imported > 0) "已导入 $imported 个文件" else "没有文件导入成功")
                navigateTo(destination)
            }
        }.start()
    }

    private val exportFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = pendingExport
        pendingExport = null
        val target = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || source == null || target == null) return@registerForActivityResult
        Thread {
            val outcome = runCatching {
                source.inputStream().use { input ->
                    requireContext().contentResolver.openOutputStream(target, "w")?.use { output ->
                        input.copyTo(output, 128 * 1024)
                    } ?: error("无法写入选择的位置")
                }
            }
            activity?.runOnUiThread {
                toast(outcome.fold({ "已导出 ${source.name}" }, { it.message ?: "导出失败" }))
            }
        }.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pathText = view.findViewById(R.id.file_path_text)
        scopeText = view.findViewById(R.id.file_scope_text)
        emptyText = view.findViewById(R.id.file_empty_text)
        fileList = view.findViewById(R.id.file_list)

        view.findViewById<TextView>(R.id.file_workspace_btn).setOnClickListener {
            navigateTo(TiyoWorkspace.root(requireContext()))
        }
        view.findViewById<TextView>(R.id.file_phone_btn).setOnClickListener {
            navigateTo(TiyoWorkspace.phoneRoot())
        }
        view.findViewById<TextView>(R.id.file_back_btn).setOnClickListener { navigateUp() }
        view.findViewById<TextView>(R.id.file_search_btn).setOnClickListener { showSearch() }
        view.findViewById<TextView>(R.id.file_import_btn).setOnClickListener {
            importFiles.launch(arrayOf("*/*"))
        }
        view.findViewById<TextView>(R.id.file_new_btn).setOnClickListener { showCreateMenu() }

        // 今日任务（从 Today 挪到工作台）
        tasksRows = view.findViewById(R.id.ws_tasks_rows)
        tasksRows?.let { TaskUi.render(requireContext(), layoutInflater, it) }
        view.findViewById<TextView>(R.id.ws_tasks_add).setOnClickListener {
            tasksRows?.let { rows -> TaskUi.showAddDialog(requireContext(), layoutInflater, rows) }
        }

        fileList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val file = currentFiles.getOrNull(position) ?: return@OnItemClickListener
            if (file.isDirectory) navigateTo(file) else openFile(file)
        }
        fileList.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            currentFiles.getOrNull(position)?.let(::showFileActions)
            true
        }

        currentDir = TiyoWorkspace.root(requireContext())
        navigateTo(currentDir)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::currentDir.isInitialized) navigateTo(currentDir)
        if (!hidden) tasksRows?.let { TaskUi.render(requireContext(), layoutInflater, it) }
    }

    private fun navigateTo(directory: File) {
        if (!directory.exists() || !directory.isDirectory || !FileManager.isAllowed(directory.absolutePath)) {
            toast("这个位置暂时无法打开")
            return
        }
        currentDir = directory
        currentFiles = FileManager.listFiles(directory)
        pathText.text = compactPath(directory)
        val workspace = TiyoWorkspace.root(requireContext()).canonicalPath
        scopeText.text = if (directory.canonicalPath.startsWith(workspace)) {
            "Tiyo 工作区 · Agent 可以直接使用"
        } else {
            "手机共享文件 · 修改前会由你确认"
        }
        emptyText.visibility = if (currentFiles.isEmpty()) View.VISIBLE else View.GONE
        fileList.adapter = FileAdapter(currentFiles)
    }

    private fun navigateUp() {
        val phoneRoot = TiyoWorkspace.phoneRoot().canonicalFile
        val parent = currentDir.parentFile?.canonicalFile ?: return
        if (parent.toPath().startsWith(phoneRoot.toPath())) navigateTo(parent)
    }

    private fun openFile(file: File) {
        when {
            FileManager.isWebsite(file) -> showOpenWebsite(file)
            FileManager.isEditable(file) -> showEditor(file)
            else -> showProperties(file)
        }
    }

    private fun showOpenWebsite(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(arrayOf("预览网站", "编辑代码", "文件信息")) { _, index ->
                when (index) {
                    0 -> startActivity(WebsitePreviewActivity.intent(requireContext(), file))
                    1 -> showEditor(file)
                    else -> showProperties(file)
                }
            }
            .show()
    }

    private fun showEditor(file: File) {
        val content = FileManager.readText(file).getOrElse {
            toast(it.message ?: "无法读取文件")
            return
        }
        val editor = EditText(requireContext()).apply {
            setText(content)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(true)
            minLines = 14
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(editor)
            .setNegativeButton("取消", null)
            .setNeutralButton("预览") { _, _ ->
                FileManager.writeText(file, editor.text.toString())
                if (FileManager.isWebsite(file)) {
                    startActivity(WebsitePreviewActivity.intent(requireContext(), file))
                }
            }
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                FileManager.writeText(file, editor.text.toString()).fold(
                    onSuccess = {
                        toast("已保存 ${file.name}")
                        dialog.dismiss()
                        navigateTo(currentDir)
                    },
                    onFailure = { toast(it.message ?: "保存失败") }
                )
            }
        }
        dialog.show()
    }

    private fun showCreateMenu() {
        AlertDialog.Builder(requireContext())
            .setTitle("在这里新建")
            .setItems(arrayOf("文本或代码文件", "文件夹", "静态网站")) { _, index ->
                when (index) {
                    0 -> askForName("新建文件", "notes.md") { name -> createItem(name, false) }
                    1 -> askForName("新建文件夹", "新文件夹") { name -> createItem(name, true) }
                    2 -> askForName("新建网站项目", "my-site") { name -> createWebsite(name) }
                }
            }
            .show()
    }

    private fun createItem(name: String, folder: Boolean) {
        FileManager.create(currentDir, name, folder).fold(
            onSuccess = { created ->
                navigateTo(currentDir)
                if (!folder) showEditor(created)
            },
            onFailure = { toast(it.message ?: "创建失败") }
        )
    }

    private fun createWebsite(name: String) {
        FileManager.create(currentDir, name, true).fold(
            onSuccess = { project ->
                val index = File(project, "index.html")
                FileManager.writeText(index, websiteStarter(name))
                navigateTo(project)
                startActivity(WebsitePreviewActivity.intent(requireContext(), index))
            },
            onFailure = { toast(it.message ?: "项目创建失败") }
        )
    }

    private fun showFileActions(file: File) {
        val actions = mutableListOf("重命名", "导出副本", "删除")
        if (FileManager.isWebsite(file)) actions.add(0, "预览网站")
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    "预览网站" -> startActivity(WebsitePreviewActivity.intent(requireContext(), file))
                    "重命名" -> askForName("重命名", file.name) { rename(file, it) }
                    "导出副本" -> export(file)
                    "删除" -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun rename(file: File, name: String) {
        FileManager.rename(file, name).fold(
            onSuccess = { navigateTo(currentDir) },
            onFailure = { toast(it.message ?: "重命名失败") }
        )
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除 ${file.name}")
            .setMessage(if (file.isDirectory) "文件夹里的内容也会一起删除，这一步不能撤销" else "这一步不能撤销")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                FileManager.delete(file).fold(
                    onSuccess = { navigateTo(currentDir) },
                    onFailure = { toast(it.message ?: "删除失败") }
                )
            }
            .show()
    }

    private fun export(file: File) {
        if (!file.isFile) {
            toast("文件夹导出会在加入 ZIP 功能后开放")
            return
        }
        pendingExport = file
        exportFile.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, file.name)
            }
        )
    }

    private fun showSearch() {
        askForName("查找当前文件夹", "") { query ->
            val results = currentDir.walkTopDown()
                .maxDepth(8)
                .filter { !it.isHidden && it.name.contains(query, ignoreCase = true) }
                .take(80)
                .toList()
            if (results.isEmpty()) {
                toast("没有找到相关文件")
                return@askForName
            }
            AlertDialog.Builder(requireContext())
                .setTitle("找到 ${results.size} 项")
                .setItems(results.map { it.relativeTo(currentDir).path }.toTypedArray()) { _, index ->
                    val result = results[index]
                    if (result.isDirectory) navigateTo(result) else openFile(result)
                }
                .show()
        }
    }

    private fun showProperties(file: File) {
        val detail = buildString {
            appendLine(if (file.isDirectory) "文件夹" else "文件")
            if (file.isFile) appendLine("大小  ${FileManager.formatSize(file.length())}")
            append("位置  ${file.absolutePath}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setMessage(detail)
            .setNegativeButton("关闭", null)
            .setPositiveButton(if (file.isFile) "导出" else "确定") { _, _ ->
                if (file.isFile) export(file)
            }
            .show()
    }

    private fun askForName(title: String, initial: String, action: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    input.error = "需要输入内容"
                } else {
                    dialog.dismiss()
                    action(value)
                }
            }
        }
        dialog.show()
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

    private fun compactPath(directory: File): String {
        val phone = TiyoWorkspace.phoneRoot().absolutePath
        return directory.absolutePath.replaceFirst(phone, "手机")
    }

    private fun websiteStarter(name: String): String = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${name.replace("<", "")}</title>
  <style>
    :root { color-scheme: light; font-family: system-ui, sans-serif; background: #f7f0e6; color: #29231f; }
    body { margin: 0; min-height: 100dvh; display: grid; place-items: center; }
    main { width: min(720px, calc(100% - 48px)); }
    small { color: #c96f4b; letter-spacing: .16em; }
    h1 { margin: 14px 0; font-size: clamp(44px, 12vw, 92px); line-height: .95; letter-spacing: -.06em; }
    p { max-width: 36em; color: #675e57; line-height: 1.7; }
  </style>
</head>
<body>
  <main>
    <small>MADE WITH TIYO</small>
    <h1>${name.replace("<", "")}</h1>
    <p>这是你的新网站，回到文件工作台编辑 index.html，然后刷新预览</p>
  </main>
</body>
</html>
"""

    private inner class FileAdapter(files: List<File>) :
        ArrayAdapter<File>(requireContext(), android.R.layout.simple_list_item_1, files) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val file = getItem(position) ?: return TextView(context)
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(9), dp(13), dp(9))
                minimumHeight = dp(58)
                addView(TextView(context).apply {
                    id = android.R.id.icon
                    gravity = Gravity.CENTER
                    textSize = 17f
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                })
                addView(LinearLayout(context).apply {
                    id = android.R.id.content
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply { id = android.R.id.text1 })
                    addView(TextView(context).apply { id = android.R.id.text2 })
                })
                addView(TextView(context).apply {
                    id = android.R.id.hint
                    text = "···"
                    textSize = 15f
                    setTextColor(requireContext().getColor(R.color.tiyo_muted))
                })
            }
            row.findViewById<TextView>(android.R.id.icon).apply {
                text = if (file.isDirectory) "⌑" else if (FileManager.isWebsite(file)) "◇" else "·"
                setTextColor(requireContext().getColor(if (file.isDirectory) R.color.tiyo_accent_dark else R.color.tiyo_ink_soft))
            }
            row.findViewById<TextView>(android.R.id.text1).apply {
                text = file.name
                textSize = 13f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(requireContext().getColor(R.color.tiyo_ink))
                maxLines = 1
            }
            row.findViewById<TextView>(android.R.id.text2).apply {
                text = if (file.isDirectory) "文件夹" else "${file.extension.ifBlank { "文件" }} · ${FileManager.formatSize(file.length())}"
                textSize = 10f
                setTextColor(requireContext().getColor(R.color.tiyo_muted))
            }
            return row
        }
    }

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
