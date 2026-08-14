package com.koyo.screenwarden.live2d

import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Tiyo的停靠控制器。
 *
 * 她是挂在 Activity 根布局上的**一个**视图,不属于任何 Fragment,
 * 所以切页时她能连续动、能从今天页一路"走"到聊天页,不会被重建。
 *
 * 六种停靠位:
 *   HERO   今天页,大立绘,居中偏上
 *   CHAT   聊天页,半身趴在输入框上沿
 *   ROOM   房间相处模式,全身立绘
 *   DESK   书桌共事模式,趴在卷轴桌沿
 *   EDGE   其它页(工作台/外设/我的),趴在屏幕右侧边缘往里看
 *   HIDDEN 收起
 */
class KoyoDock(private val host: FrameLayout) {

    enum class State { HIDDEN, HERO, ROOM, DESK, CHAT, EDGE }

    private val density = host.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    val view = KoyoFrameView(host.context).apply {
        layoutParams = FrameLayout.LayoutParams(dp(HERO_DP), dp(HERO_DP))
        alphaHitTest = true
        visibility = View.GONE
    }

    var state: State = State.HIDDEN
        private set
    /** invite_chat 只在每次进入今天页时播一轮，不再作为循环待机动作 */
    private var todayInvitePlayedForEntry = false

    /** 点她身体的回调,由当前页面设置 */
    var onTap: (() -> Unit)?
        get() = view.onKoyoTap
        set(v) { view.onKoyoTap = v }

    /** CHAT 位时她底边离宿主底部的距离(输入框高度),由聊天页告知 */
    var chatBottomInset = dp(96f)
        set(v) {
            field = v
            // 推屏动画进行中不重排:位置由动画接管,这里只更新数值
            if (state == State.CHAT && !pushing) applyLayout(State.CHAT, animate = false)
        }

    init {
        host.addView(view)
        // 用 post:slotTicker 是后面声明的属性,init 里直接读还是 null
        view.post { scheduleSlot() }
    }

    /**
     * HERO 位跟随一个占位槽。今天页是可滚动的,她得跟着占位一起滚,
     * 不然页面一滑她就浮在原地压住别的卡片。
     */
    private var slot: View? = null
    private val slotRect = IntArray(2)
    private val hostRect = IntArray(2)

    /** 聊天页输入区的上沿。她要一直趴在这上面,键盘/面板一动就跟着动 */
    private var chatAnchor: View? = null

    fun setChatAnchor(target: View?) {
        chatAnchor = target
        if (target != null) {
            host.viewTreeObserver.addOnPreDrawListener(follow)
        } else if (slot == null) {
            host.viewTreeObserver.removeOnPreDrawListener(follow)
        }
        syncChatAnchor()
    }

    /**
     * 按锚点重算她的位置。每帧查一次,只在真的变了时才改布局,
     * 免得在 onPreDraw 里无条件 setLayoutParams 触发重排死循环。
     */
    /** 推屏动画进行中。此时位置由动画接管,别让锚点跟随插手 */
    private var pushing = false

    private fun syncChatAnchor() {
        val a = chatAnchor ?: return
        if (state != State.CHAT || !a.isAttachedToWindow || a.height <= 0) return
        a.getLocationInWindow(slotRect)
        host.getLocationInWindow(hostRect)
        val topInRoot = slotRect[1] - hostRect[1]
        // FrameLayout 的 TOP margin 从 paddingTop 之后开始算。宿主为沉浸式窗口，
        // 状态栏 inset 正好就是这段 padding；不补回来会让模型整体向下压一截
        val want = (host.height - topInRoot + host.paddingTop).coerceAtLeast(0)
        if (want != chatBottomInset) chatBottomInset = want
    }

