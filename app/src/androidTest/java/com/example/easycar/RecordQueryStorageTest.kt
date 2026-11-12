package com.example.easycar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.data.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordQueryStorageTest {
    private val app get()=ApplicationProvider.getApplicationContext<EasyCarApplication>()
    private fun record():SessionRecord {
        val fixture=RecordQueryDemoData.session(app.profile,app.evaluationPolicy.documentDataset!!)
        return SessionRecord(UUID.randomUUID().toString(),"기록 조회 저장 시험",System.currentTimeMillis(),fixture.source,fixture.profileVersion,fixture.state,
            fixture.results,listOf(VehicleFrame(0,null,null,null,Signal(1.0,0,0,"degree","RAW_SIGNAL_NOT_FOR_CALLBACK"))),videoFile="/not-a-real-private-video.mp4")
    }
    private fun access(id:String)=RecordQueryAccess(setOf(id),true,true,app.profile.version,app.evaluationPolicy.version)
    @Test fun roomReloadQueriesStoredFactsAndDoesNotModifyScoresFramesOrMedia()=runBlocking {
        val name="record-query-${UUID.randomUUID()}.db";val saved=record();val before=Gson().toJson(saved)
        val first=AppRepository(app,name)
        try {first.save(saved)} finally {first.close()}
        val second=AppRepository(app,name)
        try {
            second.load()
            val gateway=LocalRecordQueryGateway {second.records.value}
            val response=gateway.execute(RecordQueryProtocol.request(RecordQueryAction.EXPLAIN_RESULT,saved.id,"fixture-tpark-85"),access(saved.id))
            assertEquals(RecordQueryStatus.OK,response.status);assertEquals(85,response.result!!.score)
            assertEquals(4,response.result!!.reverseToDriveCount);assertEquals(15,response.result!!.appliedDeductionTotal)
            val callback=RecordQueryProtocol.callbackPreview(response)
            assertFalse(callback.contains("RAW_SIGNAL_NOT_FOR_CALLBACK"));assertFalse(callback.contains("not-a-real-private-video"))
            assertEquals(Gson().toJson(saved.copy(frames=emptyList())),Gson().toJson(second.records.value.single()))
            assertEquals(before,Gson().toJson(second.records.value.single().copy(frames=second.frames(saved.id))))
            File(app.filesDir,"validation-gleo-query-storage-v011.json").writeText(Gson().toJson(mapOf("storedRecordUnchanged" to true,"query" to response,"callbackPreview" to JsonParser.parseString(callback))))
        } finally {second.close();app.deleteDatabase(name)}
    }
    @Test fun deletedAndOutOfScopeRecordsAreNotReturnedFromACachedAnswer()=runBlocking {
        val name="record-query-delete-${UUID.randomUUID()}.db";val saved=record();val other=record().copy(source="OTHER_SCOPE_PRIVATE")
        val repository=AppRepository(app,name)
        try {
            repository.save(saved);repository.save(other)
            val gateway=LocalRecordQueryGateway {repository.records.value}
            val request=RecordQueryProtocol.request(RecordQueryAction.GET_LAST_RESULT,saved.id,maneuverType=ManeuverType.T_PARK)
            assertEquals(85,gateway.execute(request,access(saved.id)).result!!.score)
            val forbidden=gateway.execute(RecordQueryProtocol.request(RecordQueryAction.GET_LAST_RESULT,other.id),access(saved.id))
            assertEquals(RecordQueryStatus.NOT_FOUND,forbidden.status);assertFalse(RecordQueryProtocol.responseJson(forbidden).contains("OTHER_SCOPE_PRIVATE"))
            repository.delete(saved.id)
            assertEquals(RecordQueryStatus.NOT_FOUND,gateway.execute(request,access(saved.id)).status)
            assertEquals(listOf(other.id),repository.records.value.map {it.id})
        } finally {repository.close();app.deleteDatabase(name)}
    }
    @Test fun heldAndLegacyRecordsKeepTheirOriginalNullsAndVersions()=runBlocking {
        val name="record-query-old-${UUID.randomUUID()}.db";val saved=record()
        val legacy=saved.results.first().copy(profileVersion="legacy-profile",evaluationPolicyVersion=null,scoreKind=null,assessments=null)
        val old=saved.copy(profileVersion="legacy-profile",results=listOf(legacy,saved.results.last()))
        val repository=AppRepository(app,name)
        try {
            repository.save(old);repository.load()
            val gateway=LocalRecordQueryGateway {repository.records.value}
            val held=gateway.execute(RecordQueryProtocol.request(RecordQueryAction.GET_LAST_RESULT,old.id),access(old.id))
            assertEquals(RecordQueryStatus.HELD,held.status);assertNull(held.result!!.score)
            val history=gateway.execute(RecordQueryProtocol.request(RecordQueryAction.EXPLAIN_RESULT,old.id,legacy.id),access(old.id))
            assertEquals(100,history.result!!.score);assertTrue(history.result!!.historical);assertNull(history.result!!.evaluationPolicyVersion)
            assertEquals(old.copy(frames=emptyList()),repository.records.value.single());assertEquals(old.frames,repository.frames(old.id))
        } finally {repository.close();app.deleteDatabase(name)}
    }
}
