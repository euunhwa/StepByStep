package com.example.easycar.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.easycar.core.*
import kotlinx.coroutines.flow.*
import java.util.Locale

data class PlatformState(val ux:UxState=UxState(),val connection:String="차량 연결 미확인",val speed:String="수신 대기",
    val gear:String="수신 대기",val steering:String="수신 대기",val heading:String="수신 대기",val tts:String="초기화 중",
    val priorityAudioIdle:Boolean?=null,val isEmulator:Boolean=false,
    /** Logic reads this flag; [connection] is display text only and may be reworded freely. */
    val vehicleConnected:Boolean=false)
interface VehicleDataSource { val frames:SharedFlow<VehicleFrame>;fun start();fun stop() }
/** What a speech engine is doing. Logic reads [phase] only; [text] is for display and may be reworded freely. */
enum class SpeechPhase {
    INITIALIZING, READY, SPEAKING,
    /** Priority audio (call, other media) took over and stopped our speech. */
    INTERRUPTED,
    /** Engine missing, initialization failed, or the last utterance failed. Guidance falls back to text. */
    FAILED
}
data class SpeechStatus(val phase:SpeechPhase,val text:String) {
    override fun toString()=text
}
interface SpeechOutput { val status:StateFlow<SpeechStatus>;fun speak(id:String,text:String):Boolean;fun stop();fun release() }
interface PlatformBridge:VehicleDataSource { val state:StateFlow<PlatformState>;val latestFrame:StateFlow<VehicleFrame?>;fun release() }

class PortableBridge(context:Context):PlatformBridge {
    private val mutable=MutableStateFlow(PlatformState(ux=if(context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) UxState() else UxState(true,false),connection="데모 전용 빌드 · 차량 신호 미지원"))
    override val state=mutable.asStateFlow()
    override val latestFrame=MutableStateFlow<VehicleFrame?>(null).asStateFlow()
    override val frames=MutableSharedFlow<VehicleFrame>().asSharedFlow()
    override fun start() {} ; override fun stop() {};override fun release() {}
}
class AndroidSpeech(private val context:Context):SpeechOutput {
    private val mutable=MutableStateFlow(SpeechStatus(SpeechPhase.INITIALIZING,"Android 음성 초기화 중"))
    override val status=mutable.asStateFlow()
    private val audio=context.getSystemService(AudioManager::class.java)
    private var ready=false
    private val readyStatus=SpeechStatus(SpeechPhase.READY,"Android 한국어 음성 준비됨")
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener { if(it<=0) stopForPriority() }.build()
    private var tts:TextToSpeech?=null
    init {
        tts=TextToSpeech(context) { code ->
            if(code==TextToSpeech.SUCCESS) {
                val result=tts?.setLanguage(Locale.KOREAN)
                val offline=tts?.voices?.firstOrNull { it.locale.language=="ko" && !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ready=result!=TextToSpeech.LANG_MISSING_DATA && result!=TextToSpeech.LANG_NOT_SUPPORTED && result!=null && offline!=null
                if(offline!=null) tts?.voice=offline
                mutable.value=if(ready) readyStatus else SpeechStatus(SpeechPhase.FAILED,"기기 내 한국어 음성 데이터 없음 · 글로 안내")
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                    override fun onStart(id:String?) { mutable.value=SpeechStatus(SpeechPhase.SPEAKING,"Android 음성 안내 중") }
                    override fun onDone(id:String?) { audio.abandonAudioFocusRequest(focus);mutable.value=readyStatus }
                    @Deprecated("platform") override fun onError(id:String?) { audio.abandonAudioFocusRequest(focus);mutable.value=SpeechStatus(SpeechPhase.FAILED,"음성 출력 실패 · 글로 안내") }
                })
            } else mutable.value=SpeechStatus(SpeechPhase.FAILED,"Android 음성 엔진 없음 · 글로 안내")
        }
    }
    private fun stopForPriority() { tts?.stop();audio.abandonAudioFocusRequest(focus);mutable.value=SpeechStatus(SpeechPhase.INTERRUPTED,"우선 오디오로 음성 중단") }
    override fun speak(id:String,text:String):Boolean {
        if(!ready || audio.isMusicActive || audio.mode!=AudioManager.MODE_NORMAL) return false
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        return (tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,id)==TextToSpeech.SUCCESS).also { if(!it) audio.abandonAudioFocusRequest(focus) }
    }
    override fun stop() { tts?.stop();audio.abandonAudioFocusRequest(focus);if(ready) mutable.value=readyStatus }
    override fun release() { stop();tts?.shutdown() }
}
