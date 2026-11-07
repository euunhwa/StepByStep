package com.example.easycar.ui

import com.easycar.core.FeedbackReceipt
import com.easycar.core.FeedbackConfig

/** A newer voice callback must not extend the visual lifetime of an old maneuver. */
data class MiniCoachContent(val title:String,val body:String,val freshFeedback:Boolean,val score:Int?) {
    companion object {
        const val FEEDBACK_VISIBLE_MS=FeedbackConfig.DEFAULT_DISPLAY_MS
        fun from(running:Boolean,paused:Boolean,feedback:FeedbackReceipt?,nowMs:Long):MiniCoachContent {
            if(!running) return MiniCoachContent("코칭을 마쳤어요","창을 열어 주행 기록을 확인하세요.",false,null)
            if(paused) return MiniCoachContent("시연 일시 정지","코칭 창을 열면 이어서 재생할 수 있어요.",false,null)
            val age=feedback?.let {nowMs-it.publishedAtMs}
            if(feedback!=null && age!=null && age in 0 until FEEDBACK_VISIBLE_MS)
                return MiniCoachContent(feedback.title,feedbackReasonCaption(feedback),true,feedback.score)
            return MiniCoachContent("다음 동작 대기 중","",false,null)
        }
    }
}
