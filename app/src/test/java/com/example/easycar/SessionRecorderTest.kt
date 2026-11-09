package com.example.easycar

import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionRecorderTest {
    private class Append(val record:SessionRecord,val frames:List<VehicleFrame>,val keepFromMs:Long?)
    private class FakeStore:SessionStore {
        override val records:StateFlow<List<SessionRecord>> = MutableStateFlow(emptyList())
        val appends=mutableListOf<Append>()
        var failNext=false
        override suspend fun save(record:SessionRecord) {}
        override suspend fun append(record:SessionRecord,frames:List<VehicleFrame>,keepFromMs:Long?) {
            if(failNext) {failNext=false;error("disk full")}
            appends+=Append(record,frames,keepFromMs)
        }
        override suspend fun delete(id:String) {}
    }
    private fun frame(i:Int)=VehicleFrame(i*100L,null,null,null,null)
    private fun record(progress:Int)=SessionRecord("s","t",0,"src","p",Lifecycle.ACTIVE,emptyList(),emptyList(),progressIndex=progress)

    @Test fun eachCheckpointAppendsOnlyFramesNotStoredYet()=runBlocking {
        val store=FakeStore();val recorder=SessionRecorder(store)
        (0 until 5).forEach {recorder.window.add(frame(it))}
        recorder.checkpoint {record(5)}
        (5 until 8).forEach {recorder.window.add(frame(it))}
        recorder.checkpoint {record(8)}
        recorder.checkpoint {record(8)}
        assertEquals(listOf((0 until 5).map(::frame),(5 until 8).map(::frame),emptyList()),store.appends.map {it.frames})
        assertEquals(listOf(5,8,8),store.appends.map {it.record.progressIndex})
        assertTrue(store.appends.all {it.keepFromMs==null})
    }

    @Test fun aFailedWriteIsRetriedByTheNextCheckpoint()=runBlocking {
        val store=FakeStore();val recorder=SessionRecorder(store)
        (0 until 3).forEach {recorder.window.add(frame(it))}
        store.failNext=true
        assertTrue(runCatching {recorder.checkpoint {record(3)}}.isFailure)
        recorder.window.add(frame(3))
        recorder.checkpoint {record(4)}
        assertEquals((0 until 4).map(::frame),store.appends.single().frames)
    }

    @Test fun theWindowKeepsTheNewestFramesAndTrimsStorageBehindThem()=runBlocking {
        val store=FakeStore();val recorder=SessionRecorder(store,FrameWindow(capacity=4))
        (0 until 10).forEach {recorder.window.add(frame(it))}
        assertEquals((6 until 10).map(::frame),recorder.window.snapshot())
        recorder.checkpoint {record(10)}
        // Frames 0..5 left the window before any checkpoint; storage keeps nothing before frame 6.
        assertEquals((6 until 10).map(::frame),store.appends.single().frames)
        assertEquals(frame(6).timeMs,store.appends.single().keepFromMs)
    }

    @Test fun aResumedWindowAppendsAfterTheStoredFrames()=runBlocking {
        val store=FakeStore();val recorder=SessionRecorder(store)
        recorder.window.reset((0 until 20).map(::frame))
        recorder.window.add(frame(20))
        recorder.checkpoint {record(21)}
        assertEquals(listOf(frame(20)),store.appends.single().frames)
        assertEquals(21,recorder.window.size)
    }

    @Test fun noRecordMeansNoWrite()=runBlocking {
        val store=FakeStore();val recorder=SessionRecorder(store)
        recorder.window.add(frame(0))
        recorder.checkpoint {null}
        assertTrue(store.appends.isEmpty())
        recorder.checkpoint {record(1)}
        assertEquals(listOf(frame(0)),store.appends.single().frames)
    }
}
