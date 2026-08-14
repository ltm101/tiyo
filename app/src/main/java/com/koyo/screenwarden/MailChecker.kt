package com.koyo.screenwarden

import android.util.Log
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

object MailChecker {

    private const val TAG = "MailChecker"

    /**
     * 检查未读邮件，返回匹配到的指令。
     * 查询类：tiyo-report（查报告）、tiyo-file /path（查文件）
     * 动作类：tiyo-ring（响铃）、tiyo-notify 文本（推送通知）、
     *         tiyo-launch 包名（打开App）、tiyo-sms（正文 号码|内容，发短信）、
     *         tiyo-suggest（正文 联系人|建议回复，半自动回微信）
     */
    suspend fun checkForCommand(): Command? {
        // 发布版未配置邮箱：静默跳过，不连 IMAP
        if (!MailConfig.isMailReady() || !MailConfig.isAgentReady()) return null
        return withContext(Dispatchers.IO) {
            try {
                val props = Properties().apply {
                    put("mail.store.protocol", "imaps")
                    put("mail.imaps.host", Config.IMAP_HOST)
                    put("mail.imaps.port", Config.IMAP_PORT)
                    put("mail.imaps.ssl.enable", "true")
                    put("mail.imaps.connectiontimeout", 10000)
                    put("mail.imaps.timeout", 10000)
                }

                val session = Session.getInstance(props)
                val store = session.store
                store.connect(Config.IMAP_HOST, MailConfig.qqEmail(), MailConfig.qqAuth())

                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_WRITE)

                val unread = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                var command: Command? = null

                val agentUser = MailConfig.agentEmail().substringBefore("@")

                for (msg in unread) {
                    val from = msg.from?.firstOrNull()?.toString() ?: ""
                    val subject = msg.subject ?: ""
                    val body = extractText(msg)

                    if (!from.contains(agentUser, ignoreCase = true)) continue

                    when {
                        subject.contains("tiyo-file", ignoreCase = true) -> {
                            // 从主题或正文提取路径
                            val path = (subject.substringAfter("tiyo-file", "").trim()
                                .ifEmpty { body.lines().firstOrNull()?.trim() ?: "" })
                                .ifEmpty { "/sdcard/" }
                            command = Command.FileQuery(path)
                        }
                        subject.contains("tiyo-report", ignoreCase = true) -> {
                            command = Command.Report
                        }
                        subject.contains("tiyo-ring", ignoreCase = true) -> {
                            command = Command.Ring
                        }
                        // 排除自己转发通知的 tiyo-notify-fwd 回信误触
                        subject.contains("tiyo-notify", ignoreCase = true) &&
                            !subject.contains("fwd", ignoreCase = true) -> {
                            val text = subject.substringAfter("tiyo-notify", "").trim()
                                .ifEmpty { body.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "" }
                            if (text.isNotEmpty()) command = Command.Notify(text)
                        }
                        subject.contains("tiyo-launch", ignoreCase = true) -> {
                            val pkg = subject.substringAfter("tiyo-launch", "").trim()
                                .ifEmpty { body.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "" }
                            if (pkg.isNotEmpty()) command = Command.LaunchApp(pkg)
                        }
                        subject.contains("tiyo-sms", ignoreCase = true) -> {
                            // 正文格式：号码|短信内容
                            val line = body.lines().firstOrNull { it.contains("|") }?.trim()
                                ?: subject.substringAfter("tiyo-sms", "").trim()
                            val number = line.substringBefore("|").trim()
                            val text = line.substringAfter("|", "").trim()
                            if (number.isNotEmpty() && text.isNotEmpty()) {
                                command = Command.SendSms(number, text)
                            }
                        }
                        subject.contains("tiyo-suggest", ignoreCase = true) -> {
                            // 正文格式：联系人|建议回复
                            val line = body.lines().firstOrNull { it.contains("|") }?.trim()
                                ?: subject.substringAfter("tiyo-suggest", "").trim()
                            val contact = line.substringBefore("|").trim()
                            val text = line.substringAfter("|", "").trim()
                            if (contact.isNotEmpty() && text.isNotEmpty()) {
                                command = Command.Suggest(contact, text)
                            }
                        }
                    }

                    if (command != null) {
                        msg.setFlag(Flags.Flag.SEEN, true)
                        break
                    }
                }

                inbox.close(false)
                store.close()
                command
            } catch (e: Exception) {
                Log.e(TAG, "IMAP check failed", e)
                null
            }
        }
    }

    /**
     * Jakarta Mail 的 msg.content 可能返回 String 也可能返回 MimeMultipart，
     * 后者 .toString() 只给类名，不是正文。这里统一提取真实文本。
     */
    private fun extractText(msg: Message): String {
        return try {
            when (val c = msg.content) {
                is String -> c
                is MimeMultipart -> {
                    val sb = StringBuilder()
                    for (i in 0 until c.count) {
                        val bp = c.getBodyPart(i)
                        val text = bp.content?.toString() ?: ""
                        if (text.isNotBlank()) sb.appendLine(text)
                    }
                    sb.toString().trimEnd()
                }
                else -> c?.toString() ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
