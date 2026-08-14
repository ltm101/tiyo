package com.koyo.screenwarden

import android.util.Log
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

object EmailSender {

    private const val TAG = "EmailSender"

// QQ 邮箱地址与授权码由用户在运行时配置

    suspend fun sendReport(report: String, recipient: String, subject: String = "Screen Usage Report"): Result<Unit> {
        if (!MailConfig.isMailReady() || recipient.isBlank()) {
            // 发布版未配置邮箱：静默跳过，不打扰
            return Result.failure(IllegalStateException("邮箱未配置"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val props = Properties().apply {
                    put("mail.smtp.host", Config.SMTP_HOST)
                    put("mail.smtp.port", Config.SMTP_PORT)
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.ssl.protocols", "TLSv1.2")
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(MailConfig.qqEmail(), MailConfig.qqAuth())
                    }
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(MailConfig.qqEmail()))
                    setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(recipient)))
                    this.subject = subject
                    setText(report)
                }

                Transport.send(message)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 将 tiyo 实时状态保存为草稿，覆盖旧的草稿。
     * 可又通过 [check_tiyo_mail.py --draft] 随时读取。
     */
    suspend fun saveDraft(stateText: String): Result<Unit> {
        if (!MailConfig.isMailReady() || !MailConfig.isAgentReady()) {
            // 发布版未配置邮箱：静默跳过
            return Result.failure(IllegalStateException("邮箱未配置"))
        }
        return withContext(Dispatchers.IO) {
            var store: Store? = null
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
                store = session.store
                store.connect(Config.IMAP_HOST, MailConfig.qqEmail(), MailConfig.qqAuth())

                // 找到并打开草稿箱
                val drafts = store.getFolder("Drafts")
                drafts.open(Folder.READ_WRITE)

                // 删除旧 tiyo 状态草稿（主题匹配）
                for (msg in drafts.messages) {
                    if (msg.subject?.contains("tiyo-state") == true) {
                        msg.setFlag(Flags.Flag.DELETED, true)
                    }
                }

                // 追加新草稿
                val draft = MimeMessage(session).apply {
                    setFrom(InternetAddress(MailConfig.qqEmail()))
                    setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(MailConfig.agentEmail())))
                    subject = "tiyo-state"
                    setText(stateText)
                    setFlag(Flags.Flag.DRAFT, true)
                }

                drafts.appendMessages(arrayOf(draft))
                drafts.expunge()
                drafts.close(true)

                store.close()
                Log.d(TAG, "Draft saved: ${stateText.lines().firstOrNull()}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "saveDraft failed", e)
                try { store?.close() } catch (_: Exception) {}
                Result.failure(e)
            }
        }
    }
}
