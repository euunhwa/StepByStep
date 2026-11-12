package com.example.easycar.runtime

import android.os.SystemClock
import com.easycar.core.*
import com.example.easycar.EasyCarApplication
import com.example.easycar.data.SessionRecord
import com.example.easycar.data.UserSettings
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.abs

data class DriveState(val running:Boolean=false,val record:SessionRecord?=null,val motion:Motion?=null,
    val progress:Float=0f,val stage:String="준비됨",val reverseToDriveCount:Int=0,val text:String?=null,
    val finishedId:String?=null,val simulatedRestriction:Boolean=false,val error:String?=null,
    val feedback:FeedbackReceipt?=null,val feedbackHistory:List<FeedbackReceipt> = emptyList(),val paused:Boolean=false)
/** [DriveState.record] carries no raw frames; read them with [DriveRuntime.recentFrames] or from the repository. */
class DriveRuntime(private val app:EasyCarApplication) {
    private val mutable=MutableStateFlow(DriveState());val state=mutable.asStateFlow()
    val voiceStatus=MutableStateFlow(SpeechStatus(SpeechPhase.INITIALIZING,"음성 준비 중"))
    private val clock=object:TimeProvider {override fun nowMs()=SystemClock.elapsedRealtime()}
    private val audio=AudioAvailabilityMonitor(app)
    private val videos=LocalVideoInput(app)
    /** One per session, so a late checkpoint of an ended session never touches the next one's frames. */
    @Volatile private var recorder=SessionRecorder(app.repository)
    /** Serializes stop() with the next session's setup: a quick restart cannot clear the record stop() is finishing. */
    private val lifecycle=Mutex()
    private var job:Job?=null;private var reviewJob:Job?=null
    private val feedbackSpeech=FeedbackSpeech()
    private var engine:CoachEngine?=null;private var voice:VoiceCoordinator?=null
    private var current:SessionRecord?=null
    private var settings=UserSettings()
    private val yawThreshold get()=app.evaluationPolicy.documentDataset?.recognition?.yawBumpRadps
    private var revision=0L
    private var feedbackTimer:Job?=null
    // finishedId stays set after a session ends; the report for it opens once, not on every new activity.
    private var reportShownSessionId:String?=null
    fun claimFinishedReport(id:String):Boolean {
        if(reportShownSessionId==id) return false
        reportShownSessionId=id;return true
    }
    /** Raw frames of the current (or last) session still held in memory, oldest first. */
    fun recentFrames():List<VehicleFrame> = recorder.window.snapshot()
    fun start(scope:CoroutineScope,demoId:String?,speed:Int,resumeId:String?,finished:()->Unit) {
        if(job?.isActive==true && mutable.value.running) return
        reviewJob?.cancel();val prior=job
        mutable.value=DriveState(running=true,stage="일곱 동작 자동 코칭을 준비하고 있어요")
        job=scope.launch {
            lifecycle.withLock {
                prior?.cancelAndJoin();current=null;engine=null;revision=0
                recorder=SessionRecorder(app.repository)
                // A stop() that finished while this waited reported running=false; this session owns the state now.
                mutable.value=DriveState(running=true,stage="일곱 동작 자동 코칭을 준비하고 있어요")
            }
            settings=app.repository.settings.first()
            if(!settings.consent) {mutable.value=mutable.value.copy(running=false,error="먼저 학습 안내를 확인해 주세요");finished();return@launch}
            val speech=com.example.easycar.PlatformFactory.speech(app)
            val v=VoiceCoordinator(ImmediateFeedback(clock,FeedbackConfig(minimumIntervalMs=settings.voiceIntervalSeconds.coerceIn(3,30)*1000L),feedbackSpeech),
                speech,clock,audio::snapshot,settings.muted,demo=demoId!=null,yawThreshold)
            voice=v
            val voiceUpdates=launch {speech.status.collect {status->
                voiceStatus.value=status
                if(v.onStatus(status)) {updateFeedbackState();checkpointAsync()}
            }}
            // Also expires cards during input gaps or a paused demo, for every screen size.
            feedbackTimer=launch {while(isActive) {
                if(mutable.value.paused) updateFeedbackState() else tickVoice(mutable.value.simulatedRestriction)
                delay(200)
            }}
            try {
                if(demoId!=null) replay(demoId,speed,resumeId) else observe()
            } catch(c:CancellationException) {throw c}
            catch(e:Exception) {
                mutable.value=mutable.value.copy(error=e.message ?: "연습 분석 중 오류가 발생했어요")
                current?.let {setRecord(it.copy(state=Lifecycle.INTERRUPTED,error=e.message));checkpoint()}
            } finally {
                feedbackTimer?.cancel();feedbackTimer=null;voiceUpdates.cancel()
                v.release()
                if(!app.visible) app.bridge.stop()
                mutable.value=mutable.value.copy(running=false,paused=false)
                if(currentCoroutineContext().isActive) finished()
            }
        }
    }
    private fun setRecord(r:SessionRecord) {
        current=r.copy(revision=++revision,feedbacks=voice?.feedbacks?.receipts ?: r.feedbacks)
        mutable.value=mutable.value.copy(record=current)
    }
    private fun updateFeedbackState() {
        val visible=voice?.feedbacks?.visible
        mutable.value=mutable.value.copy(feedback=visible,feedbackHistory=voice?.feedbacks?.receipts.orEmpty(),text=visible?.text)
        current?.let {setRecord(it)}
    }
    /** Writes the current record when its turn comes, unless another session has replaced it by then. */
    private suspend fun checkpoint() {
        val id=current?.id ?: return
        recorder.checkpoint {current?.takeIf {it.id==id}}
    }
    /** For speech events: never blocks the frame loop, and still runs in order with other checkpoints. */
    private fun checkpointAsync() {
        val id=current?.id ?: return;val rec=recorder
        app.scope.launch {rec.checkpoint {current?.takeIf {it.id==id}}}
    }
    private suspend fun publish(new:List<ManeuverResult>,detectedAtMs:Long) {
        val e=engine ?: return;val v=voice ?: return
        // Publish and persist before decoding optional video or waiting for speech.
        new.forEach {v.feedbacks.publish(it,settings.muted,detectedAtMs)}
        current?.let {setRecord(it.copy(results=e.results))}
        updateFeedbackState()
        if(new.isNotEmpty()) checkpoint()
        for(raw in new.filter {isParking(it.type)}) {
            val r=current ?: continue
            if(r.videoAsset==null && r.videoFile==null) continue
            val evidence=withContext(Dispatchers.Default) {videos.frameAt(r,raw.endMs)?.let {ParkingLineAnalyzer().analyze(it,r.calibration)}}
            if(evidence!=null) {
                e.refineParking(evidence)?.let {v.feedbacks.refine(it)}
                setRecord(current!!.copy(alignment=evidence,results=e.results))
                updateFeedbackState();checkpoint()
            }
        }
    }
    private suspend fun replay(id:String,speed:Int,resumeId:String?) {
        val document=DocumentDemoCatalog.case(id)
        val c=document ?: DemoCatalog.case(id)
        // auto-drive and the legacy demos have exported fixtures; other document demos are generated in code.
        val asset=when {id=="auto-drive"->"datasets/document-v1/auto-drive.json";document!=null->null;else->"demo/signals/$id.json"}
        val frames=asset?.let {a->runCatching {app.assets.open(a).bufferedReader().use {Gson().fromJson(it,Array<VehicleFrame>::class.java).toList()}}.getOrNull()} ?: c.frames
        val old=app.repository.records.value.firstOrNull {it.id==resumeId && it.demoCaseId==id}
        require(old==null || old.profileVersion==app.profile.version) {"이전 평가 버전의 기록은 복기만 가능해요. 새 코칭을 시작해 주세요."}
        val e=CoachEngine(app.profile,c.context,evaluationPolicy=app.evaluationPolicy);engine=e
        revision=old?.revision ?: 0
        val begin=old?.progressIndex?.coerceIn(0,frames.size) ?: 0
        // A resumed session already stored the frames it consumed.
        recorder.window.reset(frames.take(begin))
        setRecord(old?.copy(state=Lifecycle.ACTIVE,error=null) ?: SessionRecord(UUID.randomUUID().toString(),c.title,System.currentTimeMillis(),"합성 데모 데이터",app.profile.version,Lifecycle.ACTIVE,emptyList(),emptyList(),id,
            videoAsset=videos.assetFor(c),videoDurationMs=4000,
            videoSync=if(c.videoAngle!=null) VideoSync(0,4000,frames.first().timeMs,frames.last().timeMs) else null,
            calibration=if(c.videoAngle!=null) SyntheticParkingFrames.calibration else null,practiceContext=c.context))
        frames.take(begin).forEach {e.accept(it)}
        mutable.value=mutable.value.copy(running=true,stage=if(c.group=="주차") "주차 동작을 관측하고 있어요" else "일곱 동작을 자동으로 관측하고 있어요")
        for(i in begin until frames.size) {
            while(mutable.value.paused) {delay(100)}
            val frame=frames[i]
            val events=e.accept(frame)
            // Added with progressIndex in one step, so stored frames always match it on resume.
            recorder.window.add(frame)
            val detectedAt=clock.nowMs()
            setRecord(current!!.copy(progressIndex=i+1,results=e.results))
            mutable.value=mutable.value.copy(motion=e.lastMotion,progress=(i+1).toFloat()/frames.size,stage=e.activeType,reverseToDriveCount=e.reverseToDriveCount,
                simulatedRestriction=c.fault=="restrictions" && frame.timeMs in 6000..12000)
            publish(events,detectedAt)
            tickVoice(mutable.value.simulatedRestriction)
            if(i%20==0) checkpoint()
            awaitDemoSpeech()
            delay((100L/speed.coerceIn(1,8)).coerceAtLeast(12))
        }
        e.finish()
        setRecord(current!!.copy(state=Lifecycle.COMPLETED,results=e.results))
        checkpoint()
        mutable.value=mutable.value.copy(stage="모든 연습을 마쳤어요",simulatedRestriction=false)
        // The final narration also finishes before the session closes; no fixed speech cutoff.
        awaitDemoSpeech()
        voice?.endSession();updateFeedbackState()
        checkpoint()
        mutable.value=mutable.value.copy(finishedId=current!!.id)
    }
    private suspend fun awaitDemoSpeech() {
        val v=voice ?: return
        if(!v.feedbacks.hasPendingVoice || mutable.value.simulatedRestriction) return
        // Freeze only synthetic playback, after a completed maneuver has reached a speech-safe frame.
        // Real vehicle observation must keep consuming signals while speech is in progress.
        if(!v.speechSafe(engine?.lastMotion)) return
        val startedWaiting=clock.nowMs()
        while(v.feedbacks.hasPendingVoice) {
            if(!mutable.value.paused) {v.awaitStep(engine?.lastMotion,mutable.value.simulatedRestriction,startedWaiting);updateFeedbackState()}
            delay(50)
        }
        checkpoint()
    }
    private suspend fun observe() {
        val emulatorModel=com.example.easycar.BuildConfig.DEBUG && app.bridge.state.value.isEmulator
        val context=PracticeContext(null,null,controlled=false,selectionSource="코칭 시작 후 자동 관측",dataSource=if(emulatorModel) "8088 에뮬레이터 · 회전율 모델 사용 / 실차 센서 아님" else "Pleos 차량 관측 신호")
        val e=CoachEngine(app.profile,context,evaluationPolicy=app.evaluationPolicy);engine=e
        recorder.window.reset()
        setRecord(SessionRecord(UUID.randomUUID().toString(),"일곱 동작 자동 코칭",System.currentTimeMillis(),context.dataSource,app.profile.version,Lifecycle.ACTIVE,emptyList(),emptyList(),practiceContext=context))
        checkpoint();app.bridge.start()
        app.bridge.frames.collect {frame->
            val events=e.accept(frame)
            recorder.window.add(frame)
            val detectedAt=clock.nowMs()
            setRecord(current!!.copy(progressIndex=current!!.progressIndex+1,results=e.results))
            mutable.value=mutable.value.copy(motion=e.lastMotion,stage=e.activeType,reverseToDriveCount=e.reverseToDriveCount)
            publish(events,detectedAt);tickVoice(false)
            if(current!!.progressIndex%20==0) checkpoint()
        }
    }
    private fun tickVoice(simulatedBusy:Boolean) {
        voice?.tick(engine?.lastMotion,simulatedBusy)
        updateFeedbackState()
    }
    /** Idempotent, so a repeated request cannot flip the demo back. */
    fun setDemoPaused(paused:Boolean) {
        if(current?.demoCaseId==null || !mutable.value.running || mutable.value.paused==paused) return
        mutable.value=mutable.value.copy(paused=paused)
        if(paused) voice?.interrupt()
    }
    suspend fun stop(cancelled:Boolean=false) {
        val session=job
        voice?.endSession()
        lifecycle.withLock {
            session?.cancelAndJoin();if(job===session) job=null
            // Read after the job ends, so the final record matches the frames the recorder holds.
            val r=current ?: return@withLock
            val result=if(r.state==Lifecycle.ACTIVE) engine?.finish(cancelled=cancelled) ?: r.results else r.results
            setRecord(r.copy(state=if(cancelled) Lifecycle.CANCELLED else Lifecycle.COMPLETED,results=result,error=if(cancelled) "사용자가 연습을 취소했어요" else null))
            updateFeedbackState();checkpoint();mutable.value=mutable.value.copy(finishedId=r.id,stage="주행을 마쳤어요",running=false)
        }
    }
    fun speakCoaching(result:ManeuverResult) {speakReview(feedbackSpeech.text(result))}
    fun speakReview(text:String) {
        if(mutable.value.running) return
        reviewJob?.cancel()
        reviewJob=app.scope.launch {
            val output=com.example.easycar.PlatformFactory.speech(app)
            val statuses=launch {output.status.collect {voiceStatus.value=it}}
            val restrictions=launch {app.bridge.state.collect {if(!it.ux.allowsReview()) output.stop()}}
            try {
                val ready=withTimeoutOrNull(VoiceCoordinator.ENGINE_START_TIMEOUT_MS) {output.status.first {it.phase!=SpeechPhase.INITIALIZING}}
                if(ready?.phase==SpeechPhase.READY && app.bridge.state.value.ux.allowsReview()) {
                    if(output.speak("review-${clock.nowMs()}",text)) withTimeoutOrNull(20000) {
                        output.status.first {it.phase==SpeechPhase.SPEAKING || it.phase==SpeechPhase.FAILED}
                        output.status.first {it.phase!=SpeechPhase.SPEAKING}
                    } else voiceStatus.value=SpeechStatus(SpeechPhase.READY,"음성 출력 조건 미충족 · 글로 안내")
                } else if(ready==null) voiceStatus.value=SpeechStatus(SpeechPhase.FAILED,"음성 엔진 응답 지연 · 글로 안내")
            } finally {statuses.cancel();restrictions.cancel();output.release()}
        }
    }
}