    private val follow = android.view.ViewTreeObserver.OnPreDrawListener {
        if (state == State.CHAT) syncChatAnchor()
        val s = slot
        if (state in setOf(State.HERO, State.ROOM, State.DESK) && s != null && s.isAttachedToWindow) {
            s.getLocationInWindow(slotRect)
            host.getLocationInWindow(hostRect)
            val targetX = (slotRect[0] - hostRect[0]).toFloat()
            val targetY = (slotRect[1] - hostRect[1]).toFloat()
            view.translationX = targetX - view.left
            view.translationY = targetY - view.top
            // 滑出屏幕就淡掉,省得她贴在边上半截
            val visibleFrac = 1f - (kotlin.math.abs(targetY) / (host.height * 0.9f))
                .coerceIn(0f, 1f)
            view.alpha = if (targetY < -view.height) 0f else visibleFrac.coerceAtLeast(0.15f)
        }
        true
    }

    /** 今天页把它的宠物占位 View 交给我,离开时传 null */
    fun setHeroSlot(target: View?) {
        slot = target
        if (target != null) {
            host.viewTreeObserver.addOnPreDrawListener(follow)
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.width = target.width.takeIf { it > 0 } ?: dp(HERO_DP)
            lp.height = lp.width
            lp.gravity = Gravity.TOP or Gravity.START
            lp.topMargin = 0
            lp.marginEnd = 0
            view.layoutParams = lp
        } else {
            // HERO 和 CHAT 共用 follow。离开今天页时聊天锚点可能已经接管，
            // 这时不能把监听一起拆掉，否则会退回默认 96dp，模型就压进输入框
            if (chatAnchor == null) host.viewTreeObserver.removeOnPreDrawListener(follow)
            view.translationX = 0f
            view.translationY = 0f
            view.alpha = 1f
        }
    }

    /** 切换停靠位。[animate] 为 false 时直接落位,用于首次进入或旋转 */
    fun goto(target: State, animate: Boolean = true) {
        if (target == state) return
        if (target == State.HERO) todayInvitePlayedForEntry = false
        val prev = state
        state = target
        if (target == State.HIDDEN) {
            fadeOut()
            return
        }
        if (prev == State.HIDDEN) {
            applyLayout(target, animate = false)
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(220).start()
            view.setActive(true)
            return
        }
        applyLayout(target, animate)
    }

