package com.example.easycar

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.data.SessionRecord
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordQueryFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun selectedRoomRecordCanBeQueriedThroughRealNavigationWithoutAnyMutation() {
        val app=ui.activity.application as EasyCarApplication
        val settings=runBlocking {app.repository.settings.first()}
        val fixture=RecordQueryDemoData.session(app.profile,app.evaluationPolicy.documentDataset!!)
        val saved=SessionRecord(UUID.randomUUID().toString(),"기록 조회 계측 시험",System.currentTimeMillis(),fixture.source,fixture.profileVersion,fixture.state,fixture.results,emptyList())
        val before=Gson().toJson(saved)
        fun assertFriendlyScreen() {
            listOf("yaw","jerk","rad/s","m/s","document-derived","evaluation-document","AAOS","YAW_INTEGRAL","SPEED_EVENT","관측 품질","필터","JSON","시제품 횟수 배점").forEach {term->
                ui.onAllNodesWithText(term,substring=true,ignoreCase=true).assertCountEquals(0)
            }
        }
        fun capture(name:String) {
            ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        try {
            runBlocking {app.repository.updateSettings(settings.copy(consent=true,muted=true,smallWindowOnStart=false));app.repository.save(saved)}
            ui.waitUntil(20000) {app.bridge.state.value.ux.allowsReview() && ui.onAllNodesWithTag("start-submission-demo").fetchSemanticsNodes().isNotEmpty()}
            listOf("home","records","settings").forEach {ui.onNodeWithTag("nav-$it").assertIsDisplayed()}
            ui.onNodeWithTag("main-navigation").onChildren().filter(hasClickAction()).assertCountEquals(3)
            ui.onNodeWithTag("nav-parking").assertDoesNotExist()
            ui.onNodeWithTag("nav-home").performClick()
            ui.onNodeWithTag("live-signal-panel").performScrollTo().assertIsDisplayed()
            listOf("지금 들어오는 차량 신호","속도","기어","핸들 각도","진행 방향").forEach {ui.onNodeWithText(it).assertIsDisplayed()}
            assertFriendlyScreen();capture("validation-friendly-home-v0132.png")
            ui.onNodeWithTag("nav-settings").performClick()
            ui.onNodeWithText("yaw 적분",substring=true).performScrollTo().assertExists()
            capture("validation-friendly-settings-v0132.png")
            ui.onNodeWithTag("nav-home").performClick()
            ui.onNodeWithTag("nav-records").performClick()
            ui.onNodeWithText(saved.title).performClick()
            assertFriendlyScreen()
            ui.onNodeWithText("T자 주차").performScrollTo().performClick()
            ui.onNodeWithTag("user-maneuver-detail").assertIsDisplayed()
            assertFriendlyScreen();capture("validation-friendly-detail-v0132.png")
            ui.onNodeWithText("← 연습 요약").performClick()
            ui.onNodeWithTag("record-query-open").performScrollTo().performClick()
            ui.onNodeWithTag("gleo-record-access").performScrollTo().assertIsOff().performClick()
            ui.waitUntil(5000) {runBlocking {app.repository.settings.first().gleoSessionId==saved.id}}
            ui.onNodeWithTag("gleo-record-access").assertIsOn().performClick()
            ui.waitUntil(5000) {runBlocking {app.repository.settings.first().gleoSessionId==null}}
            ui.onNodeWithTag("record-query-last").performClick()
            ui.waitUntil(10000) {ui.onAllNodesWithTag("record-query-answer").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("record-query-status").assertTextEquals("평가 보류 기록")
            ui.onNodeWithText("감점 설명 대상: 최근 동작").performScrollTo().performClick()
            ui.onNodeWithText("T자 주차 · ${fixture.results.indexOfFirst {it.type==ManeuverType.T_PARK && it.score==85}+1}번째 · 85점").performClick()
            ui.onNodeWithTag("record-query-explain").performScrollTo().performClick()
            ui.waitUntil(10000) {ui.onAllNodesWithTag("record-query-answer").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("record-query-answer").assert(hasText("85점",substring=true))
            ui.onNodeWithTag("record-query-answer").assert(hasText("초과한 3회",substring=true))
            ui.onNodeWithTag("record-query-answer").performScrollTo().assertIsDisplayed()
            assertFriendlyScreen();capture("validation-friendly-query-v0132.png")
            assertEquals(before,Gson().toJson(app.repository.records.value.first {it.id==saved.id}))
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,"validation-gleo-query-flow-v011.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            File(app.filesDir,"validation-gleo-query-flow-v011.json").writeText(Gson().toJson(mapOf("selectedRecordReadFromRoom" to true,"storedRecordUnchanged" to true,"storedParkingScore" to 85,"liveGleoDispatched" to false)))
        } finally {runBlocking {app.repository.delete(saved.id);app.repository.updateSettings(settings)}}
    }
}
