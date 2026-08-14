package com.koyo.screenwarden

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 外设控制页：按 driver 渲染控制面板。
 *
 * 本期（第 5 步）预置小车直接复用 CarFragment 作为控制面板；
 * BLE / 自定义驱动的动态渲染在后续步骤接入。
 */
class PeripheralControlFragment : Fragment(R.layout.fragment_peripheral_control) {

    companion object {
        private const val ARG_ID = "peripheral_id"
        fun newInstance(id: String): PeripheralControlFragment =
            PeripheralControlFragment().apply {
                arguments = Bundle().apply { putString(ARG_ID, id) }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = requireArguments().getString(ARG_ID) ?: return
        val p = PeripheralRepository.loadAll(requireContext()).firstOrNull { it.id == id }

        val title = view.findViewById<TextView>(R.id.peripheral_title)
        val back = view.findViewById<TextView>(R.id.btn_back)
        title.text = p?.name ?: id
        back.setOnClickListener { activity?.onBackPressed() }

        // 预置小车驱动：复用 CarFragment 作为控制面板（等于原小车页）
        if (p?.driverId == "car_koyo") {
            childFragmentManager.beginTransaction()
                .replace(R.id.control_container, CarFragment(), "car_control")
                .commit()
        } else {
            // 已连接但无驱动：明确提示，配置驱动入口后续步骤接入
            title.text = (p?.name ?: id) + " · 无驱动"
        }
    }
}
