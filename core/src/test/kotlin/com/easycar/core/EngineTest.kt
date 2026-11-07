package com.easycar.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class EngineTest {
    private fun profile()=ReferenceProfile.fromJson(File("../app/src/main/assets/profiles/demo-v1.json").readText())
    private fun run(c:DemoCase,align:Boolean=true):List<ManeuverResult> {
        val e=CoachEngine(profile(),c.context)
        c.frames.forEach { e.accept(it) }
        val vision=if(align && c.videoAngle!=null) ParkingLineAnalyzer().analyze(SyntheticParkingFrames.render(e.results.lastOrNull {isParking(it.type)}?.endMs ?: c.frames.last().timeMs,c.videoAngle,c.videoShift,c.videoOccluded),SyntheticParkingFrames.calibration) else null
        return e.finish(vision)
    }
    @Test fun weightedParkingCountsUseSameDetectorAndScorer() {
        for(type in listOf(ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK)) for(n in 0..5) {
            val c=DemoCatalog.case("park-${type.name.lowercase()}-$n-normal")
            val r=run(c).single { it.type==type }
            assertEquals("$type $n",n,r.reverseToDriveCount)
            assertEquals(listOf(0,2,6,12,20,30)[n],r.deductions.firstOrNull { it.key=="gear_repetition" }?.points ?: 0)
            assertNotNull("$type $n ${r.holdReasons}",r.score)
            assertEquals(100-r.deductions.sumOf { it.points },r.score)
        }
    }
    @Test fun stableGearsDebounceDuplicatesNeutralAndShortOscillation() {
        val c=GearTransitionCounter()
        for(t in 0L..1000L step 100) c.update(Gear.REVERSE,t)
        for(t in 1100L..1800L step 100) c.update(Gear.NEUTRAL,t)
        for(t in 1900L..2800L step 100) { c.update(Gear.DRIVE,t);c.update(Gear.DRIVE,t) }
        c.update(Gear.REVERSE,2900);c.update(Gear.DRIVE,3000)
        for(t in 3100L..4000L step 100) c.update(Gear.DRIVE,t)
        assertEquals(1,c.reverseToDriveCount)
    }
    @Test fun missingFinalEvidenceHoldsTotalButKeepsCountPenalty() {
        val r=run(DemoCatalog.case("park-t_park-3-normal"),false).single { isParking(it.type) }
        assertNull(r.score);assertEquals(12,r.deductions.first { it.key=="gear_repetition" }.points)
    }
    @Test fun turnRecognitionRequiresHeadingAndRejectsCurveLaneChange() {
        for((id,type) in listOf("left" to ManeuverType.LEFT,"right" to ManeuverType.RIGHT,"uturn" to ManeuverType.U_TURN)) assertTrue("$id",run(DemoCatalog.case(id)).any { it.type==type && it.score!=null })
        for(id in listOf("low-heading","curve","lane-change")) assertFalse(id,run(DemoCatalog.case(id)).any { it.type in listOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN) && it.score!=null })
    }
    @Test fun strongerExceedanceNeverReducesPenalty() {
        val p=profile(); val scorer=DeterministicScorer(p); val ctx=DemoCatalog.case("left").context
        fun score(speed:Double,seconds:Int)=scorer.evaluate(ManeuverType.LEFT,(0..100).map { i->Motion(i*100L,if(i<seconds*10) speed else 2.0,30.0,Gear.DRIVE,90.0,0.0,0.0,0.0,false) },ctx).deductions.sumOf { it.points }
        assertTrue(score(9.0,4)>=score(6.0,4));assertTrue(score(6.0,8)>=score(6.0,4))
    }
    @Test fun gapsAndBadTimesDoNotProduceFakeDerivatives() {
        val pipe=TelemetryPipeline(DetectionConfig())
        fun frame(t:Long,v:Double)=VehicleFrame(t,Signal(v,t,t,"m/s","test"),Signal(0.0,t,t,"degree","test"),Signal(Gear.DRIVE,t,t,"gear","test"),null)
        pipe.accept(frame(0,0.0));pipe.accept(frame(100,.1))
        assertNull(pipe.accept(frame(50,30.0)))
        val gap=pipe.accept(frame(5000,20.0))!!
        assertTrue(gap.gap);assertNull(gap.acceleration);assertNull(gap.jerk)
        val r=run(DemoCatalog.case("gap"));assertTrue(r.any { it.lifecycle==Lifecycle.INTERRUPTED });assertFalse(r.any { it.type==ManeuverType.START && (it.measurements["maxAcceleration"] ?: 0.0)>10 })
    }
    @Test fun unknownHarshBrakingDoesNotAssignFault() {
        val r=run(DemoCatalog.case("unknown-brake")).first { it.type==ManeuverType.STOP }
        assertNull(r.score);assertTrue(r.holdReasons.any { it.contains("원인") });assertTrue(r.deductions.isEmpty())
        assertEquals("합성 데모 데이터",r.source)
    }
    @Test fun playbackSchedulingDoesNotEnterAnalysis() {
        val c=DemoCatalog.case("park-t_park-3-normal")
        assertEquals(run(c),run(c.copy(frames=c.frames.flatMap { listOf(it,it) })))
        for(ca in DemoCatalog.cases) for(r in run(ca)) if(r.score!=null) { assertTrue(r.score in 0..100);assertEquals(100-r.deductions.sumOf { it.points },r.score) }
    }
    @Test fun detectsPixelsAtKnownAnglesAndIntrusionAndNoLines() {
        val a=ParkingLineAnalyzer()
        for(angle in listOf(-16.0,-8.0,0.0,4.0,12.0,20.0)) {
            val r=a.analyze(SyntheticParkingFrames.render(1000,angle),SyntheticParkingFrames.calibration)
            assertNotNull("$angle ${r.reason}",r.angleDeg);assertTrue("$angle ${r.angleDeg}",abs(angle-r.angleDeg!!)<1.1)
        }
        assertTrue(a.analyze(SyntheticParkingFrames.render(0,0.0,75.0),SyntheticParkingFrames.calibration).intrusion==true)
        assertNull(a.analyze(SyntheticParkingFrames.render(0,0.0,noLines=true),SyntheticParkingFrames.calibration).angleDeg)
        assertNull(a.analyze(SyntheticParkingFrames.render(0,0.0,occluded=true),SyntheticParkingFrames.calibration).angleDeg)
        assertNull(a.analyze(SyntheticParkingFrames.render(0,0.0),null).angleDeg)
    }
    @Test fun restrictionsAndAudioFailClosedAndDeduplicate() {
        assertFalse(UxState().allowsReview());assertFalse(UxState(true,true).allowsReview());assertTrue(UxState(true,false).allowsReview())
        var now=0L; val gate=CoachingGate(object:TimeProvider { override fun nowMs()=now })
        gate.enqueue("one","안내");gate.enqueue("one","안내")
        val ok=GuidanceConditions(true,false,true,true,false)
        assertNull(gate.next(ok.copy(priorityAudioIdle=null)));assertNull(gate.next(ok.copy(audioFocusGranted=false)));assertNull(gate.next(ok.copy(reversingOrTurning=true)))
        assertNotNull(gate.next(ok));assertNull(gate.next(ok));gate.enqueue("one","안내");now=31000;assertNull(gate.next(ok))
        gate.enqueue("old","오래된 안내");now=90000;assertNull(gate.next(ok))
        gate.enqueue("focus","포커스 확인")
        assertNull(gate.deliver(ok) {false})
        assertNotNull(gate.deliver(ok) {true})
        assertNull(gate.deliver(ok) {true})
    }
    @Test fun profileSchemaAndUnknownConditionsAreChecked() {
        val text=File("../app/src/main/assets/profiles/demo-v1.json").readText()
        assertThrows(Exception::class.java) { ReferenceProfile.fromJson(text.replace("\"maxAbs\": 4.0","\"maxAbs\": -1.0")) }
        assertThrows(Exception::class.java) { ReferenceProfile.fromJson(text.replace("\"schemaVersion\": 1,","")) }
        val c=DemoCatalog.case("left");assertTrue(run(c.copy(context=c.context.copy(courseId=null))).all { it.score==null })
    }
    @Test fun singleSampleNoiseDoesNotCreateHarshLaunch() {
        val c=DemoCatalog.case("smooth-start-stop")
        val noisy=c.copy(frames=c.frames.mapIndexed { i,f->if(i==70) f.copy(speed=f.speed?.copy(value=25.0)) else f })
        assertEquals(run(c),run(noisy))
        val bad=Signal(3.0,1000,1100,"m/s","test")
        assertFalse(bad.usable(1000,750))
    }
    @Test fun sameCourseBadSpeedAndRoughSteeringCarryMeasuredDeductions() {
        val normal=run(DemoCatalog.case("left")).first {it.type==ManeuverType.LEFT}
        val fast=run(DemoCatalog.case("fast-left")).first {it.type==ManeuverType.LEFT}
        assertTrue(fast.deductions.any {it.key=="speed"});assertTrue(fast.score!!<normal.score!!)
        val parking=run(DemoCatalog.case("park-t_park-3-speed")).single {isParking(it.type)}
        assertTrue(parking.deductions.any {it.key=="speed"});assertEquals(12,parking.deductions.first {it.key=="gear_repetition"}.points)
    }
    @Test fun missingSteeringStillPreservesConfirmedParkingCountAndHoldsTotal() {
        val c=DemoCatalog.case("park-t_park-3-normal")
        val result=run(c.copy(frames=c.frames.map {it.copy(steering=null)})).single {isParking(it.type)}
        assertEquals(3,result.reverseToDriveCount);assertNull(result.score)
        assertEquals(12,result.deductions.first {it.key=="gear_repetition"}.points)
    }
    @Test fun onChangeStateReadDoesNotPretendToBeNewSensorMeasurement() {
        val old=Signal(Gear.REVERSE,100,5000,"gear","AAOS",timing=SignalTiming.CURRENT_STATE_READ)
        assertTrue(old.usable(5100,750));assertEquals(100,old.measuredMs)
        assertFalse(old.usable(6000,750))
        assertFalse(old.copy(timing=SignalTiming.MEASUREMENT).usable(5100,750))
    }
}
