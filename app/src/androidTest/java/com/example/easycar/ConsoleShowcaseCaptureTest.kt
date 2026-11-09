package com.example.easycar

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.easycar.runtime.CoachingService
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Observes genuine console/VHAL inputs. This test never injects frames, maneuver labels or scores. */
@RunWith(AndroidJUnit4::class)
class ConsoleShowcaseCaptureTest {
    @Test fun observeConsoleWhileRecording()=runBlocking {
        // A real activity frame clock is essential for filming. Compose test rules replace it.
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>()
        suspend fun until(timeoutMs:Long,condition:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+timeoutMs
            while(!condition() && SystemClock.elapsedRealtime()<end) delay(50)
            assertTrue("Capture setup did not become ready",condition())
        }
        val previous=app.repository.settings.first()
        val stop=File(app.filesDir,"capture-stop").also {it.delete()}
        val snapshot=File(app.filesDir,"capture-state.json")
        val gson=Gson()
        try {
            app.repository.updateSettings(previous.copy(consent=true,muted=false,voiceIntervalSeconds=3,smallWindowOnStart=false))
            until(20000) {app.bridge.state.value.ux.allowsReview()}
            InstrumentationRegistry.getInstrumentation().runOnMainSync {ContextCompat.startForegroundService(app,Intent(app,CoachingService::class.java))}
            until(15000) {app.runtime.state.value.running && app.runtime.state.value.record!=null}
            val id=app.runtime.state.value.record!!.id
            val deadline=SystemClock.elapsedRealtime()+600000
            while(!stop.exists() && SystemClock.elapsedRealtime()<deadline && app.runtime.state.value.running) {
                val state=app.runtime.state.value
                val frame=app.bridge.latestFrame.value
                val json=gson.toJson(mapOf("id" to id,"ready" to (frame?.yawRate!=null && state.motion!=null && (state.record?.progressIndex ?: 0)>0),
                    "observedAtMs" to SystemClock.elapsedRealtime(),"motion" to state.motion,
                    "source" to state.record?.source,"voiceStatus" to app.runtime.voiceStatus.value.text,"voicePhase" to app.runtime.voiceStatus.value.phase,
                    "feedbacks" to state.feedbackHistory,"results" to state.record?.results,
                    "lastFrame" to frame,"error" to state.error))
                File(app.filesDir,"capture-state.tmp").also {it.writeText(json);it.renameTo(snapshot)}
                delay(100)
            }
            app.startService(Intent(app,CoachingService::class.java).setAction("stop"))
            until(15000) {!app.runtime.state.value.running}
            val saved=app.repository.records.value.single {it.id==id}
            assertNull("Console observation must not use built-in demo",saved.demoCaseId)
            assertTrue(saved.source.startsWith("8088 에뮬레이터"))
            File(app.filesDir,"capture-session.json").writeText(gson.toJson(saved))
        } finally {
            if(app.runtime.state.value.running) {
                app.startService(Intent(app,CoachingService::class.java).setAction("stop"))
                until(15000) {!app.runtime.state.value.running}
            }
            app.repository.updateSettings(previous)
            scenario.close()
        }
        Unit
    }
}
