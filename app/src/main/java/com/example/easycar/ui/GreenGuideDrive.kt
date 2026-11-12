package com.example.easycar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.formatValue
import com.example.easycar.runtime.DriveState
import com.example.easycar.runtime.MiniWindowState
import com.example.easycar.runtime.PlatformState
import kotlin.math.abs

@Composable
internal fun DriveScreen(
    d: DriveState,
    locked: Boolean,
    p: PlatformState,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    voice: String = "",
    onPause: () -> Unit = {},
    onMinimize: () -> Unit = {},
    miniWindow: MiniWindowState = MiniWindowState()
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(GuideDrive).testTag("drive-screen")) {
        val compact = maxHeight < 440.dp || maxWidth < 600.dp
        val speed = d.motion?.speedMps?.let { formatValue(abs(it) * 3.6) } ?: "—"
        val gear = userGear(d.motion?.gear)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            if (!d.running) {
                RestrictedDrivePanel(p.ux.known, onRetry)
            } else {
                val demo = d.record?.demoCaseId != null
                val feedback = d.feedback
                if (compact) {
                    if (feedback != null) CoachFeedbackCard(feedback, compact = true)
                    else WaitingDriveCard(compact = true)
                    Spacer(Modifier.height(10.dp))
                    val source = if (demo) "데모" else "차량"
                    Text(
                        "$source · $speed km/h · $gear",
                        fontSize = 17.sp,
                        color = GuideDriveMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("실시간 코칭", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = GuidePaper)
                        Spacer(Modifier.weight(1f))
                        if (demo) {
                            Text(
                                if (d.paused) "데모 일시 정지" else "데모",
                                Modifier.clip(RoundedCornerShape(50)).background(GuideDriveRaised)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GuideMint
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val card = Modifier.weight(2.2f).fillMaxHeight()
                        if (feedback != null) CoachFeedbackCard(feedback, modifier = card)
                        else WaitingDriveCard(modifier = card)
                        GuideDriveTelemetry(speed, gear, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                if (demo) {
                    LinearProgressIndicator(
                        progress = { d.progress },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                        color = GuideRoute,
                        trackColor = GuideDriveRaised
                    )
                } else if (!compact) {
                    HorizontalDivider(color = GuideDriveRaised)
                }
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                GuideDriveControls(d, locked, demo, voice, onStop, onPause, onMinimize, miniWindow, compact)
            }
            miniWindow.message?.takeIf { miniWindow.supported }?.let {
                Text(
                    it,
                    Modifier.padding(top = 8.dp),
                    color = GuideDriveMuted,
                    fontSize = if (compact) 13.sp else 15.sp,
                    lineHeight = 20.sp
                )
            }
            d.error?.let { Text(userNoticeText(it), color = Amber, fontSize = if (compact) 15.sp else 20.sp) }
        }
    }
}

@Composable
private fun WaitingDriveCard(compact: Boolean = false, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().heightIn(min = if (compact) 74.dp else 252.dp)
            .clip(RoundedCornerShape(if (compact) 16.dp else 23.dp))
            .background(GuideDrivePanel)
            .testTag("waiting-next-maneuver")
            .padding(if (compact) 15.dp else 25.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "다음 동작 대기 중",
            fontSize = if (compact) 22.sp else 38.sp,
            lineHeight = if (compact) 29.sp else 49.sp,
            fontWeight = FontWeight.Bold,
            color = GuidePaper
        )
        if (!compact) {
            Spacer(Modifier.height(12.dp))
            Text(
                "동작을 마치면 화면과 음성으로 알려드려요.",
                fontSize = 18.sp,
                lineHeight = 27.sp,
                color = GuideDriveMuted
            )
        }
    }
}

@Composable
private fun GuideDriveTelemetry(speed: String, gear: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(23.dp)).background(GuideDrivePanel).padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("현재 속도", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GuideDriveMuted)
        Spacer(Modifier.height(6.dp))
        Text(
            speed,
            fontSize = if (speed.length >= 4) 38.sp else 54.sp,
            lineHeight = 57.sp,
            fontWeight = FontWeight.Bold,
            color = GuidePaper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text("km/h", fontSize = 16.sp, color = GuideDriveMuted)
        Spacer(Modifier.height(17.dp))
        HorizontalDivider(color = GuideDriveRaised)
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("기어", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GuideDriveMuted)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(53.dp).clip(RoundedCornerShape(13.dp)).background(GuideDriveRaised),
                contentAlignment = Alignment.Center
            ) {
                Text(gear, fontSize = 29.sp, fontWeight = FontWeight.Bold, color = GuidePaper)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideDriveControls(
    drive: DriveState,
    locked: Boolean,
    demo: Boolean,
    voice: String,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onMinimize: () -> Unit,
    miniWindow: MiniWindowState,
    compact: Boolean
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        if (!compact) {
            Column(Modifier.weight(1f).widthIn(min = 150.dp)) {
                Text(
                    voice.ifBlank { "동작을 마치면 짧게 안내해 드려요." },
                    color = GuideDriveMuted,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val count = drive.feedbackHistory.size
                if (count != 0) {
                    Text("완료 피드백 ${count}개", fontSize = 14.sp, color = GuideDriveMuted)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (demo && !locked) {
            GuideDriveButton(if (drive.paused) "계속 재생" else "일시 정지", onPause, compact = compact)
        }
        if (miniWindow.supported) {
            GuideDriveButton("작은 창", onMinimize, Modifier.testTag("minimize-coach"), compact = compact)
        }
        GuideDriveButton("주행 종료", onStop, Modifier.testTag("end-drive"), primary = true, compact = compact)
    }
}

@Composable
private fun RestrictedDrivePanel(uxKnown: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 690.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(GuideDrivePanel).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(GuideDriveRaised),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(26.dp)) {
                    val width = size.width
                    val stroke = 1.8.dp.toPx()
                    drawRoundRect(
                        GuideMint,
                        topLeft = Offset(width * .17f, width * .43f),
                        size = Size(width * .66f, width * .45f),
                        cornerRadius = CornerRadius(width * .09f),
                        style = Stroke(stroke)
                    )
                    drawArc(
                        GuideMint, 180f, 180f, false,
                        topLeft = Offset(width * .29f, width * .10f),
                        size = Size(width * .42f, width * .56f),
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    drawCircle(GuideMint, radius = width * .05f, center = Offset(width / 2, width * .64f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "정차 후에 확인해 주세요",
                fontSize = 34.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                color = GuidePaper,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (uxKnown) "정차 후 상세 기록과 설정을 사용할 수 있어요."
                else "주행 규제 상태가 확인되면 상세 화면을 열 수 있어요.",
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = GuideDriveMuted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            GuideDriveButton("연결 다시 확인", onRetry)
        }
    }
}

@Composable
private fun GuideDriveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 48.dp else 56.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 13.dp else 19.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) GuideMint else GuideDriveRaised,
            contentColor = if (primary) GuideInk else GuidePaper
        )
    ) {
        Text(label, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
