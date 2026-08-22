package com.koyo.screenwarden.presence

object ExternalSharePolicy {
    const val MAX_ATTACHMENTS = PresenceEvent.MAX_ATTACHMENTS
    const val MAX_ATTACHMENT_BYTES = 30L * 1024L * 1024L

    fun normalizedText(subject: String?, text: String?): String? {
        val cleanSubject = subject.clean(240)
        val cleanText = text.clean(4_000)
        return when {
            cleanSubject == null -> cleanText
            cleanText == null -> cleanSubject
            cleanText.startsWith(cleanSubject) -> cleanText
            else -> "$cleanSubject\n$cleanText"
        }
    }

    fun modality(mimeTypes: List<String>, hasText: Boolean): PresenceModality {
        val types = mimeTypes.map(String::lowercase)
        val mediaKinds = buildSet {
            if (types.any { it.startsWith("image/") }) add(PresenceModality.IMAGE)
            if (types.any { it.startsWith("video/") }) add(PresenceModality.VIDEO)
            if (types.any { it.startsWith("audio/") }) add(PresenceModality.AUDIO)
            if (types.any { type ->
                    !type.startsWith("image/") &&
                        !type.startsWith("video/") &&
                        !type.startsWith("audio/")
                }) add(PresenceModality.FILE)
        }
        if (mediaKinds.size > 1 || (mediaKinds.isNotEmpty() && hasText)) {
            return PresenceModality.COMPOSITE
        }
        return mediaKinds.firstOrNull()
            ?: if (hasText) PresenceModality.TEXT else PresenceModality.FILE
    }

    fun safeFileName(raw: String?, fallback: String): String {
        val cleaned = raw
            ?.replace(Regex("[\\r\\n\\u0000/\\\\:*?\"<>|]+"), "_")
            ?.trim(' ', '.')
            ?.take(180)
            .orEmpty()
        return cleaned.ifBlank { fallback }
    }

    private fun String?.clean(maxChars: Int): String? = this
        ?.replace("\u0000", "")
        ?.trim()
        ?.take(maxChars)
        ?.takeIf(String::isNotBlank)
}
