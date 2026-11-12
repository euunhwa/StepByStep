package com.example.easycar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.FeedbackBand
import com.easycar.core.FeedbackReceipt

val ScoreBlue = Color(0xff74b5ff)
val ScoreOrange = Color(0xffffb454)
val ScoreRed = Color(0xffff6b6b)

fun scoreColor(score: Int?): Color = when (FeedbackBand.from(score)) {
    FeedbackBand.PERFECT -> ScoreBlue
    FeedbackBand.CAUTION -> ScoreOrange
    FeedbackBand.WARNING -> ScoreRed
    FeedbackBand.UNRATED -> Muted
}

fun liveScoreCaption(score: Int?) = score?.let { "${it}점" } ?: "평가 대기"

fun feedbackReasonCaption(f: FeedbackReceipt) = when {
    f.score == 100 -> "감점 없음"
    f.reason != null -> "(원인 : " + userFeedbackReason(f.reason!!) + ")"
    f.score != null -> "(원인 : 급한 조작)"
    else -> "평가할 정보가 부족해요"
}

@Composable
internal fun CoachFeedbackCard(
    f: FeedbackReceipt,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().heightIn(min = if (compact) 108.dp else 252.dp)
            .clip(RoundedCornerShape(if (compact) 16.dp else 23.dp))
            .background(GuideMint)
            .testTag("live-feedback")
            .padding(if (compact) 15.dp else 25.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (!compact) {
            Text("방금 마친 동작", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GuideForest)
            Spacer(Modifier.height(15.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.title,
                fontSize = if (compact) 24.sp else 40.sp,
                lineHeight = if (compact) 30.sp else 49.sp,
                fontWeight = FontWeight.Bold,
                color = GuideInk,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(RoundedCornerShape(13.dp)).background(GuideDrive)
                    .padding(horizontal = 13.dp, vertical = 6.dp)
            ) {
                Text(
                    liveScoreCaption(f.score),
                    fontSize = if (compact) 24.sp else 31.sp,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor(f.score),
                    modifier = Modifier.testTag("live-feedback-score")
                )
            }
        }
        Spacer(Modifier.height(if (compact) 7.dp else 16.dp))
        Text(
            feedbackReasonCaption(f),
            fontSize = if (compact) 18.sp else 23.sp,
            lineHeight = if (compact) 26.sp else 32.sp,
            color = GuideInk,
            modifier = Modifier.testTag("live-feedback-text")
        )
    }
}
