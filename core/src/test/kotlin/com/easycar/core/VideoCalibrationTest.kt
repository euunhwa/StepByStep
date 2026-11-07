package com.easycar.core

import org.junit.Assert.*
import org.junit.Test

class VideoCalibrationTest {
    private val json="""{"schemaVersion":1,"projection":"TOP_VIEW_UNDISTORTED","vehicleAxis":"VERTICAL","videoWidth":480,"videoHeight":360,"vehicleBounds":{"left":190,"right":290,"top":95,"bottom":265},"metersPerPixel":0.01,"source":"합성 보정 시험","sync":{"videoStartMs":0,"videoEndMs":4000,"signalStartMs":1000,"signalEndMs":9000}}"""
    @Test fun validatesCalibrationAndMapsOnlyExplicitTimeRange() {
        val d=VideoCalibrationDocument.fromJson(json)
        assertEquals(500L,d.sync!!.videoTime(2000));assertEquals(5000L,d.sync.signalTime(2000))
        assertNull(d.sync.videoTime(9500));assertNull(d.sync.signalTime(5000))
        assertEquals(.02,d.calibration.forDimensions(240,180)!!.metersPerPixel!!,.00001)
        assertNull(d.calibration.forDimensions(640,360))
    }
    @Test fun unsupportedProjectionAndImpossibleBoundsAreRejected() {
        for(bad in listOf(json.replace("TOP_VIEW_UNDISTORTED","FRONT_CAMERA"),json.replace("\"left\":190","\"left\":490"),json.replace("\"videoEndMs\":4000","\"videoEndMs\":0"))) {
            assertTrue(runCatching {VideoCalibrationDocument.fromJson(bad)}.isFailure)
        }
    }
    @Test fun multipleSlotsAreHeldUntilUserSelectsAnActualDetectedPair() {
        val original=SyntheticParkingFrames.render(1200,0.0)
        val pixels=original.argb.copyOf()
        for(y in 12 until original.height-12) for(x in 27..33) pixels[y*original.width+x]=0xfff4f4f4.toInt()
        val frame=original.copy(argb=pixels)
        val analyzer=ParkingLineAnalyzer();val c=SyntheticParkingFrames.calibration
        val ambiguous=analyzer.analyze(frame,c)
        assertNull(ambiguous.angleDeg);assertTrue(ambiguous.lines.size>=3)
        val selected=analyzer.analyze(frame,c.copy(targetCenterPx=240.0))
        assertNotNull(selected.angleDeg);assertEquals(0.0,selected.angleDeg!!,1.0)
        assertEquals(false,selected.intrusion);assertEquals(1200,selected.frameTimeMs)
        assertNull(analyzer.analyze(frame,c.copy(targetCenterPx=470.0)).angleDeg)
    }
    @Test fun resizedActualPixelsUseCorrespondinglyScaledVehicleBounds() {
        val source=SyntheticParkingFrames.render(1000,7.0)
        val half=PixelFrame(240,180,IntArray(240*180) {i->source.argb[(i/240*2)*480+(i%240)*2]},1000,"크기 변경 시험")
        val a=ParkingLineAnalyzer().analyze(half,SyntheticParkingFrames.calibration)
        assertEquals(7.0,a.angleDeg!!,1.0)
        assertEquals(1000,a.frameTimeMs)
    }
}
