package com.koyo.screenwarden

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * A small, reversible Android assistant entry point.
 *
 * The app qualifies for ROLE_ASSISTANT through ACTION_ASSIST. It deliberately
 * does not disable or modify any vivo assistant package, so the user can switch
 * the default role back at any time.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var answerText: TextView
    private lateinit var roleButton: Button
    private lateinit var micButton: Button
    private lateinit var stopButton: Button

    private lateinit var offlineSpeech: OfflineSpeechController
    private var replyPlayer: MediaPlayer? = null
    private var replyAudioFile: File? = null
    private var requestInProgress = false
    private var autoListenAfterRoleRequest = false

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            statusText.text = "需要麦克风权限才能听见你"
        }
    }

    private val assistantRoleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        updateRoleState()
        if (result.resultCode == Activity.RESULT_OK && autoListenAfterRoleRequest) {
            autoListenAfterRoleRequest = false
            beginVoiceInput()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        setContentView(R.layout.activity_assistant)

        statusText = findViewById(R.id.assistant_status)
        heardText = findViewById(R.id.assistant_heard)
        answerText = findViewById(R.id.assistant_answer)
        roleButton = findViewById(R.id.assistant_role_button)
        micButton = findViewById(R.id.assistant_mic_button)
        stopButton = findViewById(R.id.assistant_stop_button)

        val companionScope = CompanionScope.capture(this)
        CompanionUiText.applyRecursively(this, findViewById(android.R.id.content))
        findViewById<ImageView>(R.id.assistant_avatar).let { avatar ->
            val custom = AvatarStore.loadCompanionBitmap(this, companionScope)
            if (custom != null) avatar.setImageBitmap(custom)
            else avatar.setImageResource(AvatarStore.companionRes(this, companionScope))
        }

        offlineSpeech = OfflineSpeechController(
            applicationContext,
            object : OfflineSpeechController.Listener {
                override fun onPreparing() {
                    statusText.text = "正在准备离线中文识别，第一次会稍慢"
                    answerText.text = "马上就好"
                    micButton.isEnabled = false
                }

                override fun onListening() {
                    statusText.text = "正在听你说"
                    answerText.text = "我在听"
                    micButton.isEnabled = false
                }

                override fun onPartial(text: String) {
                    heardText.text = text
                    statusText.text = "听到了，继续说"
                }

                override fun onResult(text: String) {
                    micButton.isEnabled = true
                    heardText.text = text
                    sendToKoyo(text)
                }

                override fun onNoSpeech() {
                    micButton.isEnabled = true
                    statusText.text = "没听清，再说一次吧"
                    answerText.text = "点一下，我再听"
                }

                override fun onError(message: String) {
                    micButton.isEnabled = true
                    statusText.text = message
                    answerText.text = "可以再试一次"
                }
            }
        )

        roleButton.setOnClickListener { requestAssistantRole(autoListen = false) }
        micButton.setOnClickListener { beginVoiceInput() }
        stopButton.setOnClickListener {
            offlineSpeech.stop()
            stopReplyPlayback()
            statusText.text = "已停止"
            micButton.isEnabled = true
        }

        updateRoleState()

        val launchedAsAssistant = intent?.action == Intent.ACTION_ASSIST
        val requestRole = intent?.getBooleanExtra(EXTRA_REQUEST_ROLE, false) == true
        val autoListen = intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true
        when {
            requestRole && !isAssistantRoleHeld() -> requestAssistantRole(autoListen)
            launchedAsAssistant || autoListen -> beginVoiceInput()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == Intent.ACTION_ASSIST) beginVoiceInput()
    }

    private fun requestAssistantRole(autoListen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            statusText.text = "这台手机没有开放默认助理角色"
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            updateRoleState()
            if (autoListen) beginVoiceInput()
            return
        }
        autoListenAfterRoleRequest = autoListen
        assistantRoleRequest.launch(
            roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        )
    }

    private fun isAssistantRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return getSystemService(RoleManager::class.java)
            .isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    private fun updateRoleState() {
        val held = isAssistantRoleHeld()
        roleButton.visibility = if (held) View.GONE else View.VISIBLE
        statusText.text = getString(
            if (held) R.string.assistant_role_set else R.string.assistant_role_not_set
        )
    }

    private fun beginVoiceInput() {
        if (requestInProgress) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        stopReplyPlayback()
        heardText.text = "…"
        offlineSpeech.start()
    }

    private fun sendToKoyo(text: String) {
        if (!CompanionProfileStore.active(this).isBuiltInCompanion) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                    .putExtra(MainActivity.EXTRA_SEND_TEXT, text)
            )
            finish()
            return
        }
        requestInProgress = true
        micButton.isEnabled = false
        statusText.text = "${CompanionProfileStore.activeName(this)}正在想"
        answerText.text = "…"

        Thread {
            try {
                val reply = requestKoyo(text)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    requestInProgress = false
                    micButton.isEnabled = true
                    answerText.text = reply.text.ifBlank { "我在，刚才没有组织好语言" }
                    statusText.text = "正在生成手机语音"
                    downloadAndPlayReply(reply.gateway, answerText.text.toString())
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    requestInProgress = false
                    micButton.isEnabled = true
                statusText.text = "暂时没接上电脑里的${CompanionProfileStore.activeName(this)}"
                    answerText.text = error.message?.takeIf { it.isNotBlank() }
                        ?: "请确认 USB 仍然连接"
                }
            }
        }.start()
    }

    private data class KoyoReply(val text: String, val gateway: String)

    private fun requestKoyo(text: String): KoyoReply {
        val candidates = linkedSetOf("http://127.0.0.1:8888")
        val cached = getSharedPreferences(GATEWAY_PREFS_NAME, 0)
            .getString(KEY_GATEWAY_URL, "")
            .orEmpty()
            .trimEnd('/')
        if (cached.isNotBlank()) candidates.add(cached)
        discoverGateway()?.let(candidates::add)

        var lastError: Exception? = null
        candidates.forEach { baseUrl ->
            try {
                val reply = postChat(baseUrl, text)
                getSharedPreferences(GATEWAY_PREFS_NAME, 0).edit()
                    .putString(KEY_GATEWAY_URL, baseUrl)
                    .apply()
                return KoyoReply(reply, baseUrl)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("没有发现${CompanionProfileStore.activeName(this)}网关")
    }

    private fun postChat(baseUrl: String, text: String): String {
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
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException(body.ifBlank { "请求失败 $code" })
        }
        return body
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
                        DatagramPacket(query, query.size, destination, GATEWAY_DISCOVERY_PORT)
                    )
                }

                val response = DatagramPacket(ByteArray(128), 128)
                socket.receive(response)
                val reply = String(
                    response.data,
                    0,
                    response.length,
                    Charsets.US_ASCII
                )
                if (reply.trim() == GATEWAY_DISCOVERY_REPLY) {
                    "http://${response.address.hostAddress}:8888"
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadAndPlayReply(baseUrl: String, text: String) {
        Thread {
            var audioFile: File? = null
            try {
                val connection = (
                    URL("${baseUrl.trimEnd('/')}/koyo/tts.mp3")
                        .openConnection() as HttpURLConnection
                ).apply {
                    connectTimeout = 3500
                    readTimeout = 60_000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val payload = JSONObject().put("text", text).toString()
                    .toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
                if (connection.responseCode !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    connection.disconnect()
                    throw IllegalStateException(detail.ifBlank { "语音生成失败" })
                }
                val targetFile = File.createTempFile("koyo-reply-", ".mp3", cacheDir)
                audioFile = targetFile
                connection.inputStream.use { input ->
                    targetFile.outputStream().use(input::copyTo)
                }
                connection.disconnect()
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        targetFile.delete()
                        return@runOnUiThread
                    }
                    playReplyFile(targetFile)
                }
                audioFile = null
            } catch (error: Exception) {
                audioFile?.delete()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    statusText.text = "手机语音没有生成成功"
                    answerText.append("\n${error.message.orEmpty()}")
                }
            }
        }.start()
    }

    private fun playReplyFile(audioFile: File) {
        stopReplyPlayback()
        replyAudioFile = audioFile
        replyPlayer = MediaPlayer().also { player ->
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(audioFile.absolutePath)
            player.setOnPreparedListener {
                statusText.text = "${CompanionProfileStore.activeName(this)}正在朗读"
                it.start()
            }
            player.setOnCompletionListener {
                statusText.text = "回答完啦"
                stopReplyPlayback()
            }
            player.setOnErrorListener { _, _, _ ->
                statusText.text = "语音文件播放失败"
                stopReplyPlayback()
                true
            }
            player.prepareAsync()
        }
    }

    private fun stopReplyPlayback() {
        replyPlayer?.runCatching { stop() }
        replyPlayer?.release()
        replyPlayer = null
        replyAudioFile?.delete()
        replyAudioFile = null
    }

    override fun onDestroy() {
        offlineSpeech.shutdown()
        stopReplyPlayback()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_ROLE = "request_role"
        const val EXTRA_AUTO_LISTEN = "auto_listen"

        private const val GATEWAY_PREFS_NAME = "tiyo_realtime_state"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val GATEWAY_DISCOVERY_QUERY = "KOYO_GATEWAY_DISCOVER"
        private const val GATEWAY_DISCOVERY_REPLY = "KOYO_GATEWAY"
        private const val GATEWAY_DISCOVERY_PORT = 4211
        private const val GATEWAY_DISCOVERY_TIMEOUT_MS = 1200
    }
}
