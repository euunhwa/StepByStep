package com.example.easycar

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParkingVideoGalleryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun recordsExampleCanBeOpenedAnalyzedAndSavedThroughTheRealApp() {
        val app=ui.activity.application as EasyCarApplication
        val settings=runBlocking {app.repository.settings.first()}
        val before=app.repository.records.value.map {it.id}.toSet()
        var created:String?=null
        try {
            runBlocking {app.repository.updateSettings(settings.copy(consent=true,muted=true,smallWindowOnStart=false))}
            ui.waitUntil(20000) {app.bridge.state.value.ux.allowsReview() && ui.onAllNodesWithTag("nav-records").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("nav-records").performClick()
            ui.onNodeWithTag("parking-video-examples").performClick()
            ui.onNodeWithText("기울어짐").performClick()
            ui.waitUntil(20000) {ui.onAllNodesWithTag("analyze-parking-window").fetchSemanticsNodes().isNotEmpty()}
            created=app.repository.records.value.single {it.id !in before && it.title=="영상 분석 예제 · 기울어짐"}.id
            ui.onNodeWithTag("analyze-parking-window").performScrollTo().performClick()
            ui.waitUntil(20000) {app.repository.records.value.first {it.id==created}.alignment?.sampledFrames==5}
            val saved=app.repository.records.value.first {it.id==created}
            assertNotNull(saved.alignment?.angleDeg);assertTrue(saved.results.isEmpty());assertNull(saved.score)
            ui.onNodeWithTag("parking-window-result").performScrollTo().assertIsDisplayed()
            ui.waitForIdle()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,"validation-parking-gallery-v010.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        } finally {
            runBlocking {created?.let {app.repository.delete(it)};app.repository.updateSettings(settings)}
        }
    }
}
