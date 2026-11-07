package com.example.easycar

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordQueryScreenTest {
    @get:Rule val ui=createComposeRule()
    @Test fun actualQueryPortRendersFriendlyResultsWithoutDeveloperToolsAndHonorsRestriction() {
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>()
        val fixture=RecordQueryDemoData.session(app.profile,app.evaluationPolicy.documentDataset!!)
        val record=mutableStateOf(SessionRecord(fixture.id,"합성 기록 조회 화면 시험",0,fixture.source,fixture.profileVersion,fixture.state,fixture.results,emptyList()))
        val restricted=mutableStateOf(false);val size=mutableStateOf(1280 to 800)
        val gateway=LocalRecordQueryGateway {listOf(record.value)}
        val access=RecordQueryAccess(setOf(fixture.id),true,true,app.profile.version,app.evaluationPolicy.version)
        ui.setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Mint,onPrimary=Ink,surface=Panel,background=Ink,onSurface=White,onBackground=White)) {
            Surface(Modifier.fillMaxSize(),color=Ink) {Box(Modifier.fillMaxSize()) {Box(Modifier.size(size.value.first.dp,size.value.second.dp)) {
                RecordQueryScreen(record.value,restricted.value,{}, {json->gateway.execute(json,access)}, {},showDebug=true)
            }}}
        }}
        fun answerContains(text:String) {
            ui.waitUntil(10000) {ui.onAllNodesWithTag("record-query-answer").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("record-query-answer").assert(hasText(text,substring=true))
        }
        ui.onNodeWithTag("record-query-last").performClick();answerContains("평가는 보류")
        ui.onNodeWithText("감점 설명 대상: 최근 동작").performScrollTo().performClick()
        ui.onNodeWithText("T자 주차 · ${fixture.results.indexOfFirst {it.type==ManeuverType.T_PARK && it.score==85}+1}번째 · 85점").performClick()
        ui.onNodeWithTag("record-query-explain").performScrollTo().performClick();answerContains("초과한 3회")
        ui.onNodeWithTag("record-query-answer").performScrollTo().assertIsDisplayed()
        fun capture(name:String) {
            ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        capture("validation-gleo-query-wide-v011.png")
        ui.runOnIdle {size.value=700 to 1000}
        assertEquals(700f*app.resources.displayMetrics.density,ui.onNodeWithTag("record-query-screen").fetchSemanticsNode().boundsInRoot.width,2f)
        ui.onNodeWithTag("record-query-summary").performScrollTo().performClick();answerContains("보류·미완료는 1개")
        ui.onNodeWithTag("record-query-answer").performScrollTo().assertIsDisplayed()
        capture("validation-gleo-query-narrow-v011.png")
        ui.onNodeWithTag("record-query-json-toggle").assertDoesNotExist()
        ui.onNodeWithTag("record-query-json-input").assertDoesNotExist()
        ui.onNodeWithTag("record-query-callback").assertDoesNotExist()
        listOf("document-derived", "evaluation-document", "yaw", "jerk", "m/s").forEach {term->
            ui.onAllNodesWithText(term,substring=true,ignoreCase=true).assertCountEquals(0)
        }
        ui.runOnIdle {record.value=record.value.copy(revision=1,results=emptyList())}
        ui.onNodeWithTag("record-query-answer").assertDoesNotExist()
        ui.runOnIdle {restricted.value=true}
        ui.onNodeWithTag("record-query-restricted").assertIsDisplayed()
        ui.onNodeWithTag("record-query-last").assertDoesNotExist()
    }
}
