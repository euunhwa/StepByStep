package com.easycar.core

enum class VoiceState(val title:String) {
    WAITING("음성 준비"), SPEAKING("음성 안내 중"), SPOKEN("음성 안내 완료"),
    MUTED("무음 설정 · 화면 안내"), DEFERRED("음성 대기"), EXPIRED("주행 후 요약으로 이동"), FAILED("음성 오류 · 화면 안내")
}
data class FeedbackReceipt(val resultId:String,val type:ManeuverType,val title:String,val text:String,val score:Int?,
    val eventEndMs:Long,val publishedAtMs:Long,val voiceState:VoiceState,val voiceReason:String?,
    val deliveredDelayMs:Long?=null,val visualDelayMs:Long?=null,val scoreKind:String?=null,val scoreBasis:String?=null,val reason:String?=null,val voiceText:String?=null)
data class FeedbackConfig(val minimumIntervalMs:Long=6000,val expiresMs:Long=18000,val displayMs:Long=DEFAULT_DISPLAY_MS,val maxPending:Int=4) {
    companion object {const val DEFAULT_DISPLAY_MS=5000L}
}

/** Publication is synchronous and independent from speaker readiness. A failed voice never hides visual feedback. */
class ImmediateFeedback(private val clock:TimeProvider,private val config:FeedbackConfig=FeedbackConfig(),private val speech:FeedbackSpeech=FeedbackSpeech()) {
    private val history=mutableListOf<FeedbackReceipt>()
    private val queue=ArrayDeque<String>()
    private var lastDelivered=Long.MIN_VALUE/2
    val receipts get()=history.toList()
    val latest get()=history.lastOrNull()
    val hasPendingVoice get()=queue.isNotEmpty() || history.any {it.voiceState==VoiceState.SPEAKING}
    // History and delayed speech survive visual expiry. Callbacks retain the original publish time.
    val visible get()=latest?.takeIf {clock.nowMs()-it.publishedAtMs in 0 until config.displayMs}
    fun publish(result:ManeuverResult,muted:Boolean,detectedAtMs:Long=clock.nowMs()):FeedbackReceipt? {
        if(history.any {it.resultId==result.id}) return null
        val receipt=FeedbackReceipt(result.id,result.type,if(result.type==ManeuverType.UNKNOWN) "회전 판단 보류" else if(result.type==ManeuverType.PARKING_UNKNOWN) "저속 조작 · 주차 유형 보류" else "${result.type.title} ${if(result.lifecycle==Lifecycle.INTERRUPTED) "관측 중단" else "완료"}",result.coaching,result.score,
            result.endMs,clock.nowMs(),if(muted) VoiceState.MUTED else VoiceState.WAITING,if(muted) "음성 안내가 꺼져 있어요" else null,visualDelayMs=(clock.nowMs()-detectedAtMs).coerceAtLeast(0),scoreKind=result.scoreKind,scoreBasis=result.scoreBasis,reason=FeedbackSummary.reason(result),voiceText=speech.text(result))
        history+=receipt
        if(!muted) {queue+=result.id;while(queue.size>config.maxPending) update(queue.removeFirst(),VoiceState.EXPIRED,"다음 동작을 위한 요약에 보관했어요")}
        return receipt
    }
    /** Refine an already published score without republishing, extending its lifetime or replaying speech. */
    fun refine(result:ManeuverResult):Boolean {
        val i=history.indexOfFirst {it.resultId==result.id};if(i<0) return false
        val r=history[i]
        val mayUpdateText=r.voiceState in setOf(VoiceState.WAITING,VoiceState.DEFERRED,VoiceState.MUTED)
        // Keep the selected compliment stable across retries and refinements with no spoken change.
        val sameSpeech=r.score==result.score && r.type==result.type && r.reason==FeedbackSummary.reason(result) && r.text==result.coaching
        history[i]=r.copy(score=result.score,scoreKind=result.scoreKind,scoreBasis=result.scoreBasis,reason=FeedbackSummary.reason(result),text=if(mayUpdateText) result.coaching else r.text,
            voiceText=if(mayUpdateText && !sameSpeech) speech.text(result) else r.voiceText)
        return true
    }
    private fun update(id:String,status:VoiceState,reason:String?,delay:Long?=null) {
        val i=history.indexOfFirst {it.resultId==id}
        if(i>=0) history[i]=history[i].copy(voiceState=status,voiceReason=reason,deliveredDelayMs=delay ?: history[i].deliveredDelayMs)
    }
    fun process(conditions:GuidanceConditions,speakerBusy:Boolean,reason:String?,expirePending:Boolean=true,speak:(FeedbackReceipt)->Boolean):FeedbackReceipt? {
        val now=clock.nowMs()
        while(expirePending && queue.isNotEmpty()) {
            val r=history.first {it.resultId==queue.first()}
            if(now-r.publishedAtMs<=config.expiresMs) break
            update(queue.removeFirst(),VoiceState.EXPIRED,"오래된 안내는 주행 후 요약에서 확인해 주세요")
        }
        val id=queue.firstOrNull() ?: return null
        if(conditions.muted) {queue.removeFirst();update(id,VoiceState.MUTED,"음성 안내가 꺼져 있어요");return null}
        val blocked=reason ?: when {
            speakerBusy->"이전 음성 안내가 끝나면 알려드려요"
            conditions.reversingOrTurning->"회전·후진이 끝나면 알려드려요"
            !conditions.stationaryOrStraight->"정차 또는 안정된 직진 구간을 기다려요"
            conditions.priorityAudioIdle==null->"우선 오디오 상태 확인 중"
            conditions.priorityAudioIdle==false->"내비·통화·우선 오디오가 끝나면 알려드려요"
            now-lastDelivered<config.minimumIntervalMs->"다음 안내 간격을 기다려요"
            else->null
        }
        if(blocked!=null) {update(id,VoiceState.DEFERRED,blocked);return null}
        val r=history.first {it.resultId==id}
        if(!speak(r)) {update(id,VoiceState.DEFERRED,"음성 준비 또는 오디오 포커스 승인 대기");return null}
        queue.removeFirst();lastDelivered=now;update(id,VoiceState.SPEAKING,null,now-r.publishedAtMs)
        return history.first {it.resultId==id}
    }
    fun started(id:String) {val receipt=history.firstOrNull {it.resultId==id} ?: return;update(id,VoiceState.SPEAKING,null,clock.nowMs()-receipt.publishedAtMs)}
    fun finished(id:String,failed:Boolean=false) {update(id,if(failed) VoiceState.FAILED else VoiceState.SPOKEN,if(failed) "음성 엔진 오류 · 화면 안내는 유지돼요" else null)}
    fun failPending(reason:String) {
        while(queue.isNotEmpty()) update(queue.removeFirst(),VoiceState.FAILED,reason)
    }
    fun endSession() {
        queue.clear()
        history.toList().filter {it.voiceState in setOf(VoiceState.WAITING,VoiceState.DEFERRED,VoiceState.SPEAKING)}.forEach {update(it.resultId,VoiceState.EXPIRED,"연습을 마쳤어요 · 음성 대신 요약에서 확인해 주세요")}
    }
    fun interrupted(id:String) {update(id,VoiceState.EXPIRED,"우선 오디오로 중단했어요 · 요약에서 확인해 주세요")}
}
