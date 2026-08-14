package com.koyo.screenwarden

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.os.Bundle
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.SwitchCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatFragment : Fragment(R.layout.fragment_chat), TiyoAgentClient.Listener {

    private data class ChatMessage(
        val role: String,
        val text: String,
        val timestamp: Long,
        val sticker: String? = null
    )

    private data class VoiceOption(
        val id: String,
        val label: String
    )

    private data class DirectReply(
        val text: String,
        val gatewayUrl: String
    )

    private enum class AttachmentKind { IMAGE, FILE }

    /** 只在发送前存在，不进入 TiyoSessionStore 历史结构 */
    private data class PendingAttachment(
        val kind: AttachmentKind,
        val name: String,
        val imageBase64: String? = null,
        val workspacePath: String? = null
    )

    private lateinit var messagesContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var micButton: TextView
    private lateinit var stopAudioButton: TextView
    private lateinit var voiceStyleButton: TextView
    private lateinit var voiceToggleButton: TextView
    private lateinit var voiceSwitch: SwitchCompat
    private lateinit var carChatSwitch: SwitchCompat
    private lateinit var routeHint: TextView
    private lateinit var statusText: TextView
    private lateinit var modelNameText: TextView
    private lateinit var contextMeter: TextView
    private lateinit var statusDot: View
    private lateinit var agentSettingsButton: TextView
    private lateinit var planButton: TextView
    private lateinit var sessionsButton: TextView
    private lateinit var drawer: View
    private lateinit var drawerScrim: View
    private lateinit var sessionList: LinearLayout
    private lateinit var userNameButton: TextView
    private lateinit var attachmentActionMenu: View
    private lateinit var attachmentStrip: View
    private lateinit var attachmentList: LinearLayout
    private lateinit var attachmentHint: TextView
    private lateinit var focusModeContainer: View
    private lateinit var roomModeContainer: FrameLayout
    private lateinit var deskModeContainer: FrameLayout
    private var roomModeController: RoomModeController? = null
    private var deskModeController: DeskModeController? = null
    private var memoryShelfView: MemoryShelfView? = null
    private var journalView: DiaryBookView? = null
    // 普通聊天固定使用已验收的 16:14 界面；深度陪伴由 DeepCompanionController 独立承载
    private var chatMode = ChatModeManager.Mode.FOCUS

    private val messages = mutableListOf<ChatMessage>()
    private val voiceOptions = mutableListOf(
        VoiceOption("furina", "芙宁娜"),
        VoiceOption("sora", "春日野穹")
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var typingView: View? = null
    private var requestInProgress = false
    private var statusRequestInProgress = false
    private var voiceRequestInProgress = false
    private var selectedVoiceId = "edge"
    private var lastFailedText: String? = null
    private var lastFailedImages: List<String>? = null
    private var lastAutoResetAt = 0L
    private var replyRevealRunnable: Runnable? = null
    private var replyRevealTextView: TextView? = null
    private var replyRevealFullText: String? = null
    private var replyPlayer: MediaPlayer? = null
    private var replyAudioFile: File? = null
    private var phoneAudioRequestId = 0L
    private var lastAssistantRow: View? = null
    private val maxSentenceChars = 60
    private val maxSentenceCount = 12
    /** 可又本体在 Activity 上,这里只是取个引用用来切说话状态 */
    private val koyoDock: com.koyo.screenwarden.live2d.KoyoDock?
        get() = (activity as? MainActivity)?.koyo
    private var agentClient: TiyoAgentClient? = null
    private var currentTaskCard: TiyoTaskCard? = null
    private var agentReplyRow: View? = null
    private var agentReplyText: TextView? = null
    private val agentReplyBuffer = StringBuilder()
    private var pendingAgentText: String? = null
    private var pendingAgentImages: List<String>? = null
    private var pendingFileRequestId: String? = null
    private var pendingExportRequestId: String? = null
    private var pendingExportPath: String? = null
    private var lastAgentReply = ""
    private var activeSessionId = ""
    private lateinit var companionScope: CompanionScope
    private var planMode = false
    private var seenProactiveVersion = -1
    private var drawerOpen = false
    private var drawerBackCallback: OnBackPressedCallback? = null
    private var sceneBackCallback: OnBackPressedCallback? = null
    private val pendingAttachments = mutableListOf<PendingAttachment>()
    private var offlineSpeech: OfflineSpeechController? = null
    private var speechListening = false
    private var speechBaseText = ""

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.CHINA)

    private val statusPoller = object : Runnable {
        override fun run() {
            if (isAdded && !isHidden && view != null) refreshStatus()
            mainHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    /** 相册选图 → base64 → 菜单（识图 / 改图） */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) handlePickedUris(uris)
    }

    /** 拍照 → base64 → 菜单（识图 / 改图） */
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) handlePickedBitmap(bitmap)
    }

    /** 用户主动选择聊天附件；与 Agent 发起的 file_transfer_request 分开 */
    private val chatFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) queuePickedFiles(uris)
    }

    /** 改图模式：暂存待改的图（纯 base64，不含 data: 前缀） */
    private var pendingEditImage: String? = null
    private var editHint: TextView? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchVoiceRecognizer()
        } else {
            addSystemMessage("需要麦克风权限才能使用语音输入")
        }
    }

    private val agentFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val requestId = pendingFileRequestId
        pendingFileRequestId = null
        if (requestId == null) return@registerForActivityResult
        val workspace = TiyoAgentRuntime.currentInfo(companionScope)?.workspace
        if (workspace == null) {
            agentClient?.completeFileTransfer(requestId, emptyList())
            addSystemMessage("Tiyo Agent 的工作目录暂时不可用")
            return@registerForActivityResult
        }
        val inbox = TiyoWorkspace.inbox(requireContext())
        val imported = uris.mapNotNull { uri ->
            runCatching {
                val name = requireContext().contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.takeIf { it.isNotBlank() } ?: "import-${System.currentTimeMillis()}"
                val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                val target = uniqueTarget(inbox, safeName)
                requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    target.outputStream().use(inputStream::copyTo)
                } ?: error("无法读取文件")
                target.absolutePath
            }.getOrNull()
        }
        agentClient?.completeFileTransfer(requestId, imported)
        addSystemMessage(
            if (imported.isEmpty()) "没有选择文件"
            else "已把 ${imported.size} 个文件交给 Tiyo Agent"
        )
    }

    private val agentFileExporter = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val requestId = pendingExportRequestId
        val sourcePath = pendingExportPath
        pendingExportRequestId = null
        pendingExportPath = null
        if (requestId == null || sourcePath == null) return@registerForActivityResult
        val targetUri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || targetUri == null) {
            agentClient?.completeFileTransfer(requestId, emptyList())
            addSystemMessage("已取消导出")
            return@registerForActivityResult
        }
        Thread {
            val exported = runCatching {
                val source = File(sourcePath)
                require(source.isFile) { "要导出的文件已经不存在" }
                source.inputStream().use { inputStream ->
                    requireContext().contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                        inputStream.copyTo(output, 128 * 1024)
                    } ?: error("无法写入选择的位置")
                }
                source.absolutePath
            }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                exported.fold(
                    onSuccess = { path ->
                        agentClient?.completeFileTransfer(requestId, listOf(path))
                        addSystemMessage("文件已经导出到你选择的位置")
                    },
                    onFailure = { error ->
                        agentClient?.completeFileTransfer(requestId, emptyList())
                        addSystemMessage(error.message ?: "文件导出失败")
                    }
                )
            }
        }.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        companionScope = CompanionScope.capture(requireContext())

        messagesContainer = view.findViewById(R.id.chat_messages)
        chatScroll = view.findViewById(R.id.chat_scroll)
        // 用户触摸滚动时标记 userScrolling；滚动位置变化时更新是否在底部附近
        chatScroll.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> userScrolling = true
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    userScrolling = true
                    chatScroll.postDelayed({ userScrolling = false }, 500)
                }
            }
            false
        }
        chatScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            userNearBottom = isNearBottom()
        }
        input = view.findViewById(R.id.chat_input)
        sendButton = view.findViewById(R.id.btn_chat_send)
        micButton = view.findViewById(R.id.btn_chat_mic)
        view.findViewById<View>(R.id.btn_chat_image).setOnClickListener {
            closeAttachmentMenu()
            showImageSourceMenu()
        }
        view.findViewById<View>(R.id.btn_chat_camera).setOnClickListener {
            cameraLauncher.launch(null)
        }
        view.findViewById<View>(R.id.btn_chat_file).setOnClickListener {
            closeAttachmentMenu()
            chatFilePicker.launch(arrayOf("*/*"))
        }
        editHint = view.findViewById(R.id.chat_edit_hint)
        stopAudioButton = view.findViewById(R.id.btn_chat_stop_audio)
        voiceStyleButton = view.findViewById(R.id.btn_chat_voice_style)
        voiceToggleButton = view.findViewById(R.id.btn_chat_voice_toggle)
        voiceSwitch = view.findViewById(R.id.switch_chat_voice)
        carChatSwitch = view.findViewById(R.id.switch_chat_car)
        routeHint = view.findViewById(R.id.chat_route_hint)
        statusText = view.findViewById(R.id.chat_status)
        modelNameText = view.findViewById(R.id.chat_model_name)
        contextMeter = view.findViewById(R.id.chat_context_meter)
        statusDot = view.findViewById(R.id.chat_status_dot)
        agentSettingsButton = view.findViewById(R.id.btn_agent_settings)
        planButton = view.findViewById(R.id.btn_chat_plan)
        sessionsButton = view.findViewById(R.id.btn_chat_sessions)
        drawer = view.findViewById(R.id.chat_drawer)
        drawerScrim = view.findViewById(R.id.chat_drawer_scrim)
        sessionList = view.findViewById(R.id.chat_session_list)
        userNameButton = view.findViewById(R.id.btn_drawer_user_name)
        attachmentActionMenu = view.findViewById(R.id.chat_attachment_action_menu)
        attachmentStrip = view.findViewById(R.id.chat_attachment_strip)
        attachmentList = view.findViewById(R.id.chat_attachment_list)
        attachmentHint = view.findViewById(R.id.chat_attachment_hint)

        // 专注档顶栏只保留可又头像，头像本身就是会话侧栏入口
        bindAvatar(view.findViewById(R.id.chat_header_avatar), "assistant")
        view.findViewById<View>(R.id.btn_chat_drawer).setOnClickListener { openFocusDrawer() }

        agentClient = TiyoAgentClient(this)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        activeSessionId = TiyoSessionStore.activeId(
            requireContext(),
            companionScope,
            prefs.getString(KEY_AGENT_SESSION, null)
        )
        TiyoSessionStore.migrateLegacyHistory(
            requireContext(),
            companionScope,
            activeSessionId,
            prefs.getString(KEY_HISTORY, null)
        )
        setupFocusDrawer(view)
        setupAttachmentComposer(view)
        // 不挂载昨日未通过验收的普通房间/书桌层
        refreshTopBar()
        planMode = prefs.getBoolean(KEY_PLAN_MODE, false)
        selectedVoiceId = prefs.getString(companionScope.namespaced(KEY_TTS_VOICE), "furina").orEmpty()
            .takeIf { saved -> voiceOptions.any { it.id == saved } }
            ?: "furina"
        updateVoiceStyleButton()
        scanMiniMaxVoices()
        val voiceEnabledKey = companionScope.namespaced(KEY_VOICE_ENABLED)
        voiceSwitch.isChecked = prefs.getBoolean(voiceEnabledKey, true)
        voiceSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(voiceEnabledKey, enabled).apply()
            if (!enabled) stopReplyPlayback()
            updateVoiceToggleButton()
            val name = CompanionProfileStore.activeName(requireContext())
            addSystemMessage(if (enabled) "${name}会用声音回答" else "已关闭${name}的语音回答")
        }
        voiceToggleButton.setOnClickListener {
            val next = !prefs.getBoolean(voiceEnabledKey, true)
            voiceSwitch.isChecked = next
            updateVoiceToggleButton()
        }
        updateVoiceToggleButton()
        val carChatKey = companionScope.namespaced(KEY_CAR_CHAT_ENABLED)
        carChatSwitch.isChecked = companionScope.isBuiltInCompanion &&
            prefs.getBoolean(carChatKey, false)
        carChatSwitch.isEnabled = companionScope.isBuiltInCompanion
        carChatSwitch.alpha = if (companionScope.isBuiltInCompanion) 1f else 0.45f
        updateRouteHint()
        carChatSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(carChatKey, enabled).apply()
            stopReplyPlayback()
            updateRouteHint()
            addSystemMessage(
                if (enabled) "接下来的对话会交给小车，小车会播放我的回答"
                else "已切回手机对话，小车不会收到接下来的消息"
            )
            if (!enabled && TiyoAgentConfig.isConfigured(requireContext())) connectAgent()
            refreshStatus()
        }

        loadHistory()
        renderHistory()
        // 主动消息：记录当前版本，打开聊天视为已读（重置连续未回复计数）
        seenProactiveVersion = ProactiveMessenger.version(requireContext())
        ProactiveMessenger.onChatOpened(requireContext())

        sendButton.setOnClickListener { sendCurrentMessage() }
        micButton.setOnClickListener { beginVoiceInput() }
        stopAudioButton.setOnClickListener { stopAudio() }
        voiceStyleButton.setOnClickListener { cycleVoiceStyle() }
        agentSettingsButton.setOnClickListener { showFocusSettings() }
        planButton.setOnClickListener { togglePlanMode() }
        sessionsButton.setOnClickListener { openFocusDrawer() }
        view.findViewById<TextView>(R.id.btn_chat_workspace).setOnClickListener {
            closeAttachmentMenu()
            (activity as? MainActivity)?.openFilesWorkspace()
        }
        updatePlanButton()
        view.findViewById<TextView>(R.id.quick_photo).setOnClickListener {
            closeQuickPanel(view)
            takePhoto()
        }
        view.findViewById<TextView>(R.id.quick_doing).setOnClickListener {
            sendQuickMessage(view, "你在干嘛？")
        }
        view.findViewById<TextView>(R.id.quick_story).setOnClickListener {
            sendQuickMessage(view, "给我讲一个短一点的故事吧")
        }
        view.findViewById<TextView>(R.id.quick_happy).setOnClickListener {
            sendQuickMessage(view, "${CompanionProfileStore.activeName(requireContext())}，开心一点")
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }
        input.addTextChangedListener(SimpleTextWatcher {
            updateComposerState()
        })
        updateComposerState()
        startStatusPolling()
        refreshVoiceStyle()
        applyChatTheme()
        if (TiyoAgentConfig.isConfigured(requireContext())) {
            connectAgent()
        }
        setupLive2D(view)
    }

    override fun onResume() {
        super.onResume()
        applyChatTheme()
        if (::modelNameText.isInitialized) refreshTopBar()
        if (!isHidden) {
            ProactivePresence.setChatVisible(true)
            startStatusPolling()
            refreshProactiveBubbles()
        }
        // 可又的启停由 MainActivity 的 dock 按当前页面统一管,这里不插手
    }

    /** 把选中主题应用到聊天页根背景（view 未创建时安全跳过） */
    fun applyChatTheme() {
        if (!isAdded || view == null) return
        if (chatMode == ChatModeManager.Mode.ROOM) {
            roomModeController?.refreshTheme()
        } else {
            view?.background = ThemeManager.buildChatBackground(requireContext())
        }
    }

    override fun onPause() {
        super.onPause()
        ProactivePresence.setChatVisible(false)
        mainHandler.removeCallbacks(statusPoller)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            ProactivePresence.setChatVisible(false)
            mainHandler.removeCallbacks(statusPoller)
        } else {
            ProactivePresence.setChatVisible(true)
            // 从其他 tab 切回聊天：打开视为已读 + 刷新 Worker 在后台落下的主动消息气泡
            ProactiveMessenger.onChatOpened(requireContext())
            refreshProactiveBubbles()
            startStatusPolling()
            scrollToBottom()
            applyChatMode(animate = false)
            view?.let { reportKoyoInset(it) }
        }
    }

    override fun onDestroyView() {
        ProactivePresence.setChatVisible(false)
        mainHandler.removeCallbacks(statusPoller)
        replyRevealRunnable?.let { mainHandler.removeCallbacks(it) }
        replyRevealRunnable = null
        replyRevealTextView = null
        replyRevealFullText = null
        stopReplyPlayback()
        // dock 是 Activity 的,不在这里释放
        agentClient?.close()
        agentClient = null
        currentTaskCard = null
        agentReplyRow = null
        agentReplyText = null
        agentReplyBuffer.clear()
        typingView = null
        pendingAttachments.clear()
        roomModeController?.release()
        roomModeController = null
        deskModeController = null
        memoryShelfView = null
        journalView = null
        offlineSpeech?.shutdown()
        offlineSpeech = null
        speechListening = false
        drawerOpen = false
        drawerBackCallback = null
        sceneBackCallback = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CHAT_MODE_STATE, chatMode.storedValue)
        super.onSaveInstanceState(outState)
    }

    internal fun openImagePickerFromDeepMode(): Boolean {
        if (!isAdded) return false
        showImageSourceMenu()
        return true
    }

    internal fun openFilePickerFromDeepMode(): Boolean {
        if (!isAdded) return false
        chatFilePicker.launch(arrayOf("*/*"))
        return true
    }

    internal fun pendingAttachmentNamesForDeepMode(): List<String> =
        pendingAttachments.map { it.name }

    internal fun clearPendingAttachmentsFromDeepMode(): Boolean {
        if (pendingAttachments.isEmpty()) return false
        pendingAttachments.clear()
        renderPendingAttachments()
        return true
    }

    internal fun appendLocalAssistantMessageFromDeepMode(text: String, sticker: String? = null): Boolean {
        if (!isAdded || view == null || text.isBlank()) return false
        val payload = if (sticker.isNullOrBlank()) text else "$text {sticker:$sticker}"
        showAssistantReply(payload)
        scrollToBottom()
        return true
    }

    private fun switchChatMode(mode: ChatModeManager.Mode) {
        if (chatMode == mode) return
        if (drawerOpen) closeFocusDrawer()
        chatMode = mode
        ChatModeManager.setCurrent(requireContext(), mode)
        hideKeyboard()
        applyChatMode(animate = true)
    }

    private fun applyChatMode(animate: Boolean) {
        if (!::focusModeContainer.isInitialized) return
        focusModeContainer.visibility = if (chatMode == ChatModeManager.Mode.FOCUS) View.VISIBLE else View.GONE
        roomModeContainer.visibility = if (chatMode == ChatModeManager.Mode.ROOM) View.VISIBLE else View.GONE
        deskModeContainer.visibility = if (chatMode == ChatModeManager.Mode.DESK) View.VISIBLE else View.GONE
        val slot = when (chatMode) {
            ChatModeManager.Mode.ROOM -> roomModeController?.heroSlot
            ChatModeManager.Mode.DESK -> deskModeController?.heroSlot
            ChatModeManager.Mode.FOCUS -> null
        }
        koyoDock?.setHeroSlot(slot)
        if (chatMode == ChatModeManager.Mode.ROOM) roomModeController?.render(modeLines())
        if (chatMode == ChatModeManager.Mode.DESK) deskModeController?.render(modeLines())
        (activity as? MainActivity)?.onChatModeChanged(animate)
        if (chatMode == ChatModeManager.Mode.FOCUS) view?.let { reportKoyoInset(it) }
    }

    fun isFocusMode(): Boolean = chatMode == ChatModeManager.Mode.FOCUS

    fun preferredKoyoState(drawerOpen: Boolean): com.koyo.screenwarden.live2d.KoyoDock.State {
        if (drawerOpen) return com.koyo.screenwarden.live2d.KoyoDock.State.EDGE
        return when (chatMode) {
            ChatModeManager.Mode.ROOM -> com.koyo.screenwarden.live2d.KoyoDock.State.ROOM
            ChatModeManager.Mode.DESK -> com.koyo.screenwarden.live2d.KoyoDock.State.DESK
            ChatModeManager.Mode.FOCUS -> com.koyo.screenwarden.live2d.KoyoDock.State.CHAT
        }
    }

    fun onKoyoTappedInChat() {
        when (chatMode) {
            ChatModeManager.Mode.ROOM -> roomModeController?.revealInput()
            ChatModeManager.Mode.DESK -> Unit
            ChatModeManager.Mode.FOCUS -> input.requestFocus()
        }
    }

    private fun showMemoryShelf() {
        val root = view as? FrameLayout ?: return
        if (memoryShelfView != null) return
        val shelf = MemoryShelfView(
            requireContext(),
            isDrowsy = { koyoDock?.isDrowsy() == true },
            onClose = { closeMemoryShelf() },
            onOpenJournal = { showJournal() }
        )
        memoryShelfView = shelf
        sceneBackCallback?.isEnabled = true
        root.addView(shelf, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        shelf.alpha = 0f
        shelf.animate().alpha(1f).setDuration(260L).start()
    }

    private fun closeMemoryShelf() {
        val shelf = memoryShelfView ?: return
        shelf.animate().alpha(0f).setDuration(200L).withEndAction {
            (shelf.parent as? ViewGroup)?.removeView(shelf)
            if (memoryShelfView === shelf) memoryShelfView = null
            sceneBackCallback?.isEnabled = journalView != null
        }.start()
    }

    private fun showJournal() {
        if (MemoryShelfStore.journalEntries(requireContext()).isEmpty()) return
        val root = view as? FrameLayout ?: return
        if (journalView != null) return
        val journal = DiaryBookView(requireContext()) { closeJournal() }
        journalView = journal
        sceneBackCallback?.isEnabled = true
        koyoDock?.goto(com.koyo.screenwarden.live2d.KoyoDock.State.HIDDEN, animate = true)
        root.addView(journal, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        journal.alpha = 0f
        journal.scaleX = .97f
        journal.scaleY = .97f
        journal.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).start()
    }

    private fun closeJournal() {
        val journal = journalView ?: return
        journal.animate().alpha(0f).scaleX(.98f).scaleY(.98f).setDuration(180L).withEndAction {
            (journal.parent as? ViewGroup)?.removeView(journal)
            if (journalView === journal) journalView = null
            sceneBackCallback?.isEnabled = memoryShelfView != null
            (activity as? MainActivity)?.onChatModeChanged(animate = true)
        }.start()
    }

    private fun modeLines(): List<ChatModeLine> = messages.map {
        ChatModeLine(it.role, it.text, it.timestamp)
    }

    private fun deliverModeMessage(message: ChatMessage) {
        val line = ChatModeLine(message.role, message.text, message.timestamp)
        when (chatMode) {
            ChatModeManager.Mode.ROOM -> roomModeController?.onMessage(line)
            ChatModeManager.Mode.DESK -> deskModeController?.onMessage(line)
            ChatModeManager.Mode.FOCUS -> Unit
        }
    }

    private fun sendModeMessage(text: String) {
        if (pendingAttachments.isNotEmpty()) sendPendingAttachments(text) else sendMessage(text)
    }

    /**
     * 可又本体在 MainActivity 的 KoyoDock 里,聊天页只做两件事:
     * 1. 告诉她输入区有多高,让她正好趴在输入框上沿
     * 2. 把收起按钮接到 dock 上
     */
    private fun setupLive2D(view: View) {
        val collapseButton = view.findViewById<TextView>(R.id.btn_live2d_collapse)
        fun syncCollapseText() {
            val collapsed = (activity as? MainActivity)?.isKoyoCollapsed() ?: false
            collapseButton.text = getString(
                if (collapsed) R.string.live2d_expand else R.string.live2d_collapse
            )
        }
        syncCollapseText()
        collapseButton.setOnClickListener {
            (activity as? MainActivity)?.toggleKoyoCollapsed()
            syncCollapseText()
        }
        reportKoyoInset(view)
        setupQuickPanel(view)
    }

    private fun setupFocusDrawer(view: View) {
        view.findViewById<View>(R.id.btn_chat_new_session).setOnClickListener {
            if (requestInProgress) {
                addSystemMessage("当前任务结束后再新建会话")
            } else {
                createSession()
                closeFocusDrawer()
            }
        }
        view.findViewById<View>(R.id.btn_drawer_today).setOnClickListener {
            closeFocusDrawer()
            (activity as? MainActivity)?.openToday()
        }
        view.findViewById<View>(R.id.btn_drawer_workspace).setOnClickListener {
            closeFocusDrawer()
            (activity as? MainActivity)?.openFilesWorkspace()
        }
        view.findViewById<View>(R.id.btn_drawer_peripherals).setOnClickListener {
            closeFocusDrawer()
            (activity as? MainActivity)?.openPeripherals()
        }
        view.findViewById<View>(R.id.btn_drawer_me).setOnClickListener {
            closeFocusDrawer()
            (activity as? MainActivity)?.openMe()
        }
        view.findViewById<View>(R.id.btn_drawer_mcp).setOnClickListener {
            closeFocusDrawer()
            (activity as? MainActivity)?.openOverlay(McpSettingsFragment())
        }
        view.findViewById<View>(R.id.btn_drawer_skills).setOnClickListener {
            closeFocusDrawer()
            (activity as? MainActivity)?.openOverlay(SkillSettingsFragment())
        }
        view.findViewById<View>(R.id.btn_drawer_agent_mail).setOnClickListener {
            closeFocusDrawer()
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://agent.qq.com")))
            }.onFailure {
                Toast.makeText(requireContext(), "没有找到可打开网页的应用", Toast.LENGTH_SHORT).show()
            }
        }
        refreshUserNameEntry()
        userNameButton.setOnClickListener {
            closeFocusDrawer()
            userNameButton.postDelayed({
                if (!isAdded) return@postDelayed
                UserNameDialog(requireContext()) { name ->
                    UserPrefs.setName(requireContext(), name)
                    PersonaFragment.updateUserName(requireContext(), name)
                    refreshUserNameEntry()
                    (activity as? MainActivity)?.onUserNameChanged()
                    Toast.makeText(
                        requireContext(),
                        "以后就这样叫你，今天页已经换好了",
                        Toast.LENGTH_SHORT
                    ).show()
                }.show()
            }, 180L)
        }
        drawerScrim.setOnClickListener { closeFocusDrawer() }

        val defaultFocus = view.findViewById<SwitchCompat>(R.id.switch_default_focus)
        defaultFocus.isChecked = ChatModeManager.isDefaultFocus(requireContext())
        defaultFocus.setOnCheckedChangeListener { _, enabled ->
            ChatModeManager.setDefaultFocus(requireContext(), enabled)
            Toast.makeText(
                requireContext(),
                if (enabled) "以后默认进入专注模式" else "以后默认进入房间模式",
                Toast.LENGTH_SHORT
            ).show()
        }

        drawerBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeFocusDrawer()
            }
        }.also { callback ->
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        }
        renderSessionDrawer()
    }

    /** 供保存称呼和页面恢复时刷新侧栏文案。 */
    fun refreshUserNameEntry() {
        if (!::userNameButton.isInitialized) return
        userNameButton.text = "${CompanionProfileStore.activeName(requireContext())}怎么叫我  ·  ${UserPrefs.displayName(requireContext())}"
    }

    private fun openFocusDrawer() {
        if (!::drawer.isInitialized || drawerOpen) return
        renderSessionDrawer()
        drawerOpen = true
        (activity as? MainActivity)?.setChatDrawerOpen(true)
        drawerBackCallback?.isEnabled = true
        drawerScrim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawerScrim.animate().cancel()
        drawer.animate().cancel()
        drawerScrim.alpha = 0f
        drawer.post {
            drawer.translationX = -drawer.width.toFloat()
            drawer.animate()
                .translationX(0f)
                .setDuration(180L)
                .start()
            drawerScrim.animate()
                .alpha(0.34f)
                .setDuration(180L)
                .start()
        }
    }

    private fun closeFocusDrawer() {
        if (!::drawer.isInitialized || !drawerOpen) return
        drawerOpen = false
        (activity as? MainActivity)?.setChatDrawerOpen(false)
        drawerBackCallback?.isEnabled = false
        drawerScrim.animate().cancel()
        drawer.animate().cancel()
        drawerScrim.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction { drawerScrim.visibility = View.GONE }
            .start()
        drawer.animate()
            .translationX(-drawer.width.toFloat())
            .setDuration(170L)
            .withEndAction {
                drawer.visibility = View.GONE
                drawer.translationX = 0f
            }
            .start()
    }

    fun closeDrawerFromKoyo() {
        closeFocusDrawer()
    }

    /** registry_v1 已由 TiyoSessionStore 保证置顶优先、更新时间倒序 */
    private fun renderSessionDrawer() {
        if (!::sessionList.isInitialized) return
        sessionList.removeAllViews()
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        TiyoSessionStore.sessions(requireContext(), companionScope).forEach { session ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(8), dp(7))
                setBackgroundResource(R.drawable.chat_focus_drawer_item_bg)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(62)
                ).apply { bottomMargin = dp(2) }
                setOnClickListener {
                    if (requestInProgress) {
                        addSystemMessage("当前任务结束后再切换会话，执行过程不会被丢掉")
                    } else {
                        openSession(session.id)
                        closeFocusDrawer()
                    }
                }
                setOnLongClickListener {
                    showSessionActions(session)
                    true
                }
            }
            val labels = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            val title = TextView(requireContext()).apply {
                text = session.title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 14f
                setTextColor(
                    requireContext().getColor(
                        R.color.d_focus_ink
                    )
                )
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    if (session.id == activeSessionId) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL
                )
            }
            labels.addView(title)
            labels.addView(TextView(requireContext()).apply {
                text = formatter.format(Date(session.updatedAt))
                textSize = 11f
                setTextColor(requireContext().getColor(R.color.d_focus_ink_3))
            })
            row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (session.pinned) {
                row.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_focus_pin)
                    contentDescription = "已置顶"
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
            }
            sessionList.addView(row)
        }
    }

    private fun showSessionActions(session: TiyoChatSession) {
        val actions = arrayOf(if (session.pinned) "取消置顶" else "置顶", "删除")
        AlertDialog.Builder(requireContext())
            .setTitle(session.title)
            .setItems(actions) { _, action ->
                if (action == 0) {
                    TiyoSessionStore.setPinned(requireContext(), companionScope, session.id, !session.pinned)
                    renderSessionDrawer()
                } else if (session.id == activeSessionId) {
                    addSystemMessage("正在使用的会话不能删除，先切换到另一条会话")
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("删除这条会话")
                        .setMessage("本机聊天记录会一并删除")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                    TiyoSessionStore.delete(requireContext(), companionScope, session.id)
                            renderSessionDrawer()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun showFocusSettings() {
        closeFocusDrawer()
        AlertDialog.Builder(requireContext())
            .setTitle("聊天设置")
            .setItems(arrayOf("Agent 设置", "聊天头像", "聊天背景")) { _, index ->
                when (index) {
                    0 -> showAgentSettings()
                    1 -> (activity as? MainActivity)?.openOverlay(AvatarSettingsFragment())
                    2 -> (activity as? MainActivity)?.openOverlay(ThemeFragment())
                }
            }
            .show()
    }

    private fun setupAttachmentComposer(view: View) {
        view.findViewById<View>(R.id.btn_chat_attach).setOnClickListener {
            val opening = attachmentActionMenu.visibility != View.VISIBLE
            view.findViewById<View>(R.id.quick_panel)?.visibility = View.GONE
            attachmentActionMenu.visibility = if (opening) View.VISIBLE else View.GONE
            it.alpha = if (opening) 1f else 0.76f
            attachmentActionMenu.post { pushKoyoInset(view) }
        }
        input.setOnFocusChangeListener { _, focused ->
            if (focused) closeAttachmentMenu()
        }
        renderPendingAttachments()
    }

    private fun closeAttachmentMenu() {
        if (!::attachmentActionMenu.isInitialized) return
        attachmentActionMenu.visibility = View.GONE
        view?.findViewById<View>(R.id.btn_chat_attach)?.alpha = 0.76f
        view?.let(::pushKoyoInset)
    }

    private fun showImageSourceMenu() {
        AlertDialog.Builder(requireContext())
            .setTitle("添加图片")
            .setItems(arrayOf("从相册选择", "拍照")) { _, index ->
        if (index == 0) {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } else {
            cameraLauncher.launch(null)
        }
            }
            .show()
    }

    /** 快捷指令从输入区图标旁横向浮出 */
    private fun setupQuickPanel(view: View) {
        val panel = view.findViewById<View>(R.id.quick_panel) ?: return
        val toggle = view.findViewById<TextView>(R.id.btn_quick_toggle) ?: return
        renderCustomQuick(view)
        view.findViewById<TextView>(R.id.btn_quick_custom)?.setOnClickListener {
            promptCustomQuick(view)
        }
        toggle.setOnClickListener {
            val opening = panel.visibility != View.VISIBLE
            closeAttachmentMenu()
            panel.visibility = if (opening) View.VISIBLE else View.GONE
            toggle.contentDescription = if (opening) "收起快捷指令" else "快捷指令"
            toggle.alpha = if (opening) 1f else 0.76f
            // 面板占了高度,她得往上让一让。
            // 不能重调 reportKoyoInset,那会每按一次多挂一个布局监听
            panel.post { pushKoyoInset(view) }
        }
    }

    /** 读出用户自己存的快捷键,格式 [{n:名字, t:发送内容}] */
    private fun customQuick(): JSONArray {
        val raw = requireContext().getSharedPreferences(PREFS_NAME, 0)
            .getString(KEY_QUICK_CUSTOM, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun saveCustomQuick(arr: JSONArray) {
        requireContext().getSharedPreferences(PREFS_NAME, 0).edit()
            .putString(KEY_QUICK_CUSTOM, arr.toString()).apply()
    }

    /** 自定义快捷键跟内置指令一起横向排列，长按删除 */
    private fun renderCustomQuick(view: View) {
        val box = view.findViewById<LinearLayout>(R.id.quick_custom_box) ?: return
        box.removeAllViews()
        val arr = customQuick()
        val dp = resources.displayMetrics.density
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("n").trim()
            if (name.isEmpty()) continue
            val text = o.optString("t").trim().ifEmpty { name }
            val chip = TextView(requireContext(), null, 0, R.style.dChip).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (48 * dp).toInt()
                ).also { it.marginStart = (2 * dp).toInt() }
                setBackgroundResource(R.drawable.chat_focus_drawer_item_bg)
                setText(name)
                gravity = Gravity.CENTER
                setOnClickListener { sendQuickMessage(view, text) }
                setOnLongClickListener {
                    removeCustomQuick(view, i)
                    true
                }
            }
            box.addView(chip)
        }
    }

    private fun removeCustomQuick(view: View, index: Int) {
        val arr = customQuick()
        val kept = JSONArray()
        for (i in 0 until arr.length()) if (i != index) kept.put(arr.opt(i))
        saveCustomQuick(kept)
        renderCustomQuick(view)
        view.findViewById<View>(R.id.quick_panel)?.post { pushKoyoInset(view) }
    }

    /**
     * 快捷指令会把焦点留在面板按钮上。消息区重新布局时 ScrollView 会尝试把
     * 这个焦点带回可见范围，长对话里就可能跳到第一条用户消息。先收起面板、
     * 清掉按钮焦点，再用明确坐标滚到底部，不让焦点参与滚动定位。
     */
    private fun sendQuickMessage(view: View, text: String) {
        if (requestInProgress) return
        closeQuickPanel(view)
        userScrolling = false
        userNearBottom = true
        sendMessage(text)
        messagesContainer.post { scrollToBottom(force = true) }
    }

    fun sendExternalMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        view?.post {
            if (!isAdded || requestInProgress) return@post
            userScrolling = false
            userNearBottom = true
            sendMessage(clean)
            messagesContainer.post { scrollToBottom(force = true) }
        }
    }

    private fun closeQuickPanel(view: View) {
        view.findViewById<View>(R.id.quick_panel)?.apply {
            visibility = View.GONE
            clearFocus()
        }
        view.findViewById<TextView>(R.id.btn_quick_toggle)?.apply {
            contentDescription = "快捷指令"
            alpha = 0.76f
            clearFocus()
        }
        view.findViewById<View>(R.id.chat_scroll)?.post { pushKoyoInset(view) }
    }

    /** 弹窗新增一个快捷键:一个显示名字,一个点了实际发出去的话 */
    private fun promptCustomQuick(view: View) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val nameBox = EditText(ctx).apply { hint = "按钮上显示的字,比如 今天天气" }
        val textBox = EditText(ctx).apply { hint = "点了实际发送的内容" }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (12 * dp).toInt(), pad, 0)
            addView(nameBox)
            addView(textBox)
        }
        AlertDialog.Builder(ctx)
            .setTitle("自定义快捷键")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val n = nameBox.text.toString().trim()
                val t = textBox.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                val arr = customQuick()
                arr.put(JSONObject().put("n", n).put("t", t.ifEmpty { n }))
                saveCustomQuick(arr)
                renderCustomQuick(view)
                view.findViewById<View>(R.id.quick_panel)?.post { pushKoyoInset(view) }
            }
            .show()
    }

    /** 输入区(输入胶囊 + 快捷键触发行 + 展开的面板)总高度,决定她趴在哪 */
    private fun reportKoyoInset(view: View) {
        pushKoyoInset(view)
    }

    /**
     * 把输入区上沿交给停靠层当锚点。位置由它每帧自己跟,
     * 这样键盘弹出、面板展开都不用再通知一次。
     */
    private fun pushKoyoInset(view: View) {
        val composer = view.findViewById<View>(R.id.chat_composer) ?: return
        val panel = view.findViewById<View>(R.id.quick_panel)
        val attachmentMenu = view.findViewById<View>(R.id.chat_attachment_action_menu)
        val attachments = view.findViewById<View>(R.id.chat_attachment_strip)
        val anchor = when {
            attachmentMenu != null && attachmentMenu.visibility == View.VISIBLE -> attachmentMenu
            panel != null && panel.visibility == View.VISIBLE -> panel
            attachments != null && attachments.visibility == View.VISIBLE -> attachments
            else -> composer
        }
        koyoDock?.setChatAnchor(anchor)
    }

    private fun sendCurrentMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        if (requestInProgress) {
            if (pendingAttachments.isNotEmpty()) {
                Toast.makeText(requireContext(), "等当前任务结束后再发送附件", Toast.LENGTH_SHORT).show()
                return
            }
            val canJumpIn = !carChatSwitch.isChecked &&
                TiyoAgentConfig.isConfigured(requireContext()) &&
                agentClient?.isOpen() == true
            if (!canJumpIn) return
            input.text.clear()
            hideKeyboard()
            addMessage("user", text)
            if (agentClient?.jumpIn(text) == true) {
                addSystemMessage("已补充到当前任务，我会在合适的步骤接进去")
                setConnectionState(true, "Tiyo Agent · 已收到补充")
            } else {
                addSystemMessage("当前任务暂时无法接收补充")
            }
            updateComposerState()
            return
        }
        if (pendingAttachments.isNotEmpty()) {
            sendPendingAttachments(text)
            return
        }
        input.text.clear()
        hideKeyboard()
        sendMessage(text)
    }

    private fun sendPendingAttachments(prompt: String) {
        if (carChatSwitch.isChecked || !TiyoAgentConfig.isConfigured(requireContext())) {
            Toast.makeText(requireContext(), "附件需要先启用手机上的 Tiyo Agent", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = pendingAttachments.toList()
        val images = selected.mapNotNull { it.imageBase64 }
        val filePaths = selected.mapNotNull { it.workspacePath }
        val names = selected.joinToString("、") { it.name }
        val displayText = if (prompt.isBlank()) {
            "已添加附件：$names"
        } else {
            "$prompt\n附件：$names"
        }
        val payloadText = buildString {
            append(prompt.ifBlank { "请查看这些附件" })
            if (filePaths.isNotEmpty()) {
                append("\n\n手机工作台附件路径：")
                filePaths.forEach { path -> append("\n- ").append(path) }
            }
        }

        input.text.clear()
        hideKeyboard()
        pendingAttachments.clear()
        renderPendingAttachments()
        addMessage("user", displayText)
        if (images.isNotEmpty()) {
            sendAgentMessageWithImages(payloadText, images, appendUserMessage = false)
        } else {
            sendAgentMessage(payloadText)
        }
    }

    // ---------- 图片：识图 / 改图 / 拍照 ----------

    /** 相册图片先进入附件条，等用户补一句提示词再一起发送 */
    private fun handlePickedUris(uris: List<Uri>) {
        Thread {
            val picked = uris.mapIndexedNotNull { index, uri ->
                BuiltinVision.uriToBase64(requireContext(), uri)?.let { base64 ->
                    PendingAttachment(
                        kind = AttachmentKind.IMAGE,
                        name = attachmentDisplayName(uri, "图片 ${index + 1}.jpg"),
                        imageBase64 = base64
                    )
                }
            }
            mainHandler.post {
                if (!isAdded) return@post
                if (picked.isEmpty()) {
                    Toast.makeText(requireContext(), "图片读取失败", Toast.LENGTH_SHORT).show()
                    return@post
                }
                pendingAttachments += picked
                renderPendingAttachments()
                if (chatMode != ChatModeManager.Mode.FOCUS) {
                    Toast.makeText(requireContext(), "已选 ${picked.size} 张图片，可以继续说想让我做什么", Toast.LENGTH_SHORT).show()
                }
                when (chatMode) {
                    ChatModeManager.Mode.ROOM -> roomModeController?.revealInput()
                    ChatModeManager.Mode.DESK -> deskModeController?.focusInput()
                    ChatModeManager.Mode.FOCUS -> input.requestFocus()
                }
            }
        }.start()
    }

    /** 拍照也先进入附件条，保留相机回归入口 */
    private fun handlePickedBitmap(bitmap: Bitmap) {
        val maxDim = 1280
        val scale = Math.min(1f, maxDim.toFloat() / Math.max(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (scaled !== bitmap) scaled.recycle()
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        pendingAttachments += PendingAttachment(
            kind = AttachmentKind.IMAGE,
            name = "拍摄图片.jpg",
            imageBase64 = b64
        )
        renderPendingAttachments()
        if (chatMode != ChatModeManager.Mode.FOCUS) {
            Toast.makeText(requireContext(), "照片已放进这次对话", Toast.LENGTH_SHORT).show()
        }
        when (chatMode) {
            ChatModeManager.Mode.ROOM -> roomModeController?.revealInput()
            ChatModeManager.Mode.DESK -> deskModeController?.focusInput()
            ChatModeManager.Mode.FOCUS -> input.requestFocus()
        }
    }

    /** 文件立即复制进 Agent 工作台，附件条只暂存名称和工作台路径 */
    private fun queuePickedFiles(uris: List<Uri>) {
        Thread {
            val inbox = TiyoWorkspace.inbox(requireContext())
            val imported = uris.mapIndexedNotNull { index, uri ->
                runCatching {
                    val name = attachmentDisplayName(uri, "附件-${index + 1}")
                    val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                    val target = uniqueTarget(inbox, safeName)
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        target.outputStream().use(inputStream::copyTo)
                    } ?: error("无法读取文件")
                    PendingAttachment(
                        kind = AttachmentKind.FILE,
                        name = target.name,
                        workspacePath = target.absolutePath
                    )
                }.getOrNull()
            }
            mainHandler.post {
                if (!isAdded) return@post
                if (imported.isEmpty()) {
                    Toast.makeText(requireContext(), "文件读取失败", Toast.LENGTH_SHORT).show()
                } else {
                    pendingAttachments += imported
                    renderPendingAttachments()
                    input.requestFocus()
                }
            }
        }.start()
    }

    private fun attachmentDisplayName(uri: Uri, fallback: String): String {
        return requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun renderPendingAttachments() {
        if (!::attachmentList.isInitialized) return
        attachmentList.removeAllViews()
        val visible = pendingAttachments.isNotEmpty()
        deskModeController?.setPendingAttachments(pendingAttachments.map { it.name })
        attachmentStrip.visibility = if (visible) View.VISIBLE else View.GONE
        attachmentHint.visibility = if (visible) View.VISIBLE else View.GONE

        pendingAttachments.forEachIndexed { index, attachment ->
            val cell = FrameLayout(requireContext()).apply {
                setBackgroundResource(R.drawable.chat_focus_attachment_bg)
                layoutParams = LinearLayout.LayoutParams(dp(74), dp(66)).apply {
                    marginEnd = dp(7)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { showQueuedAttachmentActions(index) }
            }
            if (attachment.kind == AttachmentKind.IMAGE) {
                val bytes = runCatching {
                    Base64.decode(attachment.imageBase64, Base64.DEFAULT)
                }.getOrNull()
                val bitmap = bytes?.let {
                    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, options)
                }
                cell.addView(ImageView(requireContext()).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = attachment.name
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            } else {
                val preview = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(5), dp(5), dp(4))
                }
                preview.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_focus_file)
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                preview.addView(TextView(requireContext()).apply {
                    text = attachment.name
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    gravity = Gravity.CENTER
                    textSize = 9f
                    setTextColor(requireContext().getColor(R.color.d_focus_ink_2))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                cell.addView(preview, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }

            val remove = ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_focus_close)
                background = requireContext().getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "移除 ${attachment.name}"
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    if (index in pendingAttachments.indices) {
                        pendingAttachments.removeAt(index)
                        renderPendingAttachments()
                    }
                }
            }
            cell.addView(remove, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.TOP))
            attachmentList.addView(cell)
        }
        updateComposerState()
        view?.let { root -> attachmentStrip.post { pushKoyoInset(root) } }
    }

    private fun showQueuedAttachmentActions(index: Int) {
        val attachment = pendingAttachments.getOrNull(index) ?: return
        val actions = if (attachment.kind == AttachmentKind.IMAGE) {
            arrayOf("作为改图原图", "移除附件")
        } else {
            arrayOf("打开工作台", "移除附件")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(attachment.name)
            .setItems(actions) { _, action ->
                if (attachment.kind == AttachmentKind.IMAGE && action == 0) {
                    pendingEditImage = attachment.imageBase64
                    pendingAttachments.clear()
                    renderPendingAttachments()
                    editHint?.visibility = View.VISIBLE
                    input.requestFocus()
                } else if (attachment.kind == AttachmentKind.FILE && action == 0) {
                    (activity as? MainActivity)?.openFilesWorkspace()
                } else {
                    pendingAttachments.removeAt(index)
                    renderPendingAttachments()
                }
            }
            .show()
    }

    /** 图片菜单：发给可又（agent 原生识图）or 改图 */
    private fun showImageActions(b64: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("这张图片")
            .setItems(arrayOf(
                "发给${CompanionProfileStore.activeName(requireContext())}看看（识图）",
                "让${CompanionProfileStore.activeName(requireContext())}帮我改图"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val text = input?.text?.toString()?.trim() ?: ""
                        sendAgentMessageWithImages(text, listOf(b64))
                    }
                    1 -> {
                        pendingEditImage = b64
                        editHint?.visibility = View.VISIBLE
                        Toast.makeText(requireContext(), "输入想改成什么样，发送即可", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** 识图：内置视觉模型（glm→Agnes）→ 描述发给 agent */
    private fun recognizeBase64AndSend(b64: String) {
        if (requestInProgress) {
            Toast.makeText(requireContext(), "等上一条回复完再发图", Toast.LENGTH_SHORT).show()
            return
        }
        setConnectionState(true, "正在识别图片…")
        Thread {
            val desc = BuiltinVision.recognize(requireContext(), "data:image/jpeg;base64,$b64")
            mainHandler.post {
                if (!isAdded) return@post
                setConnectionState(true, "Tiyo Agent")
                if (desc == "识别失败，稍后再试") addSystemMessage(desc)
                else sendMessage("发了一张图片：$desc")
            }
        }.start()
    }

    /** 改图：原图 + 描述走生图 API */
    private fun runEditImage(prompt: String, imageBase64: String) {
        if (requestInProgress) return
        addMessage("user", "帮我改图：$prompt")
        requestInProgress = true
        setConnectionState(true, "正在生图…")
        showTyping()
        Thread {
            val result = ImageGenClient.editImage(prompt, imageBase64)
            mainHandler.post {
                requestInProgress = false
                hideTyping()
                setConnectionState(true, "Tiyo Agent")
                if (result != null) {
                    renderImageMessage("data:image/png;base64,$result")
                } else {
                    addSystemMessage("改图失败，检查生图配置（provider 选 gpt-image + key）")
                }
            }
        }.start()
    }

    private fun hideEditHint() {
        editHint?.visibility = View.GONE
    }

    private fun sendMessage(text: String, appendUserMessage: Boolean = true) {
        if (requestInProgress) return
        // 改图模式：有待改图时，输入的文字作为改图描述
        val editImage = pendingEditImage
        if (editImage != null) {
            pendingEditImage = null
            hideEditHint()
            runEditImage(text, editImage)
            return
        }
        finishReplyReveal()
        if (!carChatSwitch.isChecked) stopReplyPlayback()
        if (appendUserMessage) addMessage("user", text)

        val throughCar = carChatSwitch.isChecked
        if (!throughCar && TiyoAgentConfig.isConfigured(requireContext())) {
            sendAgentMessage(text)
            return
        }
        if (!throughCar && !companionScope.isBuiltInCompanion) {
            addSystemMessage("${companionScope.displayName}的本地模型还没有配置，先去模型设置完成配置")
            return
        }

        requestInProgress = true
        lastFailedText = null
        lastFailedImages = null
        setConnectionState(true, "正在思考")
        showTyping()
        updateComposerState()

        val allowVoice = voiceSwitch.isChecked
        Thread {
            try {
                val directReply: DirectReply?
                val reply = if (throughCar) {
                    val baseUrl = findController()
                        ?: throw IllegalStateException("没有找到小车，请检查手机和小车是否在同一网络")
                    val answer = postCarChat(baseUrl, text, allowVoice)
                    requireContext().getSharedPreferences("car", 0).edit()
                        .putString("base_url", baseUrl)
                        .apply()
                    directReply = null
                    answer
                } else {
                    directReply = requestKoyoDirect(text)
                    directReply.text
                }
                mainHandler.post replyUi@{
                    if (!isAdded || view == null) return@replyUi
                    hideTyping()
                    requestInProgress = false
                    showAssistantReply(
                        reply.ifBlank { "我在，刚才没有组织好语言。" }
                    )
                    if (!throughCar && allowVoice && directReply != null) {
                        setConnectionState(true, "正在生成手机语音")
                        playReplySentences(reply)
                    } else {
                        val label = when {
                            throughCar && allowVoice -> "小车正在说话"
                            throughCar -> "小车在线"
                            else -> "电脑聊天网关 · 无本机工具"
                        }
                        setConnectionState(true, label)
                    }
                    updateComposerState()
                    if (throughCar || !allowVoice) {
                        mainHandler.postDelayed({
                            if (!requestInProgress && isAdded && view != null) refreshStatus()
                        }, 1800)
                    }
                }
            } catch (error: Exception) {
                deliverChatError(
                    text,
                    error.message?.takeIf { it.isNotBlank() } ?: "消息发送失败"
                )
            }
        }.start()
    }

    private fun sendAgentMessage(text: String) {
        requestInProgress = true
        lastFailedText = text
        lastFailedImages = null
        lastAgentReply = ""
        pendingAgentText = text
        pendingAgentImages = null
        setConnectionState(true, "正在准备 Tiyo Agent")
        showTyping()
        updateComposerState()
        if (agentClient?.isOpen() == true) {
            flushPendingAgentMessage()
        } else {
            connectAgent()
        }
    }

    /** 带图发送：图片 base64 列表沿用现有 Agent 图片协议 */
    private fun sendAgentMessageWithImages(
        text: String,
        images: List<String>,
        appendUserMessage: Boolean = true
    ) {
        requestInProgress = true
        lastFailedText = text
        lastFailedImages = images.toList()
        lastAgentReply = ""
        if (appendUserMessage) {
            addMessage("user", if (text.isBlank()) "发了一张图片" else "发了一张图片：$text")
        }
        pendingAgentText = text
        pendingAgentImages = images
        setConnectionState(true, "正在识别图片…")
        showTyping()
        updateComposerState()
        if (agentClient?.isOpen() == true) {
            flushPendingAgentMessage()
        } else {
            connectAgent()
        }
    }

    private fun connectAgent() {
        if (!isAdded || view == null || carChatSwitch.isChecked) return
        if (!TiyoAgentConfig.isConfigured(requireContext())) {
            setConnectionState(false, "Tiyo Agent · 待配置")
            return
        }
        val client = agentClient ?: TiyoAgentClient(this).also { agentClient = it }
        setConnectionState(true, "正在启动本机 Agent")
        TiyoAgentRuntime.ensureStarted(
            requireContext(),
            companionScope,
            onReady = { info ->
                if (!isAdded || view == null || carChatSwitch.isChecked) return@ensureStarted
                client.connect(info, TiyoAgentConfig.load(requireContext()), activeSessionId)
            },
            onError = { message ->
                if (!isAdded || view == null) return@ensureStarted
                onAgentError(message)
            }
        )
    }

    /** MCP / Skill 配置改变后由侧栏管理页调用，空闲时无损重载本机 Agent */
    fun reloadAgentRuntime(): Boolean {
        if (!isAdded || view == null || requestInProgress || !TiyoAgentConfig.isConfigured(requireContext())) {
            return false
        }
        setConnectionState(true, "正在重载 Agent 能力")
        agentClient?.close()
        agentClient = TiyoAgentClient(this)
        TiyoAgentRuntime.restart(
            requireContext(),
            companionScope,
            onReady = {
                if (isAdded && view != null) connectAgent()
            },
            onError = {
                if (isAdded && view != null) onAgentError(it)
            }
        )
        return true
    }

    private fun flushPendingAgentMessage() {
        val text = pendingAgentText ?: return
        val client = agentClient ?: return
        val images = pendingAgentImages
        val sent = if (images != null) {
            client.sendMessageWithImages(text, images)
        } else {
            client.sendMessage(text)
        }
        if (!sent) {
            onAgentError("Tiyo Agent 还没有准备好")
            return
        }
        pendingAgentText = null
        pendingAgentImages = null
        requireContext().getSharedPreferences(PREFS_NAME, 0).edit()
            .putString(KEY_AGENT_SESSION, client.currentSessionId())
            .apply()
        activeSessionId = client.currentSessionId()
        TiyoSessionStore.activate(requireContext(), companionScope, activeSessionId)
        setConnectionState(true, "Tiyo Agent · 正在思考")
    }

    override fun onAgentState(connected: Boolean, label: String) {
        if (!isAdded || view == null || carChatSwitch.isChecked) return
        setConnectionState(connected, label)
        if (connected) {
            if (planMode) agentClient?.setPlanMode(true)
            flushPendingAgentMessage()
        }
    }

    override fun onAgentEvent(event: JSONObject) {
        if (event.optString("event_type") == "phone_tool_request") {
            handlePhoneToolRequest(event)
            return
        }
        if (!isAdded || view == null) return
        when (event.optString("event_type")) {
            "text_chunk" -> {
                hideTyping()
                setConnectionState(true, "Tiyo Agent · 正在回复")
                appendAgentReply(event.optString("content"))
            }
            "reasoning_chunk" -> {
                setConnectionState(true, "Tiyo Agent · 正在梳理任务")
            }
            "vision_status" -> {
                val label = when (event.optString("status")) {
                    "analyzing" -> "正在识别图片…"
                    "ready" -> when (event.optString("route")) {
                        "mcp" -> "视觉 MCP · 已读到图片"
                        "ark" -> "豆包视觉 · 已读到图片"
                        "agnes" -> "Agnes 视觉 · 已读到图片"
                        else -> "视觉服务 · 已读到图片"
                    }
                    "failed" -> "视觉服务 · 需要重试"
                    else -> "Tiyo Agent · 正在处理图片"
                }
                setConnectionState(event.optString("status") != "failed", label)
            }
            "tool_start", "tool_running", "tool_done", "tool_cache_hit",
            "tool_approval_request", "loop_step_start", "loop_progress",
            "loop_step_done", "loop_issue_created", "bg_task_detached",
            "bg_task_completed", "agent_cancelled" -> {
                hideTyping()
                if (event.optString("event_type") == "tool_start") {
                    finishAgentReplyStream()
                    // 写代码 / 查资料给对应动作,一直播到工具结束
                    koyoDock?.setWorkingTool(event.optString("tool_name"))
                    // 手机 agent 写入记忆时，转成候选事件加入待同步队列
                    if (event.optString("tool_name") == "memory_write") {
                        val args = event.optJSONObject("arguments") ?: JSONObject()
                        // 面向用户：本地直接落盘，让"可又的时刻"时间线能读到（不依赖电脑同步）
                        TiyoMemoryBridge.saveLocalMemory(requireContext(), companionScope, args)
                        // 保留电脑同步候选（配了网关的旧通道，不影响本地闭环）
                        TiyoMemoryBridge.enqueueMemoryWrite(requireContext(), companionScope, args)
                    }
                }
                // 生图工具完成：事件里带 images（data URL 数组），渲染成图片气泡
                if (event.optString("event_type") == "tool_done") {
                    val images = event.optJSONArray("images")
                    if (images != null && images.length() > 0) {
                        finishAgentReplyStream()
                        for (i in 0 until images.length()) {
                            renderImageMessage(images.optString(i))
                        }
                    }
                }
                ensureTaskCard().handle(event)
                val status = if (event.optString("event_type") == "tool_approval_request") {
                    "Tiyo Agent · 等你确认"
                } else {
                    "Tiyo Agent · 正在执行"
                }
                setConnectionState(true, status)
                scrollToBottom()
            }
            "user_question_request" -> showAgentQuestion(event)
            "file_transfer_request" -> handleAgentFileTransfer(event)
            "usage_update" -> updateUsage(event.optJSONObject("usage") ?: JSONObject())
            "compression" -> {
                val before = event.optLong("before")
                val after = event.optLong("after")
                addSystemMessage(
                    if (before > 0 && after > 0) "上下文已整理 ${formatTokens(before)} → ${formatTokens(after)}"
                    else "较早的对话已经整理成摘要"
                )
            }
            "connection_retry" -> {
                setConnectionState(false, "模型连接正在重试")
            }
            "agent_error" -> {
                val message = event.optString("message").ifBlank { "Tiyo Agent 执行失败" }
                if (event.optBoolean("is_fatal")) {
                    currentTaskCard?.fail(message)
                    finishAgentRequest(false, message)
                } else {
                    addSystemMessage(message)
                }
            }
            "turn_end" -> {
                currentTaskCard?.handle(event)
                finishAgentReplyStream()
                finishAgentRequest(true, null)
            }
        }
    }

    private fun handlePhoneToolRequest(event: JSONObject) {
        val requestId = event.optString("request_id")
        val toolName = event.optString("tool_name")
        val client = agentClient ?: return
        val appContext = context?.applicationContext
        if (requestId.isBlank() || toolName.isBlank() || appContext == null) {
            client.completePhoneTool(
                requestId,
                PhoneToolExecutor.Outcome(false, error = "手机界面当前不可用")
            )
            return
        }
        PhoneToolExecutor.executeAsync(
            appContext,
            toolName,
            event.optJSONObject("arguments") ?: JSONObject()
        ) { outcome ->
            client.completePhoneTool(requestId, outcome)
        }
    }

    override fun onAgentError(message: String) {
        if (!isAdded || view == null) return
        // 历史脏数据（空 content 的 assistant 消息）导致模型 400：自动换新会话绕过（30 秒限频防循环）
        val now = System.currentTimeMillis()
        if (
            now - lastAutoResetAt > 30_000 &&
            (message.contains("400") || message.contains("Invalid assistant") ||
             message.contains("provider request failed"))
        ) {
            lastAutoResetAt = now
            addSystemMessage("检测到历史消息异常，已自动换新会话，重新发送刚才的消息")
            val retryText = lastFailedText
            val retryImages = lastFailedImages?.toList()
            resetSessionOnError()
            if (retryText != null || !retryImages.isNullOrEmpty()) {
                mainHandler.postDelayed({
                    if (isAdded && view != null && !requestInProgress) {
                        if (!retryImages.isNullOrEmpty()) {
                            sendAgentMessageWithImages(retryText.orEmpty(), retryImages, appendUserMessage = false)
                        } else {
                            sendMessage(retryText.orEmpty(), appendUserMessage = false)
                        }
                    }
                }, 800)
            }
            return
        }
        currentTaskCard?.takeIf { !it.isCompleted() }?.fail(message)
        finishAgentRequest(false, message)
    }

    private fun ensureTaskCard(): TiyoTaskCard {
        val current = currentTaskCard
        if (current != null && !current.isCompleted()) return current
        return TiyoTaskCard(
            requireContext(),
            messagesContainer,
            onCancel = { agentClient?.cancel() },
            onApprove = { callId, decision -> agentClient?.approve(callId, decision) }
        ).also { currentTaskCard = it }
    }

    private fun appendAgentReply(chunk: String) {
        if (chunk.isEmpty()) return
        if (agentReplyRow == null) {
            agentReplyRow = addMessage("assistant", "", persist = false)
            agentReplyText = agentReplyRow?.findViewById(R.id.chat_message_text)
            agentReplyBuffer.clear()
        }
        agentReplyBuffer.append(chunk)
        agentReplyText?.text = agentReplyBuffer.toString()
        // 流式气泡初始文本为空会被 renderMessage 隐藏，文字到了要恢复显示
        agentReplyText?.visibility = View.VISIBLE
        scrollToBottom()
    }

    private fun finishAgentReplyStream() {
        val raw = agentReplyBuffer.toString().trim()
        if (raw.isNotBlank()) {
            val (text, sticker) = StickerStore.extractSticker(raw)
            // 流式渲染的文本去掉表情包标记
            if (PaperSheetView.shouldUse(text)) {
                agentReplyRow?.let { row ->
                    val index = messagesContainer.indexOfChild(row).coerceAtLeast(0)
                    messagesContainer.removeView(row)
                    val paper = PaperSheetView(requireContext()).bind(
                        PaperSheetView.titleFor(text),
                        text
                    )
                    messagesContainer.addView(paper, index)
                    lastAssistantRow = paper
                }
            } else {
                agentReplyText?.text = text
            }
            val completedMessage = ChatMessage(
                "assistant",
                text,
                System.currentTimeMillis(),
                sticker
            )
            messages.add(completedMessage)
            while (messages.size > MAX_HISTORY) messages.removeAt(0)
            saveHistory()
            deliverModeMessage(completedMessage)
            lastAgentReply = text
            if (sticker != null) renderStickerBubble(sticker)
        } else {
            agentReplyRow?.let(messagesContainer::removeView)
        }
        agentReplyRow = null
        agentReplyText = null
        agentReplyBuffer.clear()
    }

    private fun finishAgentRequest(success: Boolean, message: String?) {
        hideTyping()
        koyoDock?.clearWorking()
        requestInProgress = false
        pendingAgentText = null
        pendingAgentImages = null
        setConnectionState(success, if (success) "Tiyo Agent · 就绪" else "Tiyo Agent · 需要处理")
        if (!success && !message.isNullOrBlank()) {
            addSystemMessage("$message，点这里重试") { retryFailedAgentTurn() }
        }
        if (success) {
            lastFailedText = null
            lastFailedImages = null
            maybeExtractAtomicMemory()
        }
        updateComposerState()
        if (success && voiceSwitch.isChecked && lastAgentReply.isNotBlank()) {
            val speech = lastAgentReply
            mainHandler.post {
                if (isAdded && view != null && !carChatSwitch.isChecked) {
                    playReplySentences(speech)
                }
            }
        }
        scrollToBottom()
    }

    private fun retryFailedAgentTurn() {
        if (requestInProgress) return
        val images = lastFailedImages?.toList()
        val text = lastFailedText.orEmpty()
        if (!images.isNullOrEmpty()) {
            sendAgentMessageWithImages(text, images, appendUserMessage = false)
        } else if (lastFailedText != null) {
            sendMessage(text, appendUserMessage = false)
        }
    }

    /**
     * 一轮对话成功结束后，把最近的 user/assistant 消息交给自动提炼器，
     * 提炼成结构化原子记忆落盘。提炼器内部有游标限频，不会重复提炼。
     */
    private fun maybeExtractAtomicMemory() {
        if (!isAdded || view == null) return
        if (carChatSwitch.isChecked) return
        val turns = messages.takeLast(MAX_EXTRACT_TURNS)
            .map {
                TiyoMemoryExtractor.Turn(
                    text = it.text,
                    isUser = it.role == "user",
                    timestamp = it.timestamp
                )
            }
            .filter { it.text.isNotBlank() }
        TiyoMemoryExtractor.triggerIfDue(
            requireContext(),
            companionScope,
            activeSessionId,
            turns
        )
    }

    private fun showAgentQuestion(event: JSONObject) {
        val callId = event.optString("call_id")
        val question = event.optString("question").ifBlank { "Tiyo Agent 需要你补充一点信息" }
        // options 可能是字符串数组，也可能是 {label/value} 对象数组，两种都兼容
        val options = event.optJSONArray("options")
        val labels = mutableListOf<String>()
        if (options != null) {
            for (i in 0 until options.length()) {
                val item = options.opt(i)
                val label = when (item) {
                    is JSONObject -> item.optString("label").ifBlank { item.optString("value") }
                    else -> item?.toString()
                }?.trim().orEmpty()
                if (label.isNotBlank()) labels += label
            }
        }
        if (labels.isNotEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("需要你的选择")
                .setMessage(question)
                .setItems(labels.toTypedArray()) { _, index ->
                    agentClient?.answer(callId, labels[index])
                }
                .setNegativeButton("取消") { _, _ -> agentClient?.answer(callId, "取消") }
                .show()
            return
        }
        val answer = EditText(requireContext()).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            hint = "输入回答"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("需要你的回答")
            .setMessage(question)
            .setView(answer)
            .setPositiveButton("继续") { _, _ ->
                agentClient?.answer(callId, answer.text.toString().trim())
            }
            .setNegativeButton("取消") { _, _ -> agentClient?.answer(callId, "取消") }
            .show()
    }

    private fun handleAgentFileTransfer(event: JSONObject) {
        val requestId = event.optString("request_id")
        if (requestId.isBlank()) return
        if (event.optString("operation") == "import") {
            pendingFileRequestId = requestId
            agentFilePicker.launch(arrayOf("*/*"))
            return
        }
        val path = event.optString("path")
        val source = File(path)
        if (path.isBlank() || !source.isFile) {
            agentClient?.completeFileTransfer(requestId, emptyList())
            addSystemMessage("要导出的文件已经不存在")
            return
        }
        pendingExportRequestId = requestId
        pendingExportPath = source.absolutePath
        agentFileExporter.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(
                    Intent.EXTRA_TITLE,
                    event.optString("suggested_name").ifBlank { source.name }
                )
            }
        )
    }

    private fun showAgentSettings() {
        if (requestInProgress) {
            addSystemMessage("先等当前任务结束，再调整 Agent 设置")
            return
        }
        TiyoAgentSettingsDialog(requireContext()) {
            refreshTopBar()
            setConnectionState(true, "正在应用 Agent 设置")
            agentClient?.close()
            agentClient = TiyoAgentClient(this)
        TiyoAgentRuntime.restart(
            requireContext(),
            companionScope,
                onReady = {
                    if (isAdded && view != null) connectAgent()
                },
                onError = { onAgentError(it) }
            )
            updateRouteHint()
        }.show()
    }

    private fun showSessions() {
        if (requestInProgress) {
            addSystemMessage("当前任务结束后再切换会话，执行过程不会被丢掉")
            return
        }
        val sessions = TiyoSessionStore.sessions(requireContext(), companionScope)
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val labels = sessions.map { session ->
            val pin = if (session.pinned) "置顶 · " else ""
            "$pin${session.title}\n${formatter.format(Date(session.updatedAt))}"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Tiyo 会话")
            .setItems(labels) { _, index -> openSession(sessions[index].id) }
            .setPositiveButton("新建对话") { _, _ -> createSession() }
            .setNeutralButton("管理") { _, _ -> showSessionManager() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showSessionManager() {
        val sessions = TiyoSessionStore.sessions(requireContext(), companionScope)
        if (sessions.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("管理会话")
            .setItems(sessions.map { it.title }.toTypedArray()) { _, index ->
                val session = sessions[index]
                val actions = arrayOf(if (session.pinned) "取消置顶" else "置顶", "删除")
                AlertDialog.Builder(requireContext())
                    .setTitle(session.title)
                    .setItems(actions) { _, action ->
                        if (action == 0) {
                    TiyoSessionStore.setPinned(requireContext(), companionScope, session.id, !session.pinned)
                        } else if (session.id == activeSessionId) {
                            addSystemMessage("正在使用的会话不能删除，先切换到另一条会话")
                        } else {
                            AlertDialog.Builder(requireContext())
                                .setTitle("删除这条会话")
                                .setMessage("本机聊天记录会一并删除")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除") { _, _ ->
                    TiyoSessionStore.delete(requireContext(), companionScope, session.id)
                                }
                                .show()
                        }
                    }
                    .show()
            }
            .setNegativeButton("完成", null)
            .show()
    }

    private fun createSession() {
        saveHistory()
        val session = TiyoSessionStore.create(requireContext(), companionScope)
        switchSession(session.id)
    }

    /** 400 坏历史时自动换会话（不重置 autoResetSession 标记，避免循环）。 */
    private fun resetSessionOnError() {
        saveHistory()
        val session = TiyoSessionStore.create(requireContext(), companionScope)
        switchSession(session.id)
    }

    private fun openSession(id: String) {
        if (id == activeSessionId) return
        saveHistory()
        TiyoSessionStore.activate(requireContext(), companionScope, id)
        switchSession(id)
    }

    private fun switchSession(id: String) {
        activeSessionId = id
        requireContext().getSharedPreferences(PREFS_NAME, 0)
            .edit().putString(KEY_AGENT_SESSION, id).apply()
        agentClient?.close()
        agentClient = TiyoAgentClient(this)
        currentTaskCard = null
        agentReplyRow = null
        agentReplyText = null
        agentReplyBuffer.clear()
        contextMeter.text = "上下文 --"
        requestInProgress = false
        pendingAgentText = null
        pendingAgentImages = null
        loadHistory()
        renderHistory()
        renderSessionDrawer()
        updateComposerState()
        if (TiyoAgentConfig.isConfigured(requireContext())) connectAgent() else refreshStatus()
    }

    private fun deliverChatError(text: String, message: String) {
        activity?.runOnUiThread {
            if (!isAdded || view == null) return@runOnUiThread
            hideTyping()
            requestInProgress = false
            lastFailedText = text
            setConnectionState(false, "连接中断")
            addSystemMessage("$message，点这里重试") {
                val retry = lastFailedText ?: return@addSystemMessage
                sendMessage(retry, appendUserMessage = false)
            }
            updateComposerState()
        }
    }

    private fun postCarChat(baseUrl: String, text: String, allowVoice: Boolean): String {
        val audioValue = if (allowVoice) "1" else "0"
        val connection = (
            URL("$baseUrl/api/chat?audio=$audioValue").openConnection() as HttpURLConnection
        ).apply {
            connectTimeout = 4000
            readTimeout = 120000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        val payload = text.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException(
                response.ifBlank {
                        if (code == 409) "${CompanionProfileStore.activeName(requireContext())}还在回答上一句话" else "请求失败 $code"
                }
            )
        }
        return response
    }

    private fun requestKoyoDirect(text: String): DirectReply {
        val baseUrl = findGateway()
            ?: throw IllegalStateException(
                "没有找到电脑里的${CompanionProfileStore.activeName(requireContext())}，请检查手机和电脑是否在同一网络"
            )
        val reply = postGatewayChat(baseUrl, text)
        return DirectReply(reply, baseUrl)
    }

    private fun postGatewayChat(baseUrl: String, text: String): String {
        val connection = (
            URL("${baseUrl.trimEnd('/')}/koyo/chat?audio=0")
                .openConnection() as HttpURLConnection
        ).apply {
            connectTimeout = 3500
            readTimeout = 180000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val payload = JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException(response.ifBlank { "请求失败 $code" })
        }
        return response
    }

    /** 单句 TTS：手机直连 MiniMax/edge，返回临时 MP3 文件，失败返回 null。 */
    private fun fetchCloudTts(sentence: String): File? {
        val cacheDir = context?.applicationContext?.cacheDir ?: return null
        return try {
            val bytes = when (selectedVoiceId) {
                "sora" -> TiyoMiniMaxTts.synthesize(
                    sentence, "koyo-sora", TiyoAgentConfig.ttsApiKey(requireContext())
                )
                "furina" -> TiyoMiniMaxTts.synthesize(
                    sentence, "furina-clean-v2", TiyoAgentConfig.ttsApiKey(requireContext())
                )
                // 扫描到的克隆音色：id 就是 voice_id，直接用
                else -> TiyoMiniMaxTts.synthesize(
                    sentence, selectedVoiceId, TiyoAgentConfig.ttsApiKey(requireContext())
                )
            }
            val targetFile = File.createTempFile("koyo-chat-reply-", ".mp3", cacheDir)
            targetFile.writeBytes(bytes)
            targetFile
        } catch (error: Exception) {
            android.util.Log.e("TiyoTts", "手机语音失败($selectedVoiceId): ${error.message}")
            null
        }
    }

    /** 按句播放编排器：逐句合成、逐句串行播放（每句播完再播下一句），代际过期随时退出。 */
    private fun playReplySentences(fullText: String) {
        stopReplyPlayback()
        koyoDock?.setSpeaking(true)
        val requestId = phoneAudioRequestId
        val sentences = splitSentences(fullText)
        if (sentences.isEmpty()) return
        Thread {
            try {
                for (index in sentences.indices) {
                    if (requestId != phoneAudioRequestId) return@Thread
                    val file = fetchCloudTts(sentences[index]) ?: break
                    val last = index == sentences.lastIndex
                    val done = CountDownLatch(1)
                    mainHandler.post {
                        if (requestId != phoneAudioRequestId) {
                            file.delete()
                            done.countDown()
                            return@post
                        }
                        playSingleSentence(file, requestId, last) { done.countDown() }
                    }
                    // 等这句播完（或被打断）再合成下一句
                    done.await(10, TimeUnit.SECONDS)
                }
                mainHandler.post { koyoDock?.setSpeaking(false) }
            } catch (_: Exception) {
            }
        }.start()
    }

    /** 按句末标点分句；单句超长硬切；句数封顶，超出部分合并。 */
    private fun splitSentences(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in normalized) {
            current.append(ch)
            if (
                ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' ||
                ch == '…' || ch == '；' || ch == ';' || ch == '\n'
            ) {
                val sentence = current.toString().trim()
                if (sentence.isNotBlank()) parts.add(sentence)
                current.setLength(0)
            }
        }
        if (current.toString().trim().isNotEmpty()) parts.add(current.toString().trim())

        var sentences = parts.flatMap { sentence ->
            if (sentence.length <= maxSentenceChars) listOf(sentence)
            else sentence.chunked(maxSentenceChars)
        }
        if (sentences.size > maxSentenceCount) {
            sentences = sentences.take(maxSentenceCount - 1) +
                listOf(sentences.drop(maxSentenceCount - 1).joinToString(""))
        }
        return sentences
    }

    /** 播放单个句子；用 replyPlayer 引用相等 + 代际守卫防止旧回调误操作新播放。 */
    private fun playSingleSentence(
        audioFile: File,
        requestId: Long,
        isLast: Boolean,
        onCompleted: (() -> Unit)? = null
    ) {
        if (requestId != phoneAudioRequestId) {
            audioFile.delete()
            onCompleted?.invoke()
            return
        }
        releaseReplyPlayback()
        replyAudioFile = audioFile
        val player = MediaPlayer()
        replyPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                // vivo keeps the assistant stream separate and may leave it at volume 0.
                // Chat replies should follow the user's normal media volume instead.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(audioFile.absolutePath)
        player.setOnPreparedListener {
            if (
                requestId == phoneAudioRequestId && replyPlayer === player &&
                isAdded && view != null && !carChatSwitch.isChecked
            ) {
                setBubblePlaying(true)
                setConnectionState(true, "${CompanionProfileStore.activeName(requireContext())}正在用手机说话")
                it.start()
            } else {
                if (replyPlayer === player) releaseReplyPlayback()
                onCompleted?.invoke()
            }
        }
        player.setOnCompletionListener {
            if (requestId == phoneAudioRequestId) {
                setBubblePlaying(false)
                if (replyPlayer === player) releaseReplyPlayback()
                if (isLast && isAdded && view != null) {
                    setConnectionState(true, localRouteLabel())
                }
            }
            onCompleted?.invoke()
        }
        player.setOnErrorListener { _, _, _ ->
            if (requestId == phoneAudioRequestId) {
                setBubblePlaying(false)
                if (replyPlayer === player) releaseReplyPlayback()
                if (isAdded && view != null) setConnectionState(false, "手机语音播放失败")
            }
            onCompleted?.invoke()
            true
        }
        player.prepareAsync()
    }

    private fun stopReplyPlayback() {
        phoneAudioRequestId += 1
        releaseReplyPlayback()
        koyoDock?.setSpeaking(false)
    }

    private fun releaseReplyPlayback() {
        replyPlayer?.runCatching { stop() }
        replyPlayer?.release()
        replyPlayer = null
        replyAudioFile?.delete()
        replyAudioFile = null
        setBubblePlaying(false)
    }

    /** 在最后一条可又气泡上显示/隐藏「正在说…」状态。 */
    private fun setBubblePlaying(playing: Boolean) {
        val row = lastAssistantRow ?: return
        val label = row.findViewById<TextView>(R.id.chat_message_playing) ?: return
        label.visibility = if (playing) View.VISIBLE else View.GONE
    }

    private fun takePhoto() {
        addSystemMessage("正在让${CompanionProfileStore.activeName(requireContext())}拍照")
        requestSimple("/api/photo") { success, response ->
            addSystemMessage(
                if (success) "拍照指令已发送，${response.ifBlank { "请到摄像头页查看" }}"
                else "拍照失败，${response.ifBlank { "没有找到${CompanionProfileStore.activeName(requireContext())}" }}"
            )
        }
    }

    private fun stopAudio() {
        if (!carChatSwitch.isChecked) {
            stopReplyPlayback()
            addSystemMessage("已停止手机上的语音")
            setConnectionState(true, localRouteLabel())
            return
        }
        requestSimple("/api/audio/stop") { success, _ ->
            if (success) {
                addSystemMessage("已停止小车上的语音")
                setConnectionState(true, "小车在线")
            } else {
                addSystemMessage("没有连接到小车，暂时无法停止语音")
            }
        }
    }

    private fun updateRouteHint() {
        routeHint.text = when {
            carChatSwitch.isChecked -> getString(R.string.chat_car_route_hint)
            TiyoAgentConfig.isConfigured(requireContext()) -> "本机 Agent · 可改文件、运行工具和执行任务"
            else -> "电脑聊天网关 · 只负责对话，点设置启用本机工具"
        }
    }

    private fun togglePlanMode() {
        if (!TiyoAgentConfig.isConfigured(requireContext())) {
            addSystemMessage("计划模式需要先启用本机 Agent，点右上角设置就可以配置")
            showAgentSettings()
            return
        }
        planMode = !planMode
        requireContext().getSharedPreferences(PREFS_NAME, 0)
            .edit().putBoolean(KEY_PLAN_MODE, planMode).apply()
        agentClient?.takeIf { it.isOpen() }?.setPlanMode(planMode)
        updatePlanButton()
        addSystemMessage(
            if (planMode) "已进入计划模式，我会先把方案和步骤给你确认"
            else "已回到执行模式，可以直接使用本机工具"
        )
    }

    private fun updatePlanButton() {
        if (!::planButton.isInitialized) return
        planButton.text = if (planMode) "计划中" else "计划模式"
        planButton.setTextColor(
            requireContext().getColor(if (planMode) R.color.tiyo_accent_dark else R.color.tiyo_ink_soft)
        )
    }

    private fun updateUsage(usage: JSONObject) {
        if (!::contextMeter.isInitialized) return
        val ratio = usage.optDouble("context_ratio", 0.0)
        val used = usage.optLong("context_used_tokens", 0L)
        val window = usage.optLong("context_window_tokens", 0L)
        val percent = when {
            ratio > 0 -> (ratio * 100).toInt().coerceIn(0, 100)
            used > 0 && window > 0 -> ((used * 100.0) / window).toInt().coerceIn(0, 100)
            else -> null
        }
        contextMeter.text = when {
            percent != null && used > 0 && window > 0 ->
                "上下文 $percent% · ${formatTokens(used)}/${formatTokens(window)}"
            percent != null -> "上下文 $percent%"
            else -> "上下文 --"
        }
    }

    private fun refreshTopBar() {
        if (!::modelNameText.isInitialized || !isAdded) return
        val model = TiyoAgentConfig.load(requireContext()).model
            .ifBlank { TiyoAgentConfig.DEFAULT_MODEL }
        modelNameText.text = "模型 · $model"
        if (contextMeter.text.isNullOrBlank()) contextMeter.text = "上下文 --"
    }

    private fun formatTokens(value: Long): String = when {
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
        value >= 1_000 -> "%.1fk".format(value / 1_000.0)
        else -> value.toString()
    }

    private fun cycleVoiceStyle() {
        if (voiceOptions.isEmpty()) return
        val currentIndex = voiceOptions.indexOfFirst { it.id == selectedVoiceId }
        val next = voiceOptions[(currentIndex + 1).mod(voiceOptions.size)]
        selectedVoiceId = next.id
        requireContext().getSharedPreferences(PREFS_NAME, 0).edit()
            .putString(companionScope.namespaced(KEY_TTS_VOICE), selectedVoiceId)
            .apply()
        updateVoiceStyleButton()
        addSystemMessage("音色已切换为 ${voiceLabel(selectedVoiceId)}")
    }

    private fun refreshVoiceStyle() {
        if (!companionScope.isBuiltInCompanion) return
        Thread {
            try {
                val response = requestGatewayVoice()
                activity?.runOnUiThread {
                    if (isAdded && view != null && !voiceRequestInProgress) {
                        applyVoiceResponse(response)
                    }
                }
            } catch (_: Exception) {
                // 保留上次成功的选择，网关恢复后下次进入聊天页会重新同步。
            }
        }.start()
    }

    private fun requestGatewayVoice(voiceId: String? = null): JSONObject {
        val baseUrl = findGateway()
            ?: throw IllegalStateException("没有发现电脑网关")
        return callVoiceEndpoint(baseUrl, voiceId)
    }

    private fun callVoiceEndpoint(baseUrl: String, voiceId: String?): JSONObject {
        val connection = (
            URL("${baseUrl.trimEnd('/')}/koyo/voice").openConnection() as HttpURLConnection
        ).apply {
            connectTimeout = 3000
            readTimeout = 8000
            requestMethod = if (voiceId == null) "GET" else "POST"
            if (voiceId != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (voiceId != null) {
            val payload = JSONObject()
                .put("voice", voiceId)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("音色接口请求失败 $code")
        }
        return JSONObject(body)
    }

    private fun discoverGateway(): String? {
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = GATEWAY_DISCOVERY_TIMEOUT_MS
                val query = GATEWAY_DISCOVERY_QUERY.toByteArray(Charsets.US_ASCII)
                val destinations = linkedSetOf(InetAddress.getByName("255.255.255.255"))
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue
                    networkInterface.interfaceAddresses.forEach { address ->
                        address.broadcast?.let(destinations::add)
                    }
                }
                destinations.forEach { destination ->
                    socket.send(
                        DatagramPacket(
                            query,
                            query.size,
                            destination,
                            GATEWAY_DISCOVERY_PORT
                        )
                    )
                }

                val response = DatagramPacket(ByteArray(128), 128)
                socket.receive(response)
                val text = String(
                    response.data,
                    0,
                    response.length,
                    Charsets.US_ASCII
                )
                if (text.trim() == GATEWAY_DISCOVERY_REPLY) {
                    "http://${response.address.hostAddress}:8888"
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findGateway(): String? {
        val gatewayPrefs = requireContext().getSharedPreferences(GATEWAY_PREFS_NAME, 0)
        val cached = normalizeBaseUrl(
            gatewayPrefs.getString(KEY_GATEWAY_URL, "").orEmpty()
        )
        val localCandidates = listOf(cached, "http://127.0.0.1:8888")
            .filter { it.isNotBlank() }
            .distinct()
        localCandidates.firstOrNull { probeGateway(it) }?.let { baseUrl ->
            gatewayPrefs.edit().putString(KEY_GATEWAY_URL, baseUrl).apply()
            return baseUrl
        }

        val discovered = discoverGateway() ?: return null
        if (!probeGateway(discovered)) return null
        gatewayPrefs.edit().putString(KEY_GATEWAY_URL, discovered).apply()
        return discovered
    }

    private fun probeGateway(baseUrl: String): Boolean {
        return try {
            val connection = (
                URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection
            ).apply {
                connectTimeout = 1800
                readTimeout = 1800
                requestMethod = "GET"
            }
            val ok = connection.responseCode in 200..299
            if (ok) connection.inputStream.close()
            connection.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun applyVoiceResponse(response: JSONObject) {
        val receivedOptions = response.optJSONArray("options")
        if (receivedOptions != null && receivedOptions.length() > 0) {
            val parsed = mutableListOf<VoiceOption>()
            for (index in 0 until receivedOptions.length()) {
                val item = receivedOptions.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val label = item.optString("label")
                // edge 晓晓已剔除，不进音色列表
                if (id.isNotBlank() && label.isNotBlank() && id != "edge") {
                    parsed.add(VoiceOption(id, label))
                }
            }
            if (parsed.isNotEmpty()) {
                voiceOptions.clear()
                voiceOptions.addAll(parsed)
            }
        }

        selectedVoiceId = response.optString("selected", selectedVoiceId)
            .takeIf { selected -> voiceOptions.any { it.id == selected } }
            ?: selectedVoiceId
        requireContext().getSharedPreferences(PREFS_NAME, 0).edit()
            .putString(companionScope.namespaced(KEY_TTS_VOICE), selectedVoiceId)
            .apply()
        updateVoiceStyleButton()
    }

    private fun updateVoiceStyleButton() {
        if (::voiceStyleButton.isInitialized) {
            voiceStyleButton.text = "音色 · ${voiceLabel(selectedVoiceId)}"
        }
    }

    private fun updateVoiceToggleButton() {
        if (::voiceToggleButton.isInitialized) {
            val enabled = voiceSwitch.isChecked
            voiceToggleButton.text = getString(
                if (enabled) R.string.chat_voice_on else R.string.chat_voice_off
            )
        }
    }

    private fun voiceLabel(voiceId: String): String {
        return voiceOptions.firstOrNull { it.id == voiceId }?.label ?: "晓晓"
    }

    /**
     * 扫描 MiniMax key 下的克隆音色，并进音色列表（语音按钮自适应）。
     * 失败静默保留默认音色。
     */
    private fun scanMiniMaxVoices() {
        val apiKey = TiyoAgentConfig.ttsApiKey(requireContext())
        if (apiKey.isBlank()) return
        Thread {
            try {
                val voices = TiyoMiniMaxTts.listVoices(apiKey)
                if (voices.isEmpty()) return@Thread
                activity?.runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    val known = voiceOptions.map { it.id }.toMutableSet()
                    val added = voices.filter { it.id !in known }
                    if (added.isNotEmpty()) {
                        voiceOptions.addAll(added.map { VoiceOption(it.id, it.name) })
                        updateVoiceStyleButton()
                    }
                }
            } catch (_: Exception) {
                // 扫描失败保持默认音色
            }
        }.start()
    }

    private fun requestSimple(path: String, callback: (Boolean, String) -> Unit) {
        Thread {
            val baseUrl = findController()
            if (baseUrl == null) {
                activity?.runOnUiThread {
                    if (isAdded && view != null) callback(false, "")
                }
                return@Thread
            }
            try {
                val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 8000
                    requestMethod = "GET"
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                connection.disconnect()
                activity?.runOnUiThread {
                    if (isAdded && view != null) callback(code in 200..299, response)
                }
            } catch (error: Exception) {
                activity?.runOnUiThread {
                    if (isAdded && view != null) callback(false, error.message.orEmpty())
                }
            }
        }.start()
    }

    private fun refreshStatus() {
        if (statusRequestInProgress || requestInProgress) return
        val throughCar = carChatSwitch.isChecked
        statusRequestInProgress = true
        Thread {
            try {
                val detail = if (throughCar) {
                    val baseUrl = findController()
                        ?: throw IllegalStateException("没有找到小车")
                    val connection = (
                        URL("$baseUrl/api/status").openConnection() as HttpURLConnection
                    ).apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                        requestMethod = "GET"
                    }
                    val code = connection.responseCode
                    val body = if (code in 200..299) {
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } else {
                        ""
                    }
                    connection.disconnect()
                    if (code !in 200..299) throw IllegalStateException("status $code")

                    val status = JSONObject(body)
                    val mode = status.optString("mode", "在线")
                    val face = faceLabel(status.optString("face", "idle"))
                    val battery = status.optString("bat", "")
                    requireContext().getSharedPreferences("car", 0).edit()
                        .putString("base_url", baseUrl)
                        .apply()
                    listOf(mode, face, battery.takeIf { it.isNotBlank() }?.let { "${it}V" })
                        .filterNotNull()
                        .joinToString(" · ")
                } else {
                    if (TiyoAgentConfig.isConfigured(requireContext())) {
                        if (agentClient?.isOpen() != true) mainHandler.post { connectAgent() }
                        if (agentClient?.isOpen() == true) "Tiyo Agent · 本机" else "Tiyo Agent · 正在准备"
                    } else {
                        findGateway() ?: throw IllegalStateException("没有找到电脑网关")
                        "电脑聊天网关 · 无本机工具"
                    }
                }
                activity?.runOnUiThread {
                    if (
                        isAdded && view != null && !requestInProgress &&
                        carChatSwitch.isChecked == throughCar
                    ) {
                        setConnectionState(true, detail)
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (
                        isAdded && view != null && !requestInProgress &&
                        carChatSwitch.isChecked == throughCar
                    ) {
                        setConnectionState(
                            false,
                            if (throughCar) "小车离线"
                            else if (TiyoAgentConfig.isConfigured(requireContext())) "Tiyo Agent · 暂未就绪"
                                                else "电脑里的${CompanionProfileStore.activeName(requireContext())}暂时离线"
                        )
                    }
                }
            } finally {
                statusRequestInProgress = false
            }
        }.start()
    }

    private fun startStatusPolling() {
        mainHandler.removeCallbacks(statusPoller)
        mainHandler.post(statusPoller)
    }

    private fun setConnectionState(online: Boolean, label: String) {
        statusText.text = label
        statusDot.setBackgroundResource(
            if (online) R.drawable.chat_status_online_bg else R.drawable.chat_status_offline_bg
        )
    }

    private fun faceLabel(face: String): String {
        return when (face.lowercase(Locale.ROOT)) {
            "happy" -> "开心"
            "worried" -> "担心"
            "sad" -> "难过"
            "angry" -> "生气"
            "gentle" -> "温柔"
            "excited" -> "期待"
            else -> "待机"
        }
    }

    private fun findController(): String? {
        val discovered = discoverControllerUdp()
        if (discovered != null && probeController(discovered)) return discovered

        val saved = normalizeBaseUrl(
            requireContext().getSharedPreferences("car", 0)
                .getString("base_url", "")
                .orEmpty()
        )
        val candidates = listOf(
            saved,
            "http://koyo-car.local",
            "http://192.168.4.1"
        ).filter { it.isNotBlank() }.distinct()
        return candidates.firstOrNull { probeController(it) }
    }

    private fun discoverControllerUdp(): String? {
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1800
                val query = "KOYO_DISCOVER".toByteArray(Charsets.US_ASCII)
                val broadcastAddresses = linkedSetOf<InetAddress>()
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue
                    networkInterface.interfaceAddresses.forEach { address ->
                        address.broadcast?.let { broadcastAddresses.add(it) }
                    }
                }
                broadcastAddresses.add(InetAddress.getByName("255.255.255.255"))
                broadcastAddresses.forEach { address ->
                    socket.send(DatagramPacket(query, query.size, address, DISCOVERY_PORT))
                }

                val buffer = ByteArray(128)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val text = String(response.data, 0, response.length, Charsets.US_ASCII)
                if (text.startsWith("KOYO_CAR:http://")) {
                    normalizeBaseUrl(text.removePrefix("KOYO_CAR:"))
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun probeController(baseUrl: String): Boolean {
        return try {
            val connection = (URL("$baseUrl/api/status").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1800
                readTimeout = 1800
                requestMethod = "GET"
            }
            val ok = connection.responseCode in 200..299
            if (ok) connection.inputStream.close()
            connection.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "http://$value"
        }
    }

    private fun beginVoiceInput() {
        if (requestInProgress) return
        if (speechListening) {
            offlineSpeech?.stop()
            return
        }
        if (!carChatSwitch.isChecked) stopReplyPlayback()
        if (
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchVoiceRecognizer()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchVoiceRecognizer() {
        speechBaseText = input.text.toString().trimEnd()
        speechListening = true
        setMicListening(true)
        notifyOled("/api/oled/listen")
        val recognizer = offlineSpeech ?: createOfflineSpeechController().also { offlineSpeech = it }
        runCatching { recognizer.start() }
            .onFailure { error ->
                finishVoiceRecognition()
                addSystemMessage("离线语音识别暂时无法启动：${error.message.orEmpty()}")
            }
    }

    private fun createOfflineSpeechController(): OfflineSpeechController = OfflineSpeechController(
        requireContext().applicationContext,
        object : OfflineSpeechController.Listener {
            override fun onPreparing() = onSpeechUi {
                if (::micButton.isInitialized) {
                    micButton.text = "准备中"
                    micButton.contentDescription = "正在准备离线语音识别"
                }
            }

            override fun onListening() = onSpeechUi {
                if (speechListening) setMicListening(true)
            }

            override fun onPartial(text: String) = onSpeechUi {
                if (speechListening && text.isNotBlank()) showRecognizedText(text)
            }

            override fun onResult(text: String) = onSpeechUi {
                val wasListening = speechListening
                finishVoiceRecognition()
                if (wasListening && text.isNotBlank()) showRecognizedText(text)
            }

            override fun onNoSpeech() = onSpeechUi {
                val wasListening = speechListening
                finishVoiceRecognition()
                if (wasListening) addSystemMessage("没听清，再说一次吧")
            }

            override fun onError(message: String) = onSpeechUi {
                val wasListening = speechListening
                finishVoiceRecognition()
                if (wasListening) addSystemMessage(message.ifBlank { "离线语音识别暂时不可用" })
            }
        }
    )

    private fun onSpeechUi(action: () -> Unit) {
        mainHandler.post {
            if (isAdded && view != null) action()
        }
    }

    /** 语音只落到输入框，留给用户看清和修改，不再识别完就自动发送 */
    private fun showRecognizedText(recognized: String) {
        if (!isAdded || view == null || !::input.isInitialized) return
        val visible = if (speechBaseText.isBlank()) recognized else "$speechBaseText $recognized"
        when (chatMode) {
            ChatModeManager.Mode.ROOM -> roomModeController?.setRecognizedText(visible)
            ChatModeManager.Mode.DESK -> deskModeController?.setRecognizedText(visible)
            ChatModeManager.Mode.FOCUS -> {
                input.setText(visible)
                input.setSelection(visible.length)
            }
        }
    }

    private fun finishVoiceRecognition() {
        speechListening = false
        setMicListening(false)
        notifyOled("/api/oled/idle")
    }

    private fun notifyOled(path: String) {
        if (!::carChatSwitch.isInitialized || !carChatSwitch.isChecked) return
        Thread {
            val baseUrl = findController() ?: return@Thread
            try {
                val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1800
                    readTimeout = 1800
                    requestMethod = "GET"
                }
                connection.responseCode
                connection.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun setMicListening(listening: Boolean) {
        if (!::micButton.isInitialized) return
        micButton.text = if (listening) "正在听" else ""
        micButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (listening) 0 else R.drawable.ic_focus_mic,
            0,
            0,
            0
        )
        micButton.contentDescription = if (listening) "正在听" else "语音输入"
    }

    private fun renderHistory() {
        messagesContainer.removeAllViews()
        lastAssistantRow = null
        if (messages.isEmpty()) {
            lastAssistantRow = addMessage(
                role = "assistant",
                text = "我在。你可以打字，也可以直接对我说话。",
                persist = true
            )
            return
        }
        var lastAssistant: View? = null
        messages.forEach {
            val row = renderMessage(it)
            if (it.role == "assistant") lastAssistant = row
        }
        lastAssistantRow = lastAssistant
        roomModeController?.render(modeLines())
        deskModeController?.render(modeLines())
        scrollToBottom()
    }

    private fun addMessage(
        role: String,
        text: String,
        persist: Boolean = true,
        sticker: String? = null
    ): View {
        val message = ChatMessage(role, text, System.currentTimeMillis(), sticker)
        if (persist) {
            val firstUserMessage = role == "user" && messages.none { it.role == "user" }
            messages.add(message)
            while (messages.size > MAX_HISTORY) messages.removeAt(0)
            saveHistory()
            if (firstUserMessage) {
                val title = text.replace(Regex("\\s+"), " ").trim().take(24)
        TiyoSessionStore.touch(requireContext(), companionScope, activeSessionId, title)
                if (drawerOpen) renderSessionDrawer()
            }
            // 用户发消息：重置主动消息的连续未回复计数
            if (role == "user") {
                ProactiveMessenger.onUserMessage(requireContext())
                MemoryShelfCoordinator.onUserMessage(requireContext(), text, message.timestamp)
            }
        }
        val row = renderMessage(message)
        if (persist) deliverModeMessage(message)
        return row
    }

    /** 外部（如主动消息 Worker）注入一条可又气泡：立即渲染并持久化，下次 loadHistory 也会显示 */
    fun exposeAssistantBubble(text: String) {
        if (!isAdded || view == null) return
        addMessage("assistant", text, persist = true)
    }

    /** 主动消息版本变化时重载历史（Worker 在后台落过气泡，切回聊天 tab 时刷新） */
    fun refreshProactiveBubbles() {
        val v = ProactiveMessenger.version(requireContext())
        if (v > seenProactiveVersion && !requestInProgress && isAdded && view != null) {
            seenProactiveVersion = v
            loadHistory()
            renderHistory()
            scrollToBottom()
        }
    }

    private fun addSystemMessage(text: String, onClick: (() -> Unit)? = null) {
        val row = addMessage("system", text, persist = false)
        if (onClick != null) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onClick() }
        }
    }

    /** 头像绑定：用户 → 相册图或默认；可又 → 自定义图或所选内置帧 */
    private fun bindAvatar(avatar: android.widget.ImageView, role: String) {
        if (role == "user") {
            val bmp = AvatarStore.loadUserBitmap(requireContext())
            if (bmp != null) avatar.setImageBitmap(bmp)
            else avatar.setImageResource(R.drawable.d_ic_me)
        } else {
            val custom = AvatarStore.loadCompanionBitmap(requireContext())
            if (custom != null) avatar.setImageBitmap(custom)
            else avatar.setImageResource(AvatarStore.companionRes(requireContext()))
        }
    }

    /** 头像设置改完后刷新头部和已渲染的气泡头像 */
    fun refreshAvatars() {
        if (!::messagesContainer.isInitialized) return
        for (i in 0 until messagesContainer.childCount) {
            val avatar = messagesContainer.getChildAt(i)
                .findViewById<android.widget.ImageView>(R.id.chat_avatar) ?: continue
            bindAvatar(avatar, (avatar.tag as? String) ?: "assistant")
        }
        view?.findViewById<android.widget.ImageView>(R.id.chat_header_avatar)?.let {
            bindAvatar(it, "assistant")
        }
    }

    private fun renderMessage(message: ChatMessage): View {
        if (message.role != "system" && PaperSheetView.shouldUse(message.text)) {
            val paper = PaperSheetView(requireContext()).bind(
                PaperSheetView.titleFor(message.text),
                message.text
            )
            messagesContainer.addView(paper)
            scrollToBottom()
            return paper
        }
        val layout = when {
            message.role == "user" -> R.layout.item_chat_user
            message.role == "system" -> R.layout.item_chat_system
            message.sticker != null && StickerStore.has(requireContext(), message.sticker) ->
                R.layout.item_chat_koyo_sticker
            else -> R.layout.item_chat_koyo
        }
        val row = LayoutInflater.from(requireContext())
            .inflate(layout, messagesContainer, false)
        row.findViewById<TextView>(R.id.chat_message_text)?.apply {
            text = message.text
            if (text.isNullOrBlank()) visibility = View.GONE
        }
        row.findViewById<TextView>(R.id.chat_message_time)?.text =
            timeFormatter.format(Date(message.timestamp))
        // 表情包消息：显示表情包图
        if (layout == R.layout.item_chat_koyo_sticker && message.sticker != null) {
            StickerStore.loadBitmap(requireContext(), message.sticker)?.let { bmp ->
                row.findViewById<android.widget.ImageView>(R.id.chat_sticker_image)
                    .setImageBitmap(bmp)
            }
        }
        // 头像可换：只改视觉绑定，不碰消息逻辑
        row.findViewById<android.widget.ImageView>(R.id.chat_avatar)?.let { avatar ->
            avatar.tag = message.role
            bindAvatar(avatar, message.role)
        }
        messagesContainer.addView(row)
        scrollToBottom()
        return row
    }

    /** 从 AI 回复提取 {sticker:名字}，返回(去掉标记的正文, 表情包名或null) */
    private fun extractSticker(text: String): Pair<String, String?> {
        val m = Regex("\\{sticker:([^{}]+)\\}").find(text) ?: return text to null
        val name = m.groupValues[1].trim()
        val cleaned = text.replace(m.value, "").trim()
        return cleaned to name
    }

    /** 渲染 agent 生成的图片消息（data URL: data:image/png;base64,...）。 */
    private fun renderImageMessage(dataUrl: String) {
        if (dataUrl.isBlank() || !isAdded) return
        val base64 = dataUrl.substringAfter(',', dataUrl)
        val bytes = try {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            return
        }
        val bitmap = try {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } ?: return
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_chat_koyo_image, messagesContainer, false)
        val imageView = row.findViewById<android.widget.ImageView>(R.id.chat_image)
        imageView.setImageBitmap(bitmap)
        imageView.setOnClickListener { showFullScreenImage(bitmap) }
        imageView.setOnLongClickListener {
            saveImageToGallery(bitmap)
            true
        }
        row.findViewById<View>(R.id.chat_image_save)?.setOnClickListener {
            saveImageToGallery(bitmap)
        }
        row.findViewById<android.widget.TextView>(R.id.chat_message_time)?.text =
            timeFormatter.format(Date(System.currentTimeMillis()))
        // 头像跟随当前选的可又头像
        row.findViewById<android.widget.ImageView>(R.id.chat_avatar)
            ?.let { bindAvatar(it, "assistant") }
        messagesContainer.addView(row)
        if (chatMode != ChatModeManager.Mode.FOCUS) {
            deliverModeMessage(
                ChatMessage(
                    "assistant",
                    "图片已经放进这次对话里，切到专注视图可以打开查看",
                    System.currentTimeMillis()
                )
            )
        }
        scrollToBottom()
    }

    /** 把图片保存到系统相册 */
    private fun saveImageToGallery(bitmap: android.graphics.Bitmap) {
        try {
            val resolver = requireContext().contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "tiyo_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/tiyo")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    Toast.makeText(requireContext(), "已保存到相册", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val url = MediaStore.Images.Media.insertImage(
                    resolver, bitmap, "tiyo_${System.currentTimeMillis()}", null
                )
                Toast.makeText(
                    requireContext(),
                    if (url != null) "已保存到相册" else "保存失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 点击图片全屏查看，再点关闭 */
    private fun showFullScreenImage(bitmap: android.graphics.Bitmap) {
        val dialog = android.app.Dialog(
            requireContext(),
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        )
        val imageView = android.widget.ImageView(requireContext())
        imageView.setImageBitmap(bitmap)
        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(imageView)
        dialog.show()
    }

    /** 渲染一个表情包气泡（agent 流式回复末尾带表情包时补显示） */
    private fun renderStickerBubble(sticker: String) {
        val bmp = StickerStore.loadBitmap(requireContext(), sticker) ?: return
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_chat_koyo_image, messagesContainer, false)
        row.findViewById<android.widget.ImageView>(R.id.chat_image).setImageBitmap(bmp)
        row.findViewById<android.widget.TextView>(R.id.chat_message_time)?.text =
            timeFormatter.format(Date(System.currentTimeMillis()))
        row.findViewById<android.widget.ImageView>(R.id.chat_avatar)
            ?.let { bindAvatar(it, "assistant") }
        messagesContainer.addView(row)
        scrollToBottom()
    }

    private fun showTyping() {
        hideTyping()
        typingView = addMessage("assistant", "正在想…", persist = false)
        roomModeController?.setThinking(chatMode == ChatModeManager.Mode.ROOM)
    }

    private fun hideTyping() {
        typingView?.let { messagesContainer.removeView(it) }
        typingView = null
        roomModeController?.setThinking(false)
    }

    private fun showAssistantReply(text: String) {
        // 按回复内容挑动作。开了语音时 setSpeaking 已经在放说话动画,别抢
        if (!voiceSwitch.isChecked) koyoDock?.reactToContent(text)
        val (clean, sticker) = StickerStore.extractSticker(text)
        val message = ChatMessage("assistant", clean, System.currentTimeMillis(), sticker)
        messages.add(message)
        while (messages.size > MAX_HISTORY) messages.removeAt(0)
        saveHistory()

        val row = renderMessage(message)
        deliverModeMessage(message)
        lastAssistantRow = row
        val textView = row.findViewById<TextView>(R.id.chat_message_text) ?: return
        val animationsEnabled = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
        if (!animationsEnabled || clean.length < 3) return

        val codePoints = clean.codePoints().toArray()
        textView.text = ""
        replyRevealTextView = textView
        replyRevealFullText = text
        var visibleCount = 0
        val reveal = object : Runnable {
            override fun run() {
                if (!isAdded || view == null || replyRevealTextView !== textView) return
                visibleCount = minOf(visibleCount + 2, codePoints.size)
                textView.text = String(codePoints, 0, visibleCount)
                scrollToBottom()
                if (visibleCount < codePoints.size) {
                    mainHandler.postDelayed(this, 28L)
                } else {
                    replyRevealRunnable = null
                    replyRevealTextView = null
                    replyRevealFullText = null
                }
            }
        }
        replyRevealRunnable = reveal
        mainHandler.post(reveal)
    }

    private fun finishReplyReveal() {
        replyRevealRunnable?.let { mainHandler.removeCallbacks(it) }
        replyRevealTextView?.text = replyRevealFullText.orEmpty()
        replyRevealRunnable = null
        replyRevealTextView = null
        replyRevealFullText = null
    }

    /** 用户是否正在主动滚动（向上翻历史时不被打断） */
    private var userScrolling = false
    private var userNearBottom = true

    /** 是否接近底部：离底部 200dp 内视为在底部，才允许自动滚动 */
    private fun isNearBottom(): Boolean {
        if (!::chatScroll.isInitialized) return true
        val scroll = chatScroll
        val viewport = scroll.height
        val content = scroll.getChildAt(0)?.height ?: 0
        val maxScroll = (content - viewport).coerceAtLeast(0)
        return scroll.scrollY >= maxScroll - dp(200)
    }

    private fun scrollToBottom(force: Boolean = false) {
        if (!::chatScroll.isInitialized) return
        if (!force && (userScrolling || !userNearBottom)) return
        chatScroll.post {
            val content = chatScroll.getChildAt(0) ?: return@post
            val bottom = (content.height - chatScroll.height).coerceAtLeast(0)
            chatScroll.scrollTo(0, bottom)
            userNearBottom = true
        }
    }

    private fun updateComposerState() {
        if (!::sendButton.isInitialized) return
        val hasText = input.text.toString().trim().isNotEmpty()
        val hasAttachments = pendingAttachments.isNotEmpty()
        val canJumpIn = requestInProgress &&
            !carChatSwitch.isChecked &&
            TiyoAgentConfig.isConfigured(requireContext()) &&
            agentClient?.isOpen() == true
        sendButton.isEnabled = (hasText || hasAttachments) && (!requestInProgress || canJumpIn)
        sendButton.text = if (canJumpIn) "补充" else ""
        sendButton.foreground = if (canJumpIn) null else {
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_focus_send)
        }
        sendButton.contentDescription = if (canJumpIn) "补充到当前任务" else getString(R.string.chat_send)
        sendButton.alpha = if (sendButton.isEnabled) 1f else 0.64f
        micButton.isEnabled = !requestInProgress
        micButton.alpha = if (micButton.isEnabled) 1f else 0.55f
        carChatSwitch.isEnabled = !requestInProgress
        agentSettingsButton.isEnabled = !requestInProgress
        agentSettingsButton.alpha = if (agentSettingsButton.isEnabled) 1f else 0.5f
        if (::planButton.isInitialized) {
            planButton.isEnabled = !requestInProgress
            planButton.alpha = if (planButton.isEnabled) 1f else 0.52f
        }
        if (::sessionsButton.isInitialized) {
            sessionsButton.isEnabled = !requestInProgress
            sessionsButton.alpha = if (sessionsButton.isEnabled) 1f else 0.52f
        }
    }

    private fun localRouteLabel(): String {
        return if (TiyoAgentConfig.isConfigured(requireContext())) "Tiyo Agent · 就绪"
        else "电脑聊天网关 · 无本机工具"
    }

    private fun uniqueTarget(directory: File, requestedName: String): File {
        val base = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        var candidate = File(directory, requestedName)
        var index = 2
        while (candidate.exists()) {
            val name = if (extension.isBlank()) "$base-$index" else "$base-$index.$extension"
            candidate = File(directory, name)
            index += 1
        }
        return candidate
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hideKeyboard() {
        val keyboard = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun loadHistory() {
        messages.clear()
        val raw = TiyoSessionStore.history(requireContext(), companionScope, activeSessionId) ?: return
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val text = item.optString("text")
                if (role !in setOf("user", "assistant") || text.isBlank()) continue
                messages.add(
                    ChatMessage(
                        role = role,
                        text = text,
                        timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                        sticker = item.optString("sticker", "").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (_: Exception) {
            messages.clear()
        }
    }

    private fun saveHistory() {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("role", message.role)
                    .put("text", message.text)
                    .put("timestamp", message.timestamp)
            )
        }
        TiyoSessionStore.saveHistory(requireContext(), companionScope, activeSessionId, array.toString())
    }

    private class SimpleTextWatcher(
        private val afterChanged: () -> Unit
    ) : android.text.TextWatcher {
        override fun beforeTextChanged(
            text: CharSequence?,
            start: Int,
            count: Int,
            after: Int
        ) = Unit

        override fun onTextChanged(
            text: CharSequence?,
            start: Int,
            before: Int,
            count: Int
        ) = Unit

        override fun afterTextChanged(editable: android.text.Editable?) {
            afterChanged()
        }
    }

    companion object {
        private const val PREFS_NAME = "tiyo_chat"
        private const val KEY_QUICK_CUSTOM = "quick_custom"
        private const val KEY_HISTORY = "messages_v1"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_CAR_CHAT_ENABLED = "car_chat_enabled"
        private const val KEY_AGENT_SESSION = "agent_session_v1"
        private const val KEY_PLAN_MODE = "agent_plan_mode"
        private const val KEY_CHAT_MODE_STATE = "chat_mode_state"
        private const val GATEWAY_PREFS_NAME = "tiyo_realtime_state"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val GATEWAY_DISCOVERY_QUERY = "KOYO_GATEWAY_DISCOVER"
        private const val GATEWAY_DISCOVERY_REPLY = "KOYO_GATEWAY"
        private const val GATEWAY_DISCOVERY_PORT = 4211
        private const val GATEWAY_DISCOVERY_TIMEOUT_MS = 1800
        private const val MAX_HISTORY = 60
        private const val MAX_EXTRACT_TURNS = 8
        private const val DISCOVERY_PORT = 4210
        private const val STATUS_POLL_MS = 5000L
    }
}
