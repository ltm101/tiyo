package com.koyo.screenwarden

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/** Runs Mandarin speech recognition fully inside Tiyo, without an OEM service. */
class OfflineSpeechController(
    private val context: Context,
    private val listener: Listener
) : RecognitionListener {

    interface Listener {
        fun onPreparing()
        fun onListening()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onNoSpeech()
        fun onError(message: String)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var initializationStarted = false
    private var pendingStart = false
    private var completed = false
    private var lastPartial = ""

    fun start() {
        stopCurrentSession(cancel = true)
        completed = false
        lastPartial = ""
        val loadedModel = model
        if (loadedModel == null) {
            pendingStart = true
            listener.onPreparing()
            initializeModel()
            return
        }
        startWithModel(loadedModel)
    }

    fun stop() {
        pendingStart = false
        val partial = lastPartial
        stopCurrentSession(cancel = false)
        if (!completed) {
            completed = true
            if (partial.isNotBlank()) listener.onResult(partial) else listener.onNoSpeech()
        }
    }

    fun shutdown() {
        pendingStart = false
        stopCurrentSession(cancel = true)
        model?.close()
        model = null
    }

    private fun initializeModel() {
        if (initializationStarted) return
        initializationStarted = true
        StorageService.unpack(
            context,
            MODEL_ASSET_PATH,
            MODEL_TARGET_PATH,
            { loadedModel ->
                initializationStarted = false
                model = loadedModel
                if (pendingStart) {
                    pendingStart = false
                    startWithModel(loadedModel)
                }
            },
            { error ->
                initializationStarted = false
                pendingStart = false
                listener.onError("离线识别模型准备失败：${error.message.orEmpty()}")
            }
        )
    }

    private fun startWithModel(loadedModel: Model) {
        try {
            val activeRecognizer = Recognizer(loadedModel, SAMPLE_RATE)
            recognizer = activeRecognizer
            speechService = SpeechService(activeRecognizer, SAMPLE_RATE).also { service ->
                listener.onListening()
                service.startListening(this, LISTEN_TIMEOUT_MS)
            }
        } catch (error: Exception) {
            stopCurrentSession(cancel = true)
            listener.onError("离线识别启动失败：${error.message.orEmpty()}")
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        val text = parseText(hypothesis, "partial")
        if (text.isNotBlank()) {
            lastPartial = text
            listener.onPartial(text)
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = parseText(hypothesis, "text")
        if (text.isBlank() || completed) return
        completed = true
        stopCurrentSession(cancel = true)
        listener.onResult(text)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (completed) return
        val text = parseText(hypothesis, "text").ifBlank { lastPartial }
        completed = true
        stopCurrentSession(cancel = true)
        if (text.isBlank()) listener.onNoSpeech() else listener.onResult(text)
    }

    override fun onError(exception: Exception?) {
        if (completed) return
        completed = true
        stopCurrentSession(cancel = true)
        listener.onError(exception?.message ?: "离线语音识别失败")
    }

    override fun onTimeout() {
        if (completed) return
        val text = lastPartial
        completed = true
        stopCurrentSession(cancel = true)
        if (text.isBlank()) listener.onNoSpeech() else listener.onResult(text)
    }

    private fun stopCurrentSession(cancel: Boolean) {
        val activeService = speechService
        speechService = null
        if (activeService != null) {
            if (cancel) activeService.cancel() else activeService.stop()
            activeService.shutdown()
        }
        recognizer?.close()
        recognizer = null
    }

    private fun parseText(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(key).trim()
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val MODEL_ASSET_PATH = "model-cn"
        private const val MODEL_TARGET_PATH = "vosk-models"
        private const val SAMPLE_RATE = 16_000f
        private const val LISTEN_TIMEOUT_MS = 12_000
    }
}
