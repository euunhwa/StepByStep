package com.example.easycar

import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.data.UserSettings
import com.example.easycar.runtime.CoachingService
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SubmissionDemoTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun topRightDemoIsExplicitAndKeepsAllSevenProvisionalResults() {
        val app=ui.activity.application as EasyCarApplication
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,muted=true,demoInput=false,smallWindowOnStart=false))}
        ui.waitUntil(20000) {ui.onAllNodesWithTag("start-submission-demo").fetchSemanticsNodes().isNotEmpty() && app.bridge.state.value.ux.allowsReview()}
        ui.onNodeWithText("지금 들어오는 차량 신호").assertExists()
        listOf("속도", "기어", "핸들 각도", "진행 방향").forEach { ui.onNodeWithText(it).assertExists() }
        ui.onNodeWithTag("start-submission-demo").performClick()
        ui.waitUntil(15000) {app.runtime.state.value.running && app.runtime.state.value.record?.demoCaseId=="auto-drive"}
        val id=app.runtime.state.value.record!!.id
        ui.waitUntil(230000) {!app.runtime.state.value.running && app.runtime.state.value.finishedId==id}
        val saved=app.repository.records.value.single {it.id==id}
        assertEquals(listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.STOP,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK),saved.results.map {it.type})
        saved.results.forEach {assertNotNull("${it.type}: ${it.holdReasons}",it.score);assertEquals("PROVISIONAL",it.scoreKind);assertTrue(it.scoreBasis!!.contains("임의"))}
        assertEquals("document-derived-1.0",saved.profileVersion)
        assertTrue(saved.results.all {it.evaluationPolicyVersion=="evaluation-document-1.0"})
        assertTrue(saved.frames.all {it.yawRate?.source=="SYNTHETIC_DOCUMENT_VALIDATION"})
        val feedback=saved.feedbacks!!
        assertEquals(saved.results.map {it.score},feedback.map {it.score})
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
            putString("stream", "\nSUBMISSION_DEMO_RESULTS="+Gson().toJson(saved.results.map {mapOf("type" to it.type.name,"score" to it.score,"kind" to it.scoreKind)})+"\n")
        })
        File(app.filesDir,"validation-submission-demo-v9.json").writeText(Gson().toJson(saved))
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,muted=false,demoInput=false,smallWindowOnStart=true))}
    }
}
