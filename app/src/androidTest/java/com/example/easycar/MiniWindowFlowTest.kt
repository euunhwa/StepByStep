package com.example.easycar

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.ManeuverType
import com.example.easycar.data.UserSettings
import com.example.easycar.runtime.CoachingService
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MiniWindowFlowTest {
    private fun until(message:String,timeout:Long=20000,test:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!test() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
        assertTrue(message,test())
    }
    @Test fun koreanVoiceFinishesWithAnotherAppVisible() {
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>()
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,muted=false,demoInput=true,smallWindowOnStart=true,voiceIntervalSeconds=3))}
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {a->ContextCompat.startForegroundService(a,Intent(a,CoachingService::class.java).putExtra("demo","left").putExtra("speed",1))}
                until("voice session running") {app.runtime.state.value.running && (app.runtime.state.value.record?.progressIndex ?: 0)>3}
                val id=app.runtime.state.value.record!!.id
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                until("left-turn feedback",45000) {app.runtime.state.value.feedbackHistory.any {it.type==ManeuverType.LEFT}}
                until("real TTS completion or terminal failure",45000) {app.runtime.state.value.feedbackHistory.first {it.type==ManeuverType.LEFT}.voiceState in setOf(com.easycar.core.VoiceState.SPOKEN,com.easycar.core.VoiceState.FAILED,com.easycar.core.VoiceState.EXPIRED)}
                val receipt=app.runtime.state.value.feedbackHistory.single {it.type==ManeuverType.LEFT}
                assertEquals("${receipt.voiceReason}; engine=${app.runtime.voiceStatus.value}",com.easycar.core.VoiceState.SPOKEN,receipt.voiceState)
                assertNotNull(receipt.deliveredDelayMs)
                assertEquals(id,app.runtime.state.value.record?.id)
                File(app.filesDir,"validation-mini-voice.json").writeText(Gson().toJson(receipt))
            }
        } finally {
            if(app.runtime.state.value.running) {app.startService(Intent(app,CoachingService::class.java).setAction("stop"));until("voice cleanup") {!app.runtime.state.value.running}}
        }
    }
    @Test fun oneAutomaticMiniWindowSessionKeepsAllSevenFeedbacksAfterAppSwitch() {
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>()
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,muted=true,demoInput=true,smallWindowOnStart=true))}
        val activity=AtomicReference<MainActivity>()
        val supports=app.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {a->activity.set(a);ContextCompat.startForegroundService(a,Intent(a,CoachingService::class.java).putExtra("demo","auto-drive").putExtra("speed",4))}
                until("session running") {app.runtime.state.value.running && app.runtime.state.value.record?.id!=null}
                val id=app.runtime.state.value.record!!.id
                until("PiP entry or explicit system fallback") {activity.get().miniWindow.value.active || activity.get().miniWindow.value.message!=null}
                val window=activity.get().miniWindow.value
                assertEquals(supports,window.supported)
                if(!supports) {assertFalse(window.active);assertNotNull(window.message)}
                val before=app.runtime.state.value.record!!.progressIndex
                // A real app switch: this must never cancel the foreground coaching service.
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                until("telemetry continues with another app visible") {(app.runtime.state.value.record?.progressIndex ?: 0)>before+20}
                assertEquals(id,app.runtime.state.value.record?.id)
                until("all seven feedbacks before replay ends",60000) {app.runtime.state.value.feedbackHistory.size==7 || app.runtime.state.value.error!=null}
                assertNull(app.runtime.state.value.error)
                val feedback=app.runtime.state.value.feedbackHistory
                assertEquals(listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.STOP,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK),feedback.map {it.type})
                assertTrue(feedback.all {it.visualDelayMs!=null && it.visualDelayMs!!<1000})
                until("single session saved") {!app.runtime.state.value.running}
                assertEquals(7,app.repository.records.value.first {it.id==id}.results.size)
                File(app.filesDir,"validation-mini-window.json").writeText(Gson().toJson(mapOf("systemPipSupported" to supports,"actualWindow" to window,"sessionId" to id,"feedbacks" to feedback,"state" to app.runtime.state.value.record?.state)))
            }
        } finally {
            if(app.runtime.state.value.running) {app.startService(Intent(app,CoachingService::class.java).setAction("stop"));until("cleanup") {!app.runtime.state.value.running}}
            runBlocking {app.repository.updateSettings(UserSettings(consent=true,muted=false,demoInput=true,smallWindowOnStart=true))}
        }
    }
}
