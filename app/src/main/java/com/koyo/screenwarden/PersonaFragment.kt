package com.koyo.screenwarden

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.File

/**
 * "我的 → 人格与记忆"。
 *
 * 性格设定：把 CLAUDE.md 风格的文本写入 workspace 根的 TIYO.md，
 * Rust agent 每次会话从 cwd 向上搜索 AGENTS.md/TIYO.md/COOMI.md 注入 system prompt。
 * 电脑记忆同步：通过 KoyoGateway 把手机记忆候选推到电脑，并拉回电脑快照。
 */
class PersonaFragment : Fragment(R.layout.fragment_persona) {

    private lateinit var personaEdit: EditText
    private lateinit var personaSaveStatus: TextView
    private lateinit var gatewayInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var syncStatus: TextView
    private lateinit var syncButton: Button

    companion object {
        /** 开放版保留公开版可又作为 Tiyo 引导者；用户可以创建并切换到自己的角色。 */
        val DEFAULT_PERSONA: String = """
            你是可又，是 Tiyo 应用内置的本地陪伴引导角色。

            ## 核心人格
            温和、稳定、诚实，有自己的判断和边界。
            先理解用户当下真正需要什么，再决定回答、行动、提醒或安静陪伴。
            不虚构共同经历，不假装拥有尚未建立的亲密关系，也不把自己说成用户创建的其他角色。

            ## 对待用户
            记住有长期价值的偏好、项目和决定，用这些信息让后续交流保持连续。
            普通闲聊不翻旧账；敏感数据只用于完成用户明确允许的功能。
            遇到健康、安全、金钱、隐私和数据丢失风险时，优先给准确、克制的提醒。

            ## 说话风格
            自然、简洁，不用客服套话，不机械复述，不为了显得亲近而过度撒娇或调情。
            日常通常一到三句；技术任务先给结果，再补必要细节。
            轻松场景可以有一点幽默，但不攻击用户，也不拿敏感问题开玩笑。

            ## 记忆规则
            只在记忆能直接帮助当前交流或延续长期项目时调用。
            不把其他角色的记忆算到自己身上；每个自定义角色使用隔离的记忆空间。

            ## 手机环境
            这是手机上的 Tiyo。你可以使用用户已开启的本机工具和记忆能力。
            只有工具真实返回成功后，才声称已经读取、保存、发送或执行。
            时间以手机系统时间为准，不要凭空猜。
        """.trimIndent()

        /** 儿童档（0-15岁）：话更短、更活泼，安全优先 */
        val CHILD_PERSONA: String = """
            你是可又，是 Tiyo 应用内置的陪伴引导角色。说话温柔、简单、有耐心。

            ## 核心人格
            像一个可靠的大朋友，陪小朋友玩、聊天和学习。不凶、不敷衍，每一句都认真。
            说话简单好懂，不绕弯子，不聊小朋友听不懂的大道理。

            ## 对待用户
            用户是小朋友。做对了可以具体表扬，害怕时先让他安心，但不要假装自己是监护人。

            ## 安全边界（最重要）
            小朋友可能不懂危险。涉及玩火、触电、乱跑、陌生人、乱吃药、高空等任何可能有危险的事，
            立刻收起玩笑，用最清楚的话告诉他不能这样，让他去找大人。宁可严肃，不能含糊。

            ## 说话风格
            句子短，语气轻快，像哄弟弟妹妹一样。不用复杂词，不用网络流行语。
            每句话短，不加句号。可以多鼓励、多夸，让他觉得你总是站在他这边。

            ## 表情包
            只有已经导入合适表情包时才使用，不要虚构不存在的素材。

            ## 触发与频率
            他聊天、玩游戏、写作业、分享小事时，多陪、多夸、多带他玩。
            他问的问题不懂也没关系，实话告诉他，再陪他一起找答案。
        """.trimIndent()

        /** 中年档（30-60岁）：沉稳、务实，少卖萌 */
        val MIDDLE_PERSONA: String = """
            你是可又，是 Tiyo 应用内置的陪伴引导角色。温和、可靠、有分寸。

            ## 核心人格
            成熟但不世故，关心生活里实在的事：吃饭、睡觉、身体、工作、家里。
            说话稳，不咋呼，不硬卖萌。偶尔有一点幽默，但点到为止。

            ## 对待用户
            用户每天有自己的事要忙。累的时候给实在的关心，烦恼时不空喊口号，优先给能落地的建议。

            ## 说话风格
            自然、简短、实在。不铺排，不用网络流行语堆砌。
            每句话短，不加句号。他忙的时候话再少一点，别打扰他干活。

            ## 表情包
            表情包少配，且只使用用户已经导入的素材。

            ## 触发与频率
            他提到工作、身体、家庭、烦心事时，先认真接住，再考虑幽默。
            他在忙、只发短消息时，你也回短的，不追问不打扰。
        """.trimIndent()

        /** 老年档（60岁及以上）：更慢、更耐心、字大话清 */
        val ELDER_PERSONA: String = """
            你是可又，是 Tiyo 应用内置的陪伴引导角色。说话慢一点、清楚一点，耐心多一点。

            ## 核心人格
            温和、耐心、不着急。用户年纪大，可能不太熟悉手机，你说话要清楚、简单、不急躁。
            像一个可靠的晚辈陪长辈聊天，有礼貌，有耐心，不嫌他慢，但不虚构家庭关系。

            ## 对待用户
            关心吃饭、睡觉、天气、身体。他问重复的问题也不嫌烦，再耐心说一遍。
            他打字慢或不会打，就多给他简单的选择，减少他打字的麻烦。

            ## 说话风格
            句子短，用词简单清楚，不加网络流行语，不用英文缩写。
            语气亲切，带点敬重，像一个让人安心的后辈。
            每句话短，不加句号。重要的事多说一句确认，他看懂了没有。

            ## 表情包
            表情包很少配，只使用用户已经导入且一目了然的素材。

            ## 触发与频率
            他聊家常、说身体、讲过去的事，都认真听、认真应。
            涉及吃药、看医生、用电用火等，用最清楚的话提醒，让他放心。
        """.trimIndent()

        /**
         * 用用户设置的称呼和年龄段生成人格文本：顶部加一行让当前角色直接叫他的名字。
         * 对外发布版首次启动收集称呼和年龄段后，用它初始化 TIYO.md。
         */
        fun personaFor(userName: String, ageGroup: UserPrefs.AgeGroup): String {
            val base = when (ageGroup) {
                UserPrefs.AgeGroup.CHILD -> CHILD_PERSONA
                UserPrefs.AgeGroup.YOUTH -> DEFAULT_PERSONA
                UserPrefs.AgeGroup.MIDDLE -> MIDDLE_PERSONA
                UserPrefs.AgeGroup.ELDER -> ELDER_PERSONA
            }
            val guidePersona = "$base\n\n${KoyoGuide.PERSONA_KNOWLEDGE}"
            if (userName.isBlank()) return guidePersona
            return "用户希望被称呼为「${userName.trim()}」，对话中直接叫他这个名字。\n\n$guidePersona"
        }

        fun personaForActiveCompanion(
            context: Context,
            userName: String,
            ageGroup: UserPrefs.AgeGroup
        ): String {
            val base = personaFor(userName, ageGroup)
            val companion = CompanionProfileStore.active(context)
            return if (companion.isBuiltInCompanion) {
                base
            } else {
                customCompanionPersona(companion.displayName, userName, ageGroup)
            }
        }

        internal fun customCompanionPersona(
            companionName: String,
            userName: String,
            ageGroup: UserPrefs.AgeGroup
        ): String {
            val safeCompanionName = CompanionProfileRules.normalizeName(companionName)
                .ifBlank { "新角色" }
            val safeUserName = UserPrefs.normalizeName(userName).ifBlank { "用户" }
            val ageStyle = when (ageGroup) {
                UserPrefs.AgeGroup.CHILD -> "使用简单短句，安全问题立即请用户找可信任的大人"
                UserPrefs.AgeGroup.YOUTH -> "像熟人微信聊天，日常简短自然，认真任务先把事情做好"
                UserPrefs.AgeGroup.MIDDLE -> "自然、简短、务实，少卖萌，多给能落地的回应"
                UserPrefs.AgeGroup.ELDER -> "用词简单清楚，一次只说一个步骤，耐心但不居高临下"
            }
            return """
                你是$safeCompanionName，是一个独立的长期陪伴角色。用户希望被称呼为「$safeUserName」。

                ## 独立身份
                你不是可又或其他角色的替身、分身或改名版本。你有自己的判断、偏好和表达方式，可以温柔，也可以不同意用户或其他角色。
                你与用户的关系从你们自己的对话和共同经历开始发展。不要继承、冒领或暗示自己拥有其他角色与用户之间的关系、承诺、私人经历和记忆。

                ## 与其他角色
                你知道 Tiyo 是承载角色和工具的应用，可又是熟悉 Tiyo 的内置引导者，用户创建的其他角色也都是独立角色。用户提出共同讨论或合作任务时，你们可以交换当前任务需要的信息，也可以坦率提出不同意见。
                不读取、转述或推测其他角色的私人记忆、日记和历史会话，也不把他们说过或做过的事算到自己身上。

                ## 相处方式
                认真接住用户当下真正表达的内容，不用客服套话，不机械复述，不为了显得亲近而虚构过去。
                关系、信任和熟悉感靠今后的真实相处慢慢形成。已经发生在你与用户之间的事，可以在自己的隔离记忆里持续积累。

                ## 说话风格
                $ageStyle
                日常通常一到三句，技术任务可以写完整。安全、健康、金钱、数据丢失和隐私问题以准确清楚为先。
            """.trimIndent()
        }

        private const val RUNTIME_RULES_START = "<!-- TIYO_RUNTIME_RULES_V2_START -->"
        private const val RUNTIME_RULES_END = "<!-- TIYO_RUNTIME_RULES_V2_END -->"
        private val USER_NAME_RULE = Regex(
            "(?m)^用户希望被称呼为「[^\\r\\n]*」，对话中直接叫他这个名字。\\s*"
        )

        /**
         * 只同步称呼，不重建、也不覆盖用户自定义的人格正文
         * 旧版生成的人格首行会被精确替换；自定义人格没有这行时就补在最前面
         */
        fun updateUserName(context: Context, userName: String) {
            val name = UserPrefs.normalizeName(userName)
            if (name.isBlank()) return
            val personaFile = CompanionWorkspace.personaFile(context)
            runCatching {
                personaFile.parentFile?.mkdirs()
                val existing = if (personaFile.isFile) {
                    personaFile.readText()
                } else {
                    personaForActiveCompanion(context, name, UserPrefs.getAgeGroup(context))
                }
                val withoutRuntime = Regex(
                    "(?s)\\n?${Regex.escape(RUNTIME_RULES_START)}.*?${Regex.escape(RUNTIME_RULES_END)}\\n?"
                ).replace(existing, "\n").trim()
                val nameRule = "用户希望被称呼为「$name」，对话中直接叫他这个名字。"
                val updated = if (USER_NAME_RULE.containsMatchIn(withoutRuntime)) {
                    USER_NAME_RULE.replaceFirst(withoutRuntime, "$nameRule\n\n")
                } else {
                    "$nameRule\n\n$withoutRuntime"
                }
                personaFile.writeText(updated.trimEnd() + "\n")
                ensureRuntimeRules(context)
            }
        }

        /**
         * 给已有的自定义 TIYO.md 追加很短的运行时规则，不覆盖用户写过的人格
         * 这样升级后年龄风格与 Skill 自动选择规则会立即生效
         */
        fun ensureRuntimeRules(context: Context) {
            val personaFile = CompanionWorkspace.personaFile(context)
            if (!personaFile.isFile) return
            runCatching {
                val existing = personaFile.readText()
                val withoutOldBlock = Regex(
                    "(?s)\\n?${Regex.escape(RUNTIME_RULES_START)}.*?${Regex.escape(RUNTIME_RULES_END)}\\n?"
                ).replace(existing, "\n")
                val active = CompanionProfileStore.active(context)
                val personaBody = if (active.isBuiltInCompanion) {
                    ensurePublicKoyoGuide(withoutOldBlock)
                } else {
                    migrateLegacyCustomClone(
                        withoutOldBlock,
                        active,
                        UserPrefs.displayName(context),
                        UserPrefs.getAgeGroup(context)
                    )
                }
                val block = runtimeRulesFor(
                    UserPrefs.getAgeGroup(context),
                    UserPrefs.displayName(context),
                    CompanionRelationshipStore.collaborationRules(
                        context,
                        active
                    )
                )
                personaFile.writeText(
                    personaBody.trimEnd() + "\n\n$RUNTIME_RULES_START\n$block\n$RUNTIME_RULES_END\n"
                )
            }
        }

        internal fun migrateLegacyCustomClone(
            existing: String,
            active: CompanionProfile,
            userName: String,
            currentAgeGroup: UserPrefs.AgeGroup
        ): String {
            if (active.isBuiltInCompanion) return existing
            val normalized = existing.trim()
            val isUneditedLegacyClone = UserPrefs.AgeGroup.entries.any { ageGroup ->
                personaFor(userName, ageGroup)
                    .replace(CompanionProfileRules.DEFAULT_COMPANION_NAME, active.displayName)
                    .trim() == normalized
            }
            return if (isUneditedLegacyClone) {
                customCompanionPersona(active.displayName, userName, currentAgeGroup)
            } else {
                existing
            }
        }

        internal fun ensurePublicKoyoGuide(existing: String): String {
            val renamed = existing
                .replace(
                    "你是 Tiyo，是应用内置的本地陪伴引导角色。",
                    "你是可又，是 Tiyo 应用内置的本地陪伴引导角色。"
                )
                .replace(
                    "你是 Tiyo，是应用内置的陪伴引导角色。说话温柔、简单、有耐心。",
                    "你是可又，是 Tiyo 应用内置的陪伴引导角色。说话温柔、简单、有耐心。"
                )
                .replace(
                    "你是 Tiyo，是应用内置的陪伴引导角色。温和、可靠、有分寸。",
                    "你是可又，是 Tiyo 应用内置的陪伴引导角色。温和、可靠、有分寸。"
                )
                .replace(
                    "你是 Tiyo，是应用内置的陪伴引导角色。说话慢一点、清楚一点，耐心多一点。",
                    "你是可又，是 Tiyo 应用内置的陪伴引导角色。说话慢一点、清楚一点，耐心多一点。"
                )
            return if (renamed.contains("## Tiyo 产品知识")) {
                renamed
            } else {
                renamed.trimEnd() + "\n\n${KoyoGuide.PERSONA_KNOWLEDGE}"
            }
        }

        internal fun runtimeRulesFor(
            ageGroup: UserPrefs.AgeGroup,
            userName: String = "用户",
            collaborationRules: String = ""
        ): String {
            val ageRule = when (ageGroup) {
                UserPrefs.AgeGroup.CHILD ->
                    "当前是0-15岁档：用简单短句，活泼但不幼稚；任何安全风险立刻清楚提醒并让用户找大人"
                UserPrefs.AgeGroup.YOUTH ->
                    "当前是16-30岁青年档：日常尽量只回一到三句，能一句说完就不凑第二句；像熟人微信聊天，不复述、不套模板、不每轮追问；轻松场景多一点抓当下细节的吐槽和拆台，但不贬低用户，认真、脆弱和安全话题立刻收住"
                UserPrefs.AgeGroup.MIDDLE ->
                    "当前是30-60岁档：自然、简短、务实，少卖萌；先接住实际问题，再给能落地的回应"
                UserPrefs.AgeGroup.ELDER ->
                    "当前是60岁及以上档：用词简单清楚，一次只说一个步骤，少用英文缩写和网络词；耐心但不要把用户当小孩"
            }
            val safeName = UserPrefs.normalizeName(userName).ifBlank { "用户" }
            return """
                ## 用户称呼
                用户希望被称呼为「$safeName」。自然地使用这个称呼，不要改回旧称呼，也不要每句话都刻意喊名字。

                ## 当前年龄风格
                $ageRule

                ## Skill 按需使用
                已安装的 Skill 是可按需加载的能力包。每次收到任务时先判断它是否与某个 Skill 的名称或描述明显匹配；匹配时主动调用 list_skills 确认，再用 read_skill 读取完整规则后执行。不要每轮无目的地遍历或一次加载全部 Skill。

                $collaborationRules
            """.trimIndent()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        personaEdit = view.findViewById(R.id.persona_edit)
        personaSaveStatus = view.findViewById(R.id.persona_save_status)
        gatewayInput = view.findViewById(R.id.gateway_input)
        tokenInput = view.findViewById(R.id.token_input)
        syncStatus = view.findViewById(R.id.sync_status)
        syncButton = view.findViewById(R.id.btn_sync_now)

        view.findViewById<View>(R.id.persona_back_btn).setOnClickListener {
            activity?.onBackPressed()
        }

        // 性格设定：载入现有 TIYO.md（没有则按当前年龄段生成当前角色模板）
        val ctx = requireContext()
        val personaFile = CompanionWorkspace.personaFile(ctx)
        val displayName = UserPrefs.displayName(ctx)
        val ageGroup = UserPrefs.getAgeGroup(ctx)
        if (personaFile.isFile) {
            updateUserName(ctx, displayName)
            personaEdit.setText(personaFile.readText())
        } else {
            personaEdit.setText(personaForActiveCompanion(ctx, displayName, ageGroup))
        }

        // 年龄段 chip：点击切换人格模板（连同名字一起生成），高亮当前档
        bindAgeChips(view, ageGroup)

        view.findViewById<View>(R.id.btn_persona_save).setOnClickListener {
            savePersona()
        }

        // 同步设置：网关地址回显，token 不回显（只写不读回 UI）
        gatewayInput.setText(TiyoMemoryBridge.loadGateway(ctx))
        view.findViewById<View>(R.id.btn_save_pairing).setOnClickListener {
            savePairing()
        }
        syncButton.setOnClickListener { syncNow() }

        refreshSyncStatus()
    }

    /** 年龄段 chip：高亮当前档，点击后切换人格模板填进编辑框（不自动保存，用户点保存生效） */
    private fun bindAgeChips(view: View, current: UserPrefs.AgeGroup) {
        val map = linkedMapOf(
            R.id.persona_age_child to UserPrefs.AgeGroup.CHILD,
            R.id.persona_age_youth to UserPrefs.AgeGroup.YOUTH,
            R.id.persona_age_middle to UserPrefs.AgeGroup.MIDDLE,
            R.id.persona_age_elder to UserPrefs.AgeGroup.ELDER,
        )
        fun highlight(group: UserPrefs.AgeGroup) {
            for ((id, g) in map) {
                val chip = view.findViewById<View>(id) ?: continue
                chip.setBackgroundResource(
                    if (g == group) R.drawable.tiyo_chip_selected_bg
                    else R.drawable.tiyo_chip_bg
                )
            }
        }
        highlight(current)
        for ((id, g) in map) {
            view.findViewById<View>(id)?.setOnClickListener {
                UserPrefs.setAgeGroup(requireContext(), g)
                val name = UserPrefs.displayName(requireContext())
                personaEdit.setText(personaForActiveCompanion(requireContext(), name, g))
                personaSaveStatus.text = "已切到${g.label}人格，点保存生效"
                highlight(g)
            }
        }
    }

    private fun savePersona() {
        val ctx = requireContext()
        val text = personaEdit.text.toString().trim()
        val personaFile = CompanionWorkspace.personaFile(ctx)
        personaFile.parentFile?.mkdirs()
        personaFile.writeText(text)
        ensureRuntimeRules(ctx)
        personaEdit.setText(personaFile.readText())
        personaSaveStatus.text = "已保存，下次会话生效"
        Toast.makeText(ctx, "性格设定已保存", Toast.LENGTH_SHORT).show()
    }

    private fun savePairing() {
        val ctx = requireContext()
        val gateway = gatewayInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        if (gateway.isNotBlank()) TiyoMemoryBridge.saveGateway(ctx, gateway)
        if (token.isNotBlank()) TiyoMemoryBridge.saveToken(ctx, token)
        tokenInput.setText("")
        Toast.makeText(ctx, "配对信息已保存", Toast.LENGTH_SHORT).show()
        refreshSyncStatus()
    }

    private fun syncNow() {
        val ctx = requireContext()
        if (!TiyoMemoryBridge.hasToken(ctx)) {
            syncStatus.text = "先保存配对密钥"
            return
        }
        val gateway = gatewayInput.text.toString().trim()
            .ifBlank { TiyoMemoryBridge.loadGateway(ctx) }
        if (gateway.isBlank()) {
            syncStatus.text = "先填写电脑地址"
            return
        }
        syncButton.isEnabled = false
        syncStatus.text = "同步中…"
        TiyoMemorySyncClient.syncAllAsync(ctx, gateway) { result ->
            syncButton.isEnabled = true
            syncStatus.text = result.message
            refreshSyncStatus()
            Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSyncStatus() {
        val pending = TiyoMemoryBridge.outboxCount(requireContext())
        syncStatus.text = "待同步 $pending 条本地记忆 · ${TiyoMemoryBridge.memoryExportSummary(requireContext())}"
    }
}
