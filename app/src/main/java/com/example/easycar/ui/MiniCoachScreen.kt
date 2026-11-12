package com.example.easycar.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.easycar.R
import com.example.easycar.runtime.DriveState
import kotlinx.coroutines.delay

/** Shared by system PiP and the Pleos side pane. In PiP the system supplies all touch controls. */
@Composable
fun MiniCoachScreen(
    drive: DriveState,
    inPip: Boolean,
    onStop: () -> Unit,
    datasetNotice: String? = null,
    restricted: Boolean = false,
    onResume: () -> Unit = {}
) {
    var now by remember(drive.record?.id) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(drive.record?.id, drive.feedback?.resultId, drive.running) {
        now = SystemClock.elapsedRealtime()
        val published = drive.feedback?.publishedAtMs
        if (published != null && drive.running) {
            val remaining = MiniCoachContent.FEEDBACK_VISIBLE_MS - (now - published)
            if (remaining > 0) {
                delay(remaining)
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    val content = if (restricted && !drive.running) {
        MiniCoachContent("주행에 집중해 주세요", "정차 후 코칭을 시작할 수 있어요.", false, null)
    } else {
        MiniCoachContent.from(drive.running, drive.paused, drive.feedback, now)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(GuideDrive).testTag("mini-coach")) {
        val actions = !inPip && drive.running && maxHeight >= 300.dp
        val controls = if (!actions) 0.dp else if (drive.paused) 140.dp else 72.dp
        val contentHeight = maxHeight - controls - 28.dp
        val dense = contentHeight < 120.dp
        val tiny = contentHeight < 200.dp || maxWidth < 230.dp
        val pane = !inPip && maxWidth >= 480.dp && contentHeight >= 380.dp
        val outerPadding = if (pane) 26.dp else if (dense) 6.dp else if (tiny) 8.dp else 14.dp
        val panelPadding = if (pane) 24.dp else if (dense) 0.dp else if (tiny) 8.dp else 16.dp

        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(outerPadding)
        ) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(if (pane) 24.dp else 16.dp))
                        .background(if (dense) GuideDrive else GuideDrivePanel)
                        .padding(panelPadding)
                ) {
                    MiniCoachHeader(drive, content, dense, tiny, pane)
                    Spacer(Modifier.height(if (pane) 22.dp else if (dense) 4.dp else if (tiny) 6.dp else 10.dp))
                    val highlighted = pane && content.freshFeedback
                    Column(
                        if (highlighted) {
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(GuideMint).padding(22.dp)
                        } else Modifier.fillMaxWidth()
                    ) {
                        Text(
                            content.title,
                            fontSize = if (pane) 39.sp else if (dense) 14.sp else if (tiny) 16.sp else 23.sp,
                            lineHeight = if (pane) 49.sp else if (dense) 18.sp else if (tiny) 20.sp else 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (highlighted) GuideInk else if (content.freshFeedback) GuideMint else GuidePaper,
                            maxLines = if (pane) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("mini-title")
                        )
                        if (content.body.isNotBlank()) {
                            Spacer(Modifier.height(if (pane) 16.dp else if (tiny) 4.dp else 8.dp))
                            Text(
                                content.body,
                                fontSize = if (pane) 24.sp else if (dense) 11.sp else if (tiny) 13.sp else 17.sp,
                                lineHeight = if (pane) 35.sp else if (dense) 14.sp else if (tiny) 18.sp else 24.sp,
                                color = if (highlighted) GuideInk else GuidePaper,
                                maxLines = if (dense) 2 else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("mini-feedback-text")
                            )
                        }
                    }
                    if (datasetNotice != null) {
                        Spacer(Modifier.height(if (dense) 2.dp else 10.dp))
                        Text(
                            "연습 참고용 점수",
                            fontSize = if (pane) 15.sp else 10.sp,
                            lineHeight = if (pane) 22.sp else 12.sp,
                            color = GuidePaper.copy(alpha = .7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (actions) {
                Spacer(Modifier.height(12.dp))
                if (drive.paused) {
                    MiniCoachAction("데모 계속", "resume-demo", onResume, primary = false)
                    Spacer(Modifier.height(8.dp))
                }
                MiniCoachAction("코칭 종료", "end-drive", onStop)
            }
        }
    }
}

@Composable
private fun MiniCoachHeader(drive: DriveState, content: MiniCoachContent, dense: Boolean, tiny: Boolean, pane: Boolean) {
    val appName = stringResource(R.string.app_name)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!dense && !tiny) {
            GuideMark(Modifier.size(if (pane) 40.dp else 27.dp))
        } else {
            Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(if (drive.running) GuideRoute else GuidePaper.copy(alpha = .65f))
            )
        }
        Spacer(Modifier.width(if (pane) 10.dp else 7.dp))
        Text(
            if (drive.record?.demoCaseId != null) "$appName · 데모" else appName,
            fontSize = if (pane) 22.sp else if (dense) 10.sp else if (tiny) 11.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = GuidePaper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (content.freshFeedback) {
            Text(
                liveScoreCaption(content.score),
                fontSize = if (pane) 32.sp else if (dense) 10.sp else if (tiny) 11.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = scoreColor(content.score),
                modifier = Modifier.testTag("mini-feedback-score")
            )
        } else if (drive.running && !dense && !tiny) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(GuideRoute))
            Spacer(Modifier.width(7.dp))
            Text("코칭 중", fontSize = if (pane) 15.sp else 12.sp, color = GuideMint)
        }
    }
}

@Composable
private fun MiniCoachAction(title: String, tag: String, onClick: () -> Unit, primary: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag(tag),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) GuideMint else GuideDrivePanel,
            contentColor = if (primary) GuideInk else GuidePaper
        )
    ) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}
