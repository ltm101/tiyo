package com.koyo.screenwarden

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CarFragment : Fragment(R.layout.fragment_car) {

    private val carDriver = DriverRegistry.get("car_koyo")!!

    private lateinit var etIp: EditText
    private lateinit var etCameraIp: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvGatewayStatus: TextView
    private lateinit var tvOledExpression: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var ivCamera: ImageView
    private lateinit var placeholderCamera: View
    private lateinit var tvStreamStatus: TextView

    private var speed = 50
    private var cameraBaseUrl: String? = null
    private var micButton: Button? = null
    @Volatile private var connectionInProgress = false

    private val voiceRecognition = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        micButton?.text = "和小车说话"
        if (result.resultCode != Activity.RESULT_OK) {
            notifyOled("oledIdle")
            showMessage("已取消语音输入")
            return@registerForActivityResult
        }
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (text.isNullOrEmpty()) {
            notifyOled("oledIdle")
            showMessage("没听清，再说一次吧")
        }
        else {
            showMessage("你：$text")
            postChat(text)
        }
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchVoiceRecognizer() else showMessage("需要麦克风权限才能和小车说话")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etIp = view.findViewById(R.id.et_car_ip)
        etCameraIp = view.findViewById(R.id.et_camera_ip)
        tvStatus = view.findViewById(R.id.tv_status)
        tvGatewayStatus = view.findViewById(R.id.tv_gateway_status)
        tvOledExpression = view.findViewById(R.id.tv_oled_expression)
        tvSpeed = view.findViewById(R.id.tv_speed)
        ivCamera = view.findViewById(R.id.iv_camera)
        placeholderCamera = view.findViewById(R.id.camera_placeholder)
        tvStreamStatus = view.findViewById(R.id.tv_stream_status)
        micButton = view.findViewById(R.id.btn_mic)
        val carPrefs = requireContext().getSharedPreferences("car", 0)
        // One-time migration from DHCP addresses saved by older APKs to stable mDNS names.
        if (!carPrefs.getBoolean("mdns_names_v1", false)) {
            carPrefs.edit()
                .putString("base_url", "http://koyo-car.local")
                .putString("camera_url", "http://koyo-vision.local:81")
                .putBoolean("mdns_names_v1", true)
                .apply()
        }
        etIp.setText(carPrefs.getString("base_url", "http://koyo-car.local"))
        etCameraIp.setText(carPrefs.getString("camera_url", "http://koyo-vision.local:81"))

        // ── 连接 ──
        view.findViewById<Button>(R.id.btn_test).setOnClickListener {
            connectToCar()
        }
        view.findViewById<Button>(R.id.btn_camera_connect).setOnClickListener {
            val cameraUrl = normalizeBaseUrl(etCameraIp.text.toString())
            if (cameraUrl.isEmpty()) {
                showMessage("请填写 ESP32-CAM 地址")
            } else {
                requireContext().getSharedPreferences("car", 0).edit()
                    .putString("camera_url", cameraUrl).apply()
                startCameraStream()
            }
        }

        // ── 方向键 ──
        view.findViewById<Button>(R.id.btn_fwd).setOnClickListener { runAction("forward", "前进") }
        view.findViewById<Button>(R.id.btn_back).setOnClickListener { runAction("back", "后退") }
        view.findViewById<Button>(R.id.btn_left).setOnClickListener { runAction("left", "左转") }
        view.findViewById<Button>(R.id.btn_right).setOnClickListener { runAction("right", "右转") }
        view.findViewById<Button>(R.id.btn_stop).setOnClickListener { runAction("stop", "已停止") }

        // ── 速度 ──
        view.findViewById<Button>(R.id.btn_speed_up).setOnClickListener {
            if (speed < 100) speed += 5
            tvSpeed.text = speed.toString()
            runAction("setSpeed", "速度 $speed%", paramValue = speed.toString())
        }
        view.findViewById<Button>(R.id.btn_speed_down).setOnClickListener {
            if (speed > 0) speed -= 5
            tvSpeed.text = speed.toString()
            runAction("setSpeed", "速度 $speed%", paramValue = speed.toString())
        }

        // ── 颜色追踪 ──
        view.findViewById<Button>(R.id.btn_track_red).setOnClickListener { track("red", "正在追踪红色") }
        view.findViewById<Button>(R.id.btn_track_yellow).setOnClickListener { track("yellow", "正在追踪黄色") }
        view.findViewById<Button>(R.id.btn_track_black).setOnClickListener { track("black", "正在追踪黑色") }
        view.findViewById<Button>(R.id.btn_track_blue).setOnClickListener { track("blue", "正在追踪蓝色") }
        view.findViewById<Button>(R.id.btn_track_off).setOnClickListener { track("off", "追踪已关闭") }

        // ── OLED 表情 ──
        view.findViewById<Button>(R.id.btn_emoji_happy).setOnClickListener {
            setOledExpression("happy", "开心")
        }
        view.findViewById<Button>(R.id.btn_emoji_worried).setOnClickListener {
            setOledExpression("worried", "担心")
        }
        view.findViewById<Button>(R.id.btn_emoji_sad).setOnClickListener {
            setOledExpression("sad", "难过")
        }
        view.findViewById<Button>(R.id.btn_emoji_angry).setOnClickListener {
            setOledExpression("angry", "生气")
        }
        view.findViewById<Button>(R.id.btn_emoji_gentle).setOnClickListener {
            setOledExpression("gentle", "温柔")
        }
        view.findViewById<Button>(R.id.btn_emoji_excited).setOnClickListener {
            setOledExpression("excited", "兴奋")
        }
        view.findViewById<Button>(R.id.btn_emoji_idle).setOnClickListener {
            runAction("oledIdle", "OLED：待机") {
                tvOledExpression.text = "当前：待机"
            }
        }

        view.findViewById<Button>(R.id.btn_audio_test).setOnClickListener {
            runAction("audioTest", "正在播放三音阶测试音")
        }
        view.findViewById<Button>(R.id.btn_audio_stop).setOnClickListener {
            runAction("audioStop", "放音已停止")
        }
        micButton?.setOnClickListener { beginVoiceInput() }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopCameraStream()
        } else {
            connectToCar()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) connectToCar()
    }

    override fun onPause() {
        super.onPause()
        stopCameraStream()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopCameraStream()
        micButton = null
    }

    // ── 摄像头 ──

    private fun startCameraStream() {
        val controllerUrl = normalizeBaseUrl(etIp.text.toString())
        val manualCameraUrl = normalizeBaseUrl(etCameraIp.text.toString())
        if (controllerUrl.isEmpty() && manualCameraUrl.isEmpty()) return

        placeholderCamera.visibility = View.VISIBLE
        ivCamera.visibility = View.GONE
        tvStreamStatus.text = "连接中..."

        Thread {
            val discovered = discoverCameraUdp()
                ?: discoverCameraUrl(controllerUrl)
                ?: manualCameraUrl.takeIf { it.isNotEmpty() }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                if (discovered == null) {
                    ivCamera.visibility = View.GONE
                    placeholderCamera.visibility = View.VISIBLE
                    tvStreamStatus.text = "没有找到摄像头"
                    return@runOnUiThread
                }
                cameraBaseUrl = discovered
                etCameraIp.setText(discovered)
                requireContext().getSharedPreferences("car", 0).edit()
                    .putString("camera_url", discovered).apply()
                CameraStreamer.start(
                    baseUrl = discovered,
                    onFrame = { bitmap ->
                ivCamera.setImageBitmap(bitmap)
                if (ivCamera.visibility != View.VISIBLE) {
                    ivCamera.visibility = View.VISIBLE
                    placeholderCamera.visibility = View.GONE
                }
                tvStreamStatus.text = "串流中 ${bitmap.width}×${bitmap.height}"
                    },
                    onError = { msg ->
                ivCamera.visibility = View.GONE
                placeholderCamera.visibility = View.VISIBLE
                tvStreamStatus.text = msg
                    }
                )
            }
        }.start()
    }

    private fun stopCameraStream() {
        CameraStreamer.stop()
        ivCamera.visibility = View.GONE
        placeholderCamera.visibility = View.VISIBLE
        tvStreamStatus.text = "已断开"
    }

    // ── 辅助 ──

    private fun normalizeBaseUrl(raw: String): String {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "http://$value"
    }

    private fun track(color: String, message: String) {
        runAction("track", message, paramValue = color)
    }

    private fun setOledExpression(emotion: String, label: String) {
        runAction("emoji", "OLED：$label", paramValue = emotion) {
            tvOledExpression.text = "当前：$label"
        }
    }

    /**
     * 先通过 UDP 获取控制器当前地址，避免 .local 被 VPN/TUN 的 fake-IP DNS 劫持。
     * UDP 不可用时再回退到手动地址、上次成功地址、mDNS 和小车 AP 固定地址。
     */
    private fun connectToCar() {
        if (connectionInProgress) return
        connectionInProgress = true

        val manual = normalizeBaseUrl(etIp.text.toString())
        val saved = normalizeBaseUrl(requireContext().getSharedPreferences("car", 0)
            .getString("base_url", "").orEmpty())
        val candidates = listOf(
            manual,
            saved,
            "http://koyo-car.local",
            "http://192.168.4.1"
        ).filter { it.isNotEmpty() }.distinct()

        tvStatus.text = "正在寻找小车…"
        refreshGatewayStatus()
        Thread {
            val broadcastDiscovered = discoverControllerUdp()
            if (broadcastDiscovered != null && probeController(broadcastDiscovered)) {
                connectionInProgress = false
                activity?.runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    etIp.setText(broadcastDiscovered)
                    requireContext().getSharedPreferences("car", 0).edit()
                        .putString("base_url", broadcastDiscovered).apply()
                    tvStatus.text = "小车已连接 · $broadcastDiscovered"
                    startCameraStream()
                }
                return@Thread
            }

            for (candidate in candidates) {
                if (probeController(candidate)) {
                    connectionInProgress = false
                    activity?.runOnUiThread {
                        if (!isAdded || view == null) return@runOnUiThread
                        etIp.setText(candidate)
                        requireContext().getSharedPreferences("car", 0).edit()
                            .putString("base_url", candidate).apply()
                        tvStatus.text = "小车已连接 · $candidate"
                        startCameraStream()
                    }
                    return@Thread
                }
            }

            activity?.runOnUiThread {
                if (isAdded && view != null) {
                    tvStatus.text = "没有找到小车，请检查 WiFi 或手动填写地址"
                }
            }
            connectionInProgress = false
        }.start()
    }

    private fun refreshGatewayStatus() {
        tvGatewayStatus.text = "电脑网关：正在寻找…"
        Thread {
            val gatewayUrl = discoverGatewayUdp()
            val connected = gatewayUrl != null && probeGateway(gatewayUrl)
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                tvGatewayStatus.text = if (connected) {
                    "电脑网关：已连接 · $gatewayUrl"
                } else {
                    "电脑网关：未发现（请确认电脑与手机在同一热点）"
                }
            }
        }.start()
    }

    private fun discoverGatewayUdp(): String? =
        LanConnector.discover("KOYO_GATEWAY_DISCOVER", 4211, "KOYO_GATEWAY") { _, from ->
            "http://${from.hostAddress}:8888"
        }

    private fun probeGateway(baseUrl: String): Boolean = LanConnector.probe(baseUrl, "/health")

    private fun discoverControllerUdp(): String? =
        LanConnector.discover("KOYO_DISCOVER", 4210, "KOYO_CAR:http://")?.let(::normalizeBaseUrl)

    private fun discoverCameraUdp(): String? =
        LanConnector.discover("KOYO_VISION_DISCOVER", 4213, "KOYO_VISION:http://")?.let(::normalizeBaseUrl)

    private fun probeController(baseUrl: String): Boolean = LanConnector.probe(baseUrl, "/api/status")

    private fun beginVoiceInput() {
        showMessage("正在打开语音识别…")
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            launchVoiceRecognizer()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchVoiceRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "和${CompanionProfileStore.activeName(requireContext())}说话"
            )
        }
        try {
            micButton?.text = "正在听…"
            notifyOled("oledListen")
            voiceRecognition.launch(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                // 部分国产 ROM 安装了 Google App，但不会把它注册为系统默认识别器。
                intent.setPackage("com.google.android.googlequicksearchbox")
                notifyOled("oledListen")
                voiceRecognition.launch(intent)
            } catch (_: ActivityNotFoundException) {
                notifyOled("oledIdle")
                micButton?.text = "和小车说话"
                showMessage("Google App 未提供语音识别，请检查其麦克风权限")
            }
        }
    }

    private fun showMessage(message: String) {
        if (::tvStatus.isInitialized) tvStatus.text = message
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    /** 通知 OLED 切换角色状态，不阻塞语音界面，也不弹 Toast。 */
    private fun notifyOled(actionName: String) {
        val base = normalizeBaseUrl(etIp.text.toString())
        if (base.isEmpty()) return
        Thread {
            PeripheralActionEngine.executeLan(
                base, carDriver, actionName, connectTimeoutMs = 1200, readTimeoutMs = 1200
            )
        }.start()
    }

    private fun postChat(text: String) {
        val base = normalizeBaseUrl(etIp.text.toString())
        if (base.isEmpty()) {
            tvStatus.text = "先填写小车地址"
            return
        }
        tvStatus.text = "已发送，等待${CompanionProfileStore.activeName(requireContext())}回答…"
        Thread {
            val result = PeripheralActionEngine.executeLan(
                base, carDriver, "chat",
                connectTimeoutMs = 5000, readTimeoutMs = 110000, body = text
            )
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                when (result) {
                    is PeripheralActionEngine.Result.Success ->
                        tvStatus.text = result.reply.ifEmpty { "小车正在回答" }
                    is PeripheralActionEngine.Result.HttpError ->
                        tvStatus.text = result.message.ifEmpty { "对话请求失败（${result.code}）" }
                    is PeripheralActionEngine.Result.Timeout ->
                        tvStatus.text = "回答生成超时，小车不一定断线，可以再问一次"
                    is PeripheralActionEngine.Result.NetworkError -> {
                        // 聊天是一条很长的请求；失败后重新探测状态，不能把一次读流
                        // 异常直接等同于小车掉线。
                        val stillConnected = probeController(base)
                        tvStatus.text = if (stillConnected) {
                            "这次回答传输失败，但小车仍在线"
                        } else {
                            "暂时联系不到小车，请检查 WiFi"
                        }
                    }
                }
            }
        }.start()
    }

    private fun runAction(actionName: String, successMessage: String, paramValue: String? = null, onSuccess: (() -> Unit)? = null) {
        val base = normalizeBaseUrl(etIp.text.toString())
        if (base.isEmpty()) {
            tvStatus.text = "先填写小车地址"
            return
        }
        tvStatus.text = "发送中…"
        Thread {
            val result = PeripheralActionEngine.executeLan(base, carDriver, actionName, paramValue)
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                when (result) {
                    is PeripheralActionEngine.Result.Success -> {
                        tvStatus.text = successMessage
                        onSuccess?.invoke()
                    }
                    is PeripheralActionEngine.Result.HttpError -> tvStatus.text = result.message
                    else -> tvStatus.text = "连接失败，请检查 WiFi 和地址"
                }
            }
        }.start()
    }

    private fun discoverCameraUrl(controllerUrl: String): String? {
        if (controllerUrl.isEmpty()) return null
        return try {
            val connection = URL("$controllerUrl/api/caminfo").openConnection() as HttpURLConnection
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            JSONObject(json).optString("stream")
                .removeSuffix("/stream")
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
