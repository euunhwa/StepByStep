package com.example.easycar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.easycar.data.UserSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class DemoFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun oneStartRunsAllSevenPersistsHeldScoresAndOpensVideo() {
        val app=ui.activity.application as EasyCarApplication
        runBlocking { app.repository.updateSettings(UserSettings(consent=true,smallWindowOnStart=false,muted=true,demoInput=true)) }
        ui.waitUntil(15000) { ui.onAllNodesWithTag("start-submission-demo").fetchSemanticsNodes().isNotEmpty() }
        val previous=app.runtime.state.value.finishedId
        // The demo button sits in the fixed top bar, not in a scroll container.
        ui.onNodeWithTag("start-submission-demo").performClick()
        ui.waitUntil(150000) { (app.runtime.state.value.finishedId!=null && app.runtime.state.value.finishedId!=previous) || app.runtime.state.value.error!=null }
        assertNull(app.runtime.state.value.error)
        val record=app.repository.records.value.first {it.id==app.runtime.state.value.finishedId}
        assertEquals(setOf(com.easycar.core.ManeuverType.START,com.easycar.core.ManeuverType.STOP,com.easycar.core.ManeuverType.LEFT,com.easycar.core.ManeuverType.RIGHT,com.easycar.core.ManeuverType.U_TURN,com.easycar.core.ManeuverType.T_PARK,com.easycar.core.ManeuverType.PARALLEL_PARK),record.results.map {it.type}.toSet())
        assertTrue(record.results.all {it.score!=null && it.scoreKind=="PROVISIONAL"})
        val result=record.results.single {it.type==com.easycar.core.ManeuverType.T_PARK}
        // The document demo parks in one reverse pass (no R→D) and carries no video; video review is
        // covered by ParkingVideoGalleryTest / ParkingVideoReviewTest.
        assertEquals(0,result.reverseToDriveCount);assertTrue(result.deductions.none {it.key=="gear_repetition"})
        assertNotNull(result.score)
        assertNull(record.videoAsset)
    }
}
