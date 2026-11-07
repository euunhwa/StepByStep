package com.easycar.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ParkingVisionRobustnessTest {
    private val analyzer=ParkingLineAnalyzer()
    private val calibration=SyntheticParkingFrames.calibration
    private fun recolor(frame:PixelFrame,color:Int)=frame.copy(argb=frame.argb.map {if((it and 255)>220) color else it}.toIntArray())
    @Test fun dimWhiteAndYellowMarkingsUseLocalContrast() {
        for(color in listOf(0xff8c8c8c.toInt(),0xffd9bc35.toInt(),0xffad9a48.toInt())) {
            val result=analyzer.analyze(recolor(SyntheticParkingFrames.render(0,7.0),color),calibration)
            assertNotNull(result.reason,result.angleDeg);assertEquals(7.0,result.angleDeg!!,1.0)
            assertEquals(PARKING_VISION_VERSION,result.algorithmVersion)
        }
    }
    @Test fun redMarkingsAndUniformGlareDoNotBecomeParkingLines() {
        assertNull(analyzer.analyze(recolor(SyntheticParkingFrames.render(0,0.0),0xffe03030.toInt()),calibration).angleDeg)
        val glare=PixelFrame(480,360,IntArray(480*360){0xffeeeeee.toInt()},0,"무늬 없는 합성 밝은 바닥")
        assertNull(analyzer.analyze(glare,calibration).angleDeg)
    }
    @Test fun dashedMarkingsRetainAngleWithSufficientObservedRows() {
        val source=SyntheticParkingFrames.render(0,-9.0)
        val pixels=source.argb.copyOf()
        for(y in 12 until 348) if(y%24>=14) for(x in 0 until 480) if((pixels[y*480+x] and 255)>220) pixels[y*480+x]=0xff243139.toInt()
        val result=analyzer.analyze(source.copy(argb=pixels),calibration)
        assertNotNull(result.reason,result.angleDeg);assertEquals(-9.0,result.angleDeg!!,1.0)
    }
    @Test fun shortFragmentsCannotBeExtrapolatedAcrossTheVehicle() {
        val source=SyntheticParkingFrames.render(0,0.0)
        val pixels=source.argb.copyOf()
        for(y in 135 until 360) for(x in 0 until 480) if((pixels[y*480+x] and 255)>220) pixels[y*480+x]=0xff243139.toInt()
        assertNull(analyzer.analyze(source.copy(argb=pixels),calibration).angleDeg)
    }
    @Test fun calibratedVehicleCenterCanBeAwayFromImageCenter() {
        val source=SyntheticParkingFrames.render(0,0.0)
        val shifted=source.copy(argb=IntArray(480*360){i->val x=i%480;val y=i/480;if(x+100<480) source.argb[y*480+x+100] else 0xff243139.toInt()})
        val result=analyzer.analyze(shifted,calibration.copy(vehicleLeftPx=90.0,vehicleRightPx=190.0))
        assertNotNull(result.reason,result.angleDeg);assertEquals(false,result.intrusion)
    }
    @Test fun clearancesUseInnerMarkingEdgeAndPerpendicularDistance() {
        val result=analyzer.analyze(SyntheticParkingFrames.render(0,20.0),calibration)
        val expected=(190-(135+tan(Math.toRadians(20.0))*85)-3.5)/hypot(1.0,tan(Math.toRadians(20.0)))*.01
        assertEquals(expected,result.leftClearanceM!!,.025)
        assertEquals(0.0,result.centerOffsetM!!,.02)
        assertTrue(result.leftClearanceM!!<.21)
    }
    @Test fun contactWithinTwoPixelsIsUnknownAndClearCrossingIsObserved() {
        val boundary=analyzer.analyze(SyntheticParkingFrames.render(0,0.0,52.0),calibration)
        assertNotNull(boundary.angleDeg);assertNull(boundary.intrusion);assertNotNull(boundary.reason)
        val crossed=analyzer.analyze(SyntheticParkingFrames.render(0,0.0,75.0),calibration)
        assertEquals(true,crossed.intrusion);assertTrue(crossed.leftClearanceM!!<0)
    }
    @Test fun missingMetricScaleKeepsPixelsWithoutInventingMeters() {
        val result=analyzer.analyze(SyntheticParkingFrames.render(0,0.0,-20.0),calibration.copy(metersPerPixel=null))
        assertNotNull(result.angleDeg);assertNotNull(result.leftClearancePx)
        assertNull(result.leftClearanceM);assertNull(result.rightClearanceM);assertNull(result.centerOffsetM)
        assertTrue(result.centerOffsetPx!!>15)
    }
    @Test fun malformedCalibrationAndPixelsAreHeldRatherThanCrashing() {
        val source=SyntheticParkingFrames.render(0,0.0)
        for(c in listOf(calibration.copy(referenceWidth=0),calibration.copy(referenceHeight=null),calibration.copy(vehicleLeftPx=Double.NaN),
            calibration.copy(vehicleRightPx=900.0),calibration.copy(metersPerPixel=-.01),calibration.copy(targetCenterPx=Double.NaN),calibration.copy(topView=false))) {
            assertNull(analyzer.analyze(source,c).angleDeg)
        }
        assertNull(analyzer.analyze(source.copy(argb=intArrayOf(0)),calibration).angleDeg)
    }
    @Test fun slotSelectionUsesTheTappedRowAndCannotSelectOutsideDetectedGaps() {
        val lines=listOf(30.0,135.0,345.0).map {x->DetectedLine(Point(x-60,0.0),Point(x+60,360.0),200)}
        val selected=parkingTargetAtTap(lines,480,360,120.0/480,0.0)
        assertEquals(240.0/480,selected!!,.001)
        assertNull(parkingTargetAtTap(lines,480,360,470.0/480,0.0))
        assertNull(parkingTargetAtTap(lines,480,360,Double.NaN,0.0))
    }
    @Test fun advicePreservesUnknownAndNamesObservedGeometryOnly() {
        val held=analyzer.analyze(SyntheticParkingFrames.render(0,0.0,noLines=true),calibration)
        assertEquals(held.reason,parkingVideoAdvice(held))
        val offset=analyzer.analyze(SyntheticParkingFrames.render(0,0.0,-20.0),calibration)
        assertTrue(parkingVideoAdvice(offset).contains("오른쪽"))
        assertFalse(parkingVideoAdvice(offset).contains("주차 성공"))
    }
}
