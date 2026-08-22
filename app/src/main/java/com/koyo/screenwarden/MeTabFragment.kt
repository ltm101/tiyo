package com.koyo.screenwarden

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * "我的" tab 容器。
 *
 * UI-2 阶段：默认显示资料头与入口行（屏幕用量/步数/主题），
 * 点击后用 childFragmentManager 把对应 fragment 推入 me_content_container，
 * 返回栈变化时切换列表与子页面的可见性，系统返回键弹回列表。
 * UI-7 会把它扩展成完整的 Me 页。
 */
class MeTabFragment : Fragment() {

    private var homeList: View? = null
    private var contentContainer: View? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_me_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeList = view.findViewById(R.id.me_home_list)
        contentContainer = view.findViewById(R.id.me_content_container)

        view.findViewById<View>(R.id.me_row_stats).setOnClickListener { openChild(StatsFragment()) }
        view.findViewById<View>(R.id.me_row_activity).setOnClickListener { openChild(ActivityFragment()) }
        view.findViewById<View>(R.id.me_row_study).setOnClickListener { openChild(StudyFragment()) }
        view.findViewById<View>(R.id.me_row_reply_style).setOnClickListener { openChild(ReplyStyleFragment()) }
        view.findViewById<View>(R.id.me_row_companions).setOnClickListener { openChild(CompanionStudioFragment()) }
        view.findViewById<View>(R.id.me_row_persona).setOnClickListener { openChild(PersonaFragment()) }
        view.findViewById<View>(R.id.me_row_memory).setOnClickListener { openChild(MemoryManagerFragment()) }
        view.findViewById<View>(R.id.me_row_stickers).setOnClickListener { openChild(StickerManagerFragment()) }
        view.findViewById<View>(R.id.me_row_guard).setOnClickListener { openChild(GuardSettingsFragment()) }
        view.findViewById<View>(R.id.me_row_mail).setOnClickListener { openChild(MailSettingsFragment()) }
        view.findViewById<View>(R.id.me_row_presence).setOnClickListener { openChild(PresenceSettingsFragment()) }
        view.findViewById<View>(R.id.me_row_help).setOnClickListener { openChild(HelpFragment()) }

        // 资料头头像跟随聊天里选的可又头像
        view.findViewById<ImageView>(R.id.me_avatar)?.let { bindKoyoAvatar(it) }
        bindCompanionIdentity(view)

        // 返回栈变化时：有子页面则隐藏列表、显示容器；否则反向
        childFragmentManager.addOnBackStackChangedListener {
            val hasChild = childFragmentManager.backStackEntryCount > 0
            homeList?.visibility = if (hasChild) View.GONE else View.VISIBLE
            contentContainer?.visibility = if (hasChild) View.VISIBLE else View.GONE
        }
    }

    /** 供子页面（如守护页的"蒸馏回复风格"）跳转到回复性格页 */
    fun openReplyStyle() {
        openChild(ReplyStyleFragment())
    }

    private fun openChild(fragment: Fragment) {
        childFragmentManager.beginTransaction()
            .replace(R.id.me_content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun bindKoyoAvatar(avatar: ImageView) {
        val custom = AvatarStore.loadCompanionBitmap(requireContext())
        if (custom != null) avatar.setImageBitmap(custom)
        else avatar.setImageResource(AvatarStore.companionRes(requireContext()))
    }

    private fun bindCompanionIdentity(root: View) {
        val profile = CompanionProfileStore.active(requireContext())
        root.findViewById<TextView>(R.id.me_companion_name)?.text = profile.displayName
        root.findViewById<TextView>(R.id.me_companion_subtitle)?.text =
            if (profile.isBuiltInCompanion) "你的手机伴侣" else "独立人格与记忆空间"
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<ImageView>(R.id.me_avatar)?.let { bindKoyoAvatar(it) }
        view?.let(::bindCompanionIdentity)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.findViewById<ImageView>(R.id.me_avatar)?.let { bindKoyoAvatar(it) }
            view?.let(::bindCompanionIdentity)
        }
    }
}
