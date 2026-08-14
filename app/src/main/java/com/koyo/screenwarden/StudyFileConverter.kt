package com.koyo.screenwarden

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 学习资料格式转换：调用 Runtime 里的 python 跑 mdconv.py，把 PDF/Office 等
 * 转成 markdown 存入学习库。
 *
 * mdconv 是 markitdown 的瘦身版：启动前 stub 掉 magika（Google 的 ML 文件嗅探，
 * 它依赖 onnxruntime，termux/Android 上没有可用 wheel），让 markitdown 按文件
 * 扩展名路由转换器。PDF 则直接走 pdfminer 提取文本。
 */
object StudyFileConverter {

    private val convertibleExtensions = setOf(
        "pdf", "docx", "xlsx", "xls", "pptx", "html", "htm", "csv", "epub", "ipynb"
    )

    /** 该扩展名是否支持自动转 markdown */
    fun isConvertible(name: String): Boolean =
        convertibleExtensions.contains(name.substringAfterLast('.', "").lowercase())

    /** 转换 src 到同目录下的 .md。成功返回生成的 md 文件，失败返回 null。需在后台线程调用。 */
    fun convert(context: Context, src: File): File? {
        if (!TiyoRuntime.ensureInstalled(context)) return null
        val usr = TiyoRuntime.prefix(context)
        val python = File(usr, "bin/python3.14")
        val mdconv = File(usr, "bin/mdconv.py")
        if (!python.isFile || !mdconv.isFile) return null

        val out = File(src.parentFile, src.nameWithoutExtension + ".md")
        return runCatching {
            val process = ProcessBuilder(
                python.absolutePath, mdconv.absolutePath,
                src.absolutePath, out.absolutePath
            ).apply {
                // 注入 Runtime 环境：LD_LIBRARY_PATH 让 python 能找到 termux 的库
                environment().putAll(TiyoRuntime.environment(context))
                redirectErrorStream(true)
            }.start()
            // 读走 stdout 避免管道阻塞
            process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (out.isFile && out.length() > 0) out else null
        }.getOrNull()
    }
}
