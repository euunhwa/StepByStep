package com.example.easycar

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.RecordQueryDemoData
import com.example.easycar.data.SessionRecord
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in companion to the emulator's official synthetic microphone injection API. */
@RunWith(AndroidJUnit4::class)
class GleoVoiceProbe {
    @Test fun observeActualAssistantActionWhileSyntheticSpeechIsInjected()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gleoVoiceProbe")=="true")
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>()
        app.ready.await()
        val previous=app.repository.settings.first()
        val fixture=RecordQueryDemoData.session(app.profile,app.evaluationPolicy.documentDataset!!)
        val saved=SessionRecord(UUID.randomUUID().toString(),"Gleo 합성 음성 연결 시험",System.currentTimeMillis(),fixture.source,fixture.profileVersion,fixture.state,fixture.results,emptyList())
        val ready=File(app.filesDir,"gleo-voice-probe-ready")
        val stop=File(app.filesDir,"gleo-voice-probe-stop")
        ready.delete();stop.delete()
        try {
            app.repository.updateSettings(previous.copy(consent=true,muted=false))
            app.repository.save(saved);app.repository.selectGleoSession(saved.id)
            withContext(Dispatchers.Main) {app.bridge.start()}
            withTimeout(8000) {app.bridge.state.first {it.ux.allowsReview()}}
            val sequence=app.gleo.lastDelivery.value?.sequence ?: 0
            ready.writeText("SYNTHETIC_RECORD_READY")
            val deadline=SystemClock.elapsedRealtime()+90000
            while(!stop.exists() && SystemClock.elapsedRealtime()<deadline) delay(100)
            val received=app.gleo.lastDelivery.value?.takeIf {it.sequence>sequence}
            assertEquals(saved,app.repository.records.value.first {it.id==saved.id})
            File(app.filesDir,"validation-gleo-voice-probe-v012.json").writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(mapOf(
                "input" to "SYNTHETIC_KOREAN_SPEECH_VIA_OFFICIAL_EMULATOR_MICROPHONE_API",
                "voiceActionObserved" to (received!=null),"delivery" to received,"storedRecordUnchanged" to true)))
        } finally {
            ready.delete();stop.delete();app.repository.delete(saved.id);app.repository.updateSettings(previous)
            if(previous.consent) app.repository.selectGleoSession(previous.gleoSessionId)
            withContext(Dispatchers.Main) {if(!app.visible && !app.runtime.state.value.running) app.bridge.stop()}
        }
    }
}
