package com.easycar.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProvisionalReferenceTest {
    private val root=File("../app/src/main/assets")
    private val data=File(root,"evaluation/arbitrary-reference-v1.json").readText()
    private val config=File(root,"evaluation/evaluation-policy-prototype.json").readText()
    private val policy=EvaluationPolicy.fromJson(config,data)
    private val profile=ReferenceProfile.fromJson(File(root,"profiles/arbitrary-prototype-v1.json").readText())
    private val realContext=PracticeContext(null,null,dataSource="Pleos 차량 서비스 시험")
    private fun samples()=(0..40).map {Motion(it*100L,3.0,0.0,Gear.DRIVE,0.0,1.0,1.0,0.0,false)}
    @Test fun scoreRefinementNeverDuplicatesVoiceOrExtendsTheCardLifetime() {
        var now=1000L
        val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=now})
        val initial=DeterministicScorer(profile,policy).evaluate(ManeuverType.START,samples(),realContext)
        f.publish(initial,false)
        f.process(GuidanceConditions(true,false,true,false,false),false,null){true}
        f.finished(initial.id)
        now=12000L
        assertTrue(f.refine(initial.copy(score=88,coaching="추가 관측 문구")))
        assertEquals(1,f.receipts.size);assertEquals(88,f.latest!!.score)
        assertEquals(1000L,f.latest!!.publishedAtMs);assertEquals(initial.coaching,f.latest!!.text)
        assertEquals(VoiceState.SPOKEN,f.latest!!.voiceState)
        assertNull(f.publish(initial,false));assertNull(f.process(GuidanceConditions(true,false,true,false,false),false,null){error("must not replay")})
    }
    @Test fun arbitraryDatasetIsMandatoryAndCannotClaimExpertValidation() {
        assertFalse(policy.realWorldScoringEnabled);assertTrue(policy.provisionalEnabled);assertTrue(policy.limits.isEmpty())
        assertTrue(policy.datasetNotice!!.contains("임의"));assertTrue(policy.datasetNotice!!.contains("개선"))
        assertTrue(runCatching {EvaluationPolicy.fromJson(config)}.isFailure)
        assertTrue(runCatching {EvaluationPolicy.fromJson(config,data.replace("\"expert_validated\": false","\"expert_validated\": true"))}.isFailure)
        assertTrue(runCatching {EvaluationPolicy.fromJson(config,data.replace(policy.referenceDatasetId!!,"wrong-version"))}.isFailure)
    }
    @Test fun provisionalScoreIsUsableWithoutPretendingVehicleCalibrationOrExpertApproval() {
        val r=DeterministicScorer(profile,policy).evaluate(ManeuverType.START,samples(),realContext)
        assertEquals(100,r.score);assertEquals("PROVISIONAL",r.scoreKind)
        assertEquals(policy.datasetNotice,r.scoreBasis);assertTrue(r.coaching.contains("임의 참조"))
    }
    @Test fun absentRequiredSignalsStillHoldAndExplainTheActualCause() {
        val r=DeterministicScorer(profile,policy).evaluate(ManeuverType.START,samples().map {it.copy(speedMps=null,acceleration=null,jerk=null)},realContext)
        assertNull(r.score);assertEquals("PROVISIONAL",r.scoreKind)
        assertTrue(r.coaching.contains("신호"));assertFalse(r.coaching.contains("필요한 정보가 부족해"))
    }
    @Test fun lateMissingDerivativesAreNeverRemovedAsGearWarmup() {
        val r=DeterministicScorer(profile,policy).evaluate(ManeuverType.START,samples().mapIndexed {i,m->if(i in 10..20) m.copy(acceleration=null,jerk=null) else m},realContext)
        assertNull(r.score);assertTrue(r.holdReasons.any {it.contains("충족률")})
    }
    @Test fun receiptTimestampedSteeringStillHoldsItsMetricUnderProvisionalRules() {
        val r=DeterministicScorer(profile,policy).evaluate(ManeuverType.LEFT,samples().map {it.copy(steeringRate=null,steeringTimingReliable=false)},realContext)
        assertNull(r.score);assertTrue(r.holdReasons.any {it.contains("핸들 센서 측정 시각")})
    }
    @Test fun arbitraryLimitsNeverLeakIntoExpertOrDraftProfiles() {
        for(status in listOf("EXPERT_VALIDATED","DRAFT_REQUIRES_EXPERT_VALIDATION")) {
            val r=DeterministicScorer(profile.copy(validationStatus=status),policy).evaluate(ManeuverType.START,samples(),realContext)
            assertNull(r.score);assertNotEquals("PROVISIONAL",r.scoreKind)
        }
    }
    @Test fun oneSubmissionDriveScoresAllSevenWithTheArbitraryNotice() {
        val c=DemoCatalog.case("auto-drive");val e=CoachEngine(profile,c.context,evaluationPolicy=policy)
        c.frames.forEach {frame->e.accept(frame).filter {isParking(it.type)}.forEach {r->
            e.refineParking(ParkingLineAnalyzer().analyze(SyntheticParkingFrames.render(r.endMs,1.0),SyntheticParkingFrames.calibration))
        }}
        val seven=listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.STOP,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK)
        assertEquals(seven,e.results.map {it.type})
        e.results.forEach {assertNotNull("${it.type}: ${it.holdReasons}",it.score);assertEquals("PROVISIONAL",it.scoreKind);assertEquals(policy.datasetNotice,it.scoreBasis)}
        assertEquals(listOf(12,2),e.results.filter {isParking(it.type)}.map {it.deductions.first {d->d.key=="gear_repetition"}.points})
    }
    private fun oldScorer():DeterministicScorer {
        val archive=File("../docs/validation")
        return DeterministicScorer(ReferenceProfile.fromJson(File(archive,"v5-arbitrary-prototype-v1.json").readText()),
            EvaluationPolicy.fromJson(File(archive,"v5-evaluation-policy-prototype.json").readText(),File(archive,"v5-arbitrary-reference-v1.json").readText()))
    }
    @Test fun moderateLaunchNowFitsAndStrongerAccelerationStillDeducts() {
        val moderate=samples().map {it.copy(acceleration=2.8,jerk=2.0)}
        val scorer=DeterministicScorer(profile,policy)
        val before=oldScorer().evaluate(ManeuverType.START,moderate,realContext)
        val after=scorer.evaluate(ManeuverType.START,moderate,realContext)
        assertTrue(before.score!!<100);assertEquals(100,after.score)
        val stronger=scorer.evaluate(ManeuverType.START,moderate.map {it.copy(acceleration=4.5)},realContext)
        assertTrue(stronger.score!! in 1..99)
        assertNotEquals(before.profileVersion,after.profileVersion)
    }
    @Test fun moderateBrakingNoLongerTriggersAnUnknownHarshBrakingHold() {
        val moderate=samples().map {it.copy(acceleration=-3.0,jerk=2.0)}
        assertNull(oldScorer().evaluate(ManeuverType.STOP,moderate,realContext).score)
        assertEquals(100,DeterministicScorer(profile,policy).evaluate(ManeuverType.STOP,moderate,realContext).score)
        val harsh=DeterministicScorer(profile,policy).evaluate(ManeuverType.STOP,moderate.map {it.copy(acceleration=-6.0)},realContext)
        assertNotNull(harsh.score);assertTrue(harsh.observation!!.contains("과실"))
        val conservative=DeterministicScorer(profile,policy.copy(scoreUnconfirmedBrakingObservations=false)).evaluate(ManeuverType.STOP,moderate.map {it.copy(acceleration=-6.0)},realContext)
        assertNull(conservative.score);assertTrue(conservative.holdReasons.any {it.contains("감속 원인 미확인")})
    }
    @Test fun knownBrakingAndJerkPenaltiesAccumulateMoreGradually() {
        val controlled=realContext.copy(controlled=true)
        val scorer=DeterministicScorer(profile,policy)
        for((type,part) in listOf(ManeuverType.STOP to samples().map {it.copy(acceleration=-4.0,jerk=2.0)},
            ManeuverType.START to samples().map {it.copy(acceleration=1.0,jerk=7.0)})) {
            val before=oldScorer().evaluate(type,part,controlled)
            val after=scorer.evaluate(type,part,controlled)
            assertTrue(after.score!!>before.score!!);assertTrue(after.score!!<100)
        }
    }
    @Test fun observedBrakingComputesNinetyFromExposureAndPublishesAShortReason() {
        val part=(0..18).map {Motion(it*100L,10.0,0.0,Gear.DRIVE,0.0,-4.0,0.0,0.0,false)}
        val r=DeterministicScorer(profile,policy).evaluate(ManeuverType.STOP,part,realContext)
        assertEquals(90,r.score);assertEquals("급감속",FeedbackSummary.reason(r))
        assertTrue(r.observation!!.contains("과실"));assertEquals("PROVISIONAL",r.scoreKind)
        val f=ImmediateFeedback(object:TimeProvider {override fun nowMs()=1000L})
        assertEquals("급감속",f.publish(r,true)!!.reason)
    }
    @Test fun observedBrakingStillHoldsMissingSignals() {
        val part=samples().map {it.copy(speedMps=null,acceleration=-4.0,jerk=0.0)}
        val r=DeterministicScorer(profile,policy).evaluate(ManeuverType.STOP,part,realContext)
        assertNull(r.score);assertEquals("신호 부족",FeedbackSummary.reason(r))
    }
    @Test fun observationModeRequiresItsExplicitMeaningAndNeverOverridesExpertCauseGate() {
        assertTrue(runCatching {EvaluationPolicy.fromJson(config.replace("OBSERVED_CONTROL_SMOOTHNESS_NOT_FAULT","WRONG_MEANING"),data)}.isFailure)
        val expert=profile.copy(validationStatus="EXPERT_VALIDATED")
        val expertPolicy=policy.copy(realWorldScoringEnabled=true,limits=policy.provisionalLimits)
        val r=DeterministicScorer(expert,expertPolicy).evaluate(ManeuverType.STOP,samples().map {it.copy(acceleration=-4.0,jerk=0.0)},PracticeContext(profile.courseId,profile.vehicleId))
        assertNull(r.score);assertTrue(r.holdReasons.any {it.contains("감속 원인 미확인")})
    }
    @Test fun briefSubThresholdExposureDoesNotGetAHarshBrakingReason() {
        val r=DeterministicScorer(profile,policy).evaluate(ManeuverType.STOP,samples().mapIndexed {i,m->m.copy(acceleration=if(i in 10..11) -4.0 else 0.0,jerk=0.0)},realContext)
        assertEquals(100,r.score);assertNull(FeedbackSummary.reason(r))
    }
    @Test fun recordedVehicleServiceFramesCanNowScoreTheObservedStop() {
        val root=com.google.gson.JsonParser.parseString(File("../docs/validation/localhost-live-v5-session.json").readText()).asJsonObject
        val frames=com.google.gson.Gson().fromJson(root["frames"],Array<VehicleFrame>::class.java)
        assertTrue(frames.filter {it.speed!=null}.all {it.speed!!.source.startsWith("AAOS")})
        val engine=CoachEngine(profile,PracticeContext(null,null,dataSource=root["source"].asString),evaluationPolicy=policy)
        frames.forEach {engine.accept(it)}
        val stop=engine.results.single {it.type==ManeuverType.STOP}
        assertNotNull("${stop.holdReasons}",stop.score);assertEquals("급감속",FeedbackSummary.reason(stop))
        File("build/reports/observed-stop-replay-v8.json").apply {parentFile.mkdirs();writeText(com.google.gson.Gson().toJson(stop))}
    }
}
