package com.koyo.screenwarden

import org.json.JSONArray
import org.json.JSONObject

enum class CompanionPhotoConsent(val key: String) {
    SELF("self"),
    AUTHORIZED_ADULT("authorized_adult"),
    ORIGINAL_CHARACTER("original_character");

    companion object {
        fun fromKey(value: String?): CompanionPhotoConsent? =
            entries.firstOrNull { it.key == value }
    }
}

enum class CompanionBirthStatus(val key: String) {
    DRAFT("draft"),
    REFERENCES_READY("references_ready"),
    GENERATING_ANCHOR("generating_anchor"),
    AWAITING_APPROVAL("awaiting_approval"),
    ANCHOR_APPROVED("anchor_approved"),
    BUILDING_PACK("building_pack"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun fromKey(value: String?): CompanionBirthStatus =
            entries.firstOrNull { it.key == value } ?: DRAFT
    }
}

data class CompanionReferenceAsset(
    val fileName: String,
    val mimeType: String,
    val length: Long,
    val sha256: String
)

data class CompanionBirthCertificate(
    val companionId: String,
    val approvedName: String,
    val consent: CompanionPhotoConsent,
    val status: CompanionBirthStatus,
    val references: List<CompanionReferenceAsset> = emptyList(),
    val anchorFileName: String? = null,
    val anchorSha256: String? = null,
    val identityNotes: List<String> = emptyList(),
    val keepOriginalReferences: Boolean = false,
    val failureReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = SCHEMA_VERSION
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_REFERENCES = 3
    }
}

object CompanionBirthCertificateCodec {
    fun encode(value: CompanionBirthCertificate): String {
        val references = JSONArray()
        value.references.forEach { reference ->
            references.put(
                JSONObject()
                    .put("file_name", reference.fileName)
                    .put("mime_type", reference.mimeType)
                    .put("length", reference.length)
                    .put("sha256", reference.sha256)
            )
        }
        return JSONObject()
            .put("schema_version", value.schemaVersion)
            .put("companion_id", value.companionId)
            .put("approved_name", value.approvedName)
            .put("consent", value.consent.key)
            .put("status", value.status.key)
            .put("references", references)
            .put("anchor_file_name", value.anchorFileName ?: JSONObject.NULL)
            .put("anchor_sha256", value.anchorSha256 ?: JSONObject.NULL)
            .put("identity_notes", JSONArray(value.identityNotes))
            .put("keep_original_references", value.keepOriginalReferences)
            .put("failure_reason", value.failureReason ?: JSONObject.NULL)
            .put("created_at", value.createdAt)
            .put("updated_at", value.updatedAt)
            .toString(2)
    }

    fun decode(raw: String): CompanionBirthCertificate? = runCatching {
        val json = JSONObject(raw)
        val consent = CompanionPhotoConsent.fromKey(json.optString("consent"))
            ?: return@runCatching null
        val references = buildList {
            val array = json.optJSONArray("references") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("file_name")
                val sha = item.optString("sha256")
                if (name.isBlank() || sha.isBlank()) continue
                add(
                    CompanionReferenceAsset(
                        fileName = name,
                        mimeType = item.optString("mime_type", "image/*"),
                        length = item.optLong("length"),
                        sha256 = sha
                    )
                )
            }
        }
        val notes = buildList {
            val array = json.optJSONArray("identity_notes") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        CompanionBirthCertificate(
            companionId = CompanionProfileRules.normalizeId(json.getString("companion_id")),
            approvedName = CompanionProfileRules.normalizeName(json.getString("approved_name")),
            consent = consent,
            status = CompanionBirthStatus.fromKey(json.optString("status")),
            references = references.take(CompanionBirthCertificate.MAX_REFERENCES),
            anchorFileName = json.optNullableString("anchor_file_name"),
            anchorSha256 = json.optNullableString("anchor_sha256"),
            identityNotes = notes,
            keepOriginalReferences = json.optBoolean("keep_original_references"),
            failureReason = json.optNullableString("failure_reason"),
            createdAt = json.optLong("created_at"),
            updatedAt = json.optLong("updated_at"),
            schemaVersion = json.optInt("schema_version", CompanionBirthCertificate.SCHEMA_VERSION)
        )
    }.getOrNull()

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
