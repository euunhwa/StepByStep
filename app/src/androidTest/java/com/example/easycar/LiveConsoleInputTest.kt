package com.example.easycar

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.data.UserSettings
import com.example.easycar.runtime.CoachingService
import android.content.Intent
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Consumes real vehicle-service callbacks while the operator drives through localhost:8088.
 * No mock frames, synthetic fixture, shell VHAL injection or ground-truth labels enter this test.
 */
@RunWith(AndroidJUnit4::class)
class LiveConsoleInputTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun primaryStartUsesVehicleServiceEvenWithLegacyDemoPreference() {
        val app=ui.activity.application as EasyCarApplication
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,muted=false,demoInput=true,smallWindowOnStart=false))}
        ui.waitUntil(20000) {ui.onAllNodesWithTag("start-coaching").fetchSemanticsNodes().isNotEmpty() && app.bridge.state.value.ux.allowsReview()}
        ui.onNodeWithTag("start-submission-demo").assertExists()
        ui.onNodeWithTag("start-coaching").performScrollTo().performClick()
        ui.waitUntil(15000) {app.runtime.state.value.running && (app.runtime.state.value.record?.frames?.size ?: 0)>15}
        val id=app.runtime.state.value.record!!.id
        assertNull(app.runtime.state.value.record!!.demoCaseId)
        File(app.filesDir,"validation-live-v5-ready.json").writeText(Gson().toJson(mapOf("ready" to true,"sessionId" to id,"phase" to "WAITING_FOR_LOCALHOST_DRIVE")))
        try {
            ui.waitUntil(150000) {app.runtime.state.value.record?.frames?.any {it.speed?.value?.let {v->kotlin.math.abs(v)>1.0}==true}==true}
            ui.waitUntil(60000) {app.runtime.state.value.feedbackHistory.any {it.type==ManeuverType.STOP} || app.runtime.state.value.error!=null}
            assertNull(app.runtime.state.value.error)
            val record=app.runtime.state.value.record!!
            assertEquals(id,record.id);assertNull(record.demoCaseId)
            assertTrue(record.frames.any {it.gear?.value==Gear.DRIVE})
            assertTrue(record.frames.filter {it.speed!=null}.all {it.speed!!.source.startsWith("AAOS")})
            assertTrue(record.frames.all {it.parkingScene==null})
            assertTrue(record.results.any {it.type==ManeuverType.START})
            assertTrue(record.results.all {it.scoreKind=="PROVISIONAL" && it.scoreBasis?.contains("임의")==true})
            assertTrue(record.results.none {it.coaching.contains("필요한 정보가 부족해")})
            File(app.filesDir,"validation-live-v5-result.json").writeText(Gson().toJson(record))
        } finally {
            if(app.runtime.state.value.running) app.startService(Intent(app,CoachingService::class.java).setAction("stop"))
            ui.waitUntil(15000) {!app.runtime.state.value.running}
            runBlocking {app.repository.updateSettings(UserSettings(consent=true,muted=false,demoInput=false,smallWindowOnStart=true))}
        }
    }
}
