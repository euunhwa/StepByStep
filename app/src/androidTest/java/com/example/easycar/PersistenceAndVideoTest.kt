package com.example.easycar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.runtime.LocalVideoInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PersistenceAndVideoTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun record(asset:String)=SessionRecord("video-test","시험",System.currentTimeMillis(),"합성 데모 영상","demo-1.0.0",Lifecycle.COMPLETED,emptyList(),listOf(VehicleFrame(9000,null,null,null,null)),videoAsset="demo/video/$asset.mp4",videoDurationMs=4000)
    @Test fun actualMp4DecodeFeedsPixelAnalyzer() {
        val input=LocalVideoInput(context);val analyzer=ParkingLineAnalyzer()
        for((name,angle) in listOf("aligned" to 1.0,"tilted" to 10.0)) {
            val f=input.finalFrame(record(name));assertNotNull("$name decode",f)
            val result=analyzer.analyze(f!!,SyntheticParkingFrames.calibration)
            assertNotNull("$name ${result.reason}",result.angleDeg)
            assertTrue("$name ${result.angleDeg}",abs(result.angleDeg!!-angle)<1.5)
        }
        assertTrue(analyzer.analyze(input.finalFrame(record("intrusion"))!!,SyntheticParkingFrames.calibration).intrusion==true)
        for(name in listOf("blank","occluded")) assertNull(analyzer.analyze(input.finalFrame(record(name))!!,SyntheticParkingFrames.calibration).angleDeg)
    }
    @Test fun roomRestartRecoveryAndDeleteKeepMediaConsistent()=runBlocking {
        val name="easycar-test-${UUID.randomUUID()}.db"
        val directory=File(context.filesDir,"videos").apply { mkdirs() }
        val file=File(directory,"test-${UUID.randomUUID()}.mp4").apply { writeBytes(byteArrayOf(1,2,3)) }
        val saved=record("aligned").copy(state=Lifecycle.ACTIVE,videoFile=file.absolutePath,demoCaseId="park-t_park-3-normal",progressIndex=32)
        val a=AppRepository(context,name);a.save(saved);a.close()
        val b=AppRepository(context,name);b.load();assertEquals(32,b.records.value.single().progressIndex)
        b.recoverInterrupted();assertEquals(Lifecycle.INTERRUPTED,b.records.value.single().state)
        assertTrue(file.exists());b.delete(saved.id);assertFalse(file.exists());b.save(saved.copy(revision=10000));assertTrue("삭제 후 늦은 콜백이 기록을 복원하면 안 됩니다",b.records.value.isEmpty());b.close()
        val c=AppRepository(context,name);c.load();assertTrue(c.records.value.isEmpty());c.close();context.deleteDatabase(name);Unit
    }
    @Test fun exportedSignalsHaveNoGroundTruthFieldsAndShareEngine() {
        val json=context.assets.open("demo/signals/park-t_park-3-normal.json").bufferedReader().use { it.readText() }
        assertFalse(json.contains("Truth"));assertFalse(json.contains("label"))
        val frames=com.google.gson.Gson().fromJson(json,Array<VehicleFrame>::class.java)
        val profile=ReferenceProfile.fromJson(context.assets.open("profiles/arbitrary-prototype-v1.json").bufferedReader().use { it.readText() })
        val policy=EvaluationPolicy.fromJson(context.assets.open("evaluation/evaluation-policy-prototype.json").bufferedReader().use {it.readText()},context.assets.open("evaluation/arbitrary-reference-v1.json").bufferedReader().use {it.readText()})
        val e=CoachEngine(profile,PracticeContext(null,null),evaluationPolicy=policy);frames.forEach {e.accept(it)}
        val end=e.results.single {isParking(it.type)}.endMs
        val video=record("aligned").copy(frames=frames.toList(),videoSync=VideoSync(0,4000,frames.first().timeMs,frames.last().timeMs))
        val vision=ParkingLineAnalyzer().analyze(LocalVideoInput(context).frameAt(video,end)!!,SyntheticParkingFrames.calibration)
        val result=e.finish(vision).single {isParking(it.type)}
        assertEquals(3,result.reverseToDriveCount);assertEquals(12,result.deductions.first {it.key=="gear_repetition"}.points);assertNotNull(result.score)
    }
    @Test fun longDriveJsonLargerThanCursorWindowSurvivesRestart()=runBlocking {
        val name="easycar-large-${UUID.randomUUID()}.db"
        val saved=record("aligned").copy(title="장시간 주행 🚗",error="한글 기록 🚗".repeat(220000))
        assertTrue(com.google.gson.Gson().toJson(saved).toByteArray().size>2*1024*1024)
        val a=AppRepository(context,name);a.save(saved);a.close()
        val b=AppRepository(context,name)
        try {b.load();assertEquals(saved.copy(frames=emptyList()),b.records.value.single());assertEquals(saved.frames,b.frames(saved.id))} finally {b.close();context.deleteDatabase(name)}
    }
}
