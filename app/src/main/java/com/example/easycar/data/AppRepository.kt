package com.example.easycar.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.easycar.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private val Context.preferences by preferencesDataStore("easycar_settings")
data class UserSettings(val consent:Boolean=false,val saveVideo:Boolean=false,val muted:Boolean=false,val retentionDays:Int=30,val voiceIntervalSeconds:Int=6,val demoInput:Boolean?=null,val smallWindowOnStart:Boolean=true,val gleoSessionId:String?=null)
/**
 * Stored as one Gson JSON payload. Gson does not apply Kotlin default values: a field missing from an
 * older payload comes back null (or 0/false for primitives) even when declared non-null. So a field
 * added later must be nullable, or a primitive whose zero value is a safe default.
 * [frames] is filled only for whole-record writes and legacy payloads; see [SessionStore].
 */
data class SessionRecord(val id:String,val title:String,val createdAt:Long,val source:String,val profileVersion:String,
    val state:Lifecycle,val results:List<ManeuverResult>,val frames:List<VehicleFrame>,val demoCaseId:String?=null,
    val progressIndex:Int=0,val videoAsset:String?=null,val videoFile:String?=null,val videoDurationMs:Long?=null,
    val alignment:AlignmentEvidence?=null,val error:String?=null,val feedbacks:List<FeedbackReceipt>?=null,val focusType:ManeuverType?=null,val videoSync:VideoSync?=null,val calibration:Calibration?=null,val revision:Long=0,val practiceContext:PracticeContext?=null,val videoAnalysisPositionMs:Long?=null,val parkingCapture:ParkingCaptureRecord?=null) {
    val score:Int? get()=if(results.isNotEmpty() && results.all { it.score!=null }) results.mapNotNull { it.score }.average().toInt() else null
}
interface SessionStore {
    val records:StateFlow<List<SessionRecord>>
    /**
     * Writes the record. A record carrying frames replaces the stored frames with them; a frameless
     * record (every copy taken from [records]) leaves the stored frames untouched.
     */
    suspend fun save(record:SessionRecord)
    /**
     * A running session's checkpoint: writes the frameless record and appends [frames] after the stored
     * ones, atomically. Frames are appended even when the record itself is stale, because they are
     * immutable inputs the caller will not send again. Chunks that end before [keepFromMs] are dropped.
     */
    suspend fun append(record:SessionRecord,frames:List<VehicleFrame>,keepFromMs:Long?=null)
    suspend fun delete(id:String)
}
/** 300 frames ≈ 0.3–0.5 MB of JSON per row, well inside SQLite's 2 MB CursorWindow. */
private const val FRAME_CHUNK_SIZE=300
class AppRepository(private val context:Context,databaseName:String="easycar.db"):SessionStore {
    private val db=Room.databaseBuilder(context,EasyCarDatabase::class.java,databaseName).build()
    private val gson=Gson(); private val mutex=Mutex();private val deletedIds=mutableSetOf<String>()
    private val state=MutableStateFlow<List<SessionRecord>>(emptyList())
    override val records:StateFlow<List<SessionRecord>> = state.asStateFlow()
    private val consent=booleanPreferencesKey("consent");private val video=booleanPreferencesKey("save_video")
    private val muted=booleanPreferencesKey("muted");private val retention=intPreferencesKey("retention_days");private val voiceInterval=intPreferencesKey("voice_interval");private val demoInput=booleanPreferencesKey("demo_input");private val smallWindow=booleanPreferencesKey("small_window")
    private val gleoSession=stringPreferencesKey("gleo_selected_session")
    val settings=context.preferences.data.map { UserSettings(it[consent]?:false,it[video]?:false,it[muted]?:false,it[retention]?:30,it[voiceInterval]?:6,it[demoInput],it[smallWindow]?:true,it[gleoSession]) }
    suspend fun updateSettings(s:UserSettings) { context.preferences.edit { it[consent]=s.consent;it[video]=s.saveVideo;it[muted]=s.muted;it[retention]=s.retentionDays;it[voiceInterval]=s.voiceIntervalSeconds;it[smallWindow]=s.smallWindowOnStart;s.demoInput?.let {enabled->it[demoInput]=enabled};if(!s.consent) it.remove(gleoSession) } }
    /** Separate from general settings copies so an old UI snapshot cannot restore revoked access. */
    suspend fun selectGleoSession(id:String?)=withContext(Dispatchers.IO) {mutex.withLock {
        require(id==null || state.value.any {it.id==id})
        context.preferences.edit {if(id==null) it.remove(gleoSession) else {require(it[consent]==true);it[gleoSession]=id}}
        Unit
    } }
    suspend fun load()=withContext(Dispatchers.IO) { mutex.withLock { reload() } }
    private fun readPayload(id:String):String {
        val dao=db.sessions();val size=dao.payloadLength(id) ?: 0
        // SQLite CursorWindow cannot hold a full long-drive JSON row. Read bounded text slices;
        // SQLite length/substr both count characters, so Korean text remains intact.
        return buildString(size) {
            var offset=1
            while(offset<=size) { append(dao.payloadChunk(id,offset,65536));offset+=65536 }
        }
    }
    private fun entity(record:SessionRecord)=SessionEntity().also { it.id=record.id;it.createdAt=record.createdAt;it.payload=gson.toJson(record.copy(frames=emptyList())) }
    /** Writes the frameless payload and, when the record carries frames, replaces the stored frames, atomically. */
    private fun persist(record:SessionRecord)=db.runInTransaction {
        if(record.frames.isNotEmpty()) { db.sessions().deleteFrameChunks(record.id);appendChunks(record.id,record.frames) }
        db.sessions().save(entity(record))
    }
    /**
     * [records] holds every session without its raw frames; read them on demand with [frames].
     * v1 rows still embed frames in the payload: they move to frame_chunks once, here. If that
     * fails the transaction rolls back and [frames] keeps reading them from the payload.
     */
    private fun reload() {
        state.value=db.sessions().ids().mapNotNull { id ->
            val record=runCatching { gson.fromJson(readPayload(id),SessionRecord::class.java) }.getOrNull() ?: return@mapNotNull null
            if(record.frames.isNotEmpty()) runCatching { persist(record) }
            record.copy(frames=emptyList())
        }
    }
    /** Raw frames of one session, oldest first. Empty when the session has none. */
    suspend fun frames(id:String):List<VehicleFrame> = readFrames(id,null)
    /** Raw frames of one session within [fromMs]..[toMs], parsing only the chunks that overlap it. */
    suspend fun frames(id:String,fromMs:Long,toMs:Long):List<VehicleFrame> = readFrames(id,fromMs..toMs)
    private suspend fun readFrames(id:String,window:LongRange?):List<VehicleFrame> = withContext(Dispatchers.IO) {
        // Read under the lock; parse outside it so a long read does not hold up saves.
        val (chunks,legacy)=mutex.withLock {
            val dao=db.sessions()
            val chunks=if(window==null) dao.frameChunkPayloads(id) else dao.frameChunkPayloadsBetween(id,window.first,window.last)
            val legacy=if(chunks.isEmpty() && dao.lastFrameChunkSeq(id)==null && dao.payloadLength(id)!=null) readPayload(id) else null
            chunks to legacy
        }
        val all=if(chunks.isNotEmpty()) chunks.flatMap { gson.fromJson(it,Array<VehicleFrame>::class.java).asList() }
            else legacy?.let { runCatching { gson.fromJson(it,SessionRecord::class.java).frames.toList() }.getOrNull() } ?: emptyList()
        if(window==null) all else all.filter { it.timeMs in window }
    }
    /** Adds [frames] after the last stored chunk. Runs inside a transaction. */
    private fun appendChunks(id:String,frames:List<VehicleFrame>) {
        val dao=db.sessions();var seq=(dao.lastFrameChunkSeq(id) ?: -1)+1
        frames.chunked(FRAME_CHUNK_SIZE).forEach {part->
            dao.insertFrameChunk(FrameChunkEntity().also {it.sessionId=id;it.seq=seq++;it.lastTimeMs=part.last().timeMs;it.payload=gson.toJson(part)})
        }
    }
    private fun isStale(record:SessionRecord)=(state.value.firstOrNull {it.id==record.id}?.revision ?: -1)>record.revision
    private fun publish(record:SessionRecord) {
        state.value=(state.value.filterNot {it.id==record.id}+record.copy(frames=emptyList())).sortedByDescending {it.createdAt}
    }
    override suspend fun save(record:SessionRecord)=withContext(Dispatchers.IO) { mutex.withLock {
        if(record.id in deletedIds || isStale(record)) return@withLock
        persist(record)
        publish(record)
    } }
    override suspend fun append(record:SessionRecord,frames:List<VehicleFrame>,keepFromMs:Long?)=withContext(Dispatchers.IO) { mutex.withLock {
        if(record.id in deletedIds) return@withLock
        val stale=isStale(record)
        db.runInTransaction {
            appendChunks(record.id,frames)
            keepFromMs?.let {db.sessions().deleteFrameChunksBefore(record.id,it)}
            if(!stale) db.sessions().save(entity(record))
        }
        if(!stale) publish(record)
    } }
    override suspend fun delete(id:String)=withContext(Dispatchers.IO) { mutex.withLock {
        val record=state.value.firstOrNull { it.id==id }
        db.runInTransaction { db.sessions().delete(id);db.sessions().deleteFrameChunks(id) }
        deletedIds+=id
        record?.videoFile?.let { path -> val f=File(path);if(f.canonicalPath.startsWith(File(context.filesDir,"videos").canonicalPath+File.separator)) f.delete() }
        state.value=state.value.filterNot {it.id==id}
        context.preferences.edit {if(it[gleoSession]==id) it.remove(gleoSession)}
        Unit
    } }
    suspend fun prune(days:Int) { val cutoff=System.currentTimeMillis()-days*86400000L; records.value.filter { it.createdAt<cutoff && it.state!=Lifecycle.ACTIVE }.forEach { delete(it.id) } }
    suspend fun recoverInterrupted() { records.value.filter { it.state==Lifecycle.ACTIVE }.forEach { save(it.copy(state=Lifecycle.INTERRUPTED,error="앱 프로세스 종료로 중단됨 · 완료하지 못한 기록이에요",parkingCapture=it.parkingCapture?.copy(captureStatus="INTERRUPTED",interruptedReason="프로세스 종료로 녹화가 중단됐어요."))) } }
    fun close() { db.close() }
}
