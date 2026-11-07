package com.example.easycar

import com.easycar.core.*
import com.example.easycar.ui.MiniCoachContent
import org.junit.Assert.*
import org.junit.Test

class MiniCoachContentTest {
    private val receipt=FeedbackReceipt("left-1",ManeuverType.LEFT,"좌회전 완료","좌회전 구간을 기록했어요.",null,7000,10000,VoiceState.WAITING,null)
    @Test fun newFeedbackIsVisibleImmediatelyAndExpiresAtFiveSeconds() {
        assertTrue(MiniCoachContent.from(true,false,receipt,10000).freshFeedback)
        assertTrue(MiniCoachContent.from(true,false,receipt,14999).freshFeedback)
        assertFalse(MiniCoachContent.from(true,false,receipt,15000).freshFeedback)
    }
    @Test fun voiceCallbacksCannotReviveExpiredFeedback() {
        val spoken=receipt.copy(voiceState=VoiceState.SPOKEN,deliveredDelayMs=3000)
        assertFalse(MiniCoachContent.from(true,false,spoken,20000).freshFeedback)
    }
    @Test fun scoreHeldIsNotDisplayedAsZeroAndUnsupportedClockIsNotFresh() {
        assertNull(MiniCoachContent.from(true,false,receipt,11000).score)
        assertFalse(MiniCoachContent.from(true,false,receipt,9999).freshFeedback)
    }
    @Test fun pausedAndFinishedSessionsCannotShowAnOldManeuverAsCurrent() {
        assertFalse(MiniCoachContent.from(false,false,receipt,11000).freshFeedback)
        assertFalse(MiniCoachContent.from(true,true,receipt,11000).freshFeedback)
        assertEquals("다음 동작 대기 중",MiniCoachContent.from(true,false,null,11000).title)
    }
}
