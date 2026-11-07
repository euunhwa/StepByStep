package com.easycar.core

import org.junit.Assert.*
import org.junit.Test

class ParkingOutcomeTest {
    private val slot=listOf(Point(135.0,45.0),Point(345.0,45.0),Point(345.0,315.0),Point(135.0,315.0))
    private val body=listOf(Point(190.0,95.0),Point(290.0,95.0),Point(290.0,265.0),Point(190.0,265.0))
    private val ep=ParkingEpisode(0,6000,1,true,true,true,4850,emptyList())
    private val scorer=ParkingOutcomeScorer()
    private fun place(s:List<Point> = slot)=ParkingPlacementGeometry.measure(s,body,"TEST")!!
    private fun frame(t:Long,g:Gear=Gear.DRIVE,v:Double=.5)=VehicleFrame(t,Signal(v,t,t,"m/s","TEST"),null,Signal(g,t,t,"gear","TEST"),null)
    @Test fun alignedPlacementAndOneFreeConversionScore100() {val r=scorer.score(ep,place(),4850,true);assertEquals(100,r.score);assertTrue(r.deductions.isEmpty())}
    @Test fun additionalConversionsAreFivePointsAndCappedWithoutOverflow() {
        assertEquals(95,scorer.score(ep.copy(reverseToDriveCount=2),place(),4850,true).score)
        assertEquals(70,scorer.score(ep.copy(reverseToDriveCount=Int.MAX_VALUE),place(),4850,true).score)
    }
    @Test fun intrusionIncludesAllFourEdgesAndAppliesAnExplicitScoreCap() {
        val intruded=slot.map {Point(it.x,it.y-70)}
        val p=place(intruded);assertEquals(false,p.inside)
        val r=scorer.score(ep,p,4850,true);assertEquals("OUTSIDE_SLOT",r.status);assertTrue(r.score!!<=59)
        assertEquals(100-r.score!!,r.deductions.sumOf {it.points})
    }
    @Test fun uncertainBoundaryAndMissingSignalsOrVideoNeverBecomeAPassingScore() {
        val boundary=slot.map {Point(it.x+55,it.y)}
        assertNull(place(boundary).inside)
        for(r in listOf(scorer.score(ep,place(boundary),1,true),scorer.score(ep.copy(signalsComplete=false),place(),1,true),scorer.score(ep,null,1,true),scorer.score(ep,place(),1,false),scorer.score(ep.copy(automaticCompletion=false),place(),1,true))) assertNull(r.score)
    }
    @Test fun crossedPerspectiveAndNonFiniteQuadsAreRejected() {
        assertFalse(ParkingPlacementGeometry.validQuad(listOf(slot[0],slot[2],slot[1],slot[3])))
        assertFalse(ParkingPlacementGeometry.validQuad(slot.mapIndexed {i,p->if(i==0) Point(Double.NaN,p.y) else p}))
        assertNull(ParkingPlacementGeometry.measure(slot.take(3),body,"TEST"))
    }
    @Test fun episodeStartsBeforeReverseCountsStableTransitionsAndCompletesAfterMovedThenParked() {
        val tracker=ParkingEpisodeTracker(0)
        for(t in 0L..6500 step 100) tracker.accept(frame(t,when {t<800->Gear.DRIVE;t<1600->Gear.REVERSE;t<2400->Gear.DRIVE;t<3200->Gear.REVERSE;t<5000->Gear.DRIVE;else->Gear.PARK},if(t<4000) .5 else 0.0))
        val s=tracker.snapshot();assertEquals(2,s.reverseToDriveCount);assertTrue(s.automaticCompletion);assertTrue(s.signalsComplete);assertEquals(4850L,s.finalSignalMs)
    }
    @Test fun initialParkAndBriefGearNoiseCannotCompleteOrCountAnEpisode() {
        val tracker=ParkingEpisodeTracker(0);for(t in 0L..2000 step 100) tracker.accept(frame(t,Gear.PARK,0.0));assertFalse(tracker.snapshot().automaticCompletion)
        val other=ParkingEpisodeTracker(0)
        for(t in 0L..2000 step 100) other.accept(frame(t,if(t in 800..1000) Gear.REVERSE else Gear.DRIVE))
        assertEquals(0,other.snapshot().reverseToDriveCount)
    }
    @Test fun signalGapsAndMissingVehicleSignalsAreLatchedAsIncomplete() {
        val tracker=ParkingEpisodeTracker(0);for(t in 0L..1000 step 100) tracker.accept(frame(t));tracker.accept(frame(3000));assertFalse(tracker.snapshot().signalsComplete)
        tracker.accept(frame(3100).copy(gear=null));assertTrue(tracker.snapshot().issues.isNotEmpty())
    }
    @Test fun fullBoundaryDetectorReadsPixelsAndBlankInputCannotBeScoredByManualCoordinates() {
        val detector=ParkingSlotAnalyzer();val f=SyntheticParkingSlotFrames.render()
        val result=detector.analyze(f,SyntheticParkingSlotFrames.calibration)
        assertNotNull(result.reason,result.placement);assertTrue(result.placement!!.inside==true);assertTrue(result.placement!!.angleDeg<1)
        val good=(0..4).map {SyntheticParkingSlotFrames.render(it*150L)}
        assertNull(ParkingCaptureFrameCheck.inspect(good,slot,body))
        val bad=(0..4).map {SyntheticParkingSlotFrames.render(it*150L,blank=true)}
        assertNotNull(ParkingCaptureFrameCheck.inspect(bad,slot,body));assertNull(detector.analyze(bad.last(),SyntheticParkingSlotFrames.calibration).placement)
    }
    @Test fun movingEndFramesAreRejectedAndSingleImageIsInsufficient() {
        val frames=(0..4).map {SyntheticParkingSlotFrames.render(it*150L,shiftX=it*18.0)}
        assertNotNull(ParkingCaptureFrameCheck.inspect(frames,slot,body));assertNotNull(ParkingCaptureFrameCheck.inspect(frames.take(1),slot,body))
    }    @Test fun startupSignalLossCannotProduceACompleteCount() {
        val t=ParkingEpisodeTracker(0);t.accept(frame(800));for(i in 900L..2500 step 100) t.accept(frame(i));assertFalse(t.snapshot().signalsComplete)
        val q=ParkingEpisodeTracker(0);q.accept(frame(0).copy(speed=null));for(i in 100L..1500 step 100) q.accept(frame(i));assertFalse(q.snapshot().signalsComplete)
    }

}
