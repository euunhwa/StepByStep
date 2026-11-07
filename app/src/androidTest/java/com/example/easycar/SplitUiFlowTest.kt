package com.example.easycar

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.easycar.runtime.CoachingService
import com.example.easycar.ui.EasyCarApp
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SplitUiFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun threeMenusAndCompactStartKeepTheSameVehicleSessionAcrossResizes() {
        val app=ui.activity.application as EasyCarApplication
        val prior=runBlocking {app.repository.settings.first()}
        val size=mutableStateOf(1280 to 900)
        runBlocking {app.repository.updateSettings(prior.copy(consent=true,muted=true,smallWindowOnStart=false))}
        fun capture(name:String) {
            ui.waitForIdle()
            val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,name).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        }
        try {
            ui.runOnIdle {
                val model=ViewModelProvider(ui.activity)[EasyCarViewModel::class.java]
                ui.activity.setContent {Box(Modifier.size(size.value.first.dp,size.value.second.dp)) {EasyCarApp(model,ui.activity.miniWindow){false}}}
            }
            ui.waitUntil(20000) {app.bridge.state.value.ux.allowsReview() && ui.onAllNodesWithTag("nav-home").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("nav-home").assertExists();ui.onNodeWithTag("nav-settings").assertExists()
            ui.onNodeWithTag("nav-growth").assertDoesNotExist();ui.onNodeWithText("복기").assertDoesNotExist()
            ui.onNodeWithTag("nav-records").performClick();ui.onNodeWithText("주행 기록").assertIsDisplayed()
            ui.onNodeWithTag("nav-home").performClick();capture("validation-menu-v7.png")
            ui.runOnIdle {size.value=840 to 1000}
            ui.onNodeWithTag("split-home").assertIsDisplayed();ui.onNodeWithTag("start-coaching").assertIsDisplayed()
            ui.onNodeWithText("지금 들어오는 차량 신호").assertDoesNotExist();ui.onNodeWithText("최근 코칭").assertDoesNotExist()
            capture("validation-split-home-v7.png")
            for(dimensions in listOf(600 to 480,1280 to 360)) {
                ui.runOnIdle {size.value=dimensions}
                ui.onNodeWithTag("start-coaching").assertIsDisplayed()
                val view=ui.onNodeWithTag("split-home").getUnclippedBoundsInRoot();val start=ui.onNodeWithTag("start-coaching").getUnclippedBoundsInRoot()
                assertTrue("start button fits $dimensions",start.top>=view.top && start.bottom<=view.bottom)
            }
            ui.runOnIdle {size.value=840 to 1000}
            ui.onNodeWithTag("start-coaching").performClick()
            ui.waitUntil(15000) {app.runtime.state.value.running && (app.runtime.state.value.record?.frames?.size ?: 0)>10}
            val id=app.runtime.state.value.record!!.id
            assertNull(app.runtime.state.value.record!!.demoCaseId)
            for(dimensions in listOf(840 to 1000,600 to 480,1280 to 360)) {
                ui.runOnIdle {size.value=dimensions}
                ui.onNodeWithTag("mini-coach").assertIsDisplayed();ui.onNodeWithTag("main-navigation").assertDoesNotExist()
                ui.onNodeWithTag("end-drive").assertIsDisplayed()
                val view=ui.onNodeWithTag("mini-coach").getUnclippedBoundsInRoot();val end=ui.onNodeWithTag("end-drive").getUnclippedBoundsInRoot()
                assertTrue("end button fits $dimensions",end.bottom<=view.bottom && end.top>=view.top)
                assertEquals(id,app.runtime.state.value.record!!.id)
            }
            ui.runOnIdle {size.value=1280 to 900}
            ui.onNodeWithTag("drive-screen").assertIsDisplayed();assertEquals(id,app.runtime.state.value.record!!.id)
            ui.runOnIdle {size.value=840 to 1000}
            ui.onNodeWithTag("mini-coach").assertIsDisplayed()
            capture("validation-split-coaching-v7.png")
            File(app.filesDir,"validation-split-v7.json").writeText(Gson().toJson(mapOf(
                "menus" to listOf("홈","기록","설정"),"sameSessionAcrossResizes" to id,
                "inputMode" to "VEHICLE_SERVICE","layoutProbe" to "COMPOSE_CONSTRAINED_AREA",
                "demoCaseId" to app.runtime.state.value.record!!.demoCaseId,
                "frames" to app.runtime.state.value.record!!.frames.size,
                "sizes" to listOf("840x1000","600x480","1280x360","1280x900"))))
            ui.onNodeWithTag("end-drive").performClick()
            ui.waitUntil(15000) {!app.runtime.state.value.running}
        } finally {
            if(app.runtime.state.value.running) app.startService(Intent(app,CoachingService::class.java).setAction("stop"))
            ui.waitUntil(15000) {!app.runtime.state.value.running}
            runBlocking {app.repository.updateSettings(prior)}
        }
    }
}
