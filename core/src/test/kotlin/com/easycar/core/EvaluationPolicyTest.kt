package com.easycar.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EvaluationPolicyTest {
    private val json=File("../app/src/main/assets/evaluation/evaluation-policy-draft.json").readText()
    private val draft=EvaluationPolicy.fromJson(json)
    private val profile=ReferenceProfile.fromJson(File("../app/src/main/assets/profiles/auto-coach-draft.json").readText()).copy(validationStatus="EXPERT_VALIDATED")
    // Deliberately artificial test limits: never shipped or accepted as real driving criteria.
    private val approved=draft.copy(realWorldScoringEnabled=true,limits=draft.budgets.values.flatten().flatMap {it.metrics}.distinct().associateWith {MetricLimit(1.0,1.0,1.0,"TEST_ONLY")})
    private val context=PracticeContext(profile.courseId,profile.vehicleId,controlled=true)
    private fun samples(n:Int=40,acc:(Int)->Double={0.0},jerk:(Int)->Double={0.0})=(0..n).map {i->Motion(i*100L,3.0,if(i in 10..30) 90.0 else 0.0,Gear.DRIVE,0.0,acc(i),jerk(i),0.0,false)}
    private fun score(type:ManeuverType,s:List<Motion>,c:PracticeContext=context,p:EvaluationPolicy=approved)=PolicyScorer(profile,p).evaluate(type,s,c,0,null,null,emptyList(),null)
    @Test fun allBudgetsSumToOneHundredAndDraftCannotGrade() {
        assertTrue(draft.budgets.values.all {it.sumOf {g->g.cap}==100})
        assertFalse(draft.realWorldScoringEnabled);assertTrue(draft.limits.isEmpty())
        assertNull(score(ManeuverType.START,samples(),p=draft).score)
    }
    @Test fun correlatedAccelerationAndJerkTakeMaximumAndAssessmentsMatchAppliedTotal() {
        val r=score(ManeuverType.START,samples(acc={3.0},jerk={5.0}))
        assertEquals(0,r.score);assertEquals(1,r.deductions.size)
        assertEquals(100,r.deductions.sumOf {it.points})
        assertEquals(r.deductions.sumOf {it.points},r.assessments!!.sumOf {it.deductionPoints ?: 0})
    }
    @Test fun exposureCannotBeDilutedByAddingNormalTailAndLargerExposureIsMonotonic() {
        fun s(n:Int,level:Double)=samples(n,acc={if(it in 5..8) level else 0.0})
        val small=score(ManeuverType.START,s(40,1.5));val big=score(ManeuverType.START,s(40,2.0))
        assertEquals(big.deductions.sumOf {it.points},score(ManeuverType.START,s(100,2.0)).deductions.sumOf {it.points})
        assertTrue(big.deductions.sumOf {it.points}>small.deductions.sumOf {it.points})
        assertNotNull(big.score)
    }
    @Test fun shorterThanThreeHundredMillisecondsDoesNotDeduct() {
        val r=score(ManeuverType.START,samples(acc={if(it in 5..6) 8.0 else 0.0}))
        assertEquals(100,r.score);assertTrue(r.deductions.isEmpty())
    }
    @Test fun missingGearOrCriticalWindowHoldsTotalInsteadOfGivingHundred() {
        val gearless=score(ManeuverType.START,samples().map {it.copy(gear=null)})
        val entryMissing=score(ManeuverType.START,samples().mapIndexed {i,m->if(i==0) m.copy(speedMps=null) else m})
        assertNull(gearless.score);assertNull(entryMissing.score)
        assertTrue(gearless.holdReasons.any {it.contains("실제 기어")})
    }
    @Test fun unknownBrakingCauseHoldsWholeLinkedGroupAndPreservesMeasurements() {
        val r=score(ManeuverType.STOP,samples(acc={-3.0},jerk={5.0}),context.copy(controlled=false))
        assertNull(r.score);assertTrue(r.deductions.isEmpty())
        assertTrue(r.assessments!!.all {it.deductionPoints==null && it.measured!=null})
        val turn=score(ManeuverType.LEFT,samples(acc={if(it in 10..20) -3.0 else 3.0},jerk={5.0}),context.copy(controlled=false))
        assertNull(turn.score);assertTrue(turn.deductions.none {it.key in setOf("acceleration","jerk")})
    }
    @Test fun turnBudgetsCapAtFortyThirtyThirtyWithNoDoubleCountedAssessments() {
        val r=score(ManeuverType.LEFT,samples(acc={3.0},jerk={5.0}).map {it.copy(steeringRate=4.0)})
        assertEquals(0,r.score);assertEquals(100,r.deductions.sumOf {it.points})
        assertEquals(40,r.deductions.filter {it.key=="speed"}.sumOf {it.points})
        assertEquals(100,r.assessments!!.sumOf {it.deductionPoints ?: 0})
    }
    @Test fun noGearMeansUnknownCountRatherThanObservedZero() {
        val r=score(ManeuverType.T_PARK,samples().map {it.copy(gear=null)})
        val count=r.assessments!!.single {it.key=="confirmed_R_to_D_count"}
        assertNull(count.measured);assertNull(count.deductionPoints);assertNull(r.score)
    }
    @Test fun unavailableRequiredItemNeverRenormalizesOtherGroups() {
        val r=score(ManeuverType.LEFT,samples().map {it.copy(steeringRate=null)})
        assertNull(r.score);assertTrue(r.assessments!!.any {it.key=="steering_rate" && it.measured==null})
        assertTrue(r.deductions.sumOf {it.points}<=40)
    }
    @Test fun unvalidatedProfileCannotActivateDespitePolicyFlag() {
        val r=PolicyScorer(profile.copy(validationStatus="DRAFT_REQUIRES_EXPERT_VALIDATION"),approved)
            .evaluate(ManeuverType.START,samples(),context,0,null,null,emptyList(),null)
        assertNull(r.score)
    }
    @Test fun parserRejectsUnknownMetricsDuplicateMetricsAndBadValidatedLimits() {
        for(bad in listOf(
            json.replace("\"launch_smoothness\"","\"launch_smoothness\"").replaceFirst("\"acceleration\",","\"made_up_metric\","),
            json.replaceFirst("\"longitudinal_jerk\"\n        ]","\"acceleration\"\n        ]"),
            json.dropLast(2)+",\"validated_limits\":{\"acceleration\":{\"validation_status\":\"EXPERT_VALIDATED\",\"bound\":1,\"scale\":0,\"tau_seconds\":1,\"source\":\"TEST\"}}}")) {
            assertTrue(runCatching {EvaluationPolicy.fromJson(bad)}.isFailure)
        }
    }
}
