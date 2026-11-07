package com.easycar.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FeedbackSpeechTest {
    private val clock=object:TimeProvider {override fun nowMs()=1000L}
    private val conditions=GuidanceConditions(true,false,true,false,false)
    private fun result(score:Int?,type:ManeuverType=ManeuverType.LEFT,cause:String?=null)=ManeuverResult(
        id="speech-fixture",type=type,startMs=0,endMs=1000,
        lifecycle=if(score==null) Lifecycle.HELD else Lifecycle.COMPLETED,confidence=1.0,score=score,
        holdReasons=if(score==null) listOf("신호 부족") else emptyList(),
        deductions=cause?.let {listOf(Deduction(it,it,100-(score ?: 100),1.0,"test",0.0,1000,1000,"test"))} ?: emptyList(),
        profileVersion="test",source="SYNTHETIC_TEST",courseId=null,vehicleId=null,
        measurements=emptyMap(),phaseTimes=emptyMap(),coaching=if(score==null) "신호가 부족해 평가를 보류했어요." else "상세 평가 근거"
    )

    @Test fun bandsMatchAllColourBoundariesAndNeverTurnMissingScoresIntoZero() {
        for(score in 0..100) assertEquals(when(score) {
            100->FeedbackBand.PERFECT
            in 80..99->FeedbackBand.CAUTION
            else->FeedbackBand.WARNING
        },FeedbackBand.from(score))
        for(score in listOf(null,-1,101)) assertEquals(FeedbackBand.UNRATED,FeedbackBand.from(score))
    }

    @Test fun requestedExamplesSpeakManeuverAndScoreBeforeTheAdvice() {
        val speech=FeedbackSpeech(Random(0))
        assertTrue(speech.text(result(100)).startsWith("좌회전 100점 입니다. "))
        assertEquals("유턴 80점 입니다. 너무 빠른 속도로 회전했어요. 유턴 시에는 조금 더 천천히 회전하는 게 좋겠어요.",
            speech.text(result(80,ManeuverType.U_TURN,"rapid_turn")))
        assertEquals("우회전 60점 입니다. 너무 빠른 운전은 위험할 수 있습니다. 속도를 줄이세요.",
            speech.text(result(60,ManeuverType.RIGHT,"speed")))
    }

    @Test fun perfectScoresUseEveryComplimentBeforeRepeatingAndNeverRepeatBackToBack() {
        val speech=FeedbackSpeech(Random(7))
        val texts=(1..18).map {speech.text(result(100))}
        assertEquals(6,texts.take(6).toSet().size)
        assertTrue(texts.zipWithNext().all {(a,b)->a!=b})
        assertTrue(texts.any {it.endsWith("잘하고 있습니다.")})
    }

    @Test fun realScoresAndNamesAreUsedForEveryScoredManeuver() {
        val speech=FeedbackSpeech(Random(0))
        for(type in listOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.START,
            ManeuverType.STOP,ManeuverType.ACCELERATION,ManeuverType.DECELERATION,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK)) {
            for(score in listOf(0,60,79,80,99,100)) assertTrue(speech.text(result(score,type)).startsWith("${type.title} ${score}점 입니다. "))
        }
    }

    @Test fun warningStartsBelow80AndCautionIncludes99() {
        val speech=FeedbackSpeech()
        for(score in listOf(0,60,79)) assertTrue(speech.text(result(score,cause="rapid_turn")).endsWith("속도를 줄이세요."))
        for(score in listOf(80,99)) assertTrue(speech.text(result(score,cause="rapid_turn")).contains("조금 더 천천히"))
    }

    @Test fun adviceUsesObservedCauseRatherThanAssumingEveryLowScoreMeansSpeeding() {
        val speech=FeedbackSpeech()
        val causes=mapOf("acceleration" to "가속 페달","deceleration" to "감속","jerk" to "페달",
            "steering" to "핸들","gear_repetition" to "전후진","parking_time" to "서두르지",
            "alignment" to "주차선","intrusion" to "주차선","entry" to "주차선","lateral_acceleration" to "회전")
        for((cause,word) in causes) for(score in listOf(60,80)) {
            val text=speech.text(result(score,if(cause=="parking_time") ManeuverType.T_PARK else ManeuverType.LEFT,cause))
            assertTrue("$cause / $score: $text",text.contains(word))
            assertFalse(text.contains("너무 빠른 운전"))
        }
        assertTrue(speech.text(result(80,ManeuverType.T_PARK,"speed")).contains("주차 중 이동 속도"))
        assertFalse(speech.text(result(60)).contains("속도"))
    }

    @Test fun heldScoresUsePlainSpeechAndKeepTheStoredTechnicalExplanation() {
        val original=result(null,cause="speed").copy(
            holdReasons=listOf("차량 좌표계·측정 시각이 검증된 yaw rate 신호 부족", "가감속·jerk 측정 시각/신호 충족률 부족"),
            coaching="yaw rate 및 jerk 입력이 부족해 평가를 보류했어요.")
        val before=Gson().toJson(original)
        val text=FeedbackSpeech().text(original)
        assertEquals("좌회전 평가를 보류했어요. 회전 방향을 확인할 정보가 부족해요.",text)
        assertFalse(text.contains("yaw",ignoreCase=true));assertFalse(text.contains("jerk",ignoreCase=true))
        assertFalse(text.contains("점"));assertEquals(before,Gson().toJson(original))
    }

    @Test fun queuedSpeechKeepsItsChosenComplimentThroughRetriesAndUnchangedRefinement() {
        val f=ImmediateFeedback(clock)
        val r=result(100)
        val receipt=f.publish(r,false)!!
        assertEquals(r.coaching,receipt.text)
        repeat(3) {
            assertNull(f.process(conditions,false,null) {assertEquals(receipt.voiceText,it.voiceText);false})
            assertTrue(f.refine(r))
            assertEquals(receipt.voiceText,f.latest!!.voiceText)
        }
        assertNotNull(f.process(conditions,false,null) {assertEquals(receipt.voiceText,it.voiceText);true})
    }

    @Test fun refinementUpdatesPendingSpeechButNeverReplaysAlreadyAcceptedSpeech() {
        val f=ImmediateFeedback(clock)
        val held=result(null)
        f.publish(held,false)
        val refined=result(80,ManeuverType.LEFT,"speed")
        assertTrue(f.refine(refined))
        val queued=f.latest!!
        assertTrue(queued.voiceText!!.startsWith("좌회전 80점 입니다."))
        assertEquals(1000L,queued.publishedAtMs)
        assertNotNull(f.process(conditions,false,null) {assertEquals(queued.voiceText,it.voiceText);true})
        f.refine(result(60,cause="speed"))
        assertEquals(queued.voiceText,f.latest!!.voiceText)
        assertNull(f.process(conditions,false,null) {error("No second voice event")})
    }

    @Test fun duplicateResultsDoNotConsumeTheNextCompliment() {
        val f=ImmediateFeedback(clock,speech=FeedbackSpeech(Random(2)))
        val expected=FeedbackSpeech(Random(2))
        val r=result(100)
        assertEquals(expected.text(r),f.publish(r,true)!!.voiceText)
        assertNull(f.publish(r,true))
        assertEquals(expected.text(r),f.publish(r.copy(id="next"),true)!!.voiceText)
    }

    @Test fun storedVoiceTextRoundTripsAndOlderRecordsKeepTheirOriginalFallback() {
        val gson=Gson()
        val receipt=ImmediateFeedback(clock).publish(result(80,cause="speed"),true)!!
        val restored=gson.fromJson(gson.toJson(receipt),FeedbackReceipt::class.java)
        assertEquals(receipt.voiceText,restored.voiceText)
        val older=gson.toJsonTree(receipt).asJsonObject.also {it.remove("voiceText")}
        val legacy=gson.fromJson(older,FeedbackReceipt::class.java)
        assertNull(legacy.voiceText)
        assertEquals(receipt.text,legacy.voiceText ?: legacy.text)
    }
}
