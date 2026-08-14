package com.koyo.screenwarden

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.json.JSONObject

/**
 * 外设列表页：展示已配对外设 + 状态，点击进入控制页。
 */
class PeripheralsFragment : Fragment(R.layout.fragment_peripherals) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        PeripheralRepository.seedIfEmpty(requireContext())
        view.findViewById<Button>(R.id.btn_scan_peripherals).setOnClickListener { onScanClicked() }
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refreshList()
    }

    // ---------- 扫描新设备 ----------

    private val scanPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startBleScan()
        else toast("需要蓝牙权限才能扫描设备")
    }

    private fun onScanClicked() {
        if (!BleConnector.isBluetoothEnabled(requireContext())) {
            toast("请先打开蓝牙再扫描")
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) needed += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (needed.isNotEmpty()) {
            scanPermissionLauncher.launch(needed.toTypedArray())
        } else {
            startBleScan()
        }
    }

    private fun startBleScan() {
        toast("正在扫描附近的蓝牙设备…")
        val seen = linkedMapOf<String, BleConnector.ScannedDevice>()
        BleConnector.startScan(
            requireContext(),
            timeoutMs = 8000,
            onDevice = { device -> seen[device.address] = device },
            onDone = {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (seen.isEmpty()) {
                        toast("没扫到蓝牙设备，确认对方已开机且在附近")
                        return@runOnUiThread
                    }
                    showScanResults(seen.values.toList())
                }
            }
        )
    }

    private fun showScanResults(devices: List<BleConnector.ScannedDevice>) {
        val labels = devices.map { "${it.name} · ${it.address}" }
        AlertDialog.Builder(requireContext())
            .setTitle("扫到 ${devices.size} 台设备")
            .setItems(labels.toTypedArray()) { _, which -> pairBleDevice(devices[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pairBleDevice(device: BleConnector.ScannedDevice) {
        val existing = PeripheralRepository.loadAll(requireContext())
            .firstOrNull { it.id == device.address }
        if (existing == null) {
            PeripheralRepository.update(
                requireContext(),
                PeripheralRepository.Peripheral(
                    id = device.address,
                    name = device.name,
                    connectorType = "BLE",
                    driverId = null,
                    status = "DISCONNECTED",
                    config = JSONObject().put("address", device.address)
                )
            )
            toast("已添加 ${device.name}，点进去可配置驱动")
        }
        refreshList()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun refreshList() {
        val list = PeripheralRepository.loadAll(requireContext())
        val container = requireView().findViewById<LinearLayout>(R.id.peripheral_list)
        val empty = requireView().findViewById<TextView>(R.id.peripheral_empty)
        container.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { p -> container.addView(buildRow(p)) }
    }

    private fun buildRow(p: PeripheralRepository.Peripheral): View {
        val driverName = DriverRegistry.get(p.driverId.orEmpty())?.name
            ?: if (p.driverId.isNullOrBlank()) "无驱动" else p.driverId.orEmpty()
        val card = CardView(requireContext()).apply {
            radius = 18f
            cardElevation = 0f
            setCardBackgroundColor(0xFFFCF6.toInt())
            setContentPadding(16, 14, 16, 14)
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        row.addView(TextView(requireContext()).apply {
            text = p.name
            setTextColor(0xFF292722.toInt())
            textSize = 16f
        })
        row.addView(TextView(requireContext()).apply {
            text = when (p.status) {
                "CONNECTED" -> "已连接 · $driverName"
                "ERROR" -> "连接出错 · $driverName"
                else -> "$driverName · 未连接"
            }
            setTextColor(0xFF817A70.toInt())
            textSize = 12f
        })
        card.addView(row)
        card.setOnClickListener { openControl(p) }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun openControl(p: PeripheralRepository.Peripheral) {
        (activity as? MainActivity)?.openOverlay(PeripheralControlFragment.newInstance(p.id))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
