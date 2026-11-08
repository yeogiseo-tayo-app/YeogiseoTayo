package com.hmgapp.yeogiseotayo.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import com.hmgapp.yeogiseotayo.trip.VoicePort
import java.util.Locale

/**
 * Pleos TTS (기기 안 한국어 음성). 딜레마 제로 교훈: 첫 요청이 오래 걸리므로 시작할 때 짧게 한 번 말해 예열한다.
 * 새 안내는 앞 문장을 끊고 바로 말한다.
 */
class VoiceLink(context: Context, private val log: (String) -> Unit) : VoicePort {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false
    private val pending = ArrayList<String>()

    fun connect() {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                runCatching { tts.setLocale(Locale.KOREA) }
                ready = true
                log("음성 준비됨")
                val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (queued.isEmpty()) say("여기서 타요를 시작합니다.") else queued.forEach { say(it) }
            }
            override fun onFailure(errorType: ErrorType) { log("음성 초기화 실패: $errorType") }
        })
    }

    override fun say(text: String) {
        if (!ready) { synchronized(pending) { pending += text }; return }
        log("🔊 $text")
        runCatching { tts.stop(); tts.speak(text, true) }.onFailure { log("음성 실패: ${it.message}") }
    }

    fun release() = runCatching { tts.release() }
}
