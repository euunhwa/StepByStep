package com.example.easycar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.data.AppRepository
import com.example.easycar.runtime.LocalVideoInput
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParkingCaptureFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun syntheticMp4EpisodePublishes95AndKeepsAReplayableVideoAndStoredDeductions() {
        val app=ui.activity.application as EasyCarApplication;val previous=runBlocking {app.repository.settings.first()}
        var id:String?=null
        try {
            runBlocking {app.repository.updateSettings(previous.copy(consent=true,muted=true,smallWindowOnStart=false))}
            ui.waitUntil(20000) {app.bridge.state.value.ux.allowsReview() && ui.onAllNodesWithTag("nav-parking").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("nav-parking").performClick();ui.onNodeWithTag("parking-flow-example").performScrollTo().performClick()
            ui.waitUntil(45000) {!app.parking.state.value.running && app.parking.state.value.finishedId!=null}
            id=app.parking.state.value.finishedId!!
            val saved=app.repository.records.value.single {it.id==id};val capture=saved.parkingCapture!!
            assertEquals("SYNTHETIC_REPLAY",capture.inputSource);assertTrue(capture.videoSaved);assertEquals(2,capture.episode!!.reverseToDriveCount)
            assertTrue(capture.episode!!.automaticCompletion);assertEquals(95,capture.outcome!!.score);assertEquals(5,capture.outcome!!.deductions.sumOf {it.points})
            assertEquals(4850L,capture.finalVideoMs);assertTrue(File(saved.videoFile!!).length()>1000)
            val pixel=LocalVideoInput(app).pixelAtVideoTime(saved,capture.finalVideoMs!!)!!
            assertEquals(480,pixel.width);assertEquals(360,pixel.height)
            ui.waitUntil(5000) {ui.onAllNodesWithTag("parking-outcome-score").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("parking-outcome-score").assert(hasText("95점",substring=true))
            val reloaded=AppRepository(app)
            try {runBlocking {reloaded.load()};assertEquals(Gson().toJson(saved),Gson().toJson(reloaded.records.value.single {it.id==id}))} finally {reloaded.close()}
            val answer=runBlocking {ui.activity.runOnUiThread {} ;LocalRecordAnswer(app,saved.id)}
            assertEquals(95,answer.result!!.score);assertFalse(answer.result!!.historical)
            File(app.filesDir,"validation-parking-capture-flow-v013.json").writeText(Gson().toJson(mapOf("source" to "SYNTHETIC_REPLAY_NOT_CAMERA","score" to capture.outcome!!.score,"count" to 2,"videoSaved" to true,"roomReloadPreserved" to true,"query" to answer,"outcome" to capture.outcome)))
            val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,"validation-parking-capture-flow-v013.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
        } finally {runBlocking {if(app.parking.state.value.running) app.parking.finish("테스트 정리");id?.let {app.repository.delete(it)};app.repository.updateSettings(previous)}}
    }
    private fun LocalRecordAnswer(app:EasyCarApplication,id:String):RecordQueryResponse = com.example.easycar.data.LocalRecordQueryGateway {app.repository.records.value}.execute(
        RecordQueryProtocol.request(RecordQueryAction.EXPLAIN_RESULT,id),RecordQueryAccess(setOf(id),true,true,app.parking.policyVersion,app.parking.policyVersion))
}
