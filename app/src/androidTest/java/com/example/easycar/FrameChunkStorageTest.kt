package com.example.easycar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.data.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Frames live in append-only frame_chunks: a checkpoint must not rewrite the whole drive, and
 * [AppRepository.records] must not hold every session's frames in memory.
 */
@RunWith(AndroidJUnit4::class)
class FrameChunkStorageTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun frames(range:IntRange)=range.map {i->val t=i*100L
        VehicleFrame(t,Signal(1.5,t,t,"m/s","CHUNK_TEST"),Signal(-3.0,t,t,"degree:steering-wheel","CHUNK_TEST · 한글"),Signal(Gear.DRIVE,t,t,"gear","CHUNK_TEST"),null)}
    private fun record(range:IntRange,revision:Long)=SessionRecord("chunk-test","청크 시험",1L,"시험",
        "document-derived-1.0",Lifecycle.ACTIVE,emptyList(),frames(range),revision=revision)
    private fun <T> rawDao(name:String,block:(SessionDao)->T):T {
        val db=Room.databaseBuilder(context,EasyCarDatabase::class.java,name).build()
        try {return block(db.sessions())} finally {db.close()}
    }
    /** A throwaway database per test; deleted even when an assertion fails. */
    private fun withDb(block:suspend (String)->Unit)=runBlocking {
        val name="easycar-chunks-${UUID.randomUUID()}.db"
        try {block(name)} finally {context.deleteDatabase(name)}
    }
    private fun payloadHasFrames(dao:SessionDao)=dao.payloadChunk("chunk-test",1,dao.payloadLength("chunk-test")).contains("CHUNK_TEST")

    @Test fun checkpointsAppendOnlyNewFramesAndReloadTheExactWindow()=withDb {name->
        val a=AppRepository(context,name)
        a.save(record(0..99,1));a.save(record(0..149,2))
        // Live sessions keep a sliding window: older frames drop, the stored tail is still present.
        val window=record(100..449,3);a.save(window)
        assertTrue("records must not keep frames in memory",a.records.value.single().frames.isEmpty())
        a.close()
        rawDao(name) {dao->
            assertFalse(payloadHasFrames(dao))
            // seq0=0..99 (dropped: wholly before the window), seq1=100..149, seq2=150..449 (300 new frames).
            assertEquals(listOf(1,2),dao.frameChunkSeqs("chunk-test"))
        }
        val b=AppRepository(context,name);b.load()
        assertEquals(window.copy(frames=emptyList()),b.records.value.single())
        assertEquals(window.frames,b.frames("chunk-test"));b.close()
    }

    @Test fun savingAFramelessCopyKeepsStoredFrames()=withDb {name->
        val a=AppRepository(context,name)
        val full=record(0..199,1);a.save(full);a.close()
        val b=AppRepository(context,name);b.load()
        // e.g. recoverInterrupted() or a video edit: built from records, which carry no frames.
        b.recoverInterrupted()
        assertEquals(Lifecycle.INTERRUPTED,b.records.value.single().state)
        assertEquals(full.frames,b.frames("chunk-test"));b.close()
    }

    @Test fun replacedFramesRewriteChunksAndDeleteRemovesThem()=withDb {name->
        val a=AppRepository(context,name)
        a.save(record(0..99,1))
        val replaced=record(500..520,2);a.save(replaced)
        a.close()
        val b=AppRepository(context,name);b.load();assertEquals(replaced.frames,b.frames("chunk-test"))
        b.delete("chunk-test");assertTrue(b.frames("chunk-test").isEmpty());b.close()
        rawDao(name) {dao->assertTrue(dao.frameChunkSeqs("chunk-test").isEmpty());assertTrue(dao.ids().isEmpty())}
    }

    @Test fun legacyPayloadWithEmbeddedFramesMovesToChunksOnLoad()=withDb {name->
        val legacy=record(0..399,5)
        rawDao(name) {dao->dao.save(SessionEntity().also {it.id=legacy.id;it.createdAt=legacy.createdAt;it.payload=Gson().toJson(legacy)})}
        val a=AppRepository(context,name);a.load()
        assertEquals(legacy.copy(frames=emptyList()),a.records.value.single())
        assertEquals(legacy.frames,a.frames("chunk-test"))
        val next=record(0..409,6);a.save(next);a.close()
        rawDao(name) {dao->
            assertFalse(payloadHasFrames(dao))
            // Load moved 400 frames (300+100); the next save appended 400..409.
            assertEquals(listOf(0,1,2),dao.frameChunkSeqs("chunk-test"))
        }
        val b=AppRepository(context,name);b.load();assertEquals(next.frames,b.frames("chunk-test"));b.close()
    }
}
