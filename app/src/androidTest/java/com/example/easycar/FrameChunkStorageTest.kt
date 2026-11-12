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
    private fun record(revision:Long,range:IntRange?=null)=SessionRecord("chunk-test","청크 시험",1L,"시험",
        "document-derived-1.0",Lifecycle.ACTIVE,emptyList(),range?.let(::frames) ?: emptyList(),revision=revision)
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

    @Test fun checkpointsAppendOnlyNewFramesAndTrimTheWindow()=withDb {name->
        val a=AppRepository(context,name)
        a.append(record(1),frames(0..99));a.append(record(2),frames(100..149))
        // Live sessions keep a sliding window: chunks wholly before the first kept frame drop.
        a.append(record(3),frames(150..449),keepFromMs=100*100L)
        assertTrue("records must not keep frames in memory",a.records.value.single().frames.isEmpty())
        a.close()
        rawDao(name) {dao->
            assertFalse(payloadHasFrames(dao))
            // seq0=0..99 (dropped: wholly before the window), seq1=100..149, seq2=150..449 (300 new frames).
            assertEquals(listOf(1,2),dao.frameChunkSeqs("chunk-test"))
        }
        val b=AppRepository(context,name);b.load()
        assertEquals(record(3),b.records.value.single())
        assertEquals(frames(100..449),b.frames("chunk-test"));b.close()
    }

    @Test fun aWindowedReadReturnsExactlyTheFramesInTheWindow()=withDb {name->
        val a=AppRepository(context,name)
        // Chunks: seq0=0..299, seq1=300..599, seq2=600..899, seq3=900..949.
        a.append(record(1),frames(0..899));a.append(record(2),frames(900..949))
        assertEquals(frames(250..650),a.frames("chunk-test",250*100L,650*100L))
        assertEquals(frames(299..300),a.frames("chunk-test",299*100L,300*100L))
        assertEquals(frames(920..949),a.frames("chunk-test",920*100L,99_999_999L))
        assertTrue(a.frames("chunk-test",100_000L,200_000L).isEmpty())
        a.close()
    }

    @Test fun aStaleRecordKeepsTheNewerPayloadButStillAppendsItsFrames()=withDb {name->
        val a=AppRepository(context,name)
        a.append(record(5),frames(0..9))
        a.append(record(3).copy(title="stale"),frames(10..19))
        assertEquals(record(5),a.records.value.single());a.close()
        val b=AppRepository(context,name);b.load()
        assertEquals(record(5),b.records.value.single())
        assertEquals(frames(0..19),b.frames("chunk-test"));b.close()
    }

    @Test fun savingAFramelessCopyKeepsStoredFrames()=withDb {name->
        val a=AppRepository(context,name)
        a.save(record(1,0..199));a.close()
        val b=AppRepository(context,name);b.load()
        // e.g. recoverInterrupted() or a video edit: built from records, which carry no frames.
        b.recoverInterrupted()
        assertEquals(Lifecycle.INTERRUPTED,b.records.value.single().state)
        assertEquals(frames(0..199),b.frames("chunk-test"));b.close()
    }

    @Test fun savingFramesReplacesThemAndDeleteRemovesThem()=withDb {name->
        val a=AppRepository(context,name)
        a.save(record(1,0..99))
        a.save(record(2,500..520))
        a.close()
        val b=AppRepository(context,name);b.load();assertEquals(frames(500..520),b.frames("chunk-test"))
        b.delete("chunk-test");assertTrue(b.frames("chunk-test").isEmpty())
        // A checkpoint racing the delete must not bring the session back.
        b.append(record(3),frames(521..530));assertTrue(b.frames("chunk-test").isEmpty());b.close()
        rawDao(name) {dao->assertTrue(dao.frameChunkSeqs("chunk-test").isEmpty());assertTrue(dao.ids().isEmpty())}
    }

    @Test fun legacyPayloadWithEmbeddedFramesMovesToChunksOnLoad()=withDb {name->
        val legacy=record(5,0..399)
        rawDao(name) {dao->dao.save(SessionEntity().also {it.id=legacy.id;it.createdAt=legacy.createdAt;it.payload=Gson().toJson(legacy)})}
        val a=AppRepository(context,name);a.load()
        assertEquals(legacy.copy(frames=emptyList()),a.records.value.single())
        assertEquals(legacy.frames,a.frames("chunk-test"))
        a.append(record(6),frames(400..409));a.close()
        rawDao(name) {dao->
            assertFalse(payloadHasFrames(dao))
            // Load moved 400 frames (300+100); the next checkpoint appended 400..409.
            assertEquals(listOf(0,1,2),dao.frameChunkSeqs("chunk-test"))
        }
        val b=AppRepository(context,name);b.load();assertEquals(frames(0..409),b.frames("chunk-test"));b.close()
    }
}
