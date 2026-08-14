package com.koyo.screenwarden

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal class DeepCompanionHostView(
    context: Context,
    private val bridge: DeepCompanionChatBridge,
    private val onExit: () -> Unit,
    private val onSceneChanged: (Scene) -> Unit,
    private val isKoyoDrowsy: () -> Boolean
) : FrameLayout(context) {

    enum class Scene { ROOM, DESK, SHELF, DIARY }

    private val density = resources.displayMetrics.density
    private val sceneView = DeepCompanionSceneView(context)
    private val ambience = DeepCompanionAmbienceView(context)
    private val backLayers = DeepInteractiveLayersView(context, foreground = false, ::onSceneElementTapped)
    private val frontLayers = DeepInteractiveLayersView(context, foreground = true, ::onSceneElementTapped)
    private val ui = FrameLayout(context)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val identityScope = CompanionScope.capture(context)
    private val ambiencePrefs = context.getSharedPreferences(
        identityScope.namespaced(AMBIENCE_PREFS),
        Context.MODE_PRIVATE
    )
    private val deskPrefs = context.getSharedPreferences(
        identityScope.namespaced(DESK_PREFS),
        Context.MODE_PRIVATE
    )
    private val guidePrefs = context.getSharedPreferences(
        identityScope.namespaced(GUIDE_PREFS),
        Context.MODE_PRIVATE
    )
    private val guideCallbacks = mutableListOf<Runnable>()
    private var weatherJob: Job? = null
    private var imeInset = 0
    private var current = Scene.ROOM
    private var speech: TextView? = null
    private var transcript: TextView? = null
    private var statusPill: TextView? = null
    private var attachmentStatus: TextView? = null
    private var defaultModeSwitch: SwitchCompat? = null
    private var deskStickerView: ImageView? = null
    private var deskStickerName = ""
    private var deskTimerView: TextView? = null
    private var deskPlanView: TextView? = null
    private var syncingDefaultSwitch = false
    private var latestReply = ""
    private var promptVisible = false
    private var speechGeneration = 0
    private var shelfPage = 0
    private var shelfPageEntries: List<MemoryShelfStore.Entry> = emptyList()
    private var roomIntroShownForOpen = false
    private val companionName: String
        get() = identityScope.displayName
    private val roomIntroToResident = Runnable {
        if (visibility == VISIBLE && current == Scene.ROOM) showRoomResidentFrames()
    }

    private val poller = object : Runnable {
        override fun run() {
            if (visibility == VISIBLE) {
                val next = bridge.latestKoyoLine().orEmpty()
                if (next.isNotBlank() && next != latestReply) {
                    latestReply = next
                    showSpeech(next)
                    refreshTranscript()
                }
                refreshStatus()
                refreshAttachmentStatus()
                checkDeskTimerFinished()
                if (current == Scene.DESK) {
                    refreshDeskTimer()
                    refreshDeskSticker()
                }
                handler.postDelayed(this, 700L)
            }
        }
    }

    private val ambienceTicker = object : Runnable {
        override fun run() {
            if (visibility != VISIBLE) return
            ambience.refreshClock()
            refreshWeatherAmbience()
            handler.postDelayed(this, 60_000L)
        }
    }

    init {
        visibility = GONE
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(sceneView, match())
        addView(ambience, match())
        addView(backLayers, match())
        addView(frontLayers, match())
        addView(ui, match())
        installTopPull()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyImeInset(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            this,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyImeInset(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                    return insets
                }
            }
        )
    }

    fun open(animated: Boolean, askDefault: Boolean) {
        hideKeyboard()
        visibility = VISIBLE
        roomIntroShownForOpen = false
        alpha = if (animated) 0f else 1f
        latestReply = bridge.latestKoyoLine().orEmpty()
        showScene(Scene.ROOM)
        refreshWeatherAmbience()
        handler.removeCallbacks(ambienceTicker)
        handler.post(ambienceTicker)
        ViewCompat.requestApplyInsets(this)
        if (animated) animate().alpha(1f).setDuration(520L).start()
        handler.removeCallbacks(poller)
        handler.post(poller)
        if (askDefault) handler.postDelayed({ showDefaultQuestion() }, 1050L)
    }

    fun close(animated: Boolean, finished: () -> Unit = {}) {
        handler.removeCallbacks(poller)
        handler.removeCallbacks(ambienceTicker)
        cancelInteractionGuides()
        hideKeyboard()
        if (!animated) {
            visibility = GONE
            finished()
            return
        }
        animate().alpha(0f).setDuration(260L).withEndAction {
            visibility = GONE
            alpha = 1f
            finished()
        }.start()
    }

    fun handleBack(): Boolean {
        if (visibility != VISIBLE) return false
        if (ui.findFocus() is EditText) {
            hideKeyboard()
            ui.findFocus()?.clearFocus()
            return true
        }
        if (promptVisible) {
            dismissPrompt(saveDefault = false)
            return true
        }
        if (ui.findViewWithTag<View>(TAG_DESK_PANEL) != null) {
            dismissDeskPanel()
            return true
        }
        when (current) {
            Scene.DIARY -> showScene(Scene.SHELF)
            Scene.SHELF, Scene.DESK -> showScene(Scene.ROOM)
            Scene.ROOM -> onExit()
        }
        return true
    }

    fun onKoyoTapped(): Boolean {
        if (visibility != VISIBLE) return false
        when (current) {
            Scene.ROOM -> {
                playRoomGreeting()
                showSpeech(latestReply.ifBlank { "我在呢，想说什么就说" })
                ui.findViewWithTag<View>(TAG_COMPOSER)?.let { revealComposer(it) }
            }
            Scene.DESK -> ui.findViewWithTag<EditText>(TAG_INPUT)?.requestFocus()
            Scene.SHELF -> showShelfDetail("别急着把每件事都翻完，慢慢看")
            Scene.DIARY -> Unit
        }
        return true
    }

    private fun showScene(scene: Scene) {
        cancelInteractionGuides()
        val previous = current
        val changingScene = previous != scene
        if (changingScene) {
            hideKeyboard()
            ui.findFocus()?.clearFocus()
            cancelSceneAnimations()
        }
        if (scene == Scene.SHELF && previous != Scene.SHELF && previous != Scene.DIARY) {
            shelfPage = 0
        }
        current = scene
        promptVisible = false
        speech = null
        transcript = null
        statusPill = null
        attachmentStatus = null
        defaultModeSwitch = null
        deskStickerView = null
        deskStickerName = ""
        deskTimerView = null
        deskPlanView = null
        ui.removeAllViews()
        backLayers.showScene(scene)
        frontLayers.showScene(scene)
        ambience.showScene(scene)
        when (scene) {
            Scene.ROOM -> buildRoom()
            Scene.DESK -> buildDesk()
            Scene.SHELF -> buildShelf()
            Scene.DIARY -> buildDiary()
        }
        applyImeInset(imeInset)
        installTopPull()
        onSceneChanged(scene)
        if (changingScene) animateSceneEntrance(scene)
        scheduleInteractionGuides(scene)
    }

    private fun scheduleInteractionGuides(scene: Scene) {
        val ids = when (scene) {
            Scene.ROOM -> listOf("room_desk", "room_shelf", "room_lantern", "room_crystal")
            Scene.DESK -> listOf("desk_timer", "desk_plan", "desk_sticker")
            Scene.SHELF -> listOf("shelf_day1", "shelf_day4", "shelf_day6")
            Scene.DIARY -> emptyList()
        }
        if (ids.isEmpty()) return
        val key = "${GUIDE_VISIT_PREFIX}${scene.name.lowercase(Locale.ROOT)}"
        val visits = guidePrefs.getInt(key, 0)
        if (visits >= GUIDE_MAX_VISITS) return
        guidePrefs.edit().putInt(key, visits + 1).apply()

        val initialDelay = if (scene == Scene.ROOM) 2_250L else 1_050L
        ids.forEachIndexed { index, id ->
            val callback = Runnable {
                backLayers.guideElement(id)
                frontLayers.guideElement(id)
            }
            guideCallbacks += callback
            handler.postDelayed(callback, initialDelay + index * 1_150L)
        }
    }

    private fun cancelInteractionGuides() {
        guideCallbacks.forEach(handler::removeCallbacks)
        guideCallbacks.clear()
        backLayers.cancelGuide()
        frontLayers.cancelGuide()
    }

    private fun cancelSceneAnimations() {
        sceneView.animate().cancel()
        backLayers.animate().cancel()
        frontLayers.animate().cancel()
        ui.animate().cancel()
    }

    private fun animateSceneEntrance(scene: Scene) {
        val direction = when (scene) {
            Scene.ROOM -> -1f
            Scene.DESK, Scene.SHELF, Scene.DIARY -> 1f
        }
        sceneView.alpha = 0f
        sceneView.scaleX = 1.018f
        sceneView.scaleY = 1.018f
        backLayers.alpha = 0f
        frontLayers.alpha = 0f
        ui.alpha = 0f
        ui.translationY = dp(14).toFloat() * direction

        sceneView.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(320L).start()
        backLayers.animate().alpha(1f).setStartDelay(70L).setDuration(280L).start()
        frontLayers.animate().alpha(1f).setStartDelay(90L).setDuration(300L).start()
        ui.animate().alpha(1f).translationY(0f)
            .setStartDelay(110L).setDuration(300L).start()
    }

    private fun buildRoom() {
        if (!roomIntroShownForOpen) {
            roomIntroShownForOpen = true
            handler.removeCallbacks(roomIntroToResident)
            sceneView.showFrames("scenes/room_idle.png")
            handler.postDelayed(roomIntroToResident, 2_000L)
        } else {
            showRoomResidentFrames()
        }
        addTitle("欢迎来到${companionName}的房间")

        val roomSpeech = TextView(context).apply {
            textSize = 16f
            setTextColor(0xFFF5E9D5.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(8))
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xD51A100B.toInt())
            alpha = 0f
            visibility = GONE
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        speech = roomSpeech
        ui.addView(roomSpeech, LayoutParams(dp(176), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            marginEnd = dp(12)
            topMargin = dp(22)
        })
        installDefaultModeSwitch()

        ui.addView(buildComposer(collapsed = true), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = dp(14)
        })
        installAttachmentStatus(bottomMargin = 76)
    }

    private fun onSceneElementTapped(id: String) {
        cancelInteractionGuides()
        when (id) {
            "room_koyo" -> onKoyoTapped()
            "room_desk" -> showScene(Scene.DESK)
            "room_shelf" -> showScene(Scene.SHELF)
            "room_lantern" -> showSpeech("灯再亮一点，房间就更暖了")
            "room_crystal", "room_charm" -> showSpeech("它会记住刚才这一点光")
            "room_curtain" -> showSpeech("外面是夜色，里面有我")
            "desk_timer" -> showDeskTimerPanel()
            "desk_plan" -> deskPlanView?.let(::showPlanChecklist)
            "desk_sticker" -> playDeskStickerReaction()
            "desk_koyo" -> {
                sceneView.pulseNow()
                showSpeech("嗯，我陪你一起做")
            }
            "desk_paper" -> ui.findViewWithTag<View>(TAG_COMPOSER)?.let(::revealComposer)
            "shelf_page_older" -> changeShelfPage(older = true)
            "shelf_page_newer" -> changeShelfPage(older = false)
            else -> if (id.startsWith("shelf_day")) {
                val index = id.removePrefix("shelf_day").toIntOrNull()?.minus(1) ?: return
                val entry = shelfPageEntries.getOrNull(index)
                showShelfDetail(
                    entry?.let {
                        "${it.objectName.ifBlank { defaultShelfObjectName(it.objectId) }} · ${it.date}\n${it.summary}"
                    } ?: "这格还空着，等以后有件值得留下的小事"
                )
            } else when {
                id.startsWith("diary_") -> ui.findViewWithTag<TextView>(TAG_DIARY_DETAIL)?.animate()?.alpha(1f)?.setDuration(240L)?.start()
            }
        }
    }

    private fun buildDesk() {
        sceneView.showFrames(
            "scenes/desk_fullscreen_v3.png",
            "scenes/desk_fullscreen_blink_v3.png",
            intervalMs = 5_200L,
            durationMs = 280L
        )
        addTitle("")
        val aligned = DeepSceneAlignedLayout(context, Scene.DESK)
        ui.addView(aligned, match())

        val text = TextView(context).apply {
            textSize = 15.5f
            setTextColor(0xFF493426.toInt())
            setLineSpacing(dp(7).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(28))
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        transcript = text
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        aligned.addSourceView(scroll, RectF(.145f, .305f, .925f, .895f))

        val whisper = TextView(context).apply {
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(0xFF5A4130.toInt())
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x66FFF4DF)
            typeface = Typeface.create("serif", Typeface.NORMAL)
            visibility = GONE
            alpha = 0f
        }
        speech = whisper
        aligned.addSourceView(whisper, RectF(.30f, .245f, .88f, .286f))

        val timer = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(0xFF4A382A.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = null
            contentDescription = "书桌倒计时"
            setOnClickListener { showDeskTimerPanel() }
        }
        deskTimerView = timer
        aligned.addSourceView(timer, RectF(.045f, .045f, .205f, .116f))

        val plan = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(0xFF3F3127.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            typeface = Typeface.create("serif", Typeface.BOLD)
            background = null
            rotation = -5.5f
            contentDescription = "任务计划书，点按查看和勾选，长按编辑"
            setOnClickListener { showPlanChecklist(this) }
            setOnLongClickListener { showDeskPlanEditor(this); true }
        }
        deskPlanView = plan
        aligned.addSourceView(plan, RectF(.055f, .145f, .222f, .225f))

        val sticker = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            rotation = -2.2f
            background = null
            contentDescription = "$companionName 刚刚留下的表情便签"
            setOnClickListener { playDeskStickerReaction() }
        }
        deskStickerView = sticker
        aligned.addSourceView(sticker, RectF(.045f, .292f, .208f, .392f))

        ui.addView(buildComposer(collapsed = true), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(10)
            marginEnd = dp(10)
            bottomMargin = dp(6)
        })
        installAttachmentStatus(bottomMargin = 64)
        renderPlanBook(plan)
        refreshDeskTimer()
        refreshDeskSticker()
        refreshTranscript()
    }

    private fun buildShelf() {
        sceneView.showFrames(
            "scenes/shelf_fullscreen_v2.png",
            "scenes/shelf_fullscreen_glow_v2.png",
            intervalMs = 3_200L,
            durationMs = 900L
        )
        addTitle("")
        shelfPageEntries = MemoryShelfStore.pagedEntries(context, shelfPage * SHELF_PAGE_SIZE, SHELF_PAGE_SIZE)
            .reversed()

        val aligned = DeepSceneAlignedLayout(context, Scene.SHELF)
        ui.addView(aligned, match())

        val detail = TextView(context).apply {
            tag = TAG_SHELF_DETAIL
            text = "这里放的不是聊天记录，是一起经历过的东西"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF5B402C.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99FFF1D5.toInt())
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        speech = detail
        aligned.addSourceView(detail, RectF(.08f, .835f, .92f, .935f))
        installHiddenBook(aligned)
    }

    private fun changeShelfPage(older: Boolean) {
        if (current != Scene.SHELF) return
        val total = MemoryShelfStore.totalEntryCount(context)
        val maxPage = ((total - 1).coerceAtLeast(0)) / SHELF_PAGE_SIZE
        val next = (shelfPage + if (older) 1 else -1).coerceIn(0, maxPage)
        if (next == shelfPage) {
            showShelfDetail(if (older) "更早的物件还没有来到这里" else "已经回到最近留下的物件")
            return
        }
        shelfPage = next
        showScene(Scene.SHELF)
        val range = shelfPageEntries.map { it.date }.let { dates ->
            if (dates.isEmpty()) "" else "${dates.first()} — ${dates.last()}"
        }
        showShelfDetail(range.ifBlank { "这一层还空着" })
    }

    private fun defaultShelfObjectName(id: String): String = when (id) {
        "screw" -> "螺丝"
        "glass_orb" -> "玻璃珠"
        "paper_crane" -> "纸鹤"
        "key" -> "钥匙"
        "pressed_flower" -> "押花"
        "paper_ball" -> "纸团"
        else -> "旧书"
    }

    private fun buildDiary() {
        sceneView.showFrames("diary_bg.png")
        addTitle("没有名字的夜航图")
        val journals = MemoryShelfStore.journalEntries(context).takeLast(8)
        val detail = TextView(context).apply {
            tag = TAG_DIARY_DETAIL
            textSize = 15f
            setTextColor(0xFFF2E3BC.toInt())
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xF2091021.toInt())
            typeface = Typeface.create("serif", Typeface.NORMAL)
            text = journals.lastOrNull()?.let { (date, body) -> "$date\n\n$body" }
                ?: "这里还没有写下来的那一页"
        }
        ui.addView(detail, LayoutParams(dp(252), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            marginEnd = dp(18)
        })
        val liveReply = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFFF3E5C8.toInt())
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xEF08101B.toInt())
            typeface = Typeface.create("serif", Typeface.NORMAL)
            visibility = GONE
            alpha = 0f
        }
        speech = liveReply
        ui.addView(liveReply, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = dp(18)
        })
        val nodeY = floatArrayOf(.12f, .22f, .33f, .44f, .55f, .65f, .75f, .84f)
        journals.forEachIndexed { index, (date, body) ->
            val node = TextView(context).apply {
                text = "✦"
                textSize = if (index % 3 == 0) 24f else 18f
                gravity = Gravity.CENTER
                setTextColor(0xFFD9F2FF.toInt())
                setShadowLayer(dp(8).toFloat(), 0f, 0f, 0xFF62BCEB.toInt())
                contentDescription = "$date 的日记"
                setOnClickListener { detail.text = "$date\n\n$body" }
            }
            ui.addView(node, LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.TOP or Gravity.START
                marginStart = dp(if (index % 2 == 0) 34 else 82)
                topMargin = ((resources.displayMetrics.heightPixels - dp(52)) * nodeY[index]).toInt()
            })
        }
    }

    private fun installHiddenBook(aligned: DeepSceneAlignedLayout) {
        if (!isKoyoDrowsy()) return
        val book = View(context).apply { contentDescription = null }
        aligned.addSourceView(book, RectF(.60f, .56f, .96f, .74f))
        var downX = 0f
        var downAt = 0L
        book.setOnTouchListener { _, event ->
            if (!isKoyoDrowsy()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downAt = event.eventTime
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = (event.eventTime - downAt).coerceAtLeast(1L)
                    val distance = downX - event.x
                    val speed = distance / elapsed.toFloat()
                    if (distance >= dp(72) && elapsed >= 850L && speed <= density * .22f) {
                        if (MemoryShelfStore.journalEntries(context).isNotEmpty()) showScene(Scene.DIARY)
                        else showShelfDetail("这本书现在还是空的，等我真的写下一页再给你看")
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun buildComposer(collapsed: Boolean): View {
        val bar = LinearLayout(context).apply {
            tag = TAG_COMPOSER
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            if (collapsed) {
                background = null
                elevation = 0f
            } else {
                background = rounded(0xF3F5E9D5.toInt(), 24f, 0x586E543E)
                elevation = dp(7).toFloat()
            }
            alpha = 1f
        }
        val input = EditText(context).apply {
            tag = TAG_INPUT
            hint = if (collapsed) "说句话" else "写在这里"
            textSize = 15f
            setTextColor(if (collapsed) 0xFFF6E9D2.toInt() else 0xFF34261E.toInt())
            setHintTextColor(if (collapsed) 0xFFDCC9AA.toInt() else 0xFF766555.toInt())
            if (collapsed) setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xD20F0906.toInt())
            background = null
            maxLines = 4
            setPadding(dp(12), 0, dp(8), 0)
            setOnFocusChangeListener { _, focused -> if (focused) revealComposer(bar) }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(iconActionButton(R.drawable.ic_focus_image, "选择图片", collapsed) {
            bridge.triggerImage()
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        bar.addView(iconActionButton(R.drawable.ic_focus_file, "选择文件", collapsed) {
            bridge.triggerFile()
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        bar.addView(actionButton("⌁", "语音输入", collapsed) { bridge.triggerVoice() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(actionButton("➤", "发送", collapsed) {
            if (bridge.send(input.text?.toString().orEmpty())) {
                input.text?.clear()
                if (current == Scene.DESK) sceneView.pulseNow()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(3) })
        return bar
    }

    private fun installAttachmentStatus(bottomMargin: Int) {
        val status = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFEAD9BF.toInt())
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xD20F0906.toInt())
            background = null
            visibility = GONE
            setOnClickListener {
                bridge.clearPendingAttachments()
                refreshAttachmentStatus()
            }
        }
        attachmentStatus = status
        ui.addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(20)
            marginEnd = dp(20)
            this.bottomMargin = dp(bottomMargin)
        })
        refreshAttachmentStatus()
    }

    private fun refreshAttachmentStatus() {
        val target = attachmentStatus ?: return
        val names = bridge.pendingAttachmentNames()
        target.visibility = if (names.isEmpty()) GONE else VISIBLE
        target.text = if (names.isEmpty()) "" else {
            val shown = names.take(2).joinToString("、") { it.take(16) }
            val rest = if (names.size > 2) " 等${names.size}项" else ""
            "已放上桌：$shown$rest · 点此清空"
        }
    }

    private fun showDefaultQuestion() {
        if (visibility != VISIBLE || DeepCompanionPrefs.hasAskedDefault(context)) return
        promptVisible = true
        val scrim = FrameLayout(context).apply {
            tag = TAG_PROMPT
            setBackgroundColor(0x6E07101A)
            isClickable = true
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(21), dp(22), dp(18))
            background = null
            elevation = 0f
            addView(TextView(context).apply {
                text = "下次来找我，要直接到这里吗"
                textSize = 17f
                setTextColor(0xFFF4E5CB.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xEF070B13.toInt())
                setPadding(0, 0, 0, dp(15))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(promptButton("直接到这里") { dismissPrompt(saveDefault = true) })
            addView(promptButton("还是先去聊天") { dismissPrompt(saveDefault = false) })
        }
        scrim.addView(panel, LayoutParams(dp(310), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        ui.addView(scrim, match())
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(280L).start()
    }

    private fun dismissPrompt(saveDefault: Boolean) {
        DeepCompanionPrefs.answerDefaultQuestion(context, saveDefault)
        syncingDefaultSwitch = true
        defaultModeSwitch?.isChecked = saveDefault
        syncingDefaultSwitch = false
        promptVisible = false
        ui.findViewWithTag<View>(TAG_PROMPT)?.let { prompt ->
            prompt.animate().alpha(0f).setDuration(180L).withEndAction { ui.removeView(prompt) }.start()
        }
    }

    private fun addTitle(text: String) {
        if (text.isNotBlank()) {
            val title = TextView(context).apply {
                this.text = text
                textSize = 17f
                setTextColor(0xFFF1E1C4.toInt())
                setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xAA1A0F09.toInt())
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            ui.addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            })
        }
        val status = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFFE9F4FA.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(6), dp(13), dp(6))
            background = null
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xE20A1018.toInt())
            alpha = 0f
            visibility = GONE
        }
        statusPill = status
        ui.addView(status, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(62)
        })
    }

    private fun installDefaultModeSwitch() {
        val toggle = SwitchCompat(context).apply {
            text = "默认进入这里"
            textSize = 12f
            setTextColor(0xFFEEDFC7.toInt())
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xD20F0906.toInt())
            gravity = Gravity.CENTER_VERTICAL
            background = null
            isChecked = DeepCompanionPrefs.opensByDefault(context)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xCC729BB2.toInt(), 0x666F675E)
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFF1E4CA.toInt(), 0xFFD2C4AE.toInt())
            )
            setOnCheckedChangeListener { _, enabled ->
                if (syncingDefaultSwitch) return@setOnCheckedChangeListener
                DeepCompanionPrefs.setOpensByDefault(context, enabled)
                showSpeech(if (enabled) "好，下次进聊天我会先在房间里等你" else "好，下次还是先回普通聊天")
            }
        }
        defaultModeSwitch = toggle
        ui.addView(toggle, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = dp(12)
            topMargin = dp(52)
        })
    }

    private fun installTopPull() {
        val edge = View(context).apply { tag = TAG_TOP_PULL }
        var down = 0f
        edge.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = event.y; true }
                MotionEvent.ACTION_UP -> {
                    if (event.y - down > dp(22)) revealStatus()
                    true
                }
                else -> true
            }
        }
        ui.addView(edge, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply { gravity = Gravity.TOP })
    }

    private fun revealStatus() {
        val pill = statusPill ?: return
        refreshStatus()
        pill.visibility = VISIBLE
        pill.animate().alpha(1f).translationY(0f).setDuration(190L).start()
        handler.postDelayed({ pill.animate().alpha(0f).setDuration(300L).withEndAction { pill.visibility = GONE }.start() }, 4200L)
    }

    private fun refreshStatus() {
        val text = bridge.modelStatus()
        if (text.isNotBlank()) statusPill?.text = text
    }

    private fun refreshDeskSticker() {
        val target = deskStickerView ?: return
        val fromChat = bridge.latestKoyoSticker()?.takeIf { StickerStore.has(context, it) }
        if (!fromChat.isNullOrBlank()) {
            deskPrefs.edit().putString(KEY_DESK_STICKER, fromChat).apply()
        }
        val selected = fromChat
            ?: deskPrefs.getString(KEY_DESK_STICKER, "").orEmpty().takeIf { StickerStore.has(context, it) }
            .orEmpty()
        if (selected == deskStickerName) return
        deskStickerName = selected
        if (selected.isBlank()) {
            target.setImageDrawable(null)
            target.contentDescription = "表情便签，等$companionName 留下一张"
            return
        }
        target.setImageBitmap(StickerStore.loadBitmap(context, selected))
        target.contentDescription = "$companionName 留下的${selected}表情便签"
        target.alpha = 0f
        target.scaleX = .92f
        target.scaleY = .92f
        target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).start()
    }

    private fun playDeskStickerReaction() {
        val target = deskStickerView ?: return
        if (deskStickerName.isBlank()) {
            showSpeech("这里先空着，等我真的想留一张再贴")
            return
        }
        target.animate().cancel()
        target.animate().rotation(-6f).scaleX(1.08f).scaleY(1.08f).setDuration(150L)
            .withEndAction {
                target.animate().rotation(-2.2f).scaleX(1f).scaleY(1f).setDuration(240L).start()
            }
            .start()
    }

    private fun refreshDeskTimer() {
        val target = deskTimerView ?: return
        val state = DeskCountdownStore.state(context)
        target.text = buildString {
            append(DeskCountdownStore.format(state.remainingMs))
            append('\n')
            append(when {
                state.running -> "专注中"
                state.paused -> "已暂停"
                else -> "倒计时"
            })
        }
    }

    private fun checkDeskTimerFinished() {
        val finished = DeskCountdownStore.consumeFinished(context) ?: return
        vibrateDeskTimer()
        val message = bridge.deliverCountdownFinished(finished.label)
        latestReply = message
        refreshTranscript()
        refreshDeskSticker()
        refreshDeskTimer()
        sceneView.pulseNow()
        showSpeech(message)
    }

    private fun vibrateDeskTimer() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 180L, 110L, 180L, 110L, 260L), -1))
        }
    }

    private fun showDeskTimerPanel() {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val state = DeskCountdownStore.state(context)
        body.addView(TextView(context).apply {
            text = DeskCountdownStore.format(state.remainingMs)
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(0xFF3F3026.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(14))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(10, 25, 45, 60).forEach { minutes ->
            presets.addView(deskPanelAction("${minutes}分") {
                DeskCountdownStore.start(context, minutes)
                refreshDeskTimer()
                dismissDeskPanel()
                showSpeech("好，我陪你计时")
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        body.addView(presets, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val custom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        val minutesInput = EditText(context).apply {
            hint = "自定义分钟"
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            textSize = 15f
            setTextColor(0xFF3F3026.toInt())
            setHintTextColor(0xFF8A7564.toInt())
            background = null
            setPadding(dp(10), 0, dp(8), 0)
        }
        custom.addView(minutesInput, LinearLayout.LayoutParams(0, dp(46), 1f))
        custom.addView(deskPanelAction("开始") {
            val minutes = minutesInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 1_440) ?: return@deskPanelAction
            DeskCountdownStore.start(context, minutes)
            refreshDeskTimer()
            dismissDeskPanel()
            showSpeech("好，时间我替你看着")
        }, LinearLayout.LayoutParams(dp(78), dp(44)))
        body.addView(custom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(deskPanelAction(if (state.running) "暂停" else if (state.paused) "继续" else "开始25分") {
            when {
                state.running -> DeskCountdownStore.pause(context)
                state.paused -> DeskCountdownStore.resume(context)
                else -> DeskCountdownStore.start(context, 25)
            }
            refreshDeskTimer()
            dismissDeskPanel()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        controls.addView(deskPanelAction("重置") {
            DeskCountdownStore.reset(context)
            refreshDeskTimer()
            dismissDeskPanel()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        body.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        showDeskPanel("桌上的时钟", body)
    }

    private fun showDeskPanel(title: String, content: View) {
        dismissDeskPanel()
        val scrim = FrameLayout(context).apply {
            tag = TAG_DESK_PANEL
            setBackgroundColor(0x6A1D120B)
            isClickable = true
        }
        val paper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = rounded(0xFFF7E9CF.toInt(), 18f, 0x927D5B3E.toInt())
            elevation = dp(12).toFloat()
            rotation = -.45f
            isClickable = true
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(0xFF3D2E24.toInt())
            typeface = Typeface.create("serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(TextView(context).apply {
            text = "收好"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF6A4D38.toInt())
            setOnClickListener { dismissDeskPanel() }
        }, LinearLayout.LayoutParams(dp(58), dp(44)))
        paper.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        paper.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (content is ScrollView) dp(380) else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scrim.addView(paper, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            marginStart = dp(22)
            marginEnd = dp(22)
        })
        scrim.setOnClickListener { dismissDeskPanel() }
        ui.addView(scrim, match())
        scrim.alpha = 0f
        paper.scaleX = .96f
        paper.scaleY = .96f
        scrim.animate().alpha(1f).setDuration(180L).start()
        paper.animate().scaleX(1f).scaleY(1f).setDuration(240L).start()
    }

    private fun dismissDeskPanel() {
        ui.findViewWithTag<View>(TAG_DESK_PANEL)?.let { panel ->
            panel.animate().alpha(0f).setDuration(150L).withEndAction { ui.removeView(panel) }.start()
        }
    }

    private fun deskPanelAction(label: String, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFF4D3829.toInt())
        background = rounded(0x66D9C2A3, 12f, 0x547D5B3E)
        setOnClickListener { click() }
    }

    private fun refreshTranscript() {
        val target = transcript ?: return
        target.text = bridge.recentLines().filter { it.text.isNotBlank() }.joinToString("\n\n") { line ->
            "${line.speaker}\n${line.text}"
        }.ifBlank { "等你写下第一句话" }
        target.post { (target.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showSpeech(text: String) {
        val target = speech ?: return
        val generation = ++speechGeneration
        if (current == Scene.ROOM) {
            showRoomResidentFrames()
        }
        target.text = text.take(360)
        target.visibility = VISIBLE
        target.animate().cancel()
        target.animate().alpha(1f).setDuration(220L).start()
        handler.postDelayed({
            if (target === speech && generation == speechGeneration) {
                if (current == Scene.ROOM) {
                    showRoomResidentFrames()
                } else {
                    target.animate().alpha(0f).setDuration(280L).withEndAction {
                        if (target === speech && generation == speechGeneration) target.visibility = GONE
                    }.start()
                }
            }
        }, 5_200L)
    }

    private fun installDeskMoodSketches(aligned: DeepSceneAlignedLayout) {
        val calendar = Calendar.getInstance()
        val seed = (calendar.get(Calendar.DAY_OF_YEAR) + calendar.get(Calendar.HOUR_OF_DAY) / 4) % 4
        val left = DeskMoodSketchView(context, seed, ::showDeskMood).apply {
            rotation = -2.2f
            alpha = .94f
        }
        aligned.addSourceView(left, RectF(.02f, .37f, .20f, .51f))

        val right = DeskMoodSketchView(context, (seed + 2) % 4, ::showDeskMood).apply {
            rotation = 1.8f
            alpha = .94f
        }
        aligned.addSourceView(right, RectF(.82f, .52f, .99f, .67f))
    }

    private fun showDeskMood(mood: Int) {
        val message = when (mood) {
            0 -> "今天心里挺安静，适合和你慢慢做事"
            1 -> "刚才走了一下神，不过没有走远"
            2 -> "这一张是偷偷有点得意的我"
            else -> "眼睛有点困，还是会陪你把这段做完"
        }
        showSpeech(message)
        sceneView.pulseNow()
    }

    private fun installEditableDeskNotes(aligned: DeepSceneAlignedLayout) {
        val motto = buildEditableDeskNote(
            title = "给今天的话",
            preferenceKey = KEY_DESK_MOTTO,
            fallback = "专注是自由的开始"
        ).apply {
            rotation = -1.7f
        }
        aligned.addSourceView(motto, RectF(.02f, .65f, .21f, .92f))

        val plan = buildPlanNote().apply {
            rotation = 1.4f
        }
        aligned.addSourceView(plan, RectF(.82f, .66f, .99f, .93f))
    }

    private fun buildPlanNote(): TextView = TextView(context).apply {
        textSize = 9.5f
        gravity = Gravity.CENTER
        setTextColor(0xFF493629.toInt())
        setLineSpacing(dp(1).toFloat(), 1f)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        typeface = Typeface.create("serif", Typeface.NORMAL)
        background = null
        alpha = .9f
        renderPlanNote(this)
        setOnClickListener { showPlanChecklist(this) }
        setOnLongClickListener { showDeskPlanEditor(this); true }
    }

    private fun renderPlanBook(target: TextView) {
        val items = DeskPlanStore.load(context)
        val unfinished = items.count { !it.done }
        target.text = buildString {
            append("计划\n")
            append(if (unfinished == 0) "都完成了" else "${unfinished}项待办")
        }
        target.contentDescription = "任务计划书，${if (unfinished == 0) "今天都完成了" else "还有${unfinished}项待办"}，点按查看和勾选"
    }

    private fun renderPlanNote(target: TextView) {
        val items = DeskPlanStore.load(context)
        target.text = buildString {
            append("下一步计划\n\n")
            items.take(4).forEach { append(if (it.done) "✓ " else "□ ").append(it.text).append('\n') }
        }.trimEnd()
        target.contentDescription = "下一步计划，点按勾选，长按编辑"
    }

    private fun showPlanChecklist(note: TextView) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val items = DeskPlanStore.load(context)
        if (items.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "这里还空着，先写下今天想做的一小步"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(0xFF654B38.toInt())
                setPadding(dp(6), dp(22), dp(6), dp(22))
            })
        }
        items.forEach { item ->
            box.addView(CheckBox(context).apply {
                text = item.text
                textSize = 15f
                setTextColor(0xFF47352A.toInt())
                isChecked = item.done
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF5B86A0.toInt(), 0xFF8D765E.toInt())
                )
                setPadding(dp(3), dp(5), dp(3), dp(5))
                setOnCheckedChangeListener { _, checked ->
                    DeskPlanStore.setDone(context, item.id, checked)
                    if (checked) {
                        bridge.recordPlanCompleted(item.text)
                        showSpeech("这一步完成了，我记得")
                    }
                    renderPlanBook(note)
                }
            })
        }
        box.addView(deskPanelAction("编辑计划") {
            dismissDeskPanel()
            showDeskPlanEditor(note)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))
        showDeskPanel("今天的计划", scroll)
    }

    private fun showDeskPlanEditor(note: TextView) {
        val input = EditText(context).apply {
            setText(DeskPlanStore.load(context).joinToString("\n") { it.text })
            textSize = 16f
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(context)
            .setTitle("每行一条计划")
            .setView(input)
            .setNegativeButton("先不改", null)
            .setPositiveButton("保存") { _, _ ->
                DeskPlanStore.replace(context, input.text?.toString().orEmpty().lines())
                renderPlanBook(note)
            }
            .show()
    }

    private fun buildEditableDeskNote(
        title: String,
        preferenceKey: String,
        fallback: String
    ): TextView = TextView(context).apply {
        tag = "desk_note_$preferenceKey"
        textSize = 10.5f
        gravity = Gravity.CENTER
        setTextColor(0xFF493629.toInt())
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(5), dp(5), dp(5), dp(5))
        typeface = Typeface.create("serif", Typeface.NORMAL)
        background = null
        alpha = .88f
        fun render(value: String) {
            text = "$title\n\n${value.ifBlank { fallback }}"
            contentDescription = "$title，长按修改"
        }
        render(deskPrefs.getString(preferenceKey, fallback).orEmpty())
        setOnClickListener { showSpeech("长按这张便签，就能在原地改掉") }
        setOnLongClickListener {
            showDeskNoteEditor(title, preferenceKey, fallback) { render(it) }
            true
        }
    }

    private fun showDeskNoteEditor(
        title: String,
        preferenceKey: String,
        fallback: String,
        onSaved: (String) -> Unit
    ) {
        val currentValue = deskPrefs.getString(preferenceKey, fallback).orEmpty()
        val input = EditText(context).apply {
            setText(currentValue)
            setSelection(text?.length ?: 0)
            textSize = 16f
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("先不改", null)
            .setPositiveButton("放回桌上") { _, _ ->
                val value = input.text?.toString().orEmpty().trim().take(120).ifBlank { fallback }
                deskPrefs.edit().putString(preferenceKey, value).apply()
                onSaved(value)
                showSpeech("好，已经压回原来的位置了")
            }
            .show()
        input.requestFocus()
    }

    private fun applyImeInset(value: Int) {
        imeInset = value.coerceAtLeast(0)
        ui.setPadding(0, 0, 0, 0)
        ui.findViewWithTag<View>(TAG_COMPOSER)?.let { composer ->
            (composer.layoutParams as? LayoutParams)?.let { params ->
                params.bottomMargin = dp(if (current == Scene.DESK) 6 else 14) + imeInset
                composer.layoutParams = params
            }
        }
        attachmentStatus?.let { status ->
            (status.layoutParams as? LayoutParams)?.let { params ->
                params.bottomMargin = dp(if (current == Scene.DESK) 64 else 76) + imeInset
                status.layoutParams = params
            }
        }
        if (current == Scene.DESK) {
            (transcript?.parent as? ScrollView)?.apply {
                setPadding(0, 0, 0, imeInset)
                post { fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun refreshWeatherAmbience() {
        val cached = ambiencePrefs.getString(KEY_WEATHER, "").orEmpty()
        ambience.setWeather(cached)
        val now = System.currentTimeMillis()
        val cachedAt = ambiencePrefs.getLong(KEY_WEATHER_AT, 0L)
        val attemptedAt = ambiencePrefs.getLong(KEY_WEATHER_ATTEMPT_AT, 0L)
        if (cached.isNotBlank() && now - cachedAt < WEATHER_CACHE_MS) return
        if (now - attemptedAt < WEATHER_RETRY_MS || weatherJob?.isActive == true) return
        ambiencePrefs.edit().putLong(KEY_WEATHER_ATTEMPT_AT, now).apply()
        weatherJob = scope.launch {
            val fetched = WeatherFetcher.fetch()
            if (fetched.isNotBlank()) {
                ambiencePrefs.edit()
                    .putString(KEY_WEATHER, fetched)
                    .putLong(KEY_WEATHER_AT, System.currentTimeMillis())
                    .apply()
                ambience.setWeather(fetched)
            }
        }
    }

    private fun showRoomResidentFrames() {
        sceneView.showFrames(
            "scenes/room_speaking.png",
            "scenes/room_speaking_mouth.png",
            intervalMs = 3_400L,
            durationMs = 620L
        )
    }

    private fun playRoomGreeting() {
        handler.removeCallbacks(roomIntroToResident)
        sceneView.showFrames("scenes/room_greeting_v2.png")
        handler.postDelayed(roomIntroToResident, 1_450L)
    }

    private fun showShelfDetail(text: String) {
        ui.findViewWithTag<TextView>(TAG_SHELF_DETAIL)?.apply {
            this.text = text
            animate().alpha(1f).setDuration(180L).start()
        }
    }

    private fun revealComposer(view: View) {
        view.animate().alpha(1f).setDuration(180L).start()
        view.findViewWithTag<EditText>(TAG_INPUT)?.let { edit ->
            edit.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun actionButton(label: String, description: String, floating: Boolean, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(0xFFF2E6D0.toInt())
        contentDescription = description
        background = if (floating) null else rounded(0xFF39556D.toInt(), 24f, 0x886FAFD1.toInt())
        if (floating) setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xE10B0E15.toInt())
        setOnClickListener { click() }
    }

    private fun iconActionButton(icon: Int, description: String, floating: Boolean, click: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(
                if (floating) 0xFFF2E6D0.toInt() else 0xFF39556D.toInt()
            )
            contentDescription = description
            background = null
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (floating) elevation = 0f
            setOnClickListener { click() }
        }

    private fun promptButton(label: String, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(0xFFF1DFC1.toInt())
        background = null
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xE60A0D14.toInt())
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(7)
        }
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun match() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(value: Int) = (value * density).toInt()
    private fun dp(value: Float) = (value * density).toInt()

    fun release() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        sceneView.release()
        backLayers.release()
        frontLayers.release()
    }

    private companion object {
        const val TAG_COMPOSER = "deep_composer"
        const val TAG_INPUT = "deep_input"
        const val TAG_SHELF_DETAIL = "deep_shelf_detail"
        const val TAG_DIARY_DETAIL = "deep_diary_detail"
        const val TAG_PROMPT = "deep_default_prompt"
        const val GUIDE_PREFS = "deep_interaction_guides"
        const val GUIDE_VISIT_PREFIX = "guide_visits_"
        const val GUIDE_MAX_VISITS = 2
        const val TAG_DESK_PANEL = "deep_desk_panel"
        const val TAG_TOP_PULL = "deep_top_pull"
        const val SHELF_PAGE_SIZE = 7
        const val AMBIENCE_PREFS = "deep_companion_ambience"
        const val DESK_PREFS = "deep_companion_desk"
        const val KEY_WEATHER = "weather"
        const val KEY_WEATHER_AT = "weather_at"
        const val KEY_WEATHER_ATTEMPT_AT = "weather_attempt_at"
        const val WEATHER_CACHE_MS = 30 * 60_000L
        const val WEATHER_RETRY_MS = 10 * 60_000L
        const val KEY_DESK_PLAN = "plan"
        const val KEY_DESK_MOTTO = "motto"
        const val KEY_DESK_STICKER = "last_sticker"
    }
}
