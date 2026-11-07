package com.example.easycar

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.data.SessionRecord
import com.example.easycar.runtime.*
import com.example.easycar.ui.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders production components with explicit synthetic scoring samples, without saving fake drives. */
@RunWith(AndroidJUnit4::class)
class FeedbackScoreUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun conciseMeasuredReasonsAndScoreColorsRenderInEveryWindowSize() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        fun asset(path:String)=context.assets.open(path).bufferedReader().use {it.readText()}
        val profile=ReferenceProfile.fromJson(asset("profiles/arbitrary-prototype-v1.json"))
        val policy=EvaluationPolicy.fromJson(asset("evaluation/evaluation-policy-prototype.json"),asset("evaluation/arbitrary-reference-v1.json"))
        val result=DeterministicScorer(profile,policy).evaluate(ManeuverType.STOP,(0..18).map {Motion(it*100L,10.0,0.0,Gear.DRIVE,0.0,-4.0,0.0,0.0,false)},PracticeContext(null,null,dataSource="합성 관측 시험"))
        assertEquals(90,result.score)
        val published=FeedbackReceipt(result.id,result.type,"정지 완료",result.coaching,result.score,1800,android.os.SystemClock.elapsedRealtime(),VoiceState.MUTED,null,scoreKind=result.scoreKind,scoreBasis=result.scoreBasis,reason=FeedbackSummary.reason(result))
        val record=SessionRecord("ui-test","합성 표시 시험",0,"합성 관측 시험",profile.version,Lifecycle.ACTIVE,listOf(result),emptyList(),demoCaseId="ui-test")
        val state=mutableStateOf(DriveState(running=true,record=record,stage="다음 동작을 관측하고 있어요",feedback=published,feedbackHistory=listOf(published)))
        val size=mutableStateOf(1280 to 800)
        ui.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Box(Modifier.fillMaxSize().background(Ink)) {Box(Modifier.size(size.value.first.dp,size.value.second.dp)) {
            if(size.value.first<900) MiniCoachScreen(state.value,false,{},policy.datasetNotice)
            else DriveScreen(state.value,false,PlatformState(),{},{})
        }}}}
        fun capture(name:String) {
            ui.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir,name).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        }
        for(dimensions in listOf(1280 to 800,1280 to 360,840 to 1000)) {
            for((score,color) in listOf(100 to ScoreBlue,99 to ScoreOrange,80 to ScoreOrange,79 to ScoreRed,0 to ScoreRed,90 to ScoreOrange)) {
                ui.runOnIdle {size.value=dimensions;state.value=state.value.copy(feedback=published.copy(resultId="${dimensions.first}x${dimensions.second}-$score",score=score,reason=if(score==100) null else "급감속",publishedAtMs=android.os.SystemClock.elapsedRealtime()))}
                val tag=if(dimensions.first<900) "mini-feedback-score" else "live-feedback-score"
                ui.onNodeWithTag(tag).assertTextEquals("${score}점")
                val layouts=mutableListOf<TextLayoutResult>()
                ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
                assertEquals("$dimensions score=$score",color,layouts.single().layoutInput.style.color)
                if(score!=100) ui.onNodeWithText("(원인 : 급한 감속)").assertIsDisplayed()
                ui.onAllNodesWithText("일곱 동작 자동 코칭",substring=true).assertCountEquals(0)
                ui.onAllNodesWithText("다음 동작을 관측하고 있어요").assertCountEquals(0)
                ui.onAllNodesWithText("정지 구간을 기록했어요",substring=true).assertCountEquals(0)
            }
            capture("validation-score-v8-${dimensions.first}x${dimensions.second}.png")
            ui.runOnIdle {state.value=state.value.copy(feedback=null)}
            ui.onNodeWithText("다음 동작 대기 중").assertIsDisplayed()
            ui.onAllNodesWithText("새 동작이 끝나면",substring=true).assertCountEquals(0)
            ui.onAllNodesWithText("다음 동작을 관측하고 있어요").assertCountEquals(0)
        }
        File(context.filesDir,"validation-score-ui-v8.json").writeText(Gson().toJson(mapOf("computedScore" to result.score,"reason" to FeedbackSummary.reason(result),"checkedScores" to listOf(100,99,80,79,0,90),"sizes" to listOf("1280x800","1280x360","840x1000"),"source" to "explicit synthetic observation / production UI")))
    }
}
