package com.koyo.screenwarden

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.koyo.screenwarden.live2d.KoyoDock
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot

/** Owns the hidden entrance and keeps the deep world as an overlay over the accepted chat UI. */
internal class DeepCompanionController private constructor(
    private val activity: AppCompatActivity,
    private val root: FrameLayout,
    private val dock: KoyoDock
) {
    private val handler = Handler(Looper.getMainLooper())
    private val bridge = DeepCompanionChatBridge(activity)
    private val host = DeepCompanionHostView(
        activity,
        bridge,
        onExit = { exitToChat() },
        onSceneChanged = { syncDock(it) },
        isKoyoDrowsy = { isDeepKoyoDrowsy() }
    )
    private val shatter = CrystalShatterView(activity)
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val holdTolerance = maxOf(slop * 3, (28 * activity.resources.displayMetrics.density).toInt())
    private val originalTap = dock.onTap
    private var bodyHitMethod: Method? = null
    private var tracking = false
    private var holdTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var chatWasVisible = false
    private var released = false
    private var deepEnteredAt = 0L

    private val hold = Runnable { triggerHold() }
    private val notice = Runnable {
        if (tracking && !holdTriggered) dock.playReaction("tilt")
    }
    private val crystalNotice = Runnable {
        if (tracking && !holdTriggered) dock.playReaction("surprise")
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!host.handleBack()) isEnabled = false
        }
    }

    private val visibilityWatcher = ViewTreeObserver.OnGlobalLayoutListener {
        val visible = bridge.isChatVisible()
        if (visible && !chatWasVisible && DeepCompanionPrefs.opensByDefault(activity) && host.visibility != View.VISIBLE) {
            handler.postDelayed({ if (bridge.isChatVisible()) enterWithoutShatter() }, 220L)
        }
        chatWasVisible = visible
    }

    init {
        val koyoIndex = root.indexOfChild(dock.view).coerceAtLeast(1)
        root.addView(host, koyoIndex, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(shatter, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        shatter.visibility = View.GONE
        dock.onTap = {
            if (!host.onKoyoTapped()) originalTap?.invoke()
        }
        dock.view.setOnTouchListener(::watchKoyoTouch)
        root.viewTreeObserver.addOnGlobalLayoutListener(visibilityWatcher)
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = release()
        })
    }

    private fun watchKoyoTouch(view: View, event: MotionEvent): Boolean {
        if (released) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (host.visibility == View.VISIBLE || dock.state !in ENTRY_STATES || !bridge.isChatVisible()) {
                    return false
                }
                if (!hitsKoyoBody(event.x, event.y)) return false
                tracking = true
                holdTriggered = false
                downX = event.x
                downY = event.y
                val viewPos = IntArray(2)
                val rootPos = IntArray(2)
                dock.view.getLocationInWindow(viewPos)
                root.getLocationInWindow(rootPos)
                shatter.bringToFront()
                shatter.startCharge(
                    viewPos[0] - rootPos[0] + downX,
                    viewPos[1] - rootPos[1] + downY
                )
                handler.postDelayed(notice, 2_700L)
                handler.postDelayed(crystalNotice, 6_600L)
                handler.postDelayed(hold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val leftView = event.x < -holdTolerance || event.y < -holdTolerance ||
                    event.x > view.width + holdTolerance || event.y > view.height + holdTolerance
                if (leftView || hypot(event.x - downX, event.y - downY) > holdTolerance) cancelHold()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consume = holdTriggered
                cancelHold(resetTriggered = false)
                if (consume) {
                    view.isPressed = false
                    holdTriggered = false
                    return true
                }
            }
        }
        return false
    }

    private fun triggerHold() {
        if (!tracking || holdTriggered || host.visibility == View.VISIBLE) return
        tracking = false
        holdTriggered = true
        handler.removeCallbacks(notice)
        handler.removeCallbacks(crystalNotice)
        dock.view.isPressed = false
        dock.view.cancelLongPress()
        dock.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        handler.postDelayed({ dock.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, 95L)

        val viewPos = IntArray(2)
        val rootPos = IntArray(2)
        dock.view.getLocationInWindow(viewPos)
        root.getLocationInWindow(rootPos)
        val x = viewPos[0] - rootPos[0] + downX
        val y = viewPos[1] - rootPos[1] + downY
        shatter.bringToFront()
        shatter.play(
            x,
            y,
            onRoomReveal = {
                backCallback.isEnabled = true
                deepEnteredAt = System.currentTimeMillis()
                setFullscreen(true)
                host.open(animated = true, askDefault = !DeepCompanionPrefs.hasAskedDefault(activity))
            },
            onFinished = { dock.view.isPressed = false }
        )
    }

    private fun enterWithoutShatter() {
        if (host.visibility == View.VISIBLE || !bridge.isChatVisible()) return
        backCallback.isEnabled = true
        deepEnteredAt = System.currentTimeMillis()
        setFullscreen(true)
        host.open(animated = true, askDefault = false)
    }

    private fun exitToChat() {
        host.close(animated = true) {
            dock.goto(KoyoDock.State.CHAT, animate = true)
            backCallback.isEnabled = false
            setFullscreen(false)
        }
    }

    private fun setFullscreen(enabled: Boolean) {
        (activity as? MainActivity)?.setDeepCompanionFullscreen(enabled)
    }

    private fun syncDock(scene: DeepCompanionHostView.Scene) {
        // Deep mode owns a separate non-chibi model built from 立绘.png
        // The accepted normal-chat model remains untouched underneath
        dock.goto(KoyoDock.State.HIDDEN, animate = true)
        (activity as? MainActivity)?.setDeepCompanionScene(scene)
    }

    private fun isDeepKoyoDrowsy(now: Long = System.currentTimeMillis()): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 6 || deepEnteredAt > 0L && now - deepEnteredAt >= 5 * 60_000L
    }

    private fun cancelHold(resetTriggered: Boolean = true) {
        if (tracking && !holdTriggered) shatter.cancelCharge()
        tracking = false
        handler.removeCallbacks(hold)
        handler.removeCallbacks(notice)
        handler.removeCallbacks(crystalNotice)
        if (resetTriggered) holdTriggered = false
    }

    private fun hitsKoyoBody(x: Float, y: Float): Boolean {
        val method = bodyHitMethod ?: runCatching {
            dock.view.javaClass.getDeclaredMethod("hitsBody", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()?.also { bodyHitMethod = it }
        val exact = runCatching { method?.invoke(dock.view, x, y) as? Boolean }.getOrNull()
        if (exact != null) return exact
        return x in dock.view.width * .18f..dock.view.width * .82f &&
            y in dock.view.height * .08f..dock.view.height * .94f
    }

    private fun release() {
        if (released) return
        released = true
        cancelHold()
        handler.removeCallbacksAndMessages(null)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(visibilityWatcher)
        dock.view.setOnTouchListener(null)
        dock.onTap = originalTap
        backCallback.remove()
        setFullscreen(false)
        host.release()
        shatter.release()
        (host.parent as? ViewGroup)?.removeView(host)
        (shatter.parent as? ViewGroup)?.removeView(shatter)
        installed.remove(activity)
    }

    companion object {
        private const val HOLD_MS = 10_000L
        private val ENTRY_STATES = setOf(
            KoyoDock.State.CHAT,
            KoyoDock.State.ROOM,
            KoyoDock.State.DESK
        )
        private val installed = WeakHashMap<Activity, WeakReference<DeepCompanionController>>()

        @JvmStatic
        fun install(activity: AppCompatActivity, root: FrameLayout, dock: KoyoDock) {
            if (installed[activity]?.get() != null) return
            installed[activity] = WeakReference(DeepCompanionController(activity, root, dock))
        }
    }
}
