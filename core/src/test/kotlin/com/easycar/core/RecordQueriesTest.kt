package com.easycar.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordQueriesTest {
    private fun asset(name:String)=File("../app/src/main/assets/$name").readText()
    private val profile=ReferenceProfile.fromJson(asset("profiles/document-derived-v1.json"))
    private val dataset=DocumentDataset.fromJson(asset("datasets/document-v1/recognition.json"),asset("datasets/document-v1/evaluation.json"))
    private val session=RecordQueryDemoData.session(profile,dataset)
    private val access=RecordQueryAccess(setOf(session.id),true,true,profile.version,dataset.evaluationVersion)
    private val engine=RecordQueryEngine()
    private val parking get()=session.results.first {it.type==ManeuverType.T_PARK}
    private fun request(action:RecordQueryAction=RecordQueryAction.EXPLAIN_RESULT,resultId:String?=parking.id,type:ManeuverType?=null)=RecordQueryProtocol.request(action,session.id,resultId,type)
    private fun run(json:String=request(),sessions:List<RecordQuerySession> = listOf(session),scope:RecordQueryAccess=access)=engine.execute(json,sessions,scope)

    @Test fun explainsStoredEightyFiveWithActualScorerFactsWithoutMutation() {
        assertEquals(85,parking.score)
        val original=Gson().toJson(session)
        val answer=run()
        assertEquals(RecordQueryStatus.OK,answer.status)
        assertEquals(85,answer.result!!.score);assertEquals(4,answer.result.reverseToDriveCount)
        assertEquals(15L,answer.result.recordedDeductionTotal);assertEquals(15,answer.result.appliedDeductionTotal)
        assertEquals(true,answer.result.arithmeticMatches)
        assertTrue(answer.answer.contains("초과한 3회"));assertTrue(answer.answer.contains("15점"))
        assertEquals(4.0,answer.result.deductions.single().measured,.001)
        assertEquals(1.0,answer.result.deductions.single().allowed,.001)
        assertEquals(original,Gson().toJson(session))
    }
    @Test fun mostRecentHeldResultIsReturnedInsteadOfAnEarlierGoodScore() {
        val answer=run(request(RecordQueryAction.GET_LAST_RESULT,null))
        assertEquals(RecordQueryStatus.HELD,answer.status);assertEquals("fixture-uturn-held",answer.result!!.id)
        assertNull(answer.result.score);assertFalse(answer.result.finalResult)
        assertTrue(answer.answer.contains("보류"));assertFalse(answer.answer.contains("100점"))
    }
    @Test fun maneuverFilterFindsMostRecentStoredCategory() {
        val answer=run(request(RecordQueryAction.GET_LAST_RESULT,null,ManeuverType.T_PARK))
        assertEquals(85,answer.result!!.score);assertEquals(ManeuverType.T_PARK,answer.result.type)
        assertEquals(RecordQueryStatus.NO_RESULTS,run(request(RecordQueryAction.GET_LAST_RESULT,null,ManeuverType.RIGHT)).status)
    }
    @Test fun parkingCategoryReturnsTheLatestParkingOfAnyKindAndPreservesAnUnknownHold() {
        val json=RecordQueryProtocol.request(RecordQueryAction.GET_LAST_RESULT,session.id,parkingOnly=true)
        assertEquals(85,run(json).result!!.score)
        val parallel=parking.copy(id="parallel",type=ManeuverType.PARALLEL_PARK,startMs=15000,endMs=18000)
        assertEquals("parallel",run(json,listOf(session.copy(results=session.results+parallel))).result!!.id)
        val unknown=parallel.copy(id="held-parking",type=ManeuverType.PARKING_UNKNOWN,score=null,holdReasons=listOf("주차 유형 미확정"),startMs=19000,endMs=22000)
        val held=run(json,listOf(session.copy(results=session.results+parallel+unknown)))
        assertEquals(RecordQueryStatus.HELD,held.status);assertEquals(unknown.id,held.result!!.id);assertNull(held.result.score)
    }
    @Test fun inProgressNumericValueIsNeverPresentedAsAFinalScore() {
        val current=parking.copy(lifecycle=Lifecycle.ACTIVE)
        val answer=run(sessions=listOf(session.copy(results=listOf(current))))
        assertEquals(RecordQueryStatus.IN_PROGRESS,answer.status);assertFalse(answer.result!!.finalResult)
        assertFalse(answer.answer.contains("저장 점수는 85점"))
    }
    @Test fun missingAndOutOfScopeSessionsDoNotRevealTheirExistence() {
        val secret=session.copy(id="secret-session",source="DO_NOT_EXPOSE",createdAt=9999)
        fun lookup(id:String)=RecordQueryProtocol.request(RecordQueryAction.SUMMARIZE_SESSION,id)
        val forbidden=run(lookup(secret.id),listOf(session,secret))
        val absent=run(lookup("absent"),listOf(session,secret))
        assertEquals(forbidden,absent);assertEquals(RecordQueryStatus.NOT_FOUND,forbidden.status)
        assertFalse(RecordQueryProtocol.responseJson(forbidden).contains("DO_NOT_EXPOSE"))
    }
    @Test fun resultIdCannotReadAnotherSelectedSessionImplicitly() {
        val secret=session.copy(id="secret-session",results=listOf(parking.copy(id="secret-result",source="DO_NOT_EXPOSE")))
        val answer=run(request(resultId="secret-result"),listOf(session,secret),access.copy(sessionIds=setOf(session.id,secret.id)))
        assertEquals(RecordQueryStatus.NOT_FOUND,answer.status);assertNull(answer.result)
    }
    @Test fun latestSessionSelectionUsesWallClockSessionOrderNotRelativeEventTimes() {
        val older=session.copy(id="older",createdAt=100,results=listOf(parking.copy(endMs=99999999)))
        val newer=session.copy(id="newer",createdAt=200,results=listOf(parking.copy(id="new-parking",endMs=1000,startMs=0)))
        val json="""{"schemaVersion":1,"requestId":"latest","action":"GET_LAST_RESULT","parameters":{}}"""
        val answer=run(json,listOf(older,newer),access.copy(sessionIds=setOf("older","newer")))
        assertEquals("newer",answer.session!!.id);assertEquals("new-parking",answer.result!!.id)
    }
    @Test fun emptyLatestSessionDoesNotFallBackToOldResults() {
        val empty=session.copy(id="empty",createdAt=2000,results=emptyList())
        val json="""{"schemaVersion":1,"requestId":"latest","action":"GET_LAST_RESULT","parameters":{}}"""
        val answer=run(json,listOf(session,empty),access.copy(sessionIds=setOf(session.id,"empty")))
        assertEquals(RecordQueryStatus.NO_RESULTS,answer.status);assertEquals("empty",answer.session!!.id)
    }
    @Test fun consentScopeAndReviewPreconditionsHaveNoRecordPayload() {
        for((scope,status) in listOf(access.copy(consent=false) to RecordQueryStatus.CONSENT_REQUIRED,
            access.copy(reviewAllowed=false) to RecordQueryStatus.REVIEW_UNAVAILABLE,access.copy(sessionIds=emptySet()) to RecordQueryStatus.SCOPE_REQUIRED)) {
            val answer=run(scope=scope);assertEquals(status,answer.status);assertNull(answer.session);assertNull(answer.result);assertNull(answer.summary)
        }
    }
    @Test fun historicalAndLegacyResultsKeepTheirStoredVersionsAndValues() {
        val legacy=parking.copy(profileVersion="legacy-profile",evaluationPolicyVersion=null,scoreKind=null,scoreBasis=null,assessments=null)
        val answer=run(sessions=listOf(session.copy(profileVersion="legacy-profile",results=listOf(legacy))))
        assertEquals(85,answer.result!!.score);assertTrue(answer.result.historical)
        assertNull(answer.result.evaluationPolicyVersion);assertTrue(answer.warnings.any {it.contains("이전 저장")})
        assertTrue(answer.answer.contains("legacy-profile"))
    }
    @Test fun provisionalAndUnobservedContextRemainExplicit() {
        val answer=run()
        assertEquals("PROVISIONAL",answer.result!!.scoreKind)
        assertTrue(answer.result.unobserved.any {it.key=="safety_context"})
        assertTrue(answer.result.unobserved.any {it.key=="parking_geometry"})
        assertTrue(answer.warnings.any {it.contains("임시 점수")})
    }
    @Test fun cappedAndMismatchingDeductionsAreExplainedWithoutRecalculation() {
        val capped=parking.copy(score=0,deductions=listOf(parking.deductions.single().copy(points=80),parking.deductions.single().copy(key="other",points=70)))
        val answer=run(sessions=listOf(session.copy(results=listOf(capped))))
        assertEquals(150L,answer.result!!.recordedDeductionTotal);assertEquals(100,answer.result.appliedDeductionTotal);assertEquals(0,answer.result.score)
        assertEquals(true,answer.result.arithmeticMatches)
        val mismatch=run(sessions=listOf(session.copy(results=listOf(parking.copy(score=70)))))
        assertEquals(70,mismatch.result!!.score);assertEquals(false,mismatch.result.arithmeticMatches)
        assertTrue(mismatch.warnings.any {it.contains("차이")})
    }
    @Test fun summaryExcludesHeldDeductionsAndDoesNotAverageMissingScores() {
        val held=session.results.last().copy(deductions=listOf(parking.deductions.single().copy(key="held-only",points=99)))
        val answer=run(request(RecordQueryAction.SUMMARIZE_SESSION,null),listOf(session.copy(results=session.results.dropLast(1)+held)))
        val summary=answer.summary!!
        assertEquals(3,summary.totalResults);assertEquals(2,summary.ratedResults);assertEquals(1,summary.heldOrUnfinishedResults)
        assertNull(summary.averageScore);assertTrue(summary.strengths.single().contains("출발"))
        assertEquals("gear_repetition",summary.improvements.single().key)
    }
    @Test fun comparableCompletedResultsUseTheExistingIntegerMean() {
        val answer=run(request(RecordQueryAction.SUMMARIZE_SESSION,null),listOf(session.copy(results=session.results.take(2))))
        assertEquals(92,answer.summary!!.averageScore)
    }
    @Test fun mixedVersionSummaryDoesNotCombineIncompatibleAveragesOrGroups() {
        val old=parking.copy(id="old",profileVersion="old-profile",evaluationPolicyVersion="old-policy")
        val answer=run(request(RecordQueryAction.SUMMARIZE_SESSION,null),listOf(session.copy(results=listOf(parking,old))))
        assertTrue(answer.summary!!.mixedVersions);assertNull(answer.summary.averageScore)
        assertEquals(2,answer.summary.improvements.size)
        assertTrue(answer.summary.improvements.all {it.occurrences==1})
    }
    @Test fun repeatedRecordedDeductionsCountDistinctResults() {
        val repeat=parking.copy(id="parking-again",startMs=15000,endMs=19000)
        val answer=run(request(RecordQueryAction.SUMMARIZE_SESSION,null),listOf(session.copy(results=listOf(parking,repeat))))
        assertEquals(2,answer.summary!!.improvements.single().occurrences)
        assertEquals(30L,answer.summary.improvements.single().recordedPoints)
        assertFalse(answer.summary.nextSteps.any {it.contains("서두르")})
    }
    @Test fun noResultsSummaryHasNoInventedStrengthOrScore() {
        val answer=run(request(RecordQueryAction.SUMMARIZE_SESSION,null),listOf(session.copy(results=emptyList())))
        assertEquals(RecordQueryStatus.NO_RESULTS,answer.status);assertNull(answer.summary!!.averageScore)
        assertTrue(answer.summary.strengths.isEmpty());assertTrue(answer.summary.improvements.isEmpty())
    }
    @Test fun largeDetailListsExposeOmissionCountsAndKeepTheFullRecordedTotal() {
        val many=parking.copy(score=75,deductions=List(25){i->parking.deductions.single().copy(key="deduction-$i",points=1)})
        val answer=run(sessions=listOf(session.copy(results=listOf(many))))
        assertEquals(20,answer.result!!.deductions.size);assertEquals(5,answer.result.omittedDeductionCount)
        assertEquals(25L,answer.result.recordedDeductionTotal)
    }
    @Test fun invalidNumericAndDuplicateResultRecordsAreHeldWithoutJsonErrors() {
        val invalid=parking.copy(deductions=listOf(parking.deductions.single().copy(measured=Double.NaN)))
        assertEquals(RecordQueryStatus.INVALID_RECORD,run(sessions=listOf(session.copy(results=listOf(invalid)))).status)
        assertEquals(RecordQueryStatus.INVALID_RECORD,run(sessions=listOf(session.copy(results=listOf(parking,parking)))).status)
        assertEquals(RecordQueryStatus.INVALID_RECORD,run(sessions=listOf(session,session)).status)
    }
    @Test fun callbackPreviewUsesAnArrayStringAndKeepsNullScoreAndTransportLabel() {
        val answer=run(request(RecordQueryAction.GET_LAST_RESULT,null))
        val callback=JsonParser.parseString(RecordQueryProtocol.callbackPreview(answer)).asJsonObject
        assertEquals("GET_LAST_RESULT",callback["functionName"].asString);assertEquals("success",callback["status"].asString)
        assertEquals("silent",callback["mode"].asString)
        val data=JsonParser.parseString(callback["data"].asString).asJsonArray.single().asJsonObject
        assertEquals("LOCAL_MOCK",data["transport"].asString);assertFalse(data["externallyDispatched"].asBoolean)
        assertTrue(data["result"].asJsonObject["score"].isJsonNull)
    }
    @Test fun callbackPreviewSeparatesAskingFailureAndNormalEmptySearch() {
        val asking=run(scope=access.copy(sessionIds=emptySet()))
        val denied=run(scope=access.copy(consent=false))
        val absent=run(request(resultId="absent"))
        for((answer,status) in listOf(asking to "asking",denied to "fail",absent to "success"))
            assertEquals(status,JsonParser.parseString(RecordQueryProtocol.callbackPreview(answer)).asJsonObject["status"].asString)
    }
}
