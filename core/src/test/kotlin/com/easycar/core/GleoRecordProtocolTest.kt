package com.easycar.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class GleoRecordProtocolTest {
    @Test fun acceptsBothDocumentedParameterTransportsAndAnEmptyRequest() {
        for(packed in listOf("{\"maneuverType\":\"T_PARK\"}",Gson().toJson("{\"maneuverType\":\"T_PARK\"}"))) {
            val parsed=GleoRecordProtocol.parse("GET_LAST_RESULT",packed,emptyMap()) as QueryParseResult.Success
            assertEquals(ManeuverType.T_PARK,parsed.request.maneuverType);assertNull(parsed.request.sessionId)
        }
        val direct=GleoRecordProtocol.parse("EXPLAIN_RESULT",null,mapOf("resultId" to "stored-1")) as QueryParseResult.Success
        assertEquals("stored-1",direct.request.resultId)
        assertTrue(GleoRecordProtocol.parse("SUMMARIZE_SESSION",null,emptyMap()) is QueryParseResult.Success)
    }
    @Test fun optionalEmptyStringsObservedInGleoBecomeOmittedValuesWithoutRelaxingLocalIds() {
        val packed="""{"sessionId":"","maneuverType":"PARKING"}"""
        val parsed=(GleoRecordProtocol.parse("GET_LAST_RESULT",packed,emptyMap()) as QueryParseResult.Success).request
        assertNull(parsed.sessionId);assertTrue(parsed.parkingOnly)
        val explanation=(GleoRecordProtocol.parse("EXPLAIN_RESULT",null,mapOf("sessionId" to "", "resultId" to "")) as QueryParseResult.Success).request
        assertNull(explanation.sessionId);assertNull(explanation.resultId)
        assertTrue(RecordQueryProtocol.parse("""{"schemaVersion":1,"requestId":"r","action":"GET_LAST_RESULT","parameters":$packed}""") is QueryParseResult.Failure)
    }
    @Test fun rejectsAmbiguityInjectionDuplicatesUnknownFieldsAndOverlongParameters() {
        val bad=listOf(
            GleoRecordProtocol.parse("GET_LAST_RESULT","{}",mapOf("sessionId" to "a")),
            GleoRecordProtocol.parse("DELETE_RECORD",null,emptyMap()),
            GleoRecordProtocol.parse("GET_LAST_RESULT","{\"sessionId\":\"a\",\"sessionId\":\"b\"}",emptyMap()),
            GleoRecordProtocol.parse("GET_LAST_RESULT","{} ,\"score\":100",emptyMap()),
            GleoRecordProtocol.parse("GET_LAST_RESULT","{}{}",emptyMap()),
            GleoRecordProtocol.parse("GET_LAST_RESULT","{/* comment */}",emptyMap()),
            GleoRecordProtocol.parse("GET_LAST_RESULT",Gson().toJson(Gson().toJson("{}")),emptyMap()),
            GleoRecordProtocol.parse("GET_LAST_RESULT",null,mapOf("callbackUrl" to "https://invalid.test")),
            GleoRecordProtocol.parse("SUMMARIZE_SESSION"," ".repeat(16385),emptyMap()),
            GleoRecordProtocol.parse("SUMMARIZE_SESSION",null,mapOf("sessionId" to "a".repeat(129))))
        bad.forEach {assertTrue(it.toString(),it is QueryParseResult.Failure)}
    }
    @Test fun externalCallbackPreservesHoldsUsesVoiceAndOmitsMockTransportClaims() {
        val held=RecordQueryResponse(requestId="r",action="GET_LAST_RESULT",status=RecordQueryStatus.HELD,answer="평가 보류: yaw 누락",warnings=listOf("과거 기준"))
        val callback=JsonParser.parseString(GleoRecordProtocol.callback("GET_LAST_RESULT",held,false)).asJsonObject
        assertEquals("voice",callback["mode"].asString);assertEquals("success",callback["status"].asString)
        val data=JsonParser.parseString(callback["data"].asString).asJsonArray.single().asJsonObject
        assertEquals("HELD",data["status"].asString);assertEquals(held.answer,data["answer"].asString)
        assertFalse(data.has("externallyDispatched"));assertFalse(data.has("transport"))
        assertEquals("silent",JsonParser.parseString(GleoRecordProtocol.callback("GET_LAST_RESULT",held,true)).asJsonObject["mode"].asString)
    }
    @Test fun scopesAndFailuresHaveExplicitStatusesAndOversizedRecordsAreNotPartiallyExplained() {
        val scope=RecordQueryResponse(requestId=null,action=null,status=RecordQueryStatus.SCOPE_REQUIRED,answer="선택 필요")
        assertEquals("asking",JsonParser.parseString(GleoRecordProtocol.callback("SUMMARIZE_SESSION",scope,false)).asJsonObject["status"].asString)
        val denied=scope.copy(status=RecordQueryStatus.CONSENT_REQUIRED)
        assertEquals("fail",JsonParser.parseString(GleoRecordProtocol.callback("SUMMARIZE_SESSION",denied,false)).asJsonObject["status"].asString)
        val huge=scope.copy(status=RecordQueryStatus.OK,answer="가".repeat(20000))
        val result=GleoRecordProtocol.callback("SUMMARIZE_SESSION",huge,false)
        assertTrue(result.toByteArray().size<GleoRecordProtocol.MAX_CALLBACK_BYTES)
        assertEquals("fail",JsonParser.parseString(result).asJsonObject["status"].asString)
        assertFalse(JsonParser.parseString(result).asJsonObject.has("data"))
    }
}
