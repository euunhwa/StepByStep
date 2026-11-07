package com.example.easycar

import com.easycar.core.*
import com.example.easycar.ui.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class UserFacingTextTest {
    private val technical="yaw jerk v×yaw m/s³ AAOS document-derived-1.0"
    private fun result()=ManeuverResult("r1",ManeuverType.T_PARK,0,9000,Lifecycle.COMPLETED,.75,85,emptyList(),
        listOf(Deduction("gear_repetition",technical,15,4.0,"회",1.0,9000,600,"maneuver")),
        4,"document-derived-1.0","AAOS PRIVATE_SOURCE",null,null,emptyMap(),emptyMap(),technical,
        evaluationPolicyVersion="evaluation-document-1.0",scoreKind="PROVISIONAL",scoreBasis=technical)
    private fun query(r:ManeuverResult,profile:String=r.profileVersion)=RecordQueryEngine().execute(
        RecordQueryProtocol.request(RecordQueryAction.EXPLAIN_RESULT,"s1"),
        listOf(RecordQuerySession("s1",0,r.profileVersion,r.source,Lifecycle.COMPLETED,listOf(r))),
        RecordQueryAccess(setOf("s1"),true,true,profile,"evaluation-document-1.0"))
    private fun noTechnical(text:String) {
        listOf("yaw","jerk","v×yaw","m/s","AAOS","document-derived","evaluation-document","PRIVATE","maneuver","600ms").forEach {assertFalse("Unexpected $it in $text",text.contains(it))}
    }
    @Test fun technicalHoldReasonsBecomeEverydayReasonsWithoutChangingStoredResult() {
        val saved=result().copy(score=null,lifecycle=Lifecycle.HELD,
            holdReasons=listOf("가감속·jerk 측정 시각/신호 충족률 부족","차량 좌표계·측정 시각이 검증된 yaw rate 신호 부족"))
        val before=Gson().toJson(saved)
        val shown=userResultText(saved)
        assertTrue(shown.contains("보류"));assertTrue(shown.contains("페달"));assertTrue(shown.contains("회전"))
        noTechnical(shown);assertEquals(before,Gson().toJson(saved))
    }
    @Test fun queryKeepsStoredScoreAndActualReversalCountButNoUnitsOrDeveloperMetadata() {
        val saved=result();val before=Gson().toJson(saved)
        val response=query(saved).copy(answer=technical)
        val shown=userQueryAnswer(response)
        assertTrue(shown.contains("85점"));assertTrue(shown.contains("바꾼 4회"));assertTrue(shown.contains("초과한 3회"));assertTrue(shown.contains("15점 감점"))
        noTechnical(shown);assertEquals(before,Gson().toJson(saved));assertEquals(85,response.result!!.score)
    }
    @Test fun historicalHeldResultStaysHeldAndDoesNotInventAScore() {
        val saved=result().copy(score=null,lifecycle=Lifecycle.HELD,holdReasons=listOf(technical))
        val response=query(saved,"new-current-profile")
        val shown=userQueryAnswer(response)
        assertTrue(shown.contains("보류"));assertTrue(shown.contains("예전에 저장"));assertFalse(shown.contains("85점"))
        noTechnical(shown);assertNull(response.result!!.score)
    }
    @Test fun unknownStatusMessagesAndReasonLabelsNeverFallBackToRawPayloads() {
        RecordQueryStatus.entries.forEach {status->noTechnical(userQueryAnswer(RecordQueryResponse(requestId=null,action=null,status=status,answer=technical)))}
        assertEquals("평가에 필요한 정보가 부족해요.",userHoldReason("UNRECOGNIZED_PRIVATE_REASON"))
        assertEquals("운전 조작",userDeductionTitle("UNRECOGNIZED",technical.replace("yaw jerk", "")))
        assertEquals("회전 중 쏠림",userDeductionTitle("lateral_acceleration","횡가속"))
        assertEquals("주차 위치 치우침",userDeductionTitle("parking_lateral","치우침"))
    }
    @Test fun mixedEvaluationSummaryDoesNotAcquireAnAverageOrExposeProfileNames() {
        val a=result();val b=result().copy(id="r2",profileVersion="old-profile",evaluationPolicyVersion="old-policy")
        val response=RecordQueryEngine().execute(RecordQueryProtocol.request(RecordQueryAction.SUMMARIZE_SESSION,"s1"),
            listOf(RecordQuerySession("s1",0,a.profileVersion,a.source,Lifecycle.COMPLETED,listOf(a,b))),
            RecordQueryAccess(setOf("s1"),true,true,a.profileVersion,a.evaluationPolicyVersion))
        assertNull(response.summary!!.averageScore)
        val shown=userQueryAnswer(response);noTechnical(shown);assertFalse(shown.contains("평균은"))
        assertTrue(userQueryWarnings(response).any {it.contains("전체 평균은 표시하지 않았어요")})
    }
    @Test fun sessionSummaryUsesStoredOutcomesAndFriendlyLabelsOnly() {
        val a=result();val b=result().copy(id="r2",score=null,lifecycle=Lifecycle.HELD,holdReasons=listOf(technical))
        val before=Gson().toJson(listOf(a,b));val shown=userSessionSummary(listOf(a,b)).joinToString(" ")
        assertTrue(shown.contains("85점"));assertTrue(shown.contains("전후진 반복"));assertTrue(shown.contains("1개 동작"))
        noTechnical(shown);assertEquals(before,Gson().toJson(listOf(a,b)))
    }
}
