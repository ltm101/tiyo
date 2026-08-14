package com.koyo.screenwarden

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 手机原生 TTS（系统引擎），不依赖电脑网关。
 * 分句播报：按句 QUEUE_ADD 排队，逐句播放，最后一句播完回调 onUtteranceDone。
 */
class TiyoTts(context: Context) {

    interface Listener {
        fun onUtteranceStart()
        fun onUtteranceDone()
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var active = false
    private var listener: Listener? = null
    private var totalUtterances = 0
    private var doneUtterances = 0

    /** 初始化（异步），通常很快完成。 */
    fun init() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (active) listener?.onUtteranceStart()
            }

            override fun onDone(utteranceId: String?) {
                if (!active) return
                doneUtterances++
                if (doneUtterances >= totalUtterances) {
                    reset()
                    listener?.onUtteranceDone()
                }
            }

            override fun onError(utteranceId: String?) {
                onUtteranceFailed()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onUtteranceFailed()
            }

            private fun onUtteranceFailed() {
                if (!active) return
                doneUtterances++
                if (doneUtterances >= totalUtterances) {
                    reset()
                    listener?.onUtteranceDone()
                }
            }
        })
    }

    /** 分句播报：一句句排队说，全部播完回调 onUtteranceDone。 */
    fun speakSentences(sentences: List<String>, listener: Listener?) {
        this.listener = listener
        if (!ready || sentences.isEmpty()) {
            listener?.onUtteranceDone()
            return
        }
        active = true
        totalUtterances = sentences.size
        doneUtterances = 0
        sentences.forEachIndexed { index, sentence ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(sentence, queueMode, null, "tiyo_utt_$index")
        }
    }

    /** 停止播放并清空队列。 */
    fun stop() {
        active = false
        tts?.stop()
        reset()
    }

    fun shutdown() {
        active = false
        listener = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun reset() {
        totalUtterances = 0
        doneUtterances = 0
        listener = null
    }
}
