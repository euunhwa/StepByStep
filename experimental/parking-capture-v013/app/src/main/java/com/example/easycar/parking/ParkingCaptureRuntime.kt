package com.example.easycar.parking

import android.content.Intent
import android.os.SystemClock
import android.media.MediaMetadataRetriever
import com.easycar.core.*
import com.example.easycar.EasyCarApplication
import com.example.easycar.data.*
import com.example.easycar.runtime.LocalVideoInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

 data class ParkingCaptureState(val running:Boolean=false,val processing:Boolean=false,val record:SessionRecord?=null,
    val count:Int=0,val stage:String="준비됨",val finishedId:String?=null,val error:String?=null)
class ParkingCaptureRuntime(private val app:EasyCarApplication) {
    private val mutable=MutableStateFlow(ParkingCaptureState());val state=mutable.asStateFlow()
    private var recorder:ScreenParkingRecorder?=null;private var tracker:ParkingEpisodeTracker?=null;private var collect:Job?=null
    private val finishMutex=Mutex();private var revision=0L
    val policyVersion get()=policy.version
    private val policy get()=ParkingOutcomePolicy.fromJson(app.assets.open("profiles/parking-outcome-v1.json").bufferedReader().use {it.readText()})
    suspend fun start(resultCode:Int,data:Intent) {
        if(mutable.value.running) return
        mutable.value=ParkingCaptureState(running=true,stage="화면 녹화를 준비하고 있어요")
        var created:SessionRecord?=null
        try {
            app.ready.await();val settings=app.repository.settings.first()
            check(mutable.value.running) {"녹화 준비가 취소됐어요."}
            require(settings.consent && settings.saveVideo) {"시작 안내와 영상의 기기 내 저장에 동의해 주세요."}
            app.bridge.start()
            withTimeoutOrNull(2000) {app.bridge.state.first {it.ux.known}}
            check(mutable.value.running) {"녹화 준비가 취소됐어요."}
            require(!app.runtime.state.value.running && app.bridge.state.value.ux.allowsReview()) {"정차 후 다른 코칭을 종료하고 시작해 주세요."}
            val id=UUID.randomUUID().toString()
            val capture=ScreenParkingRecorder(app,File(app.filesDir,"videos/$id.pending.mp4")) {reason->app.scope.launch {finish(reason)}}
            recorder=capture;capture.start(resultCode,data)
            val start=capture.startedElapsedMs;tracker=ParkingEpisodeTracker(start);revision=0
            created=SessionRecord(id,"주차 녹화와 최종 배치",System.currentTimeMillis(),"사용자가 선택한 화면 녹화 · AVM 직접 입력 아님",policy.version,Lifecycle.ACTIVE,emptyList(),emptyList(),
                videoFile=File(app.filesDir,"videos/$id.pending.mp4").path,parkingCapture=ParkingCaptureRecord("USER_SELECTED_SCREEN","RECORDING",start))
            update(created);app.repository.save(mutable.value.record!!);app.bridge.start()
            if(!mutable.value.running) return
            mutable.value=mutable.value.copy(stage="녹화 중 · 이동 후 P단 정차를 유지하면 자동 완료해요")
            collect=app.scope.launch {
                var i=0
                app.bridge.frames.collect {frame->
                    val snapshot=tracker!!.accept(frame);val current=mutable.value.record ?: return@collect
                    update(current.copy(frames=(current.frames+frame).takeLast(5000),parkingCapture=current.parkingCapture!!.copy(episode=snapshot)))
                    mutable.value=mutable.value.copy(count=snapshot.reverseToDriveCount)
                    if(++i%20==0) app.repository.save(mutable.value.record!!)
                    if(snapshot.automaticCompletion) {app.scope.launch {finish()};return@collect}
                }
            }
        } catch(e:Exception) {
            recorder?.stop()?.file?.delete();recorder=null
            created?.let {app.repository.save(it.copy(state=Lifecycle.INTERRUPTED,error=e.message,parkingCapture=it.parkingCapture?.copy(captureStatus="FAILED",interruptedReason=e.message)))}
            mutable.value=ParkingCaptureState(error=e.message ?: "녹화를 시작하지 못했어요.",stage="녹화 시작 실패")
            if(!app.visible && !app.runtime.state.value.running) app.bridge.stop()
        }
    }
    private fun update(r:SessionRecord) {mutable.value=mutable.value.copy(record=r.copy(revision=++revision))}
    suspend fun finish(reason:String?=null)=finishMutex.withLock {
        if(!mutable.value.running || mutable.value.processing) return@withLock
        mutable.value=mutable.value.copy(processing=true,stage="영상과 주차 기록을 저장하고 있어요")
        collect?.cancelAndJoin();collect=null
        val record=mutable.value.record
        val ep=tracker?.snapshot()
        val saved=withContext(Dispatchers.IO) {if(record?.parkingCapture?.inputSource=="SYNTHETIC_REPLAY") SavedParkingVideo(record.videoFile?.let(::File),record.videoDurationMs,null) else recorder?.stop() ?: SavedParkingVideo(null,null,"녹화기가 없어요.")};recorder=null
        if(record!=null && ep!=null && app.repository.records.value.any {it.id==record.id}) {
            val duration=saved.durationMs
            val finalMs=if(ep.finalSignalMs!=null && duration!=null) (ep.finalSignalMs!!-ep.startedMs).takeIf {it in 0 until duration} else null
            val extra=listOfNotNull(reason,saved.error,if(finalMs==null) "영상과 최종 정차 시각을 연결하지 못했어요." else null)
            val outcome=ParkingOutcomeScorer(policy).score(ep,null,finalMs,saved.file!=null,extra)
            val finished=record.copy(state=if(reason==null && saved.file!=null) Lifecycle.COMPLETED else Lifecycle.INTERRUPTED,
                results=listOf(outcome.asManeuver(ep,record.source,record.id+"-parking")),videoFile=saved.file?.path,videoDurationMs=duration,
                videoSync=duration?.let {VideoSync(0,it,ep.startedMs,ep.startedMs+it)},videoAnalysisPositionMs=finalMs,
                parkingCapture=record.parkingCapture!!.copy(captureStatus=if(saved.file==null) "FAILED" else if(reason!=null) "INTERRUPTED" else "SAVED",episode=ep,outcome=outcome,finalVideoMs=finalMs,videoSaved=saved.file!=null,interruptedReason=reason ?: saved.error))
            update(finished);app.repository.save(mutable.value.record!!)
            mutable.value=mutable.value.copy(finishedId=record.id)
        }
        if(record!=null && app.repository.records.value.none {it.id==record.id}) saved.file?.delete()
        if(record?.parkingCapture?.inputSource=="SYNTHETIC_REPLAY" && record.calibration!=null && ep?.automaticCompletion==true) {
            runCatching {analyze(record.id,record.calibration,null)}.onFailure {mutable.value=mutable.value.copy(error=it.message)}
        }
        mutable.value=mutable.value.copy(running=false,processing=false,stage=if(saved.file!=null) "영상 저장 완료 · 최종 배치를 확인해 주세요" else "영상 저장 실패")
        if(!app.visible && !app.runtime.state.value.running) app.bridge.stop()
    }
    suspend fun runExample(kind:String,paceMs:Long=100) {
        require(kind in setOf("aligned","intrusion","blank","tilted"))
        if(mutable.value.running || app.runtime.state.value.running) return
        app.ready.await()
        val settings=app.repository.settings.first();require(settings.consent)
        val id=UUID.randomUUID().toString();val file=File(app.filesDir,"videos/$id.mp4");file.parentFile?.mkdirs()
        withContext(Dispatchers.IO) {app.assets.open("demo/parking-capture/$kind.mp4").use {i->file.outputStream().use {i.copyTo(it)}}}
        val reader=MediaMetadataRetriever();val duration=try {reader.setDataSource(file.path);reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()} finally {reader.release()}
        revision=0;tracker=ParkingEpisodeTracker(0)
        mutable.value=ParkingCaptureState(running=true,stage="합성 영상·신호 재생 · 실제 화면 녹화가 아니에요")
        update(SessionRecord(id,"주차 흐름 합성 예제 · $kind",System.currentTimeMillis(),"합성 영상·신호 예제 · 실제 어라운드뷰 녹화 아님",policy.version,Lifecycle.ACTIVE,emptyList(),emptyList(),
            videoFile=file.path,videoDurationMs=duration,videoSync=VideoSync(0,duration,0,duration),calibration=SyntheticParkingSlotFrames.calibration,
            parkingCapture=ParkingCaptureRecord("SYNTHETIC_REPLAY","REPLAYING",0,timeMappingKind="SYNTHETIC_SHARED_TIMELINE_NOT_CAMERA")))
        app.repository.save(mutable.value.record!!)
        collect=app.scope.launch {
            for(t in 0L..6200 step 100) {
                val gear=when {t<800->Gear.DRIVE;t<1600->Gear.REVERSE;t<2400->Gear.DRIVE;t<3200->Gear.REVERSE;t<5000->Gear.DRIVE;else->Gear.PARK}
                val frame=VehicleFrame(t,Signal(if(t<4000) .5 else 0.0,t,t,"m/s","SYNTHETIC_PARKING_FIXTURE"),null,Signal(gear,t,t,"gear","SYNTHETIC_PARKING_FIXTURE"),null)
                val ep=tracker!!.accept(frame);val current=mutable.value.record!!
                update(current.copy(frames=current.frames+frame,parkingCapture=current.parkingCapture!!.copy(episode=ep)))
                mutable.value=mutable.value.copy(count=ep.reverseToDriveCount)
                if(ep.automaticCompletion) {app.scope.launch {finish()};return@launch}
                delay(paceMs.coerceAtLeast(1))
            }
        }
    }
    suspend fun analyze(id:String,calibration:Calibration,manualSlot:List<Point>?=null) {
        val record=app.repository.records.value.firstOrNull {it.id==id} ?: return
        val capture=record.parkingCapture ?: return;val ep=capture.episode ?: return
        require(capture.outcome?.score==null) {"이미 공개한 점수는 변경하지 않아요."}
        val time=capture.finalVideoMs ?: error("최종 정차 시각을 확인하지 못했어요.")
        val (placement,hold)=withContext(Dispatchers.Default) {
            val input=LocalVideoInput(app);val calibrated=record.copy(calibration=calibration)
            val start=(time-600).coerceAtLeast(0)
            val frames=(0..4).map {i->input.pixelAtVideoTime(calibrated,start+(time-start)*i/4) ?: error("최종 영상 프레임을 읽지 못했어요.")}
            val frame=frames.last()
            val c=calibration.forDimensions(frame.width,frame.height) ?: error("영상과 보정 정보 크기가 달라요.")
            val vehicle=listOf(Point(c.vehicleLeftPx,c.vehicleTopPx),Point(c.vehicleRightPx,c.vehicleTopPx),Point(c.vehicleRightPx,c.vehicleBottomPx),Point(c.vehicleLeftPx,c.vehicleBottomPx))
            val observed=if(manualSlot!=null) {
                val scale=frame.width.toDouble()/(calibration.referenceWidth ?: frame.width)
                ParkingSlotObservation(ParkingPlacementGeometry.measure(manualSlot.map {Point(it.x*scale,it.y*scale)},vehicle,"USER_MARKED_SLOT_AND_CALIBRATED_VEHICLE"),null,null)
            } else ParkingSlotAnalyzer().analyze(frame,c)
            val p=observed.placement
            if(p==null) null to (observed.reason ?: "보정된 직사각형 영역을 확인해 주세요.")
            else ParkingCaptureFrameCheck.inspect(frames,p.slot,p.vehicle).let {reason->if(reason==null) p to null else null to reason}
        }
        val extra=listOfNotNull(capture.interruptedReason,hold)
        val outcome=ParkingOutcomeScorer(policy).score(ep,placement,time,capture.videoSaved,extra)
        // Recheck identity/revision after decoding so deletion and concurrent changes cannot revive a record.
        val fresh=app.repository.records.value.firstOrNull {it.id==id && it.revision==record.revision} ?: return
        val changed=fresh.copy(results=listOf(outcome.asManeuver(ep,fresh.source,fresh.id+"-parking")),calibration=calibration,
            parkingCapture=capture.copy(outcome=outcome,calibrationConfirmed=true),revision=fresh.revision+1)
        app.repository.save(changed)
    }
}
