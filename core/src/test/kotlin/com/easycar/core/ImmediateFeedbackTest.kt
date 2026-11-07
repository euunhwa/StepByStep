package com.easycar.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ImmediateFeedbackTest {
    @Test fun demoKeepsPendingSpeechBeyondExpiryAndRespectsTheSelectedVoiceInterval() {
        var now=0L
        val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now},FeedbackConfig(minimumIntervalMs=30000))
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        val ok=GuidanceConditions(true,false,true,false,false)
        f.publish(r,false)
        assertNotNull(f.process(ok,false,null,expirePending=false){true})
        f.finished(r.id)
        f.publish(r.copy(id="next"),false)
        now=20000
        assertNull(f.process(ok,false,null,expirePending=false){error("Voice interval must still apply")})
        assertTrue(f.hasPendingVoice)
        assertEquals(VoiceState.DEFERRED,f.latest!!.voiceState)
        now=30000
        assertNotNull(f.process(ok,false,null,expirePending=false){true})
        assertEquals(VoiceState.SPEAKING,f.latest!!.voiceState)
    }
    @Test fun acceptedSpeechRemainsPendingUntilTheActualCompletionCallback() {
        var now=0L
        val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        val ok=GuidanceConditions(true,false,true,false,false)
        f.publish(r,false)
        f.process(ok,false,null,expirePending=false){true}
        now=60000
        assertTrue(f.hasPendingVoice)
        assertNull(f.process(ok,true,null,expirePending=false){error("Already speaking")})
        assertEquals(VoiceState.SPEAKING,f.latest!!.voiceState)
        f.finished(r.id)
        assertFalse(f.hasPendingVoice)
        assertEquals(VoiceState.SPOKEN,f.latest!!.voiceState)
    }
    @Test fun demoWaitingNeverBypassesTurningOrPriorityAudio() {
        var now=0L
        val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        val ok=GuidanceConditions(true,false,true,false,false)
        f.publish(r,false);now=40000
        assertNull(f.process(ok.copy(reversingOrTurning=true),false,null,expirePending=false){error("Turning")})
        assertNull(f.process(ok.copy(priorityAudioIdle=false),false,null,expirePending=false){error("Priority audio")})
        assertTrue(f.hasPendingVoice)
        assertNotNull(f.process(ok,false,null,expirePending=false){true})
    }
    @Test fun unavailableOrMutedSpeechDoesNotLeaveDemoWaitingForeverOrPretendCompletion() {
        val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=0L})
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        f.publish(r,false)
        assertTrue(f.hasPendingVoice)
        f.failPending("음성 엔진 초기화 실패")
        assertFalse(f.hasPendingVoice)
        assertEquals(VoiceState.FAILED,f.latest!!.voiceState)
        f.publish(r.copy(id="muted"),true)
        assertFalse(f.hasPendingVoice)
        assertEquals(VoiceState.MUTED,f.latest!!.voiceState)
    }
    @Test fun cardExpiresWhileHistoryAndLateSpeechRemainAvailable() {
        var now=1000L;val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        f.publish(r,false)
        assertEquals(r.id,f.visible!!.resultId)
        now=5999;assertNotNull(f.visible)
        now=6000;assertNull(f.visible)
        f.started(r.id);f.finished(r.id);f.refine(r.copy(score=88))
        assertNull("late speech/refinement must not revive the card",f.visible)
        assertEquals(1,f.receipts.size);assertEquals(VoiceState.SPOKEN,f.latest!!.voiceState)
        assertEquals(88,f.latest!!.score);assertEquals(1000L,f.latest!!.publishedAtMs)
    }
    @Test fun expiredCardDoesNotPreventTheNextManeuverFromPublishing() {
        var now=0L;val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        f.publish(r,true);now=5000;assertNull(f.visible)
        now=8000;f.publish(r.copy(id="next-maneuver",type=ManeuverType.RIGHT),true)
        assertEquals("next-maneuver",f.visible!!.resultId);assertEquals(2,f.receipts.size)
        now=13000;assertNull(f.visible);assertEquals(2,f.receipts.size)
    }
    private fun profile()=ReferenceProfile.fromJson(File("../app/src/main/assets/profiles/demo-v2.json").readText())
    private fun events(id:String):Pair<DemoCase,List<ManeuverResult>> {
        val c=DemoCatalog.case(id);val e=CoachEngine(profile(),c.context)
        return c to c.frames.flatMap {e.accept(it)}
    }
    @Test fun everyRequestedSituationFinishesBeforeSessionEnds() {
        val pairs=listOf("left" to ManeuverType.LEFT,"right" to ManeuverType.RIGHT,"uturn" to ManeuverType.U_TURN,
            "start-smooth" to ManeuverType.START,"stop-smooth" to ManeuverType.STOP,
            "park-t_park-3-normal" to ManeuverType.T_PARK,"park-parallel_park-3-normal" to ManeuverType.PARALLEL_PARK)
        for((id,type) in pairs) {
            val (c,completed)=events(id)
            val r=completed.single {it.type==type}
            assertTrue("$type is emitted while session is active",r.endMs<c.frames.last().timeMs)
            if(!isParking(type)) assertNotNull("$type ${r.holdReasons}",r.score)
        }
    }
    @Test fun completeDriveRecognizesAllFiveNonParkingSituationsWithoutLabels() {
        val (_,rs)=events("drive-course")
        assertEquals(listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.STOP),rs.map {it.type})
        assertTrue(rs.all {it.score!=null})
    }
    @Test fun visualFeedbackIsImmediateEvenWithMutedOrUnavailableVoice() {
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        val clock=object:TimeProvider {override fun nowMs()=1000L}
        val f=ImmediateFeedback(clock)
        assertEquals(r.id,f.publish(r,true)?.resultId);assertEquals(r.coaching,f.latest?.text)
        assertEquals(VoiceState.MUTED,f.latest?.voiceState)
        val notReady=ImmediateFeedback(clock);notReady.publish(r,false)
        assertNull(notReady.process(GuidanceConditions(true,false,true,false,false),false,null){false})
        assertEquals(r.id,notReady.latest?.resultId);assertEquals(VoiceState.DEFERRED,notReady.latest?.voiceState)
    }
    @Test fun everyRecognizedTypeGetsItsOwnFeedbackWithoutFocusFiltering() {
        var now=0L;val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        val rs=events("left").second
        for(r in rs) {assertEquals(r.id,f.publish(r,false)?.resultId);now+=10000}
        assertEquals(rs.map {it.type},f.receipts.map {it.type})
        assertNull(f.publish(rs.last(),false))
    }
    @Test fun voiceWaitsForActualPermissionAndExpiresWithoutHidingResult() {
        var now=0L;val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        val r=events("right").second.first {it.type==ManeuverType.RIGHT};f.publish(r,false)
        val ok=GuidanceConditions(true,false,true,false,false)
        assertNull(f.process(ok.copy(priorityAudioIdle=null),false,null){error("must not speak")})
        assertNull(f.process(ok.copy(reversingOrTurning=true),false,null){error("must not speak")})
        assertNull(f.process(ok,false,null){false});assertEquals(r.coaching,f.latest?.text)
        now=19000;assertNull(f.process(ok,false,null){error("stale speech")})
        assertEquals(VoiceState.EXPIRED,f.latest?.voiceState);assertEquals(r.coaching,f.latest?.text)
    }
    @Test fun startAndStopAreNotBlockedByMissingSteering() {
        val c=DemoCatalog.case("smooth-start-stop");val e=CoachEngine(profile(),c.context)
        val events=c.frames.flatMap {e.accept(it.copy(steering=null,heading=null))}
        for(type in listOf(ManeuverType.START,ManeuverType.STOP)) assertNotNull(events.first {it.type==type}.score)
    }
    @Test fun parkingEmitsOnceThenRefinesActualPixelsWithoutAnotherVoiceEvent() {
        val c=DemoCatalog.case("park-t_park-3-normal");val e=CoachEngine(profile(),c.context)
        val rs=c.frames.flatMap {e.accept(it)};val p=rs.single {isParking(it.type)}
        assertNull(p.score);assertEquals(12,p.deductions.first {it.key=="gear_repetition"}.points)
        val evidence=ParkingLineAnalyzer().analyze(SyntheticParkingFrames.render(p.endMs,9.0),SyntheticParkingFrames.calibration)
        val refined=e.refineParking(evidence)!!;assertEquals(p.id,refined.id);assertNotNull(refined.score)
        assertTrue(refined.deductions.any {it.key=="alignment"});assertEquals(1,e.results.count {isParking(it.type)})
        val future=evidence.copy(frameTimeMs=p.endMs+100);assertNull(e.refineParking(future)!!.score)
    }
    @Test fun receiptTimestampedSteeringCanDetectCandidateButNeverInventsSensorRate() {
        val c=DemoCatalog.case("right");val pipe=TelemetryPipeline(DetectionConfig())
        val motions=c.frames.mapNotNull {f->pipe.accept(f.copy(steering=f.steering?.copy(quality=Quality.ESTIMATED_TIME,timing=SignalTiming.RECEIPT_ESTIMATE)))}
        assertTrue(motions.any {it.steeringDeg!=null});assertTrue(motions.all {it.steeringRate==null && !it.steeringTimingReliable})
    }
    @Test fun phaseEvidenceComesFromSignalsAndKeepsDeductionTotals() {
        val r=events("fast-left").second.first {it.type==ManeuverType.LEFT}
        assertTrue(r.assessments!!.filter {it.key=="speed"}.map {it.phase}.containsAll(listOf("진입","회전","탈출")))
        assertEquals(100-r.deductions.sumOf {it.points},r.score)
        assertTrue(r.deductions.any {it.title.contains("속도") && it.measured>it.allowed})
    }
    @Test fun rotationDecelerationDoesNotIncludeBrakingBeforeSteeringBegins() {
        val samples=(0..30).map {i->Motion(i*100L,3.0,if(i in 10..20) 90.0 else 0.0,Gear.DRIVE,0.0,if(i<9) -4.0 else 0.0,0.0,0.0,false)}
        val r=DeterministicScorer(profile()).evaluate(ManeuverType.LEFT,samples,DemoCatalog.case("left").context)
        val rotation=r.assessments!!.single {it.key=="deceleration"}
        assertEquals(0.0,rotation.measured!!,.0001)
        assertTrue(r.deductions.none {it.key=="deceleration"})
    }
    @Test fun stoppedWithTurnedWheelCanSpeakButMovingTurnAndReverseMustWait() {
        val m=Motion(1000,0.0,180.0,Gear.PARK,null,null,null,null,false)
        val r=events("left").second.first {it.type==ManeuverType.LEFT}
        val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=1000L});f.publish(r,false)
        assertNotNull(f.process(guidanceConditions(m,true,false),false,null){true})
        assertTrue(guidanceConditions(m.copy(speedMps=3.0,gear=Gear.DRIVE),true,false).reversingOrTurning)
        assertTrue(guidanceConditions(m.copy(gear=Gear.REVERSE),true,false).reversingOrTurning)
        assertFalse(guidanceConditions(m.copy(speedMps=null,steeringDeg=0.0),true,false).stationaryOrStraight)
    }
}
