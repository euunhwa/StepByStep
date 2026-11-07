package com.example.easycar

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.easycar.runtime.LocalVideoInput
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParkingProjectionCaptureTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun userApprovedScreenProjectionCreatesARealPlayableMp4AndAnIncompleteScoreStaysHeld() {
        val app=ui.activity.application as EasyCarApplication;val previous=runBlocking {app.repository.settings.first()};var id:String?=null
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun clickSystemConsent():Boolean {
            val root=automation.rootInActiveWindow ?: return false
            val all=nodes(root)
            if(!all.any {it.packageName?.toString()=="com.android.systemui"}) return false
            val button=all.firstOrNull {it.viewIdResourceName=="android:id/button1" && it.isEnabled}
                ?: all.firstOrNull {it.isClickable && it.text?.toString() in listOf("지금 시작","시작하기","Start now","Start recording","녹화 시작","시작")}
            return button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        }
        try {
            runBlocking {app.repository.updateSettings(previous.copy(consent=true,saveVideo=true,muted=true,smallWindowOnStart=false))}
            ui.waitUntil(20000) {app.bridge.state.value.ux.allowsReview() && ui.onAllNodesWithTag("nav-parking").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("nav-parking").performClick();ui.onNodeWithTag("parking-record-start").performScrollTo().performClick()
            val until=SystemClock.elapsedRealtime()+8000;var approved=false
            while(!approved && SystemClock.elapsedRealtime()<until) {approved=clickSystemConsent();Thread.sleep(150)}
            assertTrue("MediaProjection system consent was not found",approved)
            val end=SystemClock.elapsedRealtime()+10000
            while((app.parking.state.value.record==null || !app.parking.state.value.running) && SystemClock.elapsedRealtime()<end) Thread.sleep(100)
            assertTrue(app.parking.state.value.error,app.parking.state.value.running);assertNotNull(app.parking.state.value.record)
            id=app.parking.state.value.record!!.id
            val consoleFlow=InstrumentationRegistry.getArguments().getString("externalConsoleFlow")=="true"
            val cameraProbe=InstrumentationRegistry.getArguments().getString("cameraDisplayProbe")=="true"
            if(cameraProbe) {
                // Normal tap on the observed Pleos launcher camera button; no privileged camera API.
                val screen=automation.takeScreenshot()
                val x=screen.width*.617f;val y=screen.height*.953f;screen.recycle()
                val now=SystemClock.uptimeMillis()
                val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0)
                val up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,x,y,0)
                automation.injectInputEvent(down,true);automation.injectInputEvent(up,true);down.recycle();up.recycle()
            }
            if(consoleFlow) {
                File(app.filesDir,"parking-capture-console-ready.json").writeText(Gson().toJson(mapOf("ready" to true,"id" to id)))
                val deadline=SystemClock.elapsedRealtime()+45000
                while(app.parking.state.value.running && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
                assertFalse("8088 parking episode did not automatically finish",app.parking.state.value.running)
            } else {
                Thread.sleep(2300)
                runBlocking {withContext(Dispatchers.Main) {app.parking.finish("화면 녹화 계측 검사 종료")}}
            }
            val record=app.repository.records.value.single {it.id==id};val capture=record.parkingCapture!!
            assertEquals("USER_SELECTED_SCREEN",capture.inputSource);assertTrue(capture.interruptedReason,capture.videoSaved)
            assertTrue(record.videoDurationMs!!>=1000);assertTrue(File(record.videoFile!!).length()>1000);assertNull(capture.outcome!!.score)
            if(consoleFlow) {
                assertTrue(capture.episode!!.automaticCompletion);assertTrue(capture.episode!!.signalsComplete)
                assertEquals(2,capture.episode!!.reverseToDriveCount);assertNotNull(capture.finalVideoMs)
                assertTrue(record.frames.any {it.speed?.source?.startsWith("AAOS")==true})
                assertTrue(record.frames.none {it.speed?.source?.contains("SYNTHETIC")==true})
                File(app.filesDir,"validation-parking-console-record-v013.json").writeText(Gson().toJson(record))
            }
            val frame=LocalVideoInput(app).pixelAtVideoTime(record,(record.videoDurationMs!!-400))!!
            val image=Bitmap.createBitmap(frame.argb,frame.width,frame.height,Bitmap.Config.ARGB_8888)
            val suffix=if(consoleFlow) "console" else if(cameraProbe) "camera-probe" else "projection"
            File(app.filesDir,"validation-parking-$suffix-frame-v013.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            File(app.filesDir,"validation-parking-$suffix-v013.json").writeText(Gson().toJson(mapOf("systemConsentClicked" to approved,"realMediaProjectionRecording" to true,"normalCameraLauncherTapped" to cameraProbe,"avmImageVerified" to false,"videoBytes" to File(record.videoFile!!).length(),"durationMs" to record.videoDurationMs,"completedVehicleEpisode" to (capture.episode?.automaticCompletion==true),"heldWithoutCompletedEpisode" to (capture.episode?.automaticCompletion!=true),"heldWithoutCalibratedTopView" to true,"capture" to capture)))
        } finally {File(app.filesDir,"parking-capture-console-ready.json").delete();runBlocking {withContext(Dispatchers.Main) {if(app.parking.state.value.running) app.parking.finish("테스트 정리")};id?.let {app.repository.delete(it)};app.repository.updateSettings(previous)}}
    }
}