    /**
     * 今天页点她 -> 推屏转场:她一边做推的动作一边跟着往下走到输入框位置,
     * 落位后变成趴姿。[onArrive] 在她走到位时回调(用来真正切页面)。
     */
    fun pushDownToChat(onArrive: () -> Unit) {
        if (state != State.HERO) {
            goto(State.CHAT)
            onArrive()
            return
        }
        state = State.CHAT
        pushing = true
        if (view.hasFrames("push_down")) view.playAction("push_down")

        // HERO 跟随占位槽是靠 translationY 实现的,topMargin 一直是 0。
        // 直接动 topMargin 会从 0 开始跳,所以先把跟随的位移折进 topMargin。
        val lp0 = view.layoutParams as FrameLayout.LayoutParams
        if (view.translationY != 0f) {
            lp0.topMargin = (view.top + view.translationY).toInt()
            view.layoutParams = lp0
            view.translationY = 0f
            view.translationX = 0f
        }
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val fromTop = lp.topMargin
        val fromSize = lp.width
        val toSize = dp(CHAT_DP)
        var switched = false

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PUSH_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val p = a.animatedFraction
                val cur = view.layoutParams as FrameLayout.LayoutParams
                // 目标每帧重算:切页面前 chatBottomInset 还是今天页的旧值(默认 96dp),
                // 按它算出来的落点偏低。页面出来后量到真实输入框上沿,
                // 剩下的路程自动改朝新目标走,落位正好压在输入框上
                val aimTop = chatTopMargin()
                cur.topMargin = (fromTop + (aimTop - fromTop) * p).toInt()
                val s = (fromSize + (toSize - fromSize) * p).toInt()
                cur.width = s
                cur.height = s
                cur.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                view.layoutParams = cur
                // 走到六成时切页面,她正好压在新页面的输入框上,不会出现空白
                if (!switched && p >= 0.6f) {
                    switched = true
                    onArrive()
                }
            }
            addListener(onEnd = {
                if (!switched) onArrive()
                pushing = false
                applyLayout(State.CHAT, animate = false)
                view.gravity = KoyoFrameView.Gravity.BOTTOM
                setBaseIdle("prone")
            })
            start()
        }
    }

    /** 这个停靠状态下她本来该做的动作,工作动作结束后回到这个 */
    private var baseIdle = "idle"
    /** 正在执行工具时的持续动作(typing / read),没在工作就是 null */
    private var working: String? = null

    private fun setBaseIdle(action: String) {
        baseIdle = action
        // 正在工作时先不动,等 clearWorking 再回落
        if (working == null) view.idleAction = action
    }

    /**
     * 进入"在做事"状态,持续播到 [clearWorking]。
     * tool 名字里带 edit/write/bash 的算写代码,带 read/search/web 的算查资料。
     */
    fun setWorkingTool(toolName: String) {
        val t = toolName.lowercase()
        val action = TOOL_MAP.firstOrNull { (keys, _) -> keys.any { it in t } }?.second
            ?.takeIf { view.hasFrames(it) && view.compatibleWith(it) } ?: return
        if (working == action) return
        working = action
        view.idleAction = action
    }

    /** 工具跑完了,回到这个停靠状态本来的动作 */
    fun clearWorking() {
        if (working == null) return
        working = null
        view.idleAction = baseIdle
    }

    private fun chatTopMargin(): Int {
        val h = if (host.height > 0) host.height else
            host.resources.displayMetrics.heightPixels
        return (h - chatBottomInset - dp(CHAT_DP)).coerceAtLeast(0)
    }

    private fun applyLayout(target: State, animate: Boolean) {
        // 离开跟随占位槽的三档才清掉位移；房间和书桌也由各自槽位定位
        if (target !in setOf(State.HERO, State.ROOM, State.DESK)) {
            view.translationX = 0f
            view.translationY = 0f
            view.alpha = 1f
        }
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val toSize: Int
        val toGravity: Int
        val toTop: Int
        val toEnd: Int
        when (target) {
            State.HERO -> {
                view.gravity = KoyoFrameView.Gravity.CENTER
                // 关注视:look_* 是半身,今天页要一直全身;
                // 而且拖动会吃掉点击,点她就推不动屏幕了
                view.touchGaze = false
                view.breathing = true
                setBaseIdle("idle")
                // 有占位槽时位置交给 follow 监听器算,这里只定尺寸和动作
                slot?.let {
                    val lp2 = view.layoutParams as FrameLayout.LayoutParams
                    lp2.width = it.width.takeIf { w -> w > 0 } ?: dp(HERO_DP)
                    lp2.height = lp2.width
                    lp2.gravity = Gravity.TOP or Gravity.START
                    lp2.topMargin = 0
                    lp2.marginEnd = 0
                    view.layoutParams = lp2
                    view.playAction(view.idleAction)
                    return
                }
                toSize = dp(HERO_DP)
                toGravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                toTop = dp(HERO_TOP_DP)
                toEnd = 0
            }
            State.ROOM -> {
                view.gravity = KoyoFrameView.Gravity.CENTER
                view.touchGaze = false
                view.breathing = true
                setBaseIdle("idle")
                slot?.let {
                    val lp2 = view.layoutParams as FrameLayout.LayoutParams
                    lp2.width = it.width.takeIf { width -> width > 0 } ?: dp(ROOM_DP)
                    lp2.height = lp2.width
                    lp2.gravity = Gravity.TOP or Gravity.START
                    lp2.topMargin = 0
                    lp2.marginEnd = 0
                    view.layoutParams = lp2
                    view.playAction(view.idleAction)
                    return
                }
                toSize = dp(ROOM_DP)
                toGravity = Gravity.CENTER
                toTop = 0
                toEnd = 0
            }
            State.DESK -> {
                view.gravity = KoyoFrameView.Gravity.BOTTOM
                view.touchGaze = true
                view.breathing = true
                setBaseIdle("prone")
                slot?.let {
                    val lp2 = view.layoutParams as FrameLayout.LayoutParams
                    lp2.width = it.width.takeIf { width -> width > 0 } ?: dp(DESK_DP)
                    lp2.height = lp2.width
                    lp2.gravity = Gravity.TOP or Gravity.START
                    lp2.topMargin = 0
                    lp2.marginEnd = 0
                    view.layoutParams = lp2
                    view.playAction(view.idleAction)
                    return
                }
                toSize = dp(DESK_DP)
                toGravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                toTop = dp(54f)
                toEnd = 0
            }
            State.CHAT -> {
                toSize = dp(CHAT_DP)
                toGravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                toTop = chatTopMargin()
                toEnd = 0
                view.gravity = KoyoFrameView.Gravity.BOTTOM
                view.touchGaze = true
                // 聊天页不要呼吸缩放:她要严格钉在输入框上沿,一点位移感都不能有
                view.breathing = false
                setBaseIdle("prone")
            }
            State.EDGE -> {
                toSize = dp(EDGE_DP)
                toGravity = Gravity.TOP or Gravity.END
                toTop = dp(EDGE_TOP_DP)
                toEnd = 0                  // 整个人在屏幕内,不往外藏
                view.gravity = KoyoFrameView.Gravity.RIGHT
                view.breathing = true
                // 边缘探头是侧视,look_left/right 是正面半身,拖动会突然换个姿态,关掉
                view.touchGaze = false
                // 整个人露出来,不再往屏幕外藏一半
                setBaseIdle("edge_peek")
            }
            State.HIDDEN -> return
        }

        if (!animate) {
            lp.width = toSize
            lp.height = toSize
            lp.gravity = toGravity
            lp.topMargin = toTop
            lp.marginEnd = toEnd
            view.layoutParams = lp
            view.playAction(view.idleAction)
            return
        }

        val fromSize = lp.width
        val fromTop = lp.topMargin
        val fromEnd = lp.marginEnd
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { a ->
                val p = a.animatedFraction
                val cur = view.layoutParams as FrameLayout.LayoutParams
                val s = (fromSize + (toSize - fromSize) * p).toInt()
                cur.width = s
                cur.height = s
                cur.gravity = toGravity
                cur.topMargin = (fromTop + (toTop - fromTop) * p).toInt()
                cur.marginEnd = (fromEnd + (toEnd - fromEnd) * p).toInt()
                view.layoutParams = cur
            }
            addListener(onEnd = { view.playAction(view.idleAction) })
            start()
        }
    }

    private fun fadeOut() {
        view.animate().alpha(0f).setDuration(180).withEndAction {
            view.visibility = View.GONE
            view.setActive(false)
        }.start()
    }

    fun setSpeaking(speaking: Boolean) = view.setSpeaking(speaking)

    fun playAction(action: String) = view.playAction(action)

    /**
     * 按聊天内容挑一个合适的动作。找不到匹配就返回 null,由调用方决定
     * 保持当前动作还是随机换一个。
     */
    fun actionForContent(text: String): String? {
        val t = text.lowercase()
        val hit = CONTENT_MAP.firstOrNull { (keys, _) -> keys.any { it in t } }
        return hit?.second?.takeIf { view.hasFrames(it) && view.compatibleWith(it) }
    }

    /** 按聊天内容切动作;没匹配上就不动 */
    fun reactToContent(text: String) {
        actionForContent(text)?.let { view.playReaction(it, restoreTo = view.idleAction) }
    }

    /** 空闲一阵子后随机换个小动作,别一直一个姿势 */
    fun playRandomIdle() {
        val pool = (if (state == State.CHAT || state == State.DESK) CHAT_IDLE_POOL else HERO_IDLE_POOL)
            .filter { view.hasFrames(it) && view.compatibleWith(it) }
        if (pool.isEmpty()) return
        view.playReaction(pool.random(), restoreTo = view.idleAction)
    }

    /**
     * 30 秒时段事件:奇数段播一次眨眼(今天页 blink_full / 聊天页 blink_prone),
     * 偶数段从当前页随机池抽一个动作播一次。正在说话/工作/注视/藏起来,
     * 或还在播上一个事件动作时跳过,等下一个时段再插。
     */
    private var slotCount = 0L
    private val slotTicker = object : Runnable {
        override fun run() {
            slotCount++
            if (state != State.HIDDEN && working == null && !view.isSpeaking &&
                view.isAtIdle()
            ) {
                if (slotCount % 2 == 1L) view.playHostBlink()
                else playRandomIdle()
            }
            view.postDelayed(this, SLOT_MS)
        }
    }

    private fun scheduleSlot() {
        view.removeCallbacks(slotTicker)
        view.postDelayed(slotTicker, SLOT_MS)
    }

    fun playReaction(action: String) =
        view.playReaction(action, restoreTo = view.idleAction)

    /** 今天页刚出现时调用；同一次停留即使 Fragment 刷新多次也只播一轮 */
    fun playTodayEntryInvite() {
        if (state != State.HERO || todayInvitePlayedForEntry || working != null) return
        todayInvitePlayedForEntry = true
        if (view.hasFrames("invite_chat")) {
            view.playReaction("invite_chat", restoreTo = baseIdle)
        }
    }

    fun isDrowsy(): Boolean = view.isDrowsy()

    fun release() {
        host.viewTreeObserver.removeOnPreDrawListener(follow)
        view.removeCallbacks(slotTicker)
        view.release()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private companion object {
        /**
         * 聊天内容 -> 动作。按顺序匹配,先命中先用,所以把更具体的放前面。
         * 只列关键词,不做语义判断——判错了顶多动作不贴切,不影响功能。
         */
        val CONTENT_MAP = listOf(
            setOf("写代码", "代码", "调试", "报错", "编译", "bug", "code", "debug") to "typing",
            setOf("查资料", "搜一下", "搜索", "查查", "文档", "资料", "search") to "read",
            setOf("记一下", "写下来", "笔记", "记下", "总结") to "write",
            setOf("困了", "睡吧", "累了", "熬夜", "晚安", "早点休息") to "rub_eyes",
            setOf("奶茶", "渴了", "喝点", "喝口") to "boba",
            setOf("琢磨", "发呆", "在想", "让我想", "考虑一下") to "chin",
            setOf("哈哈", "开心", "太好了", "棒", "厉害", "笑") to "happy",
            setOf("害羞", "脸红", "别说", "羞", "讨厌") to "shy",
            setOf("真的吗", "居然", "不会吧", "吓", "没想到") to "surprise",
            setOf("伸个懒腰", "懒腰", "休息一下", "歇会") to "stretch"
        )

        /**
         * 工具名 -> 动作。写文件类算"写代码",查看/搜索类算"查资料"。
         * 匹配用 contains,所以 Edit / MultiEdit / str_replace_editor 都能命中 edit。
         */
        val TOOL_MAP = listOf(
            listOf("edit", "write", "bash", "shell", "patch", "run") to "typing",
            listOf("read", "search", "grep", "glob", "fetch", "web", "list") to "read",
            listOf("memory") to "write"
        )

        /** 30 秒时段时长:奇数段眨眼、偶数段从随机池抽动作 */
        const val SLOT_MS = 30000L

        /** 今天页随机池(全身站姿;push_down 只在转场播,不进池) */
        val HERO_IDLE_POOL = listOf(
            "hug_knees", "stretch", "wave", "yawn", "tilt", "arms_crossed"
        )
        /** 聊天页随机池(趴姿/半身,与 prone 取景相容) */
        val CHAT_IDLE_POOL = listOf("boba", "bust", "chin", "happy", "rub_eyes")

        const val HERO_DP = 200f
        const val HERO_TOP_DP = 24f
        const val ROOM_DP = 292f
        const val DESK_DP = 178f
        const val CHAT_DP = 132f
        const val EDGE_DP = 118f
        const val EDGE_TOP_DP = 190f
        /** 侧边停靠时探出屏幕外的宽度,只露上半身和手 */
        const val EDGE_OUT_DP = 30f
        const val MOVE_MS = 420L
        const val PUSH_MS = 620L
    }
}

/** ValueAnimator 结束回调的小helper,省掉整个 AnimatorListener 样板 */
private fun ValueAnimator.addListener(onEnd: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
    })
}
