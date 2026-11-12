package com.example.easycar

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.runtime.LocalVideoInput
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ParkingVideoAnalysisTest {
    private val app get()=ApplicationProvider.getApplicationContext<EasyCarApplication>()
    private fun record(name:String)=SessionRecord(UUID.randomUUID().toString(),"합성 영상 계측 시험",0,"합성 탑뷰 시험",app.profile.version,Lifecycle.HELD,
        emptyList(),emptyList(),videoAsset="demo/video/$name.mp4",videoDurationMs=4000,calibration=SyntheticParkingFrames.calibration)
    @Test fun decodedMp4WindowsObserveGeometryAndHoldBlankOccludedUncalibratedInputs()=runBlocking {
        val input=LocalVideoInput(app)
        val results=linkedMapOf<String,AlignmentEvidence>()
        for(name in listOf("aligned","tilted","intrusion","occluded","blank")) results[name]=input.analyzeWindow(record(name),3880)
        assertEquals(1.0,results.getValue("aligned").angleDeg!!,1.5)
        assertEquals(10.0,results.getValue("tilted").angleDeg!!,1.5)
        assertEquals(true,results.getValue("intrusion").intrusion)
        for(name in listOf("occluded","blank")) assertNull("$name: ${results[name]}",results.getValue(name).angleDeg)
        for(name in listOf("aligned","tilted","intrusion")) {
            assertEquals(5,results.getValue(name).sampledFrames);assertEquals(5,results.getValue(name).acceptedFrames)
        }
        assertNull(input.analyzeWindow(record("aligned").copy(calibration=null),3880).angleDeg)
        assertNull(input.analyzeWindow(record("aligned"),100).angleDeg)
        assertTrue(runCatching {input.analyzeWindow(record("aligned"),4000)}.isFailure)
        File(app.filesDir,"validation-parking-video-windows-v010.json").writeText(Gson().toJson(results))
    }
    @Test fun calibrationReviewPersistsVersionedGeometryWithoutChangingManeuverResults() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val store=ViewModelStore()
        lateinit var model:EasyCarViewModel
        instrumentation.runOnMainSync {
            app.bridge.start()
            model=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EasyCarViewModel::class.java]
        }
        fun await(description:String,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+15000
            while(!condition() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
            assertTrue(description,condition())
        }
        val engine=CoachEngine(app.profile,DocumentDemoCatalog.automaticDrive.context,evaluationPolicy=app.evaluationPolicy)
        DocumentDemoCatalog.automaticDrive.frames.forEach {engine.accept(it)};engine.finish()
        val original=record("tilted").copy(results=engine.results,frames=DocumentDemoCatalog.automaticDrive.frames,
            practiceContext=DocumentDemoCatalog.automaticDrive.context,error="원래 기록의 설명")
        val calibrationFile=File(app.cacheDir,"parking-test-${UUID.randomUUID()}.json")
        calibrationFile.writeText("""{"schemaVersion":1,"projection":"TOP_VIEW_UNDISTORTED","vehicleAxis":"VERTICAL","videoWidth":480,"videoHeight":360,"vehicleBounds":{"left":190,"right":290,"top":95,"bottom":265},"metersPerPixel":0.01,"source":"합성 계측 시험"}""")
        try {
            runBlocking {app.repository.save(original)}
            await("정차 UX 상태 확인") {model.platform.value.ux.allowsReview()}
            instrumentation.runOnMainSync {model.attachCalibration(original.id,Uri.fromFile(calibrationFile))}
            await("보정 후 영상 근거 저장") {app.repository.records.value.first {it.id==original.id}.revision==1L && !model.videoAnalyzing.value}
            val updated=app.repository.records.value.first {it.id==original.id}
            assertNotNull(updated.alignment?.angleDeg)
            assertEquals(original.results,updated.results);assertEquals(original.feedbacks,updated.feedbacks)
            assertEquals(original.frames,runBlocking {app.repository.frames(original.id)});assertEquals(original.error,updated.error)
            assertEquals(PARKING_VISION_VERSION,updated.alignment?.algorithmVersion)
            assertEquals(5,updated.alignment?.sampledFrames)
            instrumentation.runOnMainSync {model.selectParkingTarget(original.id,.5,2500)}
            await("대상 선택 시 현재 위치의 영상 구간 저장") {app.repository.records.value.first {it.id==original.id}.revision==2L && !model.videoAnalyzing.value}
            val selected=app.repository.records.value.first {it.id==original.id}
            assertEquals(2500L,selected.videoAnalysisPositionMs);assertEquals(2500L,selected.alignment?.frameTimeMs)
            assertEquals(original.results,selected.results)
            File(app.filesDir,"validation-parking-preserved-v010.json").writeText(Gson().toJson(mapOf("resultCount" to updated.results.size,"resultsUnchanged" to (updated.results==original.results),"alignment" to updated.alignment,"selectedVideoMs" to selected.videoAnalysisPositionMs)))
        } finally {
            runBlocking {app.repository.delete(original.id)};calibrationFile.delete()
            instrumentation.runOnMainSync {store.clear();app.bridge.stop()}
        }
    }
    @Test fun newGeometrySurvivesRoomRestartAndOldJsonStillLoads()=runBlocking {
        val gson=Gson();val name="video-geometry-${UUID.randomUUID()}.db"
        val original=record("aligned")
        val old=gson.toJsonTree(original).asJsonObject.apply {remove("videoAnalysisPositionMs")}
        assertNull(gson.fromJson(old,SessionRecord::class.java).videoAnalysisPositionMs)
        val updated=original.copy(alignment=LocalVideoInput(app).analyzeWindow(original,3880),videoAnalysisPositionMs=3880)
        val first=AppRepository(app,name)
        try {first.save(updated)} finally {first.close()}
        val second=AppRepository(app,name)
        try {second.load();assertEquals(updated.copy(frames=emptyList()),second.records.value.single());assertEquals(updated.frames,second.frames(updated.id))} finally {second.close();app.deleteDatabase(name)}
    }
}
