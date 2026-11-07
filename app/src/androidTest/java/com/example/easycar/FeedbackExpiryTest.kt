package com.example.easycar

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.runtime.CoachingService
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FeedbackExpiryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun completedCardClearsWhileSessionWaitsAndNextManeuverStillPublishes() {
        val app=ui.activity.application as EasyCarApplication
        val previous=runBlocking {app.repository.settings.first()}
        fun screenshot(name:String) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        runBlocking {app.repository.updateSettings(previous.copy(consent=true,muted=true,smallWindowOnStart=false))}
        try {
            ui.waitUntil(20000) {app.bridge.state.value.ux.allowsReview() && ui.onAllNodesWithTag("start-submission-demo").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("start-submission-demo").performClick()
            ui.waitUntil(25000) {app.runtime.state.value.feedback?.type==ManeuverType.START}
            val receipt=app.runtime.state.value.feedback!!
            ui.onNodeWithTag("live-feedback").assertIsDisplayed()
            screenshot("validation-feedback-v6-visible.png")
            ui.waitUntil(8000) {app.runtime.state.value.feedback==null}
            val elapsed=SystemClock.elapsedRealtime()-receipt.publishedAtMs
            assertTrue("expired after ${elapsed}ms",elapsed in 5000..7000)
            assertTrue(app.runtime.state.value.running)
            assertTrue(app.runtime.state.value.feedbackHistory.any {it.resultId==receipt.resultId})
            assertTrue(app.runtime.state.value.record!!.results.any {it.id==receipt.resultId})
            ui.onNodeWithTag("live-feedback").assertDoesNotExist()
            ui.onNodeWithTag("waiting-next-maneuver").assertIsDisplayed()
            screenshot("validation-feedback-v6-waiting.png")
            ui.waitUntil(20000) {app.runtime.state.value.feedback?.type==ManeuverType.LEFT}
            ui.onNodeWithText("좌회전 완료").assertExists()
            File(app.filesDir,"validation-feedback-v6.json").writeText(Gson().toJson(mapOf(
                "expiredAfterMs" to elapsed,"runningAfterExpiry" to true,"historyPreserved" to true,
                "nextManeuver" to app.runtime.state.value.feedback?.type,
                "feedbacks" to app.runtime.state.value.feedbackHistory,
                "profileVersion" to app.profile.version,"policyVersion" to app.evaluationPolicy.version)))
        } finally {
            if(app.runtime.state.value.running) app.startService(Intent(app,CoachingService::class.java).setAction("stop"))
            ui.waitUntil(15000) {!app.runtime.state.value.running}
            runBlocking {app.repository.updateSettings(previous)}
        }
    }
}
