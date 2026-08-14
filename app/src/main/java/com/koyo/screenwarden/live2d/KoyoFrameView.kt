package com.koyo.screenwarden.live2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.koyo.screenwarden.CompanionAssetPack
import com.koyo.screenwarden.CompanionProfileStore
import java.io.InputStream
import kotlin.math.sin

/**
 * 角色帧动画视图：自定义角色从私有资源包加载，引导角色从 assets/frames 加载。
 *
 * 素材帧数偏少(多数动作 1-8 帧),因此靠三层补偿把动画做顺:
 * 1. 帧间淡入(crossfade)——在两帧之间插值,消掉硬切
 * 2. 往复播放(ping-pong)——短序列来回走,避免末帧跳回首帧
 * 3. 呼吸位移——持续的轻微缩放+上下浮动,单帧动作也不会完全静止
 *
 * 引导角色匹配规则为**精确匹配**:tiyo_<action>.png 或 tiyo_<action>_<n>.png。
 * 不可用前缀匹配,否则 idle 会同时抓到 idle_01 等其它素材导致角色闪变。
 */
class KoyoFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dest = RectF()

    private var frames: List<Bitmap> = emptyList()
    /** 播放顺序(往复序列),元素为 frames 下标 */
    private var order: List<Int> = emptyList()
    private var orderPos = 0
    private var running = false
    private var cachedAction = ""

    /** 当前动作每帧停留时长 */
    private var frameDelayMs = 140L
    /** 距当前帧开始的毫秒数,用于帧间插值 */
    private var elapsedInFrame = 0L
    /** 第 0 帧的额外驻留时长(0 表示不驻留) */
    private var holdFirstMs = 0L
    private val startTime = System.currentTimeMillis()
    private var lastInteractionAt = System.currentTimeMillis()

    /** 点击可又的回调(聊天页/今天页用来做互动) */
    var onKoyoTap: (() -> Unit)? = null

    /** 点击后短暂播放的反应动作,播完回原动作 */
    private var reactionRestoreAction: String? = null

    companion object {
        /** 渲染步长:60fps 级别,让 crossfade 和呼吸平滑 */
        const val TICK_MS = 16L
        /** 各动作的每帧时长;未列出的用 DEFAULT_DELAY */
        /**
         * 各动作每帧时长。整体偏慢:九宫格一轮九帧,按 100ms 一帧不到一秒就播完
         * 一整个动作,看着像抽帧。除了眨眼和说话必须快,其余都拉到 200ms 以上。
         */
        val DELAYS = mapOf(
            // 待机类:最慢,主要靠呼吸撑着
            "idle" to 320L, "bust" to 320L,
            // 趴着晃腿:慢下来才像放松地晃,快了像抖腿
            "prone" to 760L,
            "chin" to 360L, "hug_knees" to 360L,
            // 侧边探头:用户要的超慢节奏,一帧 5 秒,靠呼吸浮动维持活气
            "edge_peek" to 5000L,
            // 眼部/嘴部:必须快,慢了眨眼像卡住、说话像掉帧
            "blink" to 110L, "blink_full" to 110L, "blink_prone" to 110L,
            "talk" to 85L,
            // 表情反应:中速
            "happy" to 210L, "shy" to 240L, "surprise" to 180L,
            "look_left" to 200L, "look_right" to 200L,
            // 动作类:整体放慢一档,30秒事件动作要能看清
            "walk" to 140L, "run" to 90L, "typing" to 180L, "write" to 240L,
            "read" to 300L, "boba" to 260L, "stretch" to 260L,
            "rub_eyes" to 240L, "peek_up" to 260L, "push_down" to 70L,
            "sleep" to 360L, "wave" to 260L, "invite_chat" to 300L, "yawn" to 280L,
            "tilt" to 260L, "arms_crossed" to 280L
        )
        const val DEFAULT_DELAY = 240L

        /**
         * 每个动作的取景。素材是按取景画的,混用会导致同一个位置上
         * 半身/全身/趴着来回跳,所以外部换动作前要先按取景过滤。
         */
        val VIEW_OF = mapOf(
            "idle" to "full", "peek_up" to "prone", "stretch" to "full",
            "blink_full" to "full",
            "hug_knees" to "full", "push_down" to "full", "walk" to "full",
            "invite_chat" to "full",
            "blink" to "bust", "talk" to "bust", "happy" to "bust",
            "surprise" to "bust", "shy" to "bust", "look_left" to "bust",
            "look_right" to "bust", "boba" to "bust", "rub_eyes" to "bust",
            "bust" to "bust",
            "blink_prone" to "prone",
            "chin" to "prone", "read" to "prone", "write" to "prone",
            "typing" to "prone", "prone" to "prone",
            "edge_peek" to "edge"
        )

        /** 取景相容:趴着和半身都只露上半身,可以互换;全身只能配全身 */
        fun viewsCompatible(a: String, b: String): Boolean {
            if (a == b) return true
            val upper = setOf("bust", "prone")
            return a in upper && b in upper
        }
        /** 帧切换前多少毫秒开始淡入下一帧(固定窗口,不随驻留时长拉长) */
        const val FADE_WINDOW_MS = 90L

        /**
         * 动作别名:某动作没有独立素材时复用另一套帧。
         * 新素材里 idle 已有自己的 9 帧,不再借用 blink。
         */
        val ALIAS = mapOf<String, String>()

        /** 某些动作在第 0 帧(睁眼/静止)多停留一会儿,避免动作过于频繁 */
        val HOLD_FIRST_MS = mapOf<String, Long>()
        /** 点击反应动作的候选,存在哪个用哪个 */
        val TAP_REACTIONS = listOf("happy", "surprise", "shy", "heart")

        /**
         * 帧数 >= 此值视为「作者已画成完整循环」(九宫格素材末帧接首帧),
         * 直接单向循环。少于此值的老素材才用往复补长度。
         */
        const val FORWARD_LOOP_MIN = 6

        /** 取景 -> 该取景专用的眨眼素材。必须严格同类,否则会在两种取景之间跳帧 */
        val BLINK_BY_VIEW = mapOf(
            "full" to "blink_full", "bust" to "blink",
            "prone" to "blink_prone", "edge" to "blink"
        )
    }

    init {
        isClickable = true
    }

    /**
     * 注视模式:手指按住拖动时,按左右偏移直接定帧,不走自动播放。
     * 松手后回到 [gazeRestore]。
     */
    private var gazeActive = false
    private var gazeRestore: String? = null

    private fun isGazeAction(a: String) =
        a == "look_left" || a == "look_right" || a == "peek_up"

    /**
     * 让她看向手指。[offsetX]/[offsetY] 是相对视图中心的偏移,-1~1。
     * 手指明显上移(offsetY < -0.35)时抬头看(peek_up),否则横向用
     * look_left / look_right 跟随。用序列的前半段(中间 -> 到头)当幅度,
     * 幅度越大取越靠后的帧。
     */
    fun gazeTo(offsetX: Float, offsetY: Float) {
        val ox = offsetX.coerceIn(-1f, 1f)
        val oy = offsetY.coerceIn(-1f, 1f)
        val upPeek = oy < -0.35f && hasFrames("peek_up") && compatibleWith("peek_up")
        val target = when {
            upPeek -> "peek_up"
            ox < 0f -> "look_left"
            else -> "look_right"
        }
        if (!hasFrames(target)) return
        if (!gazeActive) {
            gazeRestore = if (cachedAction.isNotEmpty() && !isGazeAction(cachedAction))
                cachedAction else idleAction
            gazeActive = true
        }
        val mag = if (upPeek) kotlin.math.abs(oy) else kotlin.math.abs(ox)
        // 只用去程,回程是回正的帧,拿来当注视会反向
        if (cachedAction != target && !switchTo(target)) return
        cachedAction = target
        val outbound = ((frames.size + 1) / 2).coerceAtLeast(1)
        orderPos = ((mag * (outbound - 1)).toInt()).coerceIn(0, order.size - 1)
        elapsedInFrame = 0L
        invalidate()
    }

    /** 松手,结束注视,回到原来的动作 */
    fun endGaze() {
        if (!gazeActive) return
        gazeActive = false
        val back = gazeRestore ?: idleAction
        gazeRestore = null
        switchTo(back)
        start()
        invalidate()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // 注视时帧由手指定,别让 tick 往前推
            if (gazeActive) {
                handler.postDelayed(this, TICK_MS)
                return
            }
            if (order.isNotEmpty()) {
                val dur = currentFrameDuration()
                elapsedInFrame += TICK_MS
                if (elapsedInFrame >= dur) {
                    elapsedInFrame = 0L
                    orderPos = (orderPos + 1) % order.size
                    // 动作连续循环播,不再定住。只有 30 秒时段插进来的事件动作
                    // (眨眼/随机)播完一轮时回到待机动作。都在整轮边界切换,
                    // 不会切在半个动作中间。
                    if (orderPos == 0) {
                        val restore = reactionRestoreAction
                        if (restore != null) {
                            reactionRestoreAction = null
                            // 直接切换,不经由 playAction 以免与本 tick 的调度冲突
                            switchTo(restore)
                        }
                    }
                }
            }
            invalidate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** 播放某个动作，如 playAction("idle") 加载 tiyo_idle.png / tiyo_idle_<n>.png */
    fun playAction(action: String) {
        // 外部显式换动作:作废还没执行的回落。
        // 不清的话,之前 playReaction 留下的 restore 会在这个动作播完一轮时
        // 把她拽回旧动作(推屏落位变 prone 又跳回站姿就是这么来的)
        reactionRestoreAction = null
        if (action == cachedAction && frames.isNotEmpty()) return
        if (!switchTo(action)) return
        start()
        invalidate()
    }

    /**
     * 换动作但不碰 tick 调度(供 tick 内部安全调用)。
     * 没有对应素材时返回 false 并保持当前画面,不会变空白。
     */
    private fun switchTo(action: String): Boolean {
        val src = ALIAS[action] ?: action
        val loaded = loadFrames(src)
        if (loaded.isEmpty()) return false
        cachedAction = action
        frames = loaded
        order = buildPlayOrder(loaded.size)
        orderPos = 0
        elapsedInFrame = 0L
        frameDelayMs = DELAYS[action] ?: DELAYS[src] ?: DEFAULT_DELAY
        holdFirstMs = HOLD_FIRST_MS[action] ?: 0L
        return true
    }

    /** 播放当前取景对应的眨眼素材(今天页 blink_full / 聊天页 blink_prone)。
     * 由 KoyoDock 的 30 秒时段在奇数段调用,播完自动回到待机动作。
     * 严格同取景:趴姿只能配趴姿眨眼,否则画面在两种取景之间来回跳(串帧)。 */
    fun playHostBlink() {
        if (reactionRestoreAction != null) return
        val hostView = VIEW_OF[idleAction] ?: return
        val variant = BLINK_BY_VIEW[hostView] ?: return
        if (!hasFrames(variant) || VIEW_OF[variant] != hostView) return
        playReaction(variant)
    }

    /** 当前帧的实际停留时长(第 0 帧可能有额外驻留) */
    private fun currentFrameDuration(): Long =
        if (orderPos == 0 && holdFirstMs > 0) holdFirstMs else frameDelayMs

    /**
     * 播放顺序。
     * 九宫格素材(>= FORWARD_LOOP_MIN 帧)本身就是一整轮闭环,倒放会变成
     * "做完再倒着做一遍",所以单向循环。
     * 老的短素材(1-5 帧)才用往复 0,1,2,3,2,1 补长度并避免末帧硬跳回首帧。
     */
    private fun buildPlayOrder(size: Int): List<Int> {
        if (size >= FORWARD_LOOP_MIN) return (0 until size).toList()
        if (size <= 2) return (0 until size).toList()
        return (0 until size) + (size - 2 downTo 1)
    }

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** 兼容 ChatFragment 的生命周期调用:显示时播放 */
    fun setActive(active: Boolean) {
        if (active) start() else stop()
    }

    /**
     * 说话状态。说话时切 talk,停止时回 [idleAction]。
     * idleAction 由外部按当前停靠位置设置(聊天页是 prone,今天页是 idle)。
     */
    fun setSpeaking(speaking: Boolean) {
        reactionRestoreAction = null
        playAction(if (speaking) "talk" else idleAction)
    }

    /** 空闲时回落到哪个动作;停靠状态变化时由外部改写 */
    /** 呼吸缩放开关:聊天页关掉,位置要严格固定 */
    var breathing = true
        set(value) {
            field = value
            invalidate()
        }

    var idleAction = "idle"
        set(value) {
            field = value
            if (!isSpeakingAction(cachedAction) && reactionRestoreAction == null) {
                playAction(value)
            }
        }

    private fun isSpeakingAction(a: String) = a == "talk"

    /** 正在放说话动画。外部插随机动作时要躲开这个 */
    val isSpeaking: Boolean get() = isSpeakingAction(cachedAction)

    /** 当前是否停在待机动作(没在播事件动作/说话/注视)。KoyoDock 30秒时段判断用 */
    fun isAtIdle(): Boolean =
        reactionRestoreAction == null && cachedAction == idleAction && !gazeActive

    /**
     * [action] 的取景跟当前待机取景是否配得上。
     * 全身待机时不接半身动作,免得身子突然被切掉一半。
     */
    fun compatibleWith(action: String): Boolean {
        val base = VIEW_OF[idleAction] ?: return true
        val other = VIEW_OF[action] ?: return true
        return viewsCompatible(base, other)
    }

    /** 播放一次反应动作,结束后自动回到 [restoreTo] */
    fun playReaction(action: String, restoreTo: String = "idle") {
        val prev = if (cachedAction.isNotEmpty()) cachedAction else restoreTo
        playAction(action)
        if (cachedAction == action) reactionRestoreAction = prev
    }

    override fun performClick(): Boolean {
        // 按取景过滤:全身待机时插半身的 happy 会突然只剩上半身
        TAP_REACTIONS.firstOrNull { hasFrames(it) && compatibleWith(it) }
            ?.let { playReaction(it) }
        onKoyoTap?.invoke()
        return super.performClick()
    }

    /**
     * 是否只在不透明像素上吃触摸。开启后点她身体外的空白会穿透到下层
     * (聊天气泡),这样她压在列表上也不挡操作。
     */
    var alphaHitTest = false

    /** 开启后按住拖动会让她看向手指 */
    var touchGaze = true

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val dragSlop by lazy {
        android.view.ViewConfiguration.get(context).scaledTouchSlop
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (alphaHitTest && event.action == MotionEvent.ACTION_DOWN &&
            !hitsBody(event.x, event.y)
        ) {
            // 返回 false,ViewGroup 会继续把这次触摸派发给下层兄弟视图
            return false
        }
        // 注意:这里不要自己动 isPressed。View 是靠 PFLAG_PRESSED 判断
        // ACTION_UP 该不该触发 performClick 的,提前清掉点击就没了。
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastInteractionAt = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (touchGaze) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging &&
                    (kotlin.math.abs(dx) > dragSlop || kotlin.math.abs(dy) > dragSlop)
                ) {
                    dragging = true
                }
                if (dragging) {
                    // 以自身宽高的一半做满量程:手指移到边上就是看到头,移到上方就是抬头
                    val half = (width / 2f).coerceAtLeast(1f)
                    val halfY = (height / 2f).coerceAtLeast(1f)
                    gazeTo(
                        (event.x - width / 2f) / half,
                        (event.y - height / 2f) / halfY
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // 拖过了就只当注视,不再算点击
                    endGaze()
                    dragging = false
                    isPressed = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** 触点是否落在当前帧的不透明像素上 */
    private fun hitsBody(x: Float, y: Float): Boolean {
        val bmp = currentBitmap() ?: return true
        if (dest.width() <= 0f || dest.height() <= 0f) return true
        if (!dest.contains(x, y)) return false
        val bx = ((x - dest.left) / dest.width() * bmp.width).toInt()
        val by = ((y - dest.top) / dest.height() * bmp.height).toInt()
        if (bx < 0 || by < 0 || bx >= bmp.width || by >= bmp.height) return false
        // 采样一个小邻域,避免正好点在发丝缝隙里被判为没中
        val r = (bmp.width / 60).coerceIn(1, 8)
        for (dy in -r..r step r) {
            for (dx in -r..r step r) {
                val sx = (bx + dx).coerceIn(0, bmp.width - 1)
                val sy = (by + dy).coerceIn(0, bmp.height - 1)
                if (bmp.getPixel(sx, sy) ushr 24 > 24) return true
            }
        }
        return false
    }

    private fun currentBitmap(): Bitmap? {
        if (frames.isEmpty() || order.isEmpty()) return null
        return frames[order[orderPos.coerceIn(0, order.size - 1)]]
    }

    /** 彩蛋只认真正的困倦状态：困倦动作、深夜，或连续五分钟没有被碰过 */
    fun isDrowsy(now: Long = System.currentTimeMillis()): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return cachedAction == "yawn" || cachedAction == "rub_eyes" ||
            hour >= 23 || hour < 6 || now - lastInteractionAt >= 5 * 60_000L
    }

    fun release() {
        stop()
        frames = emptyList()
        order = emptyList()
        cachedAction = ""
        reactionRestoreAction = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frames.isEmpty() || order.isEmpty() || width <= 0 || height <= 0) return

        // 呼吸:轻微缩放 + 上下浮动,让单帧动作也有生气
        val t = (System.currentTimeMillis() - startTime) / 1000.0
        // 纵向浮动整个去掉:用户说看着像抖动。
        // 呼吸缩放留着,但聊天页要关(她钉死在输入框上沿,不能有任何位移感)
        val breathScale =
            if (breathing) 1f + 0.014f * sin(t * 2 * Math.PI / 3.2).toFloat() else 1f
        @Suppress("UNUSED_VARIABLE")
        val unusedFloat = 0f * resources.displayMetrics.density *
            sin(t * 2 * Math.PI / 4.6).toFloat()

        val cur = frames[order[orderPos.coerceIn(0, order.size - 1)]]
        drawFrame(canvas, cur, breathScale, 0f, 255)

        // 帧间淡入:在每帧后半段把下一帧叠上来,消掉硬切
        if (frames.size > 1) {
            val dur = currentFrameDuration()
            val window = minOf(FADE_WINDOW_MS, dur / 2)
            val remain = dur - elapsedInFrame
            if (window > 0 && remain in 0..window) {
                val p = (window - remain).toFloat() / window
                val next = frames[order[(orderPos + 1) % order.size]]
                drawFrame(canvas, next, breathScale, 0f, (p * 255).toInt())
            }
        }
    }

    /**
     * 画面重心。趴姿要贴视图底边(下面就是输入框),侧边停靠要贴右边,
     * 其它情况居中。
     */
    var gravity = Gravity.CENTER

    enum class Gravity { CENTER, BOTTOM, RIGHT, LEFT }

    private fun drawFrame(
        canvas: Canvas,
        bmp: Bitmap,
        scaleMul: Float,
        offsetY: Float,
        alpha: Int
    ) {
        if (alpha <= 0) return
        val scale = minOf(
            width.toFloat() / bmp.width,
            height.toFloat() / bmp.height
        ) * scaleMul
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = when (gravity) {
            Gravity.RIGHT -> width - w
            Gravity.LEFT -> 0f
            else -> (width - w) / 2f
        }
        val top = when (gravity) {
            // 贴底时呼吸只让她微微起伏,不把身体推出视图外
            Gravity.BOTTOM -> height - h + offsetY.coerceAtMost(0f)
            else -> (height - h) / 2f + offsetY
        }
        dest.set(left, top, left + w, top + h)
        paint.alpha = alpha
        canvas.drawBitmap(bmp, null, dest, paint)
        paint.alpha = 255
    }

    /** 该动作是否有素材 */
    fun hasFrames(action: String): Boolean {
        val active = CompanionProfileStore.active(context)
        if (!active.isBuiltInCompanion) return CompanionAssetPack.actionFile(context, action)?.isFile == true
        return if ((ALIAS[action] ?: action) == "invite_chat") {
            try {
                context.assets.open("frames/tiyo_invite_chat_sheet.png").close()
                true
            } catch (_: Exception) {
                false
            }
        } else {
            frameNames(ALIAS[action] ?: action).isNotEmpty()
        }
    }

    /**
     * 精确匹配 tiyo_<action>.png 与 tiyo_<action>_<n>.png，按数字后缀排序。
     * 无后缀的排最前(视为第 0 帧)。
     */
    private fun frameNames(action: String): List<String> {
        val re = Regex("^tiyo_${Regex.escape(action)}(?:_(\\d+))?\\.png$")
        return try {
            (context.assets.list("frames") ?: emptyArray())
                .mapNotNull { name ->
                    re.find(name)?.let { m ->
                        name to (m.groupValues[1].toIntOrNull() ?: -1)
                    }
                }
                .sortedBy { it.second }
                .map { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadFrames(action: String): List<Bitmap> {
        val active = CompanionProfileStore.active(context)
        if (!active.isBuiltInCompanion) {
            return CompanionAssetPack.actionFiles(context, action)
                .mapNotNull { file -> BitmapFactory.decodeFile(file.absolutePath) }
        }
        if (action == "invite_chat") return loadInviteChatSheet()
        val result = mutableListOf<Bitmap>()
        for (name in frameNames(action)) {
            try {
                val input: InputStream = context.assets.open("frames/$name")
                val bmp = BitmapFactory.decodeStream(input)
                input.close()
                if (bmp != null) result.add(bmp)
            } catch (_: Exception) {
            }
        }
        return result
    }

    /**
     * 今天页邀请动作是按官方立绘与 Q 版参考生成的一张 3×2 六帧表
     * 运行时切帧能避免再存六份重复 PNG，也让六帧始终作为同一套形象一起更新
     */
    private fun loadInviteChatSheet(): List<Bitmap> {
        return try {
            context.assets.open("frames/tiyo_invite_chat_sheet.png").use { input ->
                val sheet = BitmapFactory.decodeStream(input) ?: return emptyList()
                val cellWidth = sheet.width / 3
                val cellHeight = sheet.height / 2
                if (cellWidth <= 0 || cellHeight <= 0) return emptyList()
                buildList(6) {
                    for (index in 0 until 6) {
                        val column = index % 3
                        val row = index / 3
                        add(
                            Bitmap.createBitmap(
                                sheet,
                                column * cellWidth,
                                row * cellHeight,
                                cellWidth,
                                cellHeight
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
