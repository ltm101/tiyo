package com.koyo.screenwarden

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.koyo.screenwarden.presence.ExternalSharePolicy
import com.koyo.screenwarden.presence.PresenceAttachment
import com.koyo.screenwarden.presence.PresenceChannel
import com.koyo.screenwarden.presence.PresenceChannelRegistry
import com.koyo.screenwarden.presence.PresenceDirection
import com.koyo.screenwarden.presence.PresenceEvent
import com.koyo.screenwarden.presence.PresenceRouter
import com.koyo.screenwarden.presence.PresenceShortcutPublisher
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Android 分享面板里的“发给可又”
 *
 * 先把外部 URI 复制到自己的私有目录，再把稳定路径交给同一个聊天会话
 * 因此来源 App 关闭、临时授权失效后，消息和附件也不会丢
 */
class ShareReceiverActivity : Activity() {
    @Volatile
    private var handling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(incoming: Intent?) {
        if (handling) return
        val shareIntent = incoming ?: run {
            finish()
            return
        }
        if (shareIntent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            finish()
            return
        }
        handling = true
        val sourcePackage = resolveSourcePackage(shareIntent)
        val sourceLabel = resolveSourceLabel(sourcePackage)
        val text = ExternalSharePolicy.normalizedText(
            shareIntent.getStringExtra(Intent.EXTRA_SUBJECT),
            shareIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        )
        val uris = sharedUris(shareIntent)

        Thread {
            val imported = uris.mapIndexedNotNull { index, uri ->
                importAttachment(uri, shareIntent.type, index)
            }
            if (text.isNullOrBlank() && imported.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "这个内容暂时读不到", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@Thread
            }

            val adapter = PresenceChannelRegistry.forPackage(sourcePackage)
            val event = PresenceRouter.publish(
                this,
                PresenceEvent(
                    channel = adapter?.channel ?: PresenceChannel.SYSTEM_SHARE,
                    direction = PresenceDirection.TO_COMPANION,
                    modality = ExternalSharePolicy.modality(
                        imported.map(PresenceAttachment::mimeType),
                        !text.isNullOrBlank()
                    ),
                    sourcePackage = sourcePackage,
                    sourceLabel = sourceLabel,
                    text = text,
                    attachments = imported,
                    conversationKey = "active_companion",
                    explicitUserAction = true
                )
            )
            PresenceShortcutPublisher.publish(this)
            runOnUiThread { openConversation(event) }
        }.start()
    }

    private fun openConversation(event: PresenceEvent) {
        val prompt = buildString {
            event.sourceLabel?.takeIf(String::isNotBlank)?.let { append("我从").append(it).append("分享给你") }
            event.text?.takeIf(String::isNotBlank)?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
            event.attachments.forEach { attachment ->
                if (isNotEmpty()) append('\n')
                append("附件：").append(attachment.displayName)
                    .append("\n本机路径：").append(attachment.privatePath)
            }
        }.ifBlank { "我从其他应用分享了一个内容给你" }
        val target = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
            .putExtra(MainActivity.EXTRA_SEND_TEXT, prompt)
        startActivity(target)
        finish()
    }

    private fun importAttachment(uri: Uri, fallbackMime: String?, index: Int): PresenceAttachment? {
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val metadata = queryMetadata(uri)
        if (metadata.second > ExternalSharePolicy.MAX_ATTACHMENT_BYTES) return null
        val mimeType = contentResolver.getType(uri)
            ?.lowercase(Locale.ROOT)
            ?: fallbackMime?.lowercase(Locale.ROOT)
            ?: "application/octet-stream"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
        val fallbackName = buildString {
            append("分享附件-").append(index + 1)
            if (extension != null) append('.').append(extension)
        }
        val safeName = ExternalSharePolicy.safeFileName(metadata.first, fallbackName)
        val dir = File(filesDir, "presence-inbox").apply { mkdirs() }
        val target = uniqueTarget(dir, safeName)
        return runCatching {
            val size = contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> copyWithLimit(input, output) }
            } ?: error("unreadable share")
            PresenceAttachment(
                privatePath = target.absolutePath,
                displayName = target.name,
                mimeType = mimeType,
                sizeBytes = size
            )
        }.onFailure { target.delete() }.getOrNull()
    }

    private fun copyWithLimit(input: java.io.InputStream, output: FileOutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > ExternalSharePolicy.MAX_ATTACHMENT_BYTES) {
                error("shared attachment too large")
            }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long> = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to -1L
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString)
            val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: -1L
            name to size
        } ?: (null to -1L)
    }.getOrDefault(null to -1L)

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val values = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(values::add)
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(values::addAll)
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(values::add)
        }
        return values.distinctBy(Uri::toString).take(ExternalSharePolicy.MAX_ATTACHMENTS)
    }

    @Suppress("DEPRECATION")
    private fun resolveSourcePackage(intent: Intent): String? {
        callingPackage?.takeIf { it != packageName }?.let { return it }
        val suppliedReferrer = intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)
        val packageFromReferrer = suppliedReferrer?.takeIf { it.scheme == "android-app" }?.host
        return packageFromReferrer?.takeIf { it != packageName }
    }

    private fun resolveSourceLabel(sourcePackage: String?): String? {
        if (sourcePackage.isNullOrBlank()) return null
        PresenceChannelRegistry.forPackage(sourcePackage)?.displayName?.let { return it }
        return runCatching {
            val info = packageManager.getApplicationInfo(sourcePackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    private fun uniqueTarget(dir: File, preferredName: String): File {
        val initial = File(dir, preferredName)
        if (!initial.exists()) return initial
        val dot = preferredName.lastIndexOf('.')
        val stem = if (dot > 0) preferredName.substring(0, dot) else preferredName
        val suffix = if (dot > 0) preferredName.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = File(dir, "$stem ($index)$suffix")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
