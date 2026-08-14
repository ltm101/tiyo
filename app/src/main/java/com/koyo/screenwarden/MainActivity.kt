package com.koyo.screenwarden

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_CHAT = "tiyo_open_chat"
        const val EXTRA_SEND_TEXT = "tiyo_send_text"
    }

    private lateinit var rootLayout: android.widget.FrameLayout
    private lateinit var overlayContainer: android.widget.FrameLayout
    private lateinit var companionBreakCoordinator: CompanionBreakCoordinator

    private lateinit var todayFragment: TodayFragment
    private lateinit var chatFragment: ChatFragment
    private lateinit var filesFragment: FilesFragment
    private lateinit var peripheralsFragment: PeripheralsFragment
    private lateinit var meTabFragment: MeTabFragment
    private var currentFragment: Fragment? = null

    /**
     * 可又本人。挂在 rootLayout 上而不是任何 Fragment 里,
     * 这样切页时她是移动过去的,动画不断、状态不丢。
     */
    // 用可空而不是 lateinit:isInitialized 只能在同类内用,别的页面判不了
    var koyo: com.koyo.screenwarden.live2d.KoyoDock? = null
        private set
    /** 用户手动收起了模型,收起状态下切页不再自动把她放出来 */
    private var koyoCollapsed = false
    /** 聊天抽屉展开时，可又临时从输入框移到右侧边缘 */
    private var chatDrawerOpen = false
    /** 记录当前页是否从聊天侧栏进入，供系统返回键无界面地回聊天 */
    private var enteredFromChat = false
    /** 深度陪伴模式自己处理系统栏和键盘，普通页面仍沿用原来的根布局避让 */
    private var deepCompanionFullscreenActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        rootLayout = findViewById(R.id.root_layout)
        overlayContainer = findViewById(R.id.overlay_container)
        companionBreakCoordinator = CompanionBreakCoordinator(this, rootLayout)
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fragmentManager: FragmentManager,
                    fragment: Fragment,
                    view: View,
                    savedInstanceState: Bundle?
                ) {
                    // The studio intentionally names the built-in Koyo as a distinct
                    // collaborator. Every normal product surface follows the active role.
                    if (fragment !is CompanionStudioFragment) {
                        view.post { CompanionUiText.applyRecursively(this@MainActivity, view) }
                    }
                }
            },
            true
        )
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (deepCompanionFullscreenActive) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            }
            insets
        }

        // 覆盖层退出后：隐藏容器；若当前在聊天页，头像可能改过，刷一下
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                overlayContainer.visibility = View.GONE
                if (currentFragment === chatFragment && ::chatFragment.isInitialized) {
                    chatFragment.refreshAvatars()
                }
                updateCompanionBreakSurface()
            }
        }

        todayFragment = TodayFragment()
        chatFragment = ChatFragment()
        filesFragment = FilesFragment()
        peripheralsFragment = PeripheralsFragment()
        meTabFragment = MeTabFragment()

        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, todayFragment, "today")
            .add(R.id.fragment_container, chatFragment, "chat")
            .add(R.id.fragment_container, filesFragment, "files")
            .add(R.id.fragment_container, peripheralsFragment, "peripherals")
            .add(R.id.fragment_container, meTabFragment, "me")
            .hide(chatFragment)
            .hide(filesFragment)
            .hide(peripheralsFragment)
            .hide(meTabFragment)
            .commit()

        currentFragment = todayFragment
        // 根布局固定默认底色，非聊天页透出；聊天页背景由 ChatFragment 自绘
        rootLayout.setBackgroundColor(ThemeManager.DEFAULT_BG_COLOR)
        applyTheme()

        // 可又要盖在页面内容之上、覆盖层之下,所以在 overlayContainer 之前插入
        val dock = com.koyo.screenwarden.live2d.KoyoDock(rootLayout)
        rootLayout.removeView(dock.view)
        rootLayout.addView(dock.view, rootLayout.indexOfChild(overlayContainer))
        dock.onTap = { onKoyoTapped() }
        koyo = dock
        DeepCompanionController.install(this, rootLayout, dock)
        rootLayout.post {
            syncKoyoDock(animate = false)
            koyo?.playTodayEntryInvite()
        }

        UsageReportWorker.schedule(this)
        CommandCheckWorker.schedule(this)

        // 对外发布版：首次启动弹窗收集称呼，并初始化人格 TIYO.md
        maybeShowWelcome()
        PersonaFragment.ensureRuntimeRules(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false) || intent.hasExtra(EXTRA_SEND_TEXT)) {
            rootLayout.post {
                openChat()
                intent.getStringExtra(EXTRA_SEND_TEXT)?.takeIf(String::isNotBlank)?.let { text ->
                    chatFragment.sendExternalMessage(text)
                    intent.removeExtra(EXTRA_SEND_TEXT)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if ((intent.getBooleanExtra(EXTRA_OPEN_CHAT, false) || intent.hasExtra(EXTRA_SEND_TEXT)) &&
            ::chatFragment.isInitialized
        ) {
            openChat()
            intent.getStringExtra(EXTRA_SEND_TEXT)?.takeIf(String::isNotBlank)?.let { text ->
                chatFragment.sendExternalMessage(text)
                intent.removeExtra(EXTRA_SEND_TEXT)
            }
        }
    }

    private fun maybeShowWelcome() {
        if (UserPrefs.isOnboarded(this)) return
        rootLayout.post {
            WelcomeDialog(this) { name, ageGroup ->
                UserPrefs.setName(this, name)
                UserPrefs.setAgeGroup(this, ageGroup)
                UserPrefs.setOnboarded(this)
                initPersonaFile(name, ageGroup)
                // 问候语用上新称呼
                todayFragment.refresh()
            }.show()
        }
    }

    /** 首次启动写入带称呼和年龄段的人格文件；已有 TIYO.md 不覆盖（用户可能自定义过） */
    private fun initPersonaFile(name: String, ageGroup: UserPrefs.AgeGroup) {
        val personaFile = CompanionWorkspace.personaFile(this)
        try {
            if (!personaFile.isFile) {
                personaFile.parentFile?.mkdirs()
                personaFile.writeText(PersonaFragment.personaFor(name, ageGroup))
            }
            PersonaFragment.updateUserName(this, name)
        } catch (_: Exception) {
            // 写失败不阻塞启动
        }
    }

    /** 称呼设置保存后的单点刷新：今天页欢迎语立即更新，侧栏同步显示当前称呼。 */
    fun onUserNameChanged() {
        todayFragment.refresh()
        if (::chatFragment.isInitialized) chatFragment.refreshUserNameEntry()
    }

    private fun switchTo(fragment: Fragment, fromChat: Boolean = false) {
        if (currentFragment === fragment) {
            enteredFromChat = fromChat
            updateCompanionBreakSurface()
            return
        }
        supportFragmentManager.beginTransaction()
            .hide(currentFragment!!)
            .show(fragment)
            .commit()
        currentFragment = fragment
        enteredFromChat = fromChat
        updateCompanionBreakSurface()
        updateStatusBarColor()
        syncKoyoDock()

        when (fragment) {
            is TodayFragment -> {
                todayFragment.refresh()
                rootLayout.post { koyo?.playTodayEntryInvite() }
            }
        }
        fragment.view?.let { fragmentView ->
            fragmentView.post {
                if (fragment !is CompanionStudioFragment) {
                    CompanionUiText.applyRecursively(this, fragmentView)
                }
            }
        }
    }

    /** 按当前页面把可又挪到对应停靠位 */
    private fun syncKoyoDock(animate: Boolean = true) {
        val dock = koyo ?: return
        if (koyoCollapsed) {
            dock.goto(com.koyo.screenwarden.live2d.KoyoDock.State.HIDDEN, animate)
            return
        }
        val target = when (currentFragment) {
            todayFragment -> com.koyo.screenwarden.live2d.KoyoDock.State.HERO
            chatFragment -> chatFragment.preferredKoyoState(chatDrawerOpen)
            else -> com.koyo.screenwarden.live2d.KoyoDock.State.EDGE
        }
        dock.goto(target, animate)
    }

    fun onChatModeChanged(animate: Boolean = true) {
        if (currentFragment === chatFragment) syncKoyoDock(animate)
    }

    /** 点可又:今天页 -> 推屏进聊天;其它页 -> 直接进聊天 */
    private fun onKoyoTapped() {
        val dock = koyo
        if (currentFragment === chatFragment && chatDrawerOpen) {
            chatFragment.closeDrawerFromKoyo()
            return
        }
        if (currentFragment === chatFragment) {
            chatFragment.onKoyoTappedInChat()
            return
        }
        if (currentFragment === todayFragment && dock != null) {
            if (!chatFragment.isFocusMode()) {
                openChat()
                return
            }
            dock.pushDownToChat {
                if (currentFragment !== chatFragment) {
                    supportFragmentManager.beginTransaction()
                        .hide(currentFragment!!)
                        .show(chatFragment)
                        .commit()
                    currentFragment = chatFragment
                    chatDrawerOpen = false
                    enteredFromChat = false
                    updateCompanionBreakSurface()
                    updateStatusBarColor()
                }
            }
            return
        }
        if (currentFragment !== chatFragment) openChat()
    }

    /** 收起/展开模型,由聊天页的按钮调用 */
    fun toggleKoyoCollapsed(): Boolean {
        koyoCollapsed = !koyoCollapsed
        syncKoyoDock()
        return koyoCollapsed
    }

    fun isKoyoCollapsed() = koyoCollapsed

    fun setChatDrawerOpen(open: Boolean) {
        chatDrawerOpen = open
        if (currentFragment === chatFragment) syncKoyoDock()
    }

    /** 聊天页告知输入框区域高度,让她正好趴在输入框上沿 */
    fun setKoyoChatInset(px: Int) {
        koyo?.chatBottomInset = px
    }

    fun openFilesWorkspace() {
        switchTo(filesFragment, fromChat = true)
    }

    fun openToday() = switchTo(todayFragment, fromChat = true)

    fun openPeripherals() = switchTo(peripheralsFragment, fromChat = true)

    fun openMe() = switchTo(meTabFragment, fromChat = true)

    /** 全屏覆盖页（头像设置等）：加返回栈，系统返回键退出 */
    fun openOverlay(fragment: Fragment) {
        companionBreakCoordinator.setSurface(CompanionBreakCoordinator.Surface.NONE)
        overlayContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.overlay_container, fragment)
            .addToBackStack("overlay")
            .commit()
    }

    fun reloadAgentRuntime(): Boolean =
        if (::chatFragment.isInitialized) chatFragment.reloadAgentRuntime() else false

    fun openChat() {
        chatDrawerOpen = false
        switchTo(chatFragment, fromChat = false)
    }

    /** 供子页面（屏幕/步数/主题）从"我的"内部返回列表 */
    fun popMeStack() {
        val child = meTabFragment.childFragmentManager
        if (child.backStackEntryCount > 0) child.popBackStack()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return
        }
        if (currentFragment === meTabFragment) {
            val child = meTabFragment.childFragmentManager
            if (child.backStackEntryCount > 0) {
                child.popBackStack()
                return
            }
        }
        if (enteredFromChat) {
            openChat()
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::companionBreakCoordinator.isInitialized) {
            companionBreakCoordinator.setForeground(true)
            updateCompanionBreakSurface()
        }
        if (::todayFragment.isInitialized) todayFragment.refresh()
    }

    override fun onPause() {
        if (::companionBreakCoordinator.isInitialized) companionBreakCoordinator.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (::companionBreakCoordinator.isInitialized) companionBreakCoordinator.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && deepCompanionFullscreenActive) applyDeepCompanionSystemBars(hide = true)
    }

    /**
     * 深度场景铺满整块屏幕，系统栏仅能从边缘临时划出
     * 根布局不再跟随 IME 整体缩放，输入区由 DeepCompanionHostView 单独避让
     */
    internal fun setDeepCompanionFullscreen(enabled: Boolean) {
        if (deepCompanionFullscreenActive == enabled) {
            if (enabled) applyDeepCompanionSystemBars(hide = true)
            return
        }
        deepCompanionFullscreenActive = enabled
        if (enabled) rootLayout.setPadding(0, 0, 0, 0)
        applyDeepCompanionSystemBars(hide = enabled)
        ViewCompat.requestApplyInsets(rootLayout)
        if (!enabled) updateCompanionBreakSurface()
    }

    internal fun setDeepCompanionScene(scene: DeepCompanionHostView.Scene) {
        if (!::companionBreakCoordinator.isInitialized) return
        companionBreakCoordinator.setSurface(
            if (scene == DeepCompanionHostView.Scene.DESK) {
                CompanionBreakCoordinator.Surface.DESK
            } else {
                CompanionBreakCoordinator.Surface.NONE
            }
        )
    }

    private fun updateCompanionBreakSurface() {
        if (!::companionBreakCoordinator.isInitialized) return
        val eligible = !deepCompanionFullscreenActive &&
            supportFragmentManager.backStackEntryCount == 0 &&
            ::chatFragment.isInitialized && currentFragment === chatFragment
        companionBreakCoordinator.setSurface(
            if (eligible) CompanionBreakCoordinator.Surface.CHAT else CompanionBreakCoordinator.Surface.NONE
        )
    }

    private fun applyDeepCompanionSystemBars(hide: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun applyTheme() {
        // 主题只作用于聊天页背景；状态栏跟随当前页
        if (::chatFragment.isInitialized) chatFragment.applyChatTheme()
        updateStatusBarColor()
    }

    /** 状态栏颜色：聊天页跟随主题色，其他页用固定默认底色 */
    private fun updateStatusBarColor() {
        window.statusBarColor = if (currentFragment === chatFragment) {
            ThemeManager.getThemeBgColor()
        } else {
            ThemeManager.DEFAULT_BG_COLOR
        }
    }
}
