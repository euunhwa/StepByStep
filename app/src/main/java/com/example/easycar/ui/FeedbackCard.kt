package com.example.easycar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.FeedbackReceipt
import com.easycar.core.FeedbackBand

val ScoreBlue=Color(0xff74b5ff)
val ScoreOrange=Color(0xffffb454)
val ScoreRed=Color(0xffff6b6b)
fun scoreColor(score:Int?):Color=when(FeedbackBand.from(score)) {
    FeedbackBand.PERFECT->ScoreBlue
    FeedbackBand.CAUTION->ScoreOrange
    FeedbackBand.WARNING->ScoreRed
    FeedbackBand.UNRATED->Muted
}
fun liveScoreCaption(score:Int?)=score?.let {"${it}점"} ?: "평가 대기"
fun feedbackReasonCaption(f:FeedbackReceipt)=if(f.score==100) "감점 없음"
    else if(f.reason!=null) "(원인 : ${userFeedbackReason(f.reason!!)})"
    else if(f.score!=null) "(원인 : 급한 조작)" else "평가할 정보가 부족해요"

@Composable internal fun CoachFeedbackCard(f:FeedbackReceipt,compact:Boolean=false) {
    CardPanel(Modifier.fillMaxWidth().testTag("live-feedback"),PanelLight) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(f.title,fontSize=if(compact) 24.sp else 32.sp,fontWeight=FontWeight.Bold,color=White,modifier=Modifier.weight(1f))
            Text(liveScoreCaption(f.score),fontSize=if(compact) 34.sp else 46.sp,fontWeight=FontWeight.Bold,color=scoreColor(f.score),modifier=Modifier.testTag("live-feedback-score"))
        }
        Spacer(Modifier.height(12.dp))
        Text(feedbackReasonCaption(f),fontSize=if(compact) 20.sp else 26.sp,lineHeight=if(compact) 28.sp else 36.sp,color=White,modifier=Modifier.testTag("live-feedback-text"))
    }
}
