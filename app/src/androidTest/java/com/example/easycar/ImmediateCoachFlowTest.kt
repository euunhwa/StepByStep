package com.example.easycar

import android.content.Intent
import android.util.Log
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.data.UserSettings
import com.example.easycar.runtime.CoachingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmediateCoachFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun oneStartAutomaticallyPublishesEveryManeuverWhileSessionIsActive() {
        val app=ui.activity.application as EasyCarApplication
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,smallWindowOnStart=false,muted=false,voiceIntervalSeconds=3,demoInput=true))}
        ui.waitUntil(20000) {app.bridge.state.value.ux.allowsReview() && ui.onAllNodesWithTag("start-submission-demo").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("training-T_PARK").assertDoesNotExist()
        ui.onNodeWithTag("start-submission-demo").performClick()
        ui.waitUntil(15000) {app.runtime.state.value.running && app.runtime.state.value.record?.id!=null}
        val sessionId=app.runtime.state.value.record!!.id
        val types=listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.STOP,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK)
        for(type in types) {
            ui.waitUntil(150000) {app.runtime.state.value.feedbackHistory.any {it.type==type && it.score!=null} || app.runtime.state.value.error!=null}
            assertNull(app.runtime.state.value.error)
            val live=app.runtime.state.value
            assertEquals(sessionId,live.record?.id);assertTrue(live.running)
            assertEquals(com.easycar.core.Lifecycle.ACTIVE,live.record?.state)
            val mini=ui.onAllNodesWithTag("mini-coach").fetchSemanticsNodes().isNotEmpty()
            val bodyTag=if(mini) "mini-feedback-text" else "live-feedback-text"
            ui.onNodeWithTag(bodyTag).assertIsDisplayed()
            val viewport=ui.onNodeWithTag(if(mini) "mini-coach" else "drive-screen").getUnclippedBoundsInRoot()
            val body=ui.onNodeWithTag(bodyTag).getUnclippedBoundsInRoot()
            assertTrue("$type feedback body fits",body.top>=viewport.top && body.bottom<=viewport.bottom)
            val receipt=live.feedbackHistory.single {it.type==type}
            assertNotNull(receipt.score);assertEquals("PROVISIONAL",receipt.scoreKind);assertTrue(receipt.visualDelayMs!!<=1000)
            assertTrue(receipt.eventEndMs<DocumentDemoCatalog.automaticDrive.frames.last().timeMs)
        }
        ui.waitUntil(45000) {!app.runtime.state.value.running && app.runtime.state.value.finishedId==sessionId}
        val saved=app.repository.records.value.first {it.id==sessionId}
        assertEquals(types,saved.feedbacks!!.map {it.type})
        // Actual speech status is recorded; deferred/interrupted audio must never be called completed.
        assertTrue(saved.feedbacks!!.all {it.voiceState in setOf(VoiceState.SPOKEN,VoiceState.EXPIRED,VoiceState.FAILED)})
        assertTrue(saved.feedbacks!!.filter {it.voiceState==VoiceState.SPOKEN}.all {it.deliveredDelayMs!=null})
    }
    @Test fun endingDuringSpeechOpensSavedSummaryAndKeepsCompletedFeedback() {
        val app=ui.activity.application as EasyCarApplication
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,smallWindowOnStart=false,muted=false))}
        val previous=app.runtime.state.value.record?.id
        ui.runOnIdle {ContextCompat.startForegroundService(ui.activity,Intent(ui.activity,CoachingService::class.java).putExtra("demo","auto-drive").putExtra("speed",4))}
        ui.waitUntil(15000) {app.runtime.state.value.running && app.runtime.state.value.record?.id!=null && app.runtime.state.value.record?.id!=previous}
        val id=app.runtime.state.value.record!!.id
        ui.waitUntil(45000) {app.runtime.state.value.feedbackHistory.any {it.type==ManeuverType.LEFT && it.voiceState==VoiceState.SPEAKING} && app.runtime.state.value.running}
        ui.onNodeWithTag("end-drive").performScrollTo().performClick()
        ui.waitUntil(15000) {!app.runtime.state.value.running && app.runtime.state.value.finishedId==id}
        val saved=app.repository.records.value.first {it.id==id}
        assertEquals(com.easycar.core.Lifecycle.COMPLETED,saved.state)
        assertTrue(saved.results.any {it.type==ManeuverType.LEFT && it.score!=null && it.scoreKind=="PROVISIONAL"})
        assertTrue(saved.feedbacks!!.none {it.voiceState==VoiceState.SPEAKING})
    }
    @Test fun mutedVoiceNeverHidesTheImmediateCard() {
        val app=ui.activity.application as EasyCarApplication
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,smallWindowOnStart=false,muted=true))}
        ui.waitUntil(15000) {app.bridge.state.value.ux.allowsReview()}
        val previous=app.runtime.state.value.record?.id
        ui.runOnIdle {ContextCompat.startForegroundService(ui.activity,Intent(ui.activity,CoachingService::class.java).putExtra("demo","auto-drive").putExtra("speed",4))}
        ui.waitUntil(15000) {app.runtime.state.value.running && app.runtime.state.value.record?.id!=null && app.runtime.state.value.record?.id!=previous}
        // The seven-maneuver demo reaches the next turn ~3 s later at speed 4; pause on the left-turn card
        // (the card still expires on its normal timer) so the UI assertions below see that card.
        var paused=false
        ui.waitUntil(30000) {(app.runtime.state.value.feedback?.type==ManeuverType.LEFT).also {left->
            if(left && !paused) {paused=true;InstrumentationRegistry.getInstrumentation().runOnMainSync {app.runtime.pauseDemo()}}}}
        assertEquals(VoiceState.MUTED,app.runtime.state.value.feedback?.voiceState)
        val mini=ui.onAllNodesWithTag("mini-coach").fetchSemanticsNodes().isNotEmpty()
        ui.onNodeWithTag(if(mini) "mini-coach" else "live-feedback").assertExists();ui.onNodeWithText("좌회전 완료").assertExists()
        // The seven-maneuver demo continues after the left turn; end it instead of waiting it out.
        ui.activity.startService(Intent(ui.activity,CoachingService::class.java).setAction("stop"))
        ui.waitUntil(30000) {!app.runtime.state.value.running}
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,smallWindowOnStart=false,muted=false))}
    }
}
