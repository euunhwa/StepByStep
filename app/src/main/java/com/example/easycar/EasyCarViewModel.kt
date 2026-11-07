package com.example.easycar

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

class EasyCarViewModel(application:Application):AndroidViewModel(application) {
    private val app=application as EasyCarApplication
    val records=app.repository.records
    val settings=app.repository.settings.stateIn(viewModelScope,SharingStarted.Eagerly,UserSettings())
    val voiceStatus=app.runtime.voiceStatus
    val drive=app.runtime.state
    val platform=app.bridge.state
    val liveFrame=app.bridge.latestFrame
    val profile=app.profile
    val evaluationPolicy=app.evaluationPolicy
    val message=MutableStateFlow<String?>(null)
    init { viewModelScope.launch { settings.filter { it.consent }.collect { app.repository.prune(it.retentionDays) } } }
    fun updateSettings(s:UserSettings) { viewModelScope.launch { app.repository.updateSettings(s) } }
    fun selectGleoRecord(id:String?) { viewModelScope.launch {
        if(id==null) app.repository.selectGleoSession(null)
        else if(settings.value.consent && platform.value.ux.allowsReview() && !drive.value.running && records.value.any {it.id==id}) app.repository.selectGleoSession(id)
    } }
    fun startDemo(id:String,speed:Int=1,resume:String?=null) {
        if(!allowed()) return
        runCatching { ContextCompat.startForegroundService(app,Intent(app,CoachingService::class.java).putExtra("demo",id).putExtra("speed",speed).putExtra("resume",resume)) }.onFailure { message.value="연습을 시작하지 못했어요: ${it.message}" }
    }
    fun startCoaching() {
        if(BuildConfig.FLAVOR!="pleos") {message.value="차량 신호 연결은 Pleos 빌드에서 사용할 수 있어요. 우측 상단의 데모를 실행할 수 있어요.";return}
        startLive()
    }
    fun startSubmissionDemo()=startDemo("auto-drive")
    private fun startLive() {
        if(!allowed()) return
        runCatching { ContextCompat.startForegroundService(app,Intent(app,CoachingService::class.java)) }.onFailure { message.value="차량 코칭을 시작하지 못했어요: ${it.message}" }
    }
    private fun allowed():Boolean {
        if(!settings.value.consent) { message.value="먼저 시작 안내에 동의해 주세요";return false }
        if(!platform.value.ux.allowsReview()) { message.value="주행 제한 상태에서는 새 연습을 설정할 수 없어요";return false }
        return !drive.value.running
    }
    fun stop() { app.startService(Intent(app,CoachingService::class.java).setAction("stop")) }
    fun pauseDemo() {app.startService(Intent(app,CoachingService::class.java).setAction("pause"))}
    fun cancel() {app.startService(Intent(app,CoachingService::class.java).setAction("cancel"))}
    fun retryVehicle() { if(!drive.value.running) { app.bridge.stop();app.bridge.start() } }
    fun delete(id:String) { viewModelScope.launch { app.repository.delete(id) } }
    fun deleteAll() { viewModelScope.launch { records.value.toList().filter { it.id!=drive.value.record?.id || !drive.value.running }.forEach { app.repository.delete(it.id) } } }
    fun speak(text:String) { if(platform.value.ux.allowsReview() && !settings.value.muted) app.runtime.speakReview(text) }
    fun speakCoaching(result:ManeuverResult) { if(platform.value.ux.allowsReview() && !settings.value.muted) app.runtime.speakCoaching(result) }
    suspend fun queryLocalRecord(recordId:String,json:String):RecordQueryResponse {
        val access=RecordQueryAccess(setOf(recordId),settings.value.consent,
            platform.value.ux.allowsReview() && !drive.value.running,app.profile.version,app.evaluationPolicy.version)
        return withContext(Dispatchers.Default) {LocalRecordQueryGateway {app.repository.records.value}.execute(json,access)}
    }
    val videoAnalyzing=MutableStateFlow(false)
    private fun editVideo(recordId:String,success:String,edit:suspend (SessionRecord)->SessionRecord) {
        if(!platform.value.ux.allowsReview() || drive.value.running || videoAnalyzing.value) return
        videoAnalyzing.value=true
        viewModelScope.launch {
            try {
                val original=records.value.firstOrNull {it.id==recordId} ?: error("연습 기록이 없어요")
                val changed=edit(original)
                val end=changed.videoAnalysisPositionMs ?: ((changed.videoDurationMs ?: 0)-120).coerceAtLeast(0)
                val evidence=withContext(Dispatchers.Default) {LocalVideoInput(app).analyzeWindow(changed,end)}
                // Review geometry has its own version. Keep original scores, feedback and policy intact.
                app.repository.save(changed.copy(alignment=evidence,videoAnalysisPositionMs=end,revision=original.revision+1,
                    error=if(original.results.isEmpty() && original.videoFile!=null) evidence.reason else original.error))
                message.value=success
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {message.value=e.message ?: "영상 분석을 완료하지 못했어요"}
            finally {videoAnalyzing.value=false}
        }
    }
    fun attachCalibration(recordId:String,uri:Uri,positionMs:Long?=null)=editVideo(recordId,"보정 정보를 연결하고 정차 구간을 분석했어요.") {record->
        val json=withContext(Dispatchers.IO) {app.contentResolver.openInputStream(uri)?.bufferedReader()?.use {reader->
            val buffer=CharArray(128001);var count=0
            while(count<buffer.size) {val n=reader.read(buffer,count,buffer.size-count);if(n<0) break;count+=n}
            require(count<=128000) {"보정 정보는 128,000자 이하로 선택해 주세요"}
            String(buffer,0,count)
        } ?: error("보정 정보를 열 수 없어요")}
        val document=VideoCalibrationDocument.fromJson(json)
        require(document.sync?.let {it.videoEndMs<=(record.videoDurationMs ?: 0)} != false) {"보정 정보의 영상 시각이 실제 길이를 벗어나요"}
        record.copy(calibration=document.calibration,videoSync=document.sync,videoAnalysisPositionMs=positionMs ?: record.videoAnalysisPositionMs)
    }
    fun selectParkingTarget(recordId:String,ratio:Double,positionMs:Long?=null)=editVideo(recordId,"대상 칸을 선택하고 정차 구간을 분석했어요.") {record->
        val calibration=record.calibration ?: error("먼저 탑뷰 보정 정보를 연결해 주세요")
        require(ratio.isFinite() && ratio in 0.0..1.0)
        val width=calibration.referenceWidth ?: error("보정 영상의 기준 해상도가 필요해요")
        record.copy(calibration=calibration.copy(targetCenterPx=ratio*width),videoAnalysisPositionMs=positionMs ?: record.videoAnalysisPositionMs)
    }
    fun clearParkingTarget(recordId:String,positionMs:Long?=null)=editVideo(recordId,"대상 칸 선택을 해제했어요.") {record->record.copy(calibration=record.calibration?.copy(targetCenterPx=null),videoAnalysisPositionMs=positionMs ?: record.videoAnalysisPositionMs)}
    fun analyzeVideoWindow(recordId:String,positionMs:Long)=editVideo(recordId,"선택한 위치 앞 1초의 영상 분석을 저장했어요.") {record->record.copy(videoAnalysisPositionMs=positionMs)}
    fun createVideoExample(name:String,onCreated:(String)->Unit) {
        if(!allowed() || name !in setOf("aligned","tilted","intrusion","occluded","blank")) return
        viewModelScope.launch {
            val title=mapOf("aligned" to "정렬","tilted" to "기울어짐","intrusion" to "선 침범","occluded" to "가림","blank" to "선 없음").getValue(name)
            val record=SessionRecord(UUID.randomUUID().toString(),"영상 분석 예제 · $title",System.currentTimeMillis(),"합성 탑뷰 예제 영상",app.profile.version,Lifecycle.HELD,
                emptyList(),emptyList(),videoAsset="demo/video/$name.mp4",videoDurationMs=4000,calibration=SyntheticParkingFrames.calibration)
            app.repository.save(record)
            onCreated(record.id)
        }
    }
    fun importVideo(uri:Uri) { viewModelScope.launch {
        if(!allowed() || !settings.value.saveVideo) { message.value="설정에서 영상의 기기 내 저장에 먼저 동의해 주세요";return@launch }
        withContext(Dispatchers.IO) {
            val id=UUID.randomUUID().toString();val directory=File(app.filesDir,"videos").apply { mkdirs() };val out=File(directory,"$id.mp4")
            try {
                app.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { output ->
                    val buffer=ByteArray(8192);var total=0L
                    while(true) { val count=input.read(buffer);if(count<0) break;total+=count;require(total<=250_000_000) { "250MB 이하의 영상을 선택해 주세요" };output.write(buffer,0,count) }
                } } ?: error("영상을 열 수 없어요")
                val retriever=MediaMetadataRetriever();val duration=try { retriever.setDataSource(out.path);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: error("영상 길이 확인 실패") } finally { retriever.release() }
                app.repository.save(SessionRecord(id,"가져온 주차 영상",System.currentTimeMillis(),"사용자가 제공한 영상",app.profile.version,Lifecycle.HELD,emptyList(),emptyList(),videoFile=out.path,videoDurationMs=duration,error="보정·차량 기준축·신호 동기화 정보 없음 · 정렬 평가 보류"))
                message.value="영상을 저장했어요. 보정 정보가 없어 정렬·속도 평가는 보류돼요."
            } catch(e:Exception) { out.delete();message.value=e.message ?: "영상 가져오기 실패" }
        }
    } }
}
