package com.easycar.core

import org.junit.Assert.*
import org.junit.Test

class ParkingVideoWindowTest {
    private fun samples(shifts:List<Double> = List(5){0.0})=shifts.mapIndexed {i,shift->ParkingLineAnalyzer().analyze(SyntheticParkingFrames.render(i*250L,5.0,shift),SyntheticParkingFrames.calibration)}
    private val window=ParkingVideoWindowAnalyzer()
    @Test fun stableWindowKeepsFinalFrameAndVersionedCounts() {
        val result=window.analyze(samples())
        assertNotNull(result.reason,result.angleDeg);assertEquals(5,result.sampledFrames);assertEquals(5,result.acceptedFrames)
        assertEquals(1000,result.frameTimeMs);assertEquals(0L,result.windowStartMs)
    }
    @Test fun oneOccludedMiddleFrameDoesNotEraseStableRecentEvidence() {
        val frames=samples().toMutableList();frames[2]=frames[2].copy(angleDeg=null,intrusion=null,reason="가림")
        val result=window.analyze(frames)
        assertNotNull(result.angleDeg);assertEquals(4,result.acceptedFrames)
    }
    @Test fun missingFinalFrameNeverReusesAnEarlierGoodResult() {
        val frames=samples().toMutableList();frames[4]=frames[4].copy(angleDeg=null,intrusion=null,reason="최근 프레임 가림")
        val result=window.analyze(frames)
        assertNull(result.angleDeg);assertEquals("최근 프레임 가림",result.reason)
    }
    @Test fun movingVehicleAndChangingIntrusionRemainHeld() {
        val moved=window.analyze(samples(listOf(0.0,5.0,10.0,15.0,20.0)))
        assertNull(moved.angleDeg);assertTrue(moved.reason!!.contains("변하고"))
        val frames=samples().toMutableList();frames[4]=frames[4].copy(intrusion=true)
        assertNull(window.analyze(frames).angleDeg)
    }
    @Test fun tooFewDistinctFramesTooShortAWindowAndMixedSourcesAreHeld() {
        val frames=samples()
        assertNull(window.analyze(List(5){frames[0]}).angleDeg)
        assertNull(window.analyze(frames.mapIndexed {i,a->a.copy(frameTimeMs=i*10L)}).angleDeg)
        assertNull(window.analyze(frames.mapIndexed {i,a->a.copy(source=if(i==4) "다른 영상" else a.source)}).angleDeg)
    }
    @Test fun tooManyMissingFramesDoNotBecomeAStableWindow() {
        val frames=samples().mapIndexed {i,a->if(i<3) a.copy(angleDeg=null,intrusion=null,reason="가림") else a}
        assertNull(window.analyze(frames).angleDeg)
    }
}
