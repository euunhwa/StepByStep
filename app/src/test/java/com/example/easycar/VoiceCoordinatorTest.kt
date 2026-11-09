package com.example.easycar

import com.easycar.core.*
import com.example.easycar.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Status texts here are deliberately meaningless: decisions must follow [SpeechPhase] alone. */
class VoiceCoordinatorTest {
    private class FakeSpeech(phase:SpeechPhase):SpeechOutput {
        override val status=MutableStateFlow(SpeechStatus(phase,"x"))
        val spoken=mutableListOf<String>();var stops=0;var released=false
        override fun speak(id:String,text:String):Boolean {spoken+=id;return true}
        override fun stop() {stops++;status.value=SpeechStatus(SpeechPhase.READY,"y")}
        override fun release() {released=true}
    }
    private val clock=object:TimeProvider {var now=100_000L;override fun nowMs()=now}
    private val stopped=Motion(0,0.0,0.0,Gear.DRIVE,null,null,null,null,false)
    private val turning=Motion(0,5.0,90.0,Gear.DRIVE,null,null,null,null,false)
    private fun result(id:String)=ManeuverResult(id,ManeuverType.START,0,1000,Lifecycle.COMPLETED,1.0,90,emptyList(),emptyList(),0,
        "p","TEST",null,null,emptyMap(),emptyMap(),"좋아요")
    private fun coordinator(speech:FakeSpeech,demo:Boolean=false,idle:Boolean?=true)=
        VoiceCoordinator(ImmediateFeedback(clock),speech,clock,{AudioAvailability(idle,null)},muted=false,demo=demo,yawThresholdRadps=null)
    private fun VoiceCoordinator.state(id:String)=feedbacks.receipts.first {it.resultId==id}.voiceState

    @Test fun speaksAWaitingReceiptAndSettlesItFromEngineStatus() {
        val speech=FakeSpeech(SpeechPhase.READY);val v=coordinator(speech)
        v.feedbacks.publish(result("a"),muted=false)
        v.tick(stopped,simulatedBusy=false)
        assertEquals(listOf("a"),speech.spoken);assertEquals("a",v.activeVoiceId)
        assertTrue(v.onStatus(SpeechStatus(SpeechPhase.SPEAKING,"z")))
        assertEquals(VoiceState.SPEAKING,v.state("a"))
        assertTrue(v.onStatus(SpeechStatus(SpeechPhase.READY,"z")))
        assertEquals(VoiceState.SPOKEN,v.state("a"));assertNull(v.activeVoiceId)
        assertFalse("no active receipt, nothing to settle",v.onStatus(SpeechStatus(SpeechPhase.READY,"z")))
    }

    @Test fun waitsWhileTheEngineIsStillStarting() {
        val speech=FakeSpeech(SpeechPhase.INITIALIZING);val v=coordinator(speech)
        v.feedbacks.publish(result("a"),muted=false)
        v.tick(stopped,simulatedBusy=false)
        assertTrue(speech.spoken.isEmpty());assertEquals(VoiceState.DEFERRED,v.state("a"))
    }

    @Test fun turningInterruptsOurSpeechAndItsStopIsNotCountedAsSpoken() {
        val speech=FakeSpeech(SpeechPhase.READY);val v=coordinator(speech)
        v.feedbacks.publish(result("a"),muted=false)
        v.tick(stopped,false);v.onStatus(SpeechStatus(SpeechPhase.SPEAKING,"z"))
        v.tick(turning,false)
        assertEquals(1,speech.stops);assertNull(v.activeVoiceId)
        assertEquals(VoiceState.EXPIRED,v.state("a"))
        v.onStatus(speech.status.value)
        assertEquals(VoiceState.EXPIRED,v.state("a"))
    }

    @Test fun anEngineFailureMarksTheActiveReceiptFailed() {
        val speech=FakeSpeech(SpeechPhase.READY);val v=coordinator(speech)
        v.feedbacks.publish(result("a"),muted=false)
        v.tick(stopped,false)
        v.onStatus(SpeechStatus(SpeechPhase.FAILED,"z"))
        assertEquals(VoiceState.FAILED,v.state("a"));assertNull(v.activeVoiceId)
    }

    @Test fun priorityAudioInterruptionExpiresTheReceipt() {
        val speech=FakeSpeech(SpeechPhase.READY);val v=coordinator(speech)
        v.feedbacks.publish(result("a"),muted=false)
        v.tick(stopped,false)
        v.onStatus(SpeechStatus(SpeechPhase.INTERRUPTED,"z"))
        assertEquals(VoiceState.EXPIRED,v.state("a"));assertNull(v.activeVoiceId)
    }

    @Test fun aDemoWithAFailedEngineFallsBackToText() {
        val speech=FakeSpeech(SpeechPhase.FAILED);val v=coordinator(speech,demo=true)
        v.feedbacks.publish(result("a"),muted=false)
        v.tick(stopped,false)
        assertEquals(VoiceState.FAILED,v.state("a"));assertFalse(v.feedbacks.hasPendingVoice)
    }

    @Test fun aDemoStopsWaitingForAnEngineThatNeverStarts() {
        val speech=FakeSpeech(SpeechPhase.INITIALIZING);val v=coordinator(speech,demo=true)
        v.feedbacks.publish(result("a"),muted=false)
        val since=clock.now
        v.awaitStep(stopped,false,since)
        assertTrue(v.feedbacks.hasPendingVoice)
        clock.now+=VoiceCoordinator.ENGINE_START_TIMEOUT_MS
        v.awaitStep(stopped,false,since)
        assertEquals(VoiceState.FAILED,v.state("a"));assertFalse(v.feedbacks.hasPendingVoice)
    }

    @Test fun onlyAStoppedOrStraightCarIsSafeForDemoSpeech() {
        val v=coordinator(FakeSpeech(SpeechPhase.READY),demo=true)
        assertTrue(v.speechSafe(stopped));assertFalse(v.speechSafe(turning))
    }

    @Test fun releaseFreesTheEngineButKeepsReceipts() {
        val speech=FakeSpeech(SpeechPhase.READY);val v=coordinator(speech)
        v.feedbacks.publish(result("a"),muted=false)
        v.release()
        assertTrue(speech.released);assertNull(v.phase);assertEquals(1,v.feedbacks.receipts.size)
    }
}
