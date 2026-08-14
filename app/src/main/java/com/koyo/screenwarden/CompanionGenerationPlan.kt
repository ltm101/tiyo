package com.koyo.screenwarden

enum class CompanionAssetRole(val key: String, val label: String) {
    IDENTITY_ANCHOR("identity_anchor", "角色出生证"),
    CHAT_PORTRAIT("chat_portrait", "聊天头像"),
    TODAY_IDLE("today_idle", "今天页待机"),
    TODAY_WAVE("today_wave", "今天页挥手"),
    CHAT_PRONE("chat_prone", "聊天框趴姿"),
    ROOM_IDLE("room_idle", "房间待机"),
    ROOM_SPEAKING("room_speaking", "房间说话"),
    DESK_IDLE("desk_idle", "书桌陪伴"),
    SHELF_IDLE("shelf_idle", "记忆架陪伴"),
    STICKER_SHEET("sticker_sheet", "表情包母版")
}

data class CompanionAssetSpec(
    val role: CompanionAssetRole,
    val width: Int,
    val height: Int,
    val transparentBackground: Boolean,
    val requiredForActivation: Boolean,
    val frameGrid: CompanionFrameGrid? = null
)

data class CompanionFrameGrid(
    val columns: Int,
    val rows: Int
) {
    val frameCount: Int get() = columns * rows
}

object CompanionGenerationPlan {
    val phaseOneAssets: List<CompanionAssetSpec> = listOf(
        CompanionAssetSpec(CompanionAssetRole.IDENTITY_ANCHOR, 1536, 1024, false, true),
        CompanionAssetSpec(CompanionAssetRole.CHAT_PORTRAIT, 1024, 1024, true, true),
        CompanionAssetSpec(
            CompanionAssetRole.TODAY_IDLE, 1536, 1024, true, true,
            frameGrid = CompanionFrameGrid(3, 2)
        ),
        CompanionAssetSpec(
            CompanionAssetRole.TODAY_WAVE, 1536, 1024, true, true,
            frameGrid = CompanionFrameGrid(3, 2)
        ),
        CompanionAssetSpec(
            CompanionAssetRole.CHAT_PRONE, 1536, 1024, true, true,
            frameGrid = CompanionFrameGrid(3, 2)
        ),
        CompanionAssetSpec(CompanionAssetRole.ROOM_IDLE, 1024, 1536, false, true),
        CompanionAssetSpec(CompanionAssetRole.ROOM_SPEAKING, 1024, 1536, false, true),
        CompanionAssetSpec(CompanionAssetRole.DESK_IDLE, 1024, 1536, false, true),
        CompanionAssetSpec(CompanionAssetRole.SHELF_IDLE, 1024, 1536, false, true),
        CompanionAssetSpec(
            CompanionAssetRole.STICKER_SHEET,
            1536,
            1024,
            true,
            false,
            frameGrid = CompanionFrameGrid(3, 3)
        )
    )

    fun anchorPrompt(displayName: String): String {
        val safeName = CompanionProfileRules.normalizeName(displayName).ifBlank { "这个角色" }
        return """
            Create a strict 2D illustrated identity anchor sheet for $safeName from the supplied authorized reference photo
            Preserve the same facial identity, apparent adult age, hairstyle, hair color, eye shape, body proportions and distinguishing features
            Show exactly five clean views on one sheet: front portrait, three-quarter portrait, left profile, neutral full body, warm natural smile
            Use one consistent outfit with simple shapes and no logos
            Premium hand-painted mobile companion aesthetic, warm restrained colors, clean edges, no text, no labels, no borders, no extra people
            The five views must unmistakably depict the same person and must not beautify them into a different identity
        """.trimIndent()
    }

    fun assetPrompt(displayName: String, role: CompanionAssetRole): String {
        val safeName = CompanionProfileRules.normalizeName(displayName).ifBlank { "the companion" }
        val direction = when (role) {
            CompanionAssetRole.CHAT_PORTRAIT -> "calm close portrait, natural expression"
            CompanionAssetRole.TODAY_IDLE -> "a six-frame subtle breathing loop while standing naturally, full body"
            CompanionAssetRole.TODAY_WAVE -> "a six-frame loop that raises one hand, waves once, and returns to rest, full body"
            CompanionAssetRole.CHAT_PRONE -> "a six-frame relaxed loop lying across the top edge of a chat input area, including one natural blink, upper body visible"
            CompanionAssetRole.ROOM_IDLE -> "standing naturally inside a warm quiet bedroom, full body"
            CompanionAssetRole.ROOM_SPEAKING -> "speaking softly with a small hand gesture, full body"
            CompanionAssetRole.DESK_IDLE -> "sitting beside a writing desk, quietly accompanying the user"
            CompanionAssetRole.SHELF_IDLE -> "standing beside a memory shelf, gently looking at a keepsake"
            CompanionAssetRole.STICKER_SHEET -> "nine separated expressive poses: happy, shy, surprised, worried, proud, sleepy, encouraging, teasing, warm"
            CompanionAssetRole.IDENTITY_ANCHOR -> return anchorPrompt(safeName)
        }
        val sceneRule = if (role in setOf(
                CompanionAssetRole.ROOM_IDLE,
                CompanionAssetRole.ROOM_SPEAKING,
                CompanionAssetRole.DESK_IDLE,
                CompanionAssetRole.SHELF_IDLE
            )) {
            "Use the second supplied image as the exact full-screen scene reference. Replace only its existing person with $safeName, preserve the room, furniture, camera, lighting and object positions, and return one opaque full-screen scene"
        } else {
            "Transparent background with clean alpha edges and no black or white halo"
        }
        val sequenceRule = if (role in setOf(
                CompanionAssetRole.TODAY_IDLE,
                CompanionAssetRole.TODAY_WAVE,
                CompanionAssetRole.CHAT_PRONE
            )) {
            "Return exactly one 3 by 2 sprite sheet with six equal cells in reading order. Keep the character at exactly the same scale, anchor point, outfit and identity in every cell. No text, labels, dividers, borders or gutters"
        } else {
            "Return one finished image"
        }
        return """
            Use the supplied approved identity anchor as the only character reference
            Draw $safeName with exactly the same face, hair, apparent adult age and body proportions
            $direction
            Match tiyo's premium warm 2D illustrated style, clean silhouette, restrained detail, no text, no frame, no unrelated objects
            $sceneRule
            $sequenceRule
        """.trimIndent()
    }
}
