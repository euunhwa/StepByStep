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
data class SessionRecord(val id:String,val title:String,val createdAt:Long,val source:String,val profileVersion:String,
    val state:Lifecycle,val results:List<ManeuverResult>,val frames:List<VehicleFrame>,val demoCaseId:String?=null,
    val progressIndex:Int=0,val videoAsset:String?=null,val videoFile:String?=null,val videoDurationMs:Long?=null,
    val alignment:AlignmentEvidence?=null,val error:String?=null,val feedbacks:List<FeedbackReceipt>?=null,val focusType:ManeuverType?=null,val videoSync:VideoSync?=null,val calibration:Calibration?=null,val revision:Long=0,val practiceContext:PracticeContext?=null,val videoAnalysisPositionMs:Long?=null,val parkingCapture:ParkingCaptureRecord?=null) {
    val score:Int? get()=if(results.isNotEmpty() && results.all { it.score!=null }) results.mapNotNull { it.score }.average().toInt() else null
}
interface SessionStore {
    val records:StateFlow<List<SessionRecord>>
    suspend fun save(record:SessionRecord)
    suspend fun delete(id:String)
}
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
    private fun reload() {
        val dao=db.sessions()
        // SQLite CursorWindow cannot hold a full long-drive JSON row. Read bounded text slices;
        // SQLite length/substr both count characters, so Korean text remains intact.
        state.value=dao.ids().mapNotNull { id ->
            val size=dao.payloadLength(id)
            val payload=buildString(size) {
                var offset=1
                while(offset<=size) { append(dao.payloadChunk(id,offset,65536));offset+=65536 }
            }
            runCatching { gson.fromJson(payload,SessionRecord::class.java) }.getOrNull()
        }
    }
    override suspend fun save(record:SessionRecord)=withContext(Dispatchers.IO) { mutex.withLock {
        if(record.id in deletedIds) return@withLock
        if((state.value.firstOrNull {it.id==record.id}?.revision ?: -1)>record.revision) return@withLock
        db.sessions().save(SessionEntity().also { it.id=record.id;it.createdAt=record.createdAt;it.payload=gson.toJson(record) })
        state.value=(state.value.filterNot {it.id==record.id}+record).sortedByDescending {it.createdAt}
    } }
    override suspend fun delete(id:String)=withContext(Dispatchers.IO) { mutex.withLock {
        val record=state.value.firstOrNull { it.id==id }
        db.sessions().delete(id);deletedIds+=id
        record?.videoFile?.let { path -> val f=File(path);if(f.canonicalPath.startsWith(File(context.filesDir,"videos").canonicalPath+File.separator)) f.delete() }
        state.value=state.value.filterNot {it.id==id}
        context.preferences.edit {if(it[gleoSession]==id) it.remove(gleoSession)}
        Unit
    } }
    suspend fun prune(days:Int) { val cutoff=System.currentTimeMillis()-days*86400000L; records.value.filter { it.createdAt<cutoff && it.state!=Lifecycle.ACTIVE }.forEach { delete(it.id) } }
    suspend fun recoverInterrupted() { records.value.filter { it.state==Lifecycle.ACTIVE }.forEach { save(it.copy(state=Lifecycle.INTERRUPTED,error="앱 프로세스 종료로 중단됨 · 완료하지 못한 기록이에요",parkingCapture=it.parkingCapture?.copy(captureStatus="INTERRUPTED",interruptedReason="프로세스 종료로 녹화가 중단됐어요."))) } }
    fun close() { db.close() }
}
