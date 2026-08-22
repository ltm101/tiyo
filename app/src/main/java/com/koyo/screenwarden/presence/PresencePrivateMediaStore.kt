package com.koyo.screenwarden.presence

import android.content.Context
import java.io.File

/** Bounded private media cache for platform attachments handed to the local multimodal Agent. */
object PresencePrivateMediaStore {
    private const val MAX_FILES = 120
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L

    fun write(context: Context, eventId: String, index: Int, extension: String, bytes: ByteArray): File {
        val root = File(context.filesDir, "presence/inbound").apply { mkdirs() }
        cleanup(root)
        val safeEvent = eventId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val safeExtension = extension.lowercase().replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "bin" }
        val target = File(root, "${safeEvent}_${index}.$safeExtension")
        val temp = File(root, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    private fun cleanup(root: File) {
        val now = System.currentTimeMillis()
        val files = root.listFiles()?.filter(File::isFile).orEmpty()
        files.filter { it.name.endsWith(".tmp") || now - it.lastModified() > MAX_AGE_MS }
            .forEach(File::delete)
        root.listFiles()?.filter(File::isFile).orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_FILES - 1)
            .forEach(File::delete)
    }
}
