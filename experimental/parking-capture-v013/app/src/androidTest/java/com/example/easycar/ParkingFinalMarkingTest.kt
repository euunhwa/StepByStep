package com.example.easycar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ParkingFinalMarkingTest {
    @get:Rule val ui=createComposeRule()
    @Test fun sixActualImageTapsCreateAProvisionalScoreAndPublishedScoreCannotBeReplaced()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>();app.ready.await()
        val settings=app.repository.settings.first();val ids=mutableListOf<String>()
        try {
            app.repository.updateSettings(settings.copy(consent=true,muted=true))
            val previous=app.parking.state.value.finishedId
            withContext(Dispatchers.Main) {app.parking.runExample("aligned",1)}
            val done=withTimeout(30000) {app.parking.state.first {!it.running && it.finishedId!=null && it.finishedId!=previous}}
            val source=app.repository.records.value.single {it.id==done.finishedId};ids+=source.id
            // A distinct pending-review fixture, never a rewrite of the original published score.
            val id=UUID.randomUUID().toString();ids+=id
            val file=File(app.filesDir,"videos/$id.mp4");File(source.videoFile!!).copyTo(file)
            val capture=source.parkingCapture!!
            val held=capture.outcome!!.copy(score=null,status="HELD",placement=null,holds=listOf("최종 배치 지정 대기"))
            val record=source.copy(id=id,videoFile=file.path,results=listOf(held.asManeuver(capture.episode!!,source.source,"$id-parking")),
                parkingCapture=capture.copy(outcome=held,calibrationConfirmed=false),revision=0)
            app.repository.save(record)
            val shown=mutableStateOf(record);val busy=mutableStateOf(false)
            ui.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Box(Modifier.fillMaxSize().background(Ink)) {Box(Modifier.size(640.dp,780.dp)) {
                ParkingCaptureReview(shown.value,false,busy.value,{}, {}) {calibration,slot->
                    app.scope.launch {busy.value=true;try {app.parking.analyze(id,calibration,slot);shown.value=app.repository.records.value.single {it.id==id}} finally {busy.value=false}}
                }
            }}}}
            ui.waitUntil(20000) {ui.onAllNodesWithTag("parking-final-frame").fetchSemanticsNodes().isNotEmpty()}
            val points=listOf(190f to 95f,290f to 265f,138f to 48f,342f to 48f,342f to 312f,138f to 312f)
            points.forEach {(x,y)->ui.onNodeWithTag("parking-final-frame").performScrollTo().performTouchInput {click(Offset(width*x/480,height*y/360))}}
            ui.onNodeWithTag("parking-final-score").performScrollTo().assertIsNotEnabled()
            ui.onNodeWithTag("parking-calibration-confirm").performScrollTo().performClick()
            ui.onNodeWithTag("parking-final-score").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(30000) {shown.value.parkingCapture?.outcome?.score!=null}
            assertEquals(95,shown.value.parkingCapture!!.outcome!!.score)
            assertEquals("USER_MARKED_SLOT_AND_CALIBRATED_VEHICLE",shown.value.parkingCapture!!.outcome!!.placement!!.source)
            ui.onNodeWithTag("parking-outcome-score").performScrollTo().assert(hasText("95점",substring=true))
            val unchanged=app.repository.records.value.single {it.id==id}
            val second=runCatching {app.parking.analyze(id,unchanged.calibration!!)}
            assertTrue(second.exceptionOrNull() is IllegalArgumentException)
            assertEquals(unchanged,app.repository.records.value.single {it.id==id})
            val screenshot=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir,"validation-parking-final-marking-v013.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
        } finally {ids.forEach {app.repository.delete(it)};app.repository.updateSettings(settings)}
    }
}
