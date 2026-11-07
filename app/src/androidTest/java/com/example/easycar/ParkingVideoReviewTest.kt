package com.example.easycar

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.data.SessionRecord
import com.example.easycar.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParkingVideoReviewTest {
    @get:Rule val ui=createComposeRule()
    @Test fun actualPreviewResponsiveControlsAndDrivingRestrictionRenderCorrectly() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val record=SessionRecord("video-ui-test","영상 시험",0,"합성 탑뷰 예제 영상","document-derived-1.0",Lifecycle.HELD,emptyList(),emptyList(),
            videoAsset="demo/video/tilted.mp4",videoDurationMs=4000,calibration=SyntheticParkingFrames.calibration)
        val size=mutableStateOf(1280 to 800);val restricted=mutableStateOf(false)
        val currentRecord=mutableStateOf(record)
        var requested:Long?=null
        ui.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Box(Modifier.fillMaxSize().background(Ink)) {Box(Modifier.size(size.value.first.dp,size.value.second.dp)) {
            VideoReview(currentRecord.value,{},restricted.value,{}, {_,_->},onAnalyzeWindow={requested=it})
        }}}}
        ui.waitUntil(20000) {
            ui.onNodeWithTag("parking-frame-angle").fetchSemanticsNode().config[SemanticsProperties.Text].any {it.text.contains("기울어져")}
        }
        for(term in listOf("탑뷰","px","프레임","동기화","수직 차량축","상대 각도"))
            ui.onAllNodes(hasText(term,substring=true)).assertCountEquals(0)
        ui.onNodeWithTag("analyze-parking-window").performScrollTo().performClick()
        assertEquals(3880L,requested)
        fun capture(name:String) {
            ui.waitForIdle()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        capture("validation-parking-video-wide-v010.png")
        ui.runOnIdle {size.value=700 to 1000}
        ui.onNodeWithTag("parking-window-result").performScrollTo().assertIsDisplayed()
        val root=ui.onNodeWithTag("parking-video-review").fetchSemanticsNode().boundsInRoot
        val panel=ui.onNodeWithTag("parking-observations").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.right<=root.right+1);assertTrue(panel.left>=root.left-1)
        capture("validation-parking-video-narrow-v010.png")
        ui.runOnIdle {currentRecord.value=record.copy(calibration=null)}
        ui.waitUntil(20000) {
            ui.onNodeWithTag("parking-frame-angle").fetchSemanticsNode().config[SemanticsProperties.Text].any {it.text.contains("보류")}
        }
        ui.onNodeWithTag("parking-frame-advice").assert(hasText("분석 설정",substring=true))
        ui.onAllNodes(hasText("탑뷰",substring=true)).assertCountEquals(0)
        ui.runOnIdle {restricted.value=true}
        ui.onNodeWithTag("parking-video-restricted").assertIsDisplayed()
        ui.onNodeWithTag("parking-video-frame").assertDoesNotExist()
    }
}
