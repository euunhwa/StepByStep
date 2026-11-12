package com.example.easycar

import android.content.Context
import android.media.*
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.*
import ai.pleos.playground.tts.listener.*
import com.example.easycar.runtime.SpeechOutput
import com.example.easycar.runtime.SpeechPhase
import com.example.easycar.runtime.SpeechStatus
import kotlinx.coroutines.flow.*
import java.util.Locale

class PleosSpeech(context:Context):SpeechOutput {
    private val mutable=MutableStateFlow(SpeechStatus(SpeechPhase.INITIALIZING,"Pleos 음성 초기화 중"));override val status=mutable.asStateFlow()
    private val readyStatus=SpeechStatus(SpeechPhase.READY,"Pleos 기기 내 음성 준비됨")
    private val sdk=TextToSpeech(context,Mode.ON_DEVICE)
    private val audio=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener { if(it<=0) stopForPriority() }.build()
    private var ready=false
    private val events=object:EventListener {
        override fun onStart() { mutable.value=SpeechStatus(SpeechPhase.SPEAKING,"Pleos 음성 안내 중") }
        override fun onReady() { ready=true;mutable.value=readyStatus }
        override fun onDone() { audio.abandonAudioFocusRequest(focus);mutable.value=readyStatus }
        override fun onError(errorType:ErrorType) { ready=false;mutable.value=SpeechStatus(SpeechPhase.FAILED,"Pleos 음성 오류: $errorType · 글로 안내");audio.abandonAudioFocusRequest(focus) }
        override fun onStop() { audio.abandonAudioFocusRequest(focus) }
        override fun onUpdatedRms(rms:Double) {}
    }
    init {
        try {
            sdk.initialize(object:OnInitializeListener {
                override fun onSuccess() { sdk.addEventListener(events);sdk.setAudioAttributes(attributes);sdk.setLocale(Locale.KOREA);ready=true;mutable.value=readyStatus }
                override fun onFailure(errorType:ErrorType) { mutable.value=SpeechStatus(SpeechPhase.FAILED,"Pleos 음성 연동 불가: $errorType") }
            })
        } catch(e:Exception) { mutable.value=SpeechStatus(SpeechPhase.FAILED,"Pleos 음성 연동 실패: ${e.message}") }
    }
    private fun stopForPriority() { runCatching { sdk.stop() };audio.abandonAudioFocusRequest(focus);mutable.value=SpeechStatus(SpeechPhase.INTERRUPTED,"우선 오디오로 음성 중단") }
    override fun speak(id:String,text:String):Boolean {
        if(!ready || audio.mode!=AudioManager.MODE_NORMAL || audio.isMusicActive) return false
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        return runCatching { sdk.speak(text,true);true }.getOrElse { audio.abandonAudioFocusRequest(focus);false }
    }
    override fun stop() { runCatching { sdk.stop() };audio.abandonAudioFocusRequest(focus);if(ready) mutable.value=readyStatus }
    override fun release() { stop();runCatching { sdk.removeEventListener(events);sdk.release() } }
}
