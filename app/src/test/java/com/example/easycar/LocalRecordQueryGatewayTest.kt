package com.example.easycar

import com.easycar.core.*
import com.example.easycar.data.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class LocalRecordQueryGatewayTest {
    private fun result()=ManeuverResult("result-1",ManeuverType.T_PARK,0,4000,Lifecycle.COMPLETED,.75,85,emptyList(),
        listOf(Deduction("gear_repetition","전후진 반복",15,4.0,"회",1.0,4000,4000,"parking")),4,"document-derived-1.0","SYNTHETIC_TEST",null,null,emptyMap(),emptyMap(),"저장된 설명",
        evaluationPolicyVersion="evaluation-document-1.0",scoreKind="PROVISIONAL")
    private fun record()=SessionRecord("session-1","조회 시험",1000,"SYNTHETIC_TEST","document-derived-1.0",Lifecycle.COMPLETED,listOf(result()),
        listOf(VehicleFrame(0,null,null,null,Signal(12.0,0,0,"degree","PRIVATE_RAW_SOURCE"))),videoFile="/private/DO_NOT_EXPOSE.mp4")
    private val access=RecordQueryAccess(setOf("session-1"),true,true,"document-derived-1.0","evaluation-document-1.0")
    private val request=RecordQueryProtocol.request(RecordQueryAction.EXPLAIN_RESULT,"session-1")
    @Test fun denialDoesNotReadTheRecordSource() {
        var reads=0
        val gateway=LocalRecordQueryGateway {reads++;listOf(record())}
        assertEquals(RecordQueryStatus.CONSENT_REQUIRED,gateway.execute(request,access.copy(consent=false)).status)
        assertEquals(RecordQueryStatus.REVIEW_UNAVAILABLE,gateway.execute(request,access.copy(reviewAllowed=false)).status)
        assertEquals(RecordQueryStatus.SCOPE_REQUIRED,gateway.execute(request,access.copy(sessionIds=emptySet())).status)
        assertEquals(0,reads)
    }
    @Test fun queriesNeverSerializeRawFramesOrPrivateMediaAndNeverMutateTheRecord() {
        val saved=record();val before=Gson().toJson(saved)
        val response=LocalRecordQueryGateway {listOf(saved)}.execute(request,access)
        val json=RecordQueryProtocol.callbackPreview(response)
        assertEquals(85,response.result!!.score);assertFalse(json.contains("PRIVATE_RAW_SOURCE"));assertFalse(json.contains("DO_NOT_EXPOSE"))
        assertFalse(json.contains("videoFile"));assertFalse(json.contains("frames"))
        assertEquals(before,Gson().toJson(saved))
    }
    @Test fun sameGatewayReadsFreshDataAfterChangesAndDeletion() {
        var stored=listOf(record())
        val gateway=LocalRecordQueryGateway {stored}
        assertEquals(85,gateway.execute(request,access).result!!.score)
        stored=listOf(stored.single().copy(results=emptyList()))
        assertEquals(RecordQueryStatus.NO_RESULTS,gateway.execute(request,access).status)
        stored=emptyList()
        assertEquals(RecordQueryStatus.NOT_FOUND,gateway.execute(request,access).status)
    }
    @Test fun unauthorizedSessionDataNeverAppearsInAResponse() {
        val other=record().copy(id="other-session",source="OTHER_SESSION_SECRET")
        val response=LocalRecordQueryGateway {listOf(record(),other)}.execute(RecordQueryProtocol.request(RecordQueryAction.SUMMARIZE_SESSION,other.id),access)
        assertEquals(RecordQueryStatus.NOT_FOUND,response.status);assertNull(response.session)
        assertFalse(RecordQueryProtocol.responseJson(response).contains("OTHER_SESSION_SECRET"))
    }
    @Test fun retiredCaptureRecordRemainsReadableWithoutRescoringAndIsHistorical() {
        val file=listOf(java.io.File("../docs/validation/parking-console-record-v013.json"),java.io.File("docs/validation/parking-console-record-v013.json")).first {it.isFile}
        val saved=Gson().fromJson(file.readText(),SessionRecord::class.java)
        val before=Gson().toJson(saved)
        assertEquals(2,saved.parkingCapture!!.episode!!.reverseToDriveCount)
        assertTrue(saved.parkingCapture!!.videoSaved)
        assertEquals(ManeuverType.PARKING,saved.results.single().type)
        val response=LocalRecordQueryGateway {listOf(saved)}.execute(RecordQueryProtocol.request(RecordQueryAction.EXPLAIN_RESULT,saved.id),access.copy(sessionIds=setOf(saved.id)))
        assertTrue(response.result!!.historical);assertNull(response.result!!.score)
        assertEquals(before,Gson().toJson(saved))
        assertFalse(RecordQueryProtocol.responseJson(response).contains("parkingCapture"))
    }
}
