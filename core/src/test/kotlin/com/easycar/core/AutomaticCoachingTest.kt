package com.easycar.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AutomaticCoachingTest {
    private val profile=ReferenceProfile.fromJson(File("../app/src/main/assets/profiles/auto-coach-draft.json").readText())
    private val policy=EvaluationPolicy.fromJson(File("../app/src/main/assets/evaluation/evaluation-policy-draft.json").readText())
    private val neutral=PracticeContext(null,null,dataSource="자동 관측 테스트")
    private val seven=listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.STOP,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK)
    private fun observe(frames:List<VehicleFrame>,context:PracticeContext=neutral):List<ManeuverResult> {
        val e=CoachEngine(profile,context,evaluationPolicy=policy)
        return frames.flatMap {e.accept(it)}
    }
    @Test fun oneUnselectedSessionRecognizesAllSevenAndPublishesBeforeSessionEnd() {
        val frames=DemoCatalog.case("auto-drive").frames
        val e=CoachEngine(profile,neutral,evaluationPolicy=policy)
        var now=0L;val feedback=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        frames.forEach {frame->now=frame.timeMs;e.accept(frame).forEach {r->
            assertEquals("emitted on current input without future frames",frame.timeMs,r.endMs)
            assertNull(r.score);feedback.publish(r,true)
        }}
        assertEquals(seven,e.results.map {it.type})
        assertEquals(seven,feedback.receipts.map {it.type})
        assertTrue(e.results.all {it.endMs<frames.last().timeMs && it.evaluationPolicyVersion==policy.version})
        assertEquals(listOf(3,1),e.results.filter {isParking(it.type)}.map {it.reverseToDriveCount})
        assertEquals(listOf(12,2),e.results.filter {isParking(it.type)}.map {it.deductions.single().points})
        assertEquals(7,e.finish().size)
    }
    @Test fun exportedContinuousInputRecognizesAllSevenWithoutLoadingTruthOrContext() {
        val json=File("../app/src/main/assets/demo/signals/auto-drive.json").readText()
        assertFalse(json.contains("Truth"));assertFalse(json.contains("label"));assertFalse(json.contains("parkingType"));assertFalse(json.contains("focusType"))
        val frames=com.google.gson.Gson().fromJson(json,Array<VehicleFrame>::class.java).toList()
        assertEquals(seven,observe(frames).map {it.type})
    }
    @Test fun steeringReceiptTimeNeverBecomesMeasurementTimeEvenWhenValueQualityIsValid() {
        for(timing in listOf(SignalTiming.RECEIPT_ESTIMATE,SignalTiming.CURRENT_STATE_READ)) {
            val p=TelemetryPipeline(DetectionConfig())
            val ms=DemoCatalog.case("right").frames.mapNotNull {f->p.accept(f.copy(steering=f.steering?.copy(timing=timing,quality=Quality.VALID)))}
            assertTrue(ms.any {it.steeringDeg!=null});assertTrue(ms.all {it.steeringRate==null && !it.steeringTimingReliable})
        }
    }
    @Test fun legacySelectionAndScenarioNameCannotChangeRecognition() {
        val frames=DemoCatalog.case("auto-drive").frames
        val poison=neutral.copy(parkingType=ManeuverType.PARALLEL_PARK,focusType=ManeuverType.STOP,selectionSource="wrong hint")
        assertEquals(seven,observe(frames,poison).map {it.type})
        assertEquals(observe(frames).map {it.type},observe(DemoCatalog.case("auto-drive").copy(id="no-label",context=poison).frames).map {it.type})
    }
    @Test fun physicalSpaceAxesDetermineParkingTypeWithoutSelectedLabel() {
        val t=DemoCatalog.case("park-t_park-2-normal").frames
        val swapped=t.map {f->f.copy(parkingScene=f.parkingScene?.let {s->s.copy(value=s.value.copy(slotAxisDeg=0.0))})}
        assertEquals(ManeuverType.T_PARK,observe(t).single().type)
        assertEquals(ManeuverType.PARALLEL_PARK,observe(swapped).single().type)
        assertEquals(2,observe(swapped).single().reverseToDriveCount)
    }
    @Test fun noSpaceIsUnconfirmedLowSpeedOperationWithActualCountsAndNoInventedPenalty() {
        val r=observe(DemoCatalog.case("parking-space-missing").frames).single()
        assertEquals(ManeuverType.PARKING_UNKNOWN,r.type);assertNull(r.score)
        assertEquals(2,r.reverseToDriveCount);assertTrue(r.deductions.none {it.key=="gear_repetition"})
        assertFalse(r.recognition!!.parkingEpisodeConfirmed)
        assertTrue(r.coaching.contains("보류"))
    }
    @Test fun ambiguousButConfirmedSpaceKeepsCountAndHoldsType() {
        val r=observe(DemoCatalog.case("parking-space-ambiguous").frames).single()
        assertEquals(ManeuverType.PARKING_UNKNOWN,r.type);assertNull(r.score)
        assertTrue(r.recognition!!.parkingEpisodeConfirmed)
        assertEquals(6,r.deductions.single {it.key=="gear_repetition"}.points)
    }
    @Test fun staleUnverifiedAndChangingSpacesNeverBecomeKnownParkingTypes() {
        val frames=DemoCatalog.case("park-t_park-2-normal").frames
        val variants=listOf(
            frames.map {f->f.copy(parkingScene=f.parkingScene?.copy(quality=Quality.LOW_CONFIDENCE))},
            frames.map {f->if(f.timeMs>5000) f.copy(parkingScene=null) else f},
            frames.map {f->f.copy(parkingScene=f.parkingScene?.let {s->s.copy(value=s.value.copy(targetAssociated=false))})},
            frames.map {f->f.copy(parkingScene=f.parkingScene?.let {s->s.copy(value=s.value.copy(targetId=if(f.timeMs>12000) "other" else "first"))})})
        variants.forEach {v->val r=observe(v).single();assertEquals(ManeuverType.PARKING_UNKNOWN,r.type);assertNull(r.score);assertTrue(r.deductions.none {it.key=="gear_repetition"})}
    }
    @Test fun forwardParkingUsesObservedSpaceAndDoesNotRequireReverseGear() {
        val frames=DemoCatalog.case("park-t_park-0-normal").frames.map {f->f.copy(gear=f.gear?.let {if(it.value==Gear.REVERSE) it.copy(value=Gear.DRIVE) else it})}
        val r=observe(frames).single {isParking(it.type)}
        assertEquals(ManeuverType.T_PARK,r.type);assertEquals(0,r.reverseToDriveCount)
    }
    @Test fun incompleteReverseIsNotReportedAsCompletedParking() {
        val frames=DemoCatalog.case("parking-space-missing").frames.takeWhile {it.gear?.value!=Gear.PARK}
        val e=CoachEngine(profile,neutral,evaluationPolicy=policy)
        assertTrue(frames.flatMap {e.accept(it)}.isEmpty())
        val r=e.finish().single();assertEquals(Lifecycle.INTERRUPTED,r.lifecycle);assertEquals(ManeuverType.PARKING_UNKNOWN,r.type);assertNull(r.score)
    }
    @Test fun draftMeasurementsUseLongitudinalReferenceWithoutActivatingScore() {
        val r=observe(DemoCatalog.case("start-rough").frames).first {it.type==ManeuverType.START}
        assertNull(r.score);assertTrue(r.deductions.isEmpty())
        val jerk=r.assessments!!.single {it.key=="longitudinal_jerk"}
        assertEquals(4.13,jerk.referenceValue!!,.0001);assertNull(jerk.allowed);assertNull(jerk.deductionPoints)
        assertNotNull(jerk.measured);assertTrue(r.holdReasons.any {it.contains("전문가")});assertNotNull(jerk.reason)
    }
    @Test fun allConfirmedParkingCountWeightsRemainVisibleWithTotalHeld() {
        for(n in 0..5) {
            val r=observe(DemoCatalog.case("park-t_park-$n-normal").frames).single()
            assertEquals(n,r.reverseToDriveCount);assertNull(r.score)
            val expected=minOf(30,n*(n+1))
            assertEquals(expected,r.assessments!!.single {it.key=="confirmed_R_to_D_count"}.deductionPoints)
            assertEquals(expected,r.deductions.sumOf {it.points})
        }
    }
}
