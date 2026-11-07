package com.example.easycar

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.runtime.CoachingService
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DemoSpeechSequenceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun everyNarrationFinishesBeforePlaybackConsumesAnotherFrame() {checkDemo(muted=false)}
    @Test fun mutedDemoCompletesWithoutWaitingForSpeech() {checkDemo(muted=true)}

    private fun checkDemo(muted:Boolean)=runBlocking {
        val app=ui.activity.application as EasyCarApplication
        val previous=app.repository.settings.first()
        val types=listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,
            ManeuverType.STOP,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK)
        val lockedFrames=mutableMapOf<String,Int>()
        val speakingSamples=mutableMapOf<String,Int>()
        val start=SystemClock.elapsedRealtime()
        try {
            app.repository.updateSettings(previous.copy(consent=true,muted=muted,voiceIntervalSeconds=3,smallWindowOnStart=false))
            val prior=app.runtime.state.value.record?.id
            ui.runOnIdle {ContextCompat.startForegroundService(ui.activity,Intent(ui.activity,CoachingService::class.java)
                .putExtra("demo","auto-drive").putExtra("speed",8))}
            ui.waitUntil(15000) {app.runtime.state.value.running && app.runtime.state.value.record?.id?.let {it!=prior}==true}
            val id=app.runtime.state.value.record!!.id
            val deadline=SystemClock.elapsedRealtime()+180000
            while(app.runtime.state.value.running && SystemClock.elapsedRealtime()<deadline) {
                val state=app.runtime.state.value
                assertNull(state.error)
                val speaking=state.feedbackHistory.singleOrNull {it.voiceState==VoiceState.SPEAKING}
                if(speaking!=null) {
                    assertFalse("Muted demo must never speak",muted)
                    val index=state.record!!.progressIndex
                    val frozen=lockedFrames.getOrPut(speaking.resultId) {index}
                    assertEquals("${speaking.type}: playback advanced before narration finished",frozen,index)
                    assertEquals("A later maneuver was published during narration",speaking.resultId,state.feedbackHistory.last().resultId)
                    assertTrue("Previous narration did not finish",state.feedbackHistory.dropLast(1).all {it.voiceState==VoiceState.SPOKEN})
                    speakingSamples[speaking.resultId]=(speakingSamples[speaking.resultId] ?: 0)+1
                }
                delay(20)
            }
            assertFalse("Demo did not finish; voice=${app.runtime.voiceStatus.value}",app.runtime.state.value.running)
            assertEquals(id,app.runtime.state.value.finishedId)
            val saved=app.repository.records.value.single {it.id==id}
            assertEquals(types,saved.results.map {it.type})
            assertEquals(types,saved.feedbacks!!.map {it.type})
            assertTrue(saved.feedbacks!!.all {it.voiceState==if(muted) VoiceState.MUTED else VoiceState.SPOKEN})
            if(!muted) {
                assertEquals(7,lockedFrames.size)
                assertTrue("Each actual narration was observed across multiple frames",speakingSamples.values.all {it>=3})
            }
            File(app.filesDir,"validation-demo-sequence-${if(muted) "muted" else "spoken"}.json").writeText(Gson().toJson(mapOf(
                "session" to saved,"lockedFrames" to lockedFrames,"speakingSamples" to speakingSamples,
                "elapsedMs" to SystemClock.elapsedRealtime()-start,"allNarrationsCompleted" to !muted)))
        } finally {
            if(app.runtime.state.value.running) {
                app.startService(Intent(app,CoachingService::class.java).setAction("stop"))
                ui.waitUntil(15000) {!app.runtime.state.value.running}
            }
            app.repository.updateSettings(previous)
        }
        Unit
    }
}
