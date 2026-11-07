package com.example.easycar

import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.gleo.GleoQueryHandler
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GleoQueryHandlerTest {
    private val result=ManeuverResult("r",ManeuverType.T_PARK,0,4000,Lifecycle.COMPLETED,.75,85,emptyList(),
        listOf(Deduction("gear_repetition","전후진 반복",15,4.0,"회",1.0,4000,4000,"parking")),4,"p","SYNTHETIC_TEST",null,null,emptyMap(),emptyMap(),"저장 설명",evaluationPolicyVersion="e",scoreKind="PROVISIONAL")
    private val saved=SessionRecord("s","PRIVATE_TITLE",1000,"SYNTHETIC_TEST","p",Lifecycle.COMPLETED,listOf(result),emptyList(),videoFile="PRIVATE_VIDEO")
    private val parsed=GleoRecordProtocol.parse("EXPLAIN_RESULT",null,emptyMap())
    @Test fun onlyExplicitlySelectedScopeIsReadAndOriginalFactsAreKept()=runBlocking {
        val before=Gson().toJson(saved)
        val handler=GleoQueryHandler({UserSettings(consent=true,gleoSessionId="s")},{true},{listOf(saved)},"p","e")
        val response=handler.execute("EXPLAIN_RESULT",parsed).first
        assertEquals(85,response.result!!.score);assertEquals(15,response.result!!.appliedDeductionTotal)
        assertEquals(before,Gson().toJson(saved))
        val callback=GleoRecordProtocol.callback("EXPLAIN_RESULT",response,false)
        assertFalse(callback.contains("PRIVATE_TITLE"));assertFalse(callback.contains("PRIVATE_VIDEO"))
        val forbidden=GleoRecordProtocol.parse("EXPLAIN_RESULT",null,mapOf("sessionId" to "another"))
        assertEquals(RecordQueryStatus.NOT_FOUND,handler.execute("EXPLAIN_RESULT",forbidden).first.status)
    }
    @Test fun deniedMalformedUnselectedAndRestrictedCallsDoNotReadRecords()=runBlocking {
        var settings=UserSettings();var allowed=true;var reads=0
        val handler=GleoQueryHandler({settings},{allowed},{reads++;listOf(saved)},"p","e")
        assertEquals(RecordQueryStatus.CONSENT_REQUIRED,handler.execute("EXPLAIN_RESULT",parsed).first.status)
        settings=settings.copy(consent=true)
        assertEquals(RecordQueryStatus.SCOPE_REQUIRED,handler.execute("EXPLAIN_RESULT",parsed).first.status)
        settings=settings.copy(gleoSessionId="s");allowed=false
        assertEquals(RecordQueryStatus.REVIEW_UNAVAILABLE,handler.execute("EXPLAIN_RESULT",parsed).first.status)
        assertEquals(RecordQueryStatus.INVALID_REQUEST,handler.execute("EXPLAIN_RESULT",GleoRecordProtocol.parse("EXPLAIN_RESULT","bad",emptyMap())).first.status)
        assertEquals(0,reads)
    }
    @Test fun revokeOrSelectionChangesDuringQueryPreventDisclosure()=runBlocking {
        for(final in listOf(UserSettings(consent=false),UserSettings(consent=true,gleoSessionId="other"))) {
            var calls=0
            val handler=GleoQueryHandler({if(calls++==0) UserSettings(consent=true,gleoSessionId="s") else final},{true},{listOf(saved)},"p","e")
            val response=handler.execute("EXPLAIN_RESULT",parsed).first
            assertEquals(RecordQueryStatus.CONSENT_REQUIRED,response.status);assertNull(response.result);assertNull(response.session)
        }
    }
    @Test fun deletedChangedOrNewlyRestrictedRecordsCannotReturnAStaleAnswer()=runBlocking {
        for(updated in listOf(emptyList(),listOf(saved.copy(revision=1)))) {
            var reads=0
            val handler=GleoQueryHandler({UserSettings(consent=true,gleoSessionId="s")},{true},{if(reads++==0) listOf(saved) else updated},"p","e")
            val response=handler.execute("EXPLAIN_RESULT",parsed).first
            assertEquals(if(updated.isEmpty()) RecordQueryStatus.NOT_FOUND else RecordQueryStatus.INVALID_RECORD,response.status);assertNull(response.result)
        }
        var gateCalls=0
        val handler=GleoQueryHandler({UserSettings(consent=true,gleoSessionId="s")},{gateCalls++==0},{listOf(saved)},"p","e")
        assertEquals(RecordQueryStatus.REVIEW_UNAVAILABLE,handler.execute("EXPLAIN_RESULT",parsed).first.status)
    }
    @Test fun mutedAndHeldStatusArePreservedForExternalResponse()=runBlocking {
        val held=saved.copy(results=listOf(result.copy(score=null,holdReasons=listOf("신호 누락"))))
        val handler=GleoQueryHandler({UserSettings(consent=true,muted=true,gleoSessionId="s")},{true},{listOf(held)},"p","e")
        val (response,muted)=handler.execute("EXPLAIN_RESULT",parsed)
        assertEquals(RecordQueryStatus.HELD,response.status);assertNull(response.result!!.score);assertTrue(muted)
    }
}
