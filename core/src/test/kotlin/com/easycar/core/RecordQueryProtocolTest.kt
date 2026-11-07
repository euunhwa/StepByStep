package com.easycar.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordQueryProtocolTest {
    private val valid="""{"schemaVersion":1,"requestId":"r-1","action":"GET_LAST_RESULT","parameters":{}}"""
    @Test fun builderAndParserRoundTripTypedRequests() {
        val json=RecordQueryProtocol.request(RecordQueryAction.GET_LAST_RESULT,"session-1",maneuverType=ManeuverType.U_TURN,requestId="req-1")
        val parsed=(RecordQueryProtocol.parse(json) as QueryParseResult.Success).request
        assertEquals("req-1",parsed.requestId);assertEquals("session-1",parsed.sessionId);assertEquals(ManeuverType.U_TURN,parsed.maneuverType)
    }
    @Test fun duplicatesCommentsTrailingObjectsAndNullsAreRejected() {
        val bad=listOf(valid.replace("\"requestId\":\"r-1\"","\"requestId\":\"r-1\",\"requestId\":\"r-2\""),
            valid.replace("{}","{\"sessionId\":\"a\",\"sessionId\":\"b\"}"),valid+"{}",valid.replace("{}","null"),valid.replace("{\"schemaVersion\"","{/* comment */\"schemaVersion\""))
        bad.forEach {assertTrue(it,RecordQueryProtocol.parse(it) is QueryParseResult.Failure)}
    }
    @Test fun unsupportedActionsUnknownParametersAndScopeOverridesAreRejected() {
        val bad=listOf(valid.replace("GET_LAST_RESULT","DELETE_RECORD"),valid.replace("{}","{\"score\":100}"),
            valid.replace("{}","{\"callbackUrl\":\"https://example.invalid\"}"),valid.replace("{}","{\"allowedSessionIds\":\"all\"}"),
            valid.replace("{}","{\"sessionId\":\"../private\"}"),valid.replace("{}","{\"maneuverType\":\"FAST_PARK\"}"))
        bad.forEach {assertTrue(it,RecordQueryProtocol.parse(it) is QueryParseResult.Failure)}
    }
    @Test fun versionAndPrimitiveTypesAreStrictAndLargeInputIsBounded() {
        assertEquals(RecordQueryStatus.UNSUPPORTED_VERSION,(RecordQueryProtocol.parse(valid.replace("\"schemaVersion\":1","\"schemaVersion\":2")) as QueryParseResult.Failure).status)
        for(json in listOf(valid.replace("\"schemaVersion\":1","\"schemaVersion\":\"1\""),valid.replace("\"r-1\"","22")," ".repeat(16385)))
            assertTrue(RecordQueryProtocol.parse(json) is QueryParseResult.Failure)
    }
    @Test fun actionSpecificFieldsCannotChangeTheMeaningOfAnotherCall() {
        val summary=valid.replace("GET_LAST_RESULT","SUMMARIZE_SESSION").replace("{}","{\"resultId\":\"r\"}")
        val explain=valid.replace("GET_LAST_RESULT","EXPLAIN_RESULT").replace("{}","{\"courseId\":\"LEFT\"}")
        assertTrue(RecordQueryProtocol.parse(summary) is QueryParseResult.Failure);assertTrue(RecordQueryProtocol.parse(explain) is QueryParseResult.Failure)
    }
    @Test fun packagedAppSchemaNamesAndParametersMatchTheImplementedPort() {
        val draft=JsonParser.parseString(File("../app/src/pleos/assets/schema.json").readText()).asJsonObject
        assertEquals("com.example.easycar",draft["packageName"].asString)
        val calls=draft["actionCalls"].asJsonArray
        assertEquals(3,calls.size());assertEquals(RecordQueryAction.entries.map {it.name}.toSet(),calls.map {it.asJsonObject["function"].asJsonObject["name"].asString}.toSet())
        assertTrue(File("../app/build.gradle.kts").readText().contains("versionName = \"${draft["version"].asString}\""))
        calls.forEach {node->val function=node.asJsonObject["function"].asJsonObject
            assertEquals("function",node.asJsonObject["type"].asString);assertEquals("service",function["component"].asString)
            assertTrue(function["name"].asString.matches(Regex("[A-Z]+(_[A-Z]+)+")))
            val parameters=function["parameters"].asJsonObject
            assertEquals("object",parameters["type"].asString);assertEquals(0,parameters["required"].asJsonArray.size())
            val request="""{"schemaVersion":1,"requestId":"draft","action":"${function["name"].asString}","parameters":{}}"""
            assertTrue(RecordQueryProtocol.parse(request) is QueryParseResult.Success)
        }
    }
}
