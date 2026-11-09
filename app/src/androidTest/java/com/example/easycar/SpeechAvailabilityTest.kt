package com.example.easycar

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.easycar.runtime.SpeechOutput
import com.example.easycar.runtime.SpeechPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechAvailabilityTest {
    @Test fun reportsActualEngineInitializationOrExplicitFailure()=runBlocking {
        lateinit var output:SpeechOutput
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { output=PlatformFactory.speech(ApplicationProvider.getApplicationContext()) }
        val status=withTimeoutOrNull(12000) { output.status.first { it.phase!=SpeechPhase.INITIALIZING } }
        Log.i("EasyCarValidation","${BuildConfig.FLAVOR} speech status=$status")
        assertNotNull("음성 엔진 초기화는 성공 또는 명시적인 실패를 알려야 합니다",status)
        if(status?.phase==SpeechPhase.READY) {
            var accepted=false
            instrumentation.runOnMainSync { accepted=output.speak("validation-short","EasyCar 음성 안내 시험입니다.") }
            Log.i("EasyCarValidation","${BuildConfig.FLAVOR} short speech accepted=$accepted")
            if(accepted) {
                val started=withTimeoutOrNull(12000) { output.status.first {it.phase==SpeechPhase.SPEAKING || it.phase==SpeechPhase.FAILED} }
                assertNotNull("수락된 발화의 시작 또는 오류 이벤트",started)
                assertEquals("발화 오류: $started",SpeechPhase.SPEAKING,started!!.phase)
                val done=withTimeoutOrNull(20000) {output.status.first {it.phase==SpeechPhase.READY || it.phase==SpeechPhase.FAILED}}
                assertEquals("발화 종료 이벤트: $done",SpeechPhase.READY,done?.phase)
                Log.i("EasyCarValidation","${BuildConfig.FLAVOR} short speech completed")
            }
        }
        instrumentation.runOnMainSync { output.release() }
        Unit
    }
}
