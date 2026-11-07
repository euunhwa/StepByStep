package com.example.easycar.runtime

import android.os.SystemClock
import com.easycar.core.*
import com.example.easycar.EasyCarApplication
import com.example.easycar.data.SessionRecord
import com.example.easycar.data.UserSettings
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.math.abs

data class DriveState(val running:Boolean=false,val record:SessionRecord?=null,val motion:Motion?=null,
    val progress:Float=0f,val stage:String="준비됨",val reverseToDriveCount:Int=0,val text:String?=null,
    val finishedId:String?=null,val simulatedRestriction:Boolean=false,val error:String?=null,
    val feedback:FeedbackReceipt?=null,val feedbackHistory:List<FeedbackReceipt> = emptyList(),val paused:Boolean=false)
class DriveRuntime(private val app:EasyCarApplication) {
    private val mutable=MutableStateFlow(DriveState());val state=mutable.asStateFlow()
    val voiceStatus=MutableStateFlow("음성 준비 중")
    private val clock=object:TimeProvider {override fun nowMs()=SystemClock.elapsedRealtime()}
    private val audio=AudioAvailabilityMonitor(app)
    private val videos=LocalVideoInput(app)
    private var job:Job?=null;private var reviewJob:Job?=null;private var speech:SpeechOutput?=null
    private val feedbackSpeech=FeedbackSpeech()
    private var engine:CoachEngine?=null;private var feedbacks=ImmediateFeedback(clock,speech=feedbackSpeech)
    private var current:SessionRecord?=null;private var activeVoiceId:String?=null
    private var speechWasStarted=false;private var interruptedVoice=false
    private var settings=UserSettings()
    private var revision=0L
    private var feedbackTimer:Job?=null
    fun start(scope:CoroutineScope,demoId:String?,speed:Int,resumeId:String?,finished:()->Unit) {
        if(job?.isActive==true && mutable.value.running) return
        reviewJob?.cancel();val prior=job
        mutable.value=DriveState(running=true,stage="일곱 동작 자동 코칭을 준비하고 있어요")
        job=scope.launch {
            prior?.cancelAndJoin();current=null;engine=null;activeVoiceId=null;speechWasStarted=false;revision=0
            settings=app.repository.settings.first()
            if(!settings.consent) {mutable.value=mutable.value.copy(running=false,error="먼저 학습 안내를 확인해 주세요");finished();return@launch}
            feedbacks=ImmediateFeedback(clock,FeedbackConfig(minimumIntervalMs=settings.voiceIntervalSeconds.coerceIn(3,30)*1000L),feedbackSpeech)
            speech=com.example.easycar.PlatformFactory.speech(app)
            val voiceUpdates=launch {speech?.status?.collect {status->
                voiceStatus.value=status
                val id=activeVoiceId
                if(id!=null) {
                    when {
                        status.contains("음성 중단")-> {feedbacks.interrupted(id);activeVoiceId=null;speechWasStarted=false}
                        status.contains("안내 중")-> {speechWasStarted=true;feedbacks.started(id)}
                        listOf("오류","실패","불가").any {status.contains(it)}->{feedbacks.finished(id,true);activeVoiceId=null;speechWasStarted=false}
                        speechWasStarted && status.contains("준비됨")->{if(!interruptedVoice) feedbacks.finished(id);activeVoiceId=null;speechWasStarted=false;interruptedVoice=false}
                    }
                    updateFeedbackState();checkpointAsync()
                }
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
                speech?.release();speech=null
                if(!app.visible) app.bridge.stop()
                mutable.value=mutable.value.copy(running=false,paused=false)
                if(currentCoroutineContext().isActive) finished()
            }
        }
    }
    private fun setRecord(r:SessionRecord) {
        current=r.copy(revision=++revision,feedbacks=feedbacks.receipts)
        mutable.value=mutable.value.copy(record=current)
    }
    private fun updateFeedbackState() {
        val visible=feedbacks.visible
        mutable.value=mutable.value.copy(feedback=visible,feedbackHistory=feedbacks.receipts,text=visible?.text)
        current?.let {setRecord(it)}
    }
    private suspend fun checkpoint() {current?.let {app.repository.save(it)}}
    private fun checkpointAsync() {val r=current ?: return;app.scope.launch {app.repository.save(r)}}
    private suspend fun publish(new:List<ManeuverResult>,detectedAtMs:Long) {
        val e=engine ?: return
        // Publish and persist before decoding optional video or waiting for speech.
        new.forEach {feedbacks.publish(it,settings.muted,detectedAtMs)}
        current?.let {setRecord(it.copy(results=e.results))}
        updateFeedbackState()
        if(new.isNotEmpty()) checkpoint()
        for(raw in new.filter {isParking(it.type)}) {
            val r=current ?: continue
            if(r.videoAsset==null && r.videoFile==null) continue
            val evidence=withContext(Dispatchers.Default) {videos.frameAt(r,raw.endMs)?.let {ParkingLineAnalyzer().analyze(it,r.calibration)}}
            if(evidence!=null) {
                e.refineParking(evidence)?.let {feedbacks.refine(it)}
                setRecord(current!!.copy(alignment=evidence,results=e.results))
                updateFeedbackState();checkpoint()
            }
        }
    }
    private suspend fun replay(id:String,speed:Int,resumeId:String?) {
        val c=if(id=="auto-drive") DocumentDemoCatalog.automaticDrive else DemoCatalog.case(id)
        val frames=runCatching {app.assets.open(if(id=="auto-drive") "datasets/document-v1/auto-drive.json" else "demo/signals/$id.json").bufferedReader().use {Gson().fromJson(it,Array<VehicleFrame>::class.java).toList()}}.getOrElse {c.frames}
        val old=app.repository.records.value.firstOrNull {it.id==resumeId && it.demoCaseId==id}
        require(old==null || old.profileVersion==app.profile.version) {"이전 평가 버전의 기록은 복기만 가능해요. 새 코칭을 시작해 주세요."}
        val e=CoachEngine(app.profile,c.context,evaluationPolicy=app.evaluationPolicy);engine=e
        revision=old?.revision ?: 0
        setRecord(old?.copy(state=Lifecycle.ACTIVE,error=null) ?: SessionRecord(UUID.randomUUID().toString(),c.title,System.currentTimeMillis(),"합성 데모 데이터",app.profile.version,Lifecycle.ACTIVE,emptyList(),emptyList(),id,
            videoAsset=videos.assetFor(c),videoDurationMs=4000,
            videoSync=if(c.videoAngle!=null) VideoSync(0,4000,frames.first().timeMs,frames.last().timeMs) else null,
            calibration=if(c.videoAngle!=null) SyntheticParkingFrames.calibration else null,practiceContext=c.context))
        val begin=old?.progressIndex?.coerceIn(0,frames.size) ?: 0
        frames.take(begin).forEach {e.accept(it)}
        mutable.value=mutable.value.copy(running=true,stage="일곱 동작을 자동으로 관측하고 있어요")
        for(i in begin until frames.size) {
            while(mutable.value.paused) {delay(100)}
            val frame=frames[i]
            val events=e.accept(frame)
            val detectedAt=clock.nowMs()
            setRecord(current!!.copy(frames=frames.take(i+1),progressIndex=i+1,results=e.results))
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
        feedbacks.endSession();activeVoiceId=null;updateFeedbackState()
        checkpoint()
        mutable.value=mutable.value.copy(finishedId=current!!.id)
    }
    private suspend fun awaitDemoSpeech() {
        if(!feedbacks.hasPendingVoice || mutable.value.simulatedRestriction) return
        // Freeze only synthetic playback, after a completed maneuver has reached a speech-safe frame.
        // Real vehicle observation must keep consuming signals while speech is in progress.
        val conditions=guidanceConditions(engine?.lastMotion,true,settings.muted,yawThresholdRadps=app.evaluationPolicy.documentDataset?.recognition?.yawBumpRadps)
        if(!conditions.stationaryOrStraight || conditions.reversingOrTurning) return
        val startedWaiting=clock.nowMs()
        while(feedbacks.hasPendingVoice) {
            if(!mutable.value.paused) {
                val status=speech?.status?.value.orEmpty()
                if(activeVoiceId==null && status.contains("초기화 중") && clock.nowMs()-startedWaiting>=12000) {
                    feedbacks.failPending("음성 엔진 응답 지연 · 화면으로 안내해요")
                    updateFeedbackState()
                } else tickVoice(mutable.value.simulatedRestriction)
            }
            delay(50)
        }
        checkpoint()
    }
    private suspend fun observe() {
        val emulatorModel=com.example.easycar.BuildConfig.DEBUG && app.bridge.state.value.isEmulator
        val context=PracticeContext(null,null,controlled=false,selectionSource="코칭 시작 후 자동 관측",dataSource=if(emulatorModel) "8088 에뮬레이터 · 회전율 모델 사용 / 실차 센서 아님" else "Pleos 차량 관측 신호")
        val e=CoachEngine(app.profile,context,evaluationPolicy=app.evaluationPolicy);engine=e
        setRecord(SessionRecord(UUID.randomUUID().toString(),"일곱 동작 자동 코칭",System.currentTimeMillis(),context.dataSource,app.profile.version,Lifecycle.ACTIVE,emptyList(),emptyList(),practiceContext=context))
        checkpoint();app.bridge.start()
        app.bridge.frames.collect {frame->
            val events=e.accept(frame)
            val detectedAt=clock.nowMs()
            setRecord(current!!.copy(frames=(current!!.frames+frame).takeLast(36000),progressIndex=current!!.progressIndex+1,results=e.results))
            mutable.value=mutable.value.copy(motion=e.lastMotion,stage=e.activeType,reverseToDriveCount=e.reverseToDriveCount)
            publish(events,detectedAt);tickVoice(false)
            if(current!!.progressIndex%20==0) checkpoint()
        }
    }
    private fun tickVoice(simulatedBusy:Boolean) {
        val demo=current?.demoCaseId!=null
        if(demo && listOf("오류","실패","불가","없음").any {speech?.status?.value?.contains(it)==true}) {
            feedbacks.failPending("음성 엔진을 사용할 수 없어 화면으로 안내해요")
        }
        val m=engine?.lastMotion
        val ownSpeech=activeVoiceId!=null
        val availability=audio.snapshot(ownSpeech)
        val conditions=guidanceConditions(m,if(simulatedBusy) false else availability.idle,settings.muted,yawThresholdRadps=app.evaluationPolicy.documentDataset?.recognition?.yawBumpRadps)
        if(ownSpeech && (conditions.reversingOrTurning || availability.idle==false || simulatedBusy)) {
            activeVoiceId?.let {feedbacks.interrupted(it)};interruptedVoice=true;speech?.stop();activeVoiceId=null;speechWasStarted=false
        }
        val accepted=feedbacks.process(conditions,activeVoiceId!=null,if(simulatedBusy) "우선 오디오 시험 상태 · 종료 후 안내" else availability.reason,expirePending=!demo) {r->
            val ready=speech?.status?.value?.let {it.contains("준비됨") || it.contains("음성 중단")}==true
            if(!ready) false else speech?.speak(r.resultId,r.voiceText ?: r.text)==true
        }
        if(accepted!=null) {activeVoiceId=accepted.resultId;speechWasStarted=false;interruptedVoice=false}
        updateFeedbackState()
    }
    fun pauseDemo() {
        if(current?.demoCaseId==null || !mutable.value.running) return
        mutable.value=mutable.value.copy(paused=!mutable.value.paused)
        if(mutable.value.paused) {activeVoiceId?.let {feedbacks.interrupted(it)};speech?.stop();activeVoiceId=null}
    }
    suspend fun stop(cancelled:Boolean=false) {
        val r=current
        feedbacks.endSession();activeVoiceId=null
        job?.cancelAndJoin();job=null
        if(r!=null) {
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
                val ready=withTimeoutOrNull(12000) {output.status.first {!it.contains("초기화 중")}}
                if(ready?.contains("준비됨")==true && app.bridge.state.value.ux.allowsReview()) {
                    if(output.speak("review-${clock.nowMs()}",text)) withTimeoutOrNull(20000) {
                        output.status.first {it.contains("안내 중") || it.contains("오류") || it.contains("실패")}
                        output.status.first {!it.contains("안내 중")}
                    } else voiceStatus.value="음성 출력 조건 미충족 · 글로 안내"
                } else if(ready==null) voiceStatus.value="음성 엔진 응답 지연 · 글로 안내"
            } finally {statuses.cancel();restrictions.cancel();output.release()}
        }
    }
}
