package com.example.easycar.runtime

import com.easycar.core.*

/**
 * Connects one session's feedback queue to its speech engine: which receipt is being spoken, when
 * to interrupt it, and how engine status changes settle each receipt. Decisions read [SpeechPhase]
 * only. Not thread-safe; the runtime calls it from one thread.
 */
class VoiceCoordinator(
    val feedbacks:ImmediateFeedback,
    private var speech:SpeechOutput?,
    private val clock:TimeProvider,
    private val audio:(ownSpeech:Boolean)->AudioAvailability,
    private val muted:Boolean,
    /** Synthetic playback waits for speech; a real drive never does. */
    private val demo:Boolean,
    private val yawThresholdRadps:Double?) {
    /** The receipt our engine is speaking, or was last asked to speak. */
    var activeVoiceId:String?=null;private set
    private var speechWasStarted=false
    private var interruptedVoice=false
    val phase:SpeechPhase? get()=speech?.status?.value?.phase

    /** Applies one engine status. Returns true when it concerned an active receipt. */
    fun onStatus(status:SpeechStatus):Boolean {
        val id=activeVoiceId ?: return false
        when(status.phase) {
            SpeechPhase.INTERRUPTED->{feedbacks.interrupted(id);activeVoiceId=null;speechWasStarted=false}
            SpeechPhase.SPEAKING->{speechWasStarted=true;feedbacks.started(id)}
            SpeechPhase.FAILED->{feedbacks.finished(id,true);activeVoiceId=null;speechWasStarted=false}
            SpeechPhase.READY->if(speechWasStarted) {
                if(!interruptedVoice) feedbacks.finished(id)
                activeVoiceId=null;speechWasStarted=false;interruptedVoice=false
            }
            SpeechPhase.INITIALIZING->{}
        }
        return true
    }

    /**
     * Interrupts our speech when driving or other audio needs the driver's attention, then starts the
     * next waiting receipt when conditions allow. [simulatedBusy] stands in for priority audio in demos.
     */
    fun tick(motion:Motion?,simulatedBusy:Boolean) {
        if(demo && phase==SpeechPhase.FAILED) feedbacks.failPending("음성 엔진을 사용할 수 없어 화면으로 안내해요")
        val ownSpeech=activeVoiceId!=null
        val availability=audio(ownSpeech)
        val conditions=guidanceConditions(motion,if(simulatedBusy) false else availability.idle,muted,yawThresholdRadps=yawThresholdRadps)
        if(ownSpeech && (conditions.reversingOrTurning || availability.idle==false || simulatedBusy)) {
            activeVoiceId?.let {feedbacks.interrupted(it)};interruptedVoice=true;speech?.stop();activeVoiceId=null;speechWasStarted=false
        }
        val accepted=feedbacks.process(conditions,activeVoiceId!=null,if(simulatedBusy) "우선 오디오 시험 상태 · 종료 후 안내" else availability.reason,expirePending=!demo) {r->
            val ready=phase.let {it==SpeechPhase.READY || it==SpeechPhase.INTERRUPTED}
            ready && speech?.speak(r.resultId,r.voiceText ?: r.text)==true
        }
        if(accepted!=null) {activeVoiceId=accepted.resultId;speechWasStarted=false;interruptedVoice=false}
    }

    /** True when a demo may hold playback for speech: the car is stopped or straight, not turning or reversing. */
    fun speechSafe(motion:Motion?):Boolean {
        val conditions=guidanceConditions(motion,true,muted,yawThresholdRadps=yawThresholdRadps)
        return conditions.stationaryOrStraight && !conditions.reversingOrTurning
    }

    /** One step while a demo holds playback for speech. Gives up on an engine still starting after [timeoutMs]. */
    fun awaitStep(motion:Motion?,simulatedBusy:Boolean,waitingSinceMs:Long,timeoutMs:Long=ENGINE_START_TIMEOUT_MS) {
        if(activeVoiceId==null && phase==SpeechPhase.INITIALIZING && clock.nowMs()-waitingSinceMs>=timeoutMs) {
            feedbacks.failPending("음성 엔진 응답 지연 · 화면으로 안내해요")
        } else tick(motion,simulatedBusy)
    }

    /** Stops our speech without retrying it, e.g. when a demo pauses. */
    fun interrupt() {
        activeVoiceId?.let {feedbacks.interrupted(it)};speech?.stop();activeVoiceId=null
    }

    fun endSession() {feedbacks.endSession();activeVoiceId=null}

    /** Releases the engine. Receipts stay readable for the session summary. */
    fun release() {speech?.release();speech=null}

    companion object {const val ENGINE_START_TIMEOUT_MS=12000L}
}
