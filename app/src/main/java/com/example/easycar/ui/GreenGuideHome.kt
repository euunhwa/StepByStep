package com.example.easycar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.Lifecycle
import com.easycar.core.VehicleFrame
import com.example.easycar.data.SessionRecord
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun GuideHome(
    records: List<SessionRecord>,
    liveFrame: StateFlow<VehicleFrame?>,
    onStart: () -> Unit,
    onReport: (String) -> Unit,
    demoActions: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("coaching-home")) {
        val sideBySide = maxWidth >= 700.dp
        val shortViewport = maxHeight < 540.dp
        val recent = records.firstOrNull()
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (shortViewport) 12.dp else 18.dp),
            ) {
                Text(
                    "운전 연습",
                    fontSize = if (shortViewport) 30.sp else 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuideInk,
                )
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GuideHomeHero(
                            shortViewport,
                            onStart,
                            Modifier.weight(1.9f).heightIn(min = if (shortViewport) 216.dp else 280.dp),
                        )
                        GuideRecentPractice(
                            recent,
                            onReport,
                            Modifier.weight(1f).heightIn(min = if (shortViewport) 216.dp else 280.dp),
                            shortViewport,
                        )
                    }
                } else {
                    GuideHomeHero(shortViewport, onStart, Modifier.fillMaxWidth())
                }
                if (!shortViewport) GuideDemoSection(demoActions)
                if (!sideBySide) {
                    GuideRecentPractice(recent, onReport, Modifier.fillMaxWidth(), shortViewport)
                }
                LiveSignalPanel(liveFrame)
            }
            if (shortViewport) {
                GuideDemoSection(demoActions, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun GuideDemoSection(actions: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("데모로 연습하기", color = GuideInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        actions()
    }
}

@Composable
private fun GuideHomeHero(compact: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val inset = if (compact) 18.dp else 24.dp
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(22.dp)).background(GuideForest).padding(inset),
        contentAlignment = Alignment.CenterStart,
    ) {
        val showRoute = maxWidth >= 440.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(if (showRoute) 1.55f else 1f)) {
                Text(
                    "실시간 코칭",
                    Modifier.clip(RoundedCornerShape(50))
                        .background(GuideMint.copy(alpha = .12f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    color = GuideMint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                Text(
                    if (showRoute && !compact) "주행 코칭을\n시작할까요?" else "주행 코칭을 시작할까요?",
                    color = GuidePaper,
                    fontSize = if (compact) 28.sp else 36.sp,
                    lineHeight = if (compact) 34.sp else 44.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "동작을 마치면 화면과 음성으로 알려드려요.",
                    color = GuidePaper,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "출발 · 정지 · 좌회전 · 우회전 · 유턴 · T자 주차 · 평행주차",
                    modifier = Modifier.testTag("automatic-maneuvers"),
                    color = GuideMint,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier.heightIn(min = 52.dp).testTag("start-coaching"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GuideMint, contentColor = GuideInk),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("코칭 시작", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (showRoute) {
                GuideHomeRoute(Modifier.weight(1f).height(if (compact) 164.dp else 224.dp))
            }
        }
    }
}

@Composable
private fun GuideRecentPractice(
    record: SessionRecord?,
    onReport: (String) -> Unit,
    modifier: Modifier,
    compact: Boolean,
) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = GuidePaper, border = BorderStroke(1.dp, GuideLine)) {
        Column(
            Modifier.padding(if (compact) 16.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp),
        ) {
            Text("최근 연습", color = GuideInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (record == null) {
                Text("첫 코칭을 시작해 보세요", color = GuideInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("완료한 동작은 기록에서 확인할 수 있어요.", color = GuideMuted, fontSize = 15.sp, lineHeight = 21.sp)
            } else {
                val score = record.score
                val interrupted = record.state == Lifecycle.INTERRUPTED || record.state == Lifecycle.CANCELLED
                val provisional = record.results.any { it.scoreKind == "PROVISIONAL" }
                Text(
                    record.title,
                    color = GuideInk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(dateText(record.createdAt), color = GuideMuted, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        score?.toString() ?: if (interrupted) "중단됨" else "평가 보류",
                        color = GuideInk,
                        fontSize = if (score == null) 21.sp else if (compact) 32.sp else 46.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (score != null) Text(if (provisional) "/ 100 · 임시 점수" else "/ 100", color = GuideMuted, fontSize = 13.sp)
                }
                val movements = record.results.map { it.type.title }.distinct().joinToString(" · ")
                if (movements.isNotBlank() || interrupted && score != null) {
                    HorizontalDivider(color = GuideLine)
                    Text(
                        if (interrupted && score != null) "중단됨" else movements,
                        color = GuideMuted,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Action("연습 기록 보기", Modifier.fillMaxWidth(), secondary = true) { onReport(record.id) }
            }
        }
    }
}

@Composable
private fun GuideHomeRoute(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val thickness = size.minDimension
        val grid = GuideMint.copy(alpha = .12f)
        for (index in 1..7) {
            val fraction = index / 8f
            drawLine(grid, Offset(w * fraction, h * .07f), Offset(w * fraction, h * .94f), 1.dp.toPx())
            drawLine(grid, Offset(w * .06f, h * fraction), Offset(w * .94f, h * fraction), 1.dp.toPx())
        }
        val start = Offset(w * .16f, h * .86f)
        val destination = Offset(w * .80f, h * .14f)
        val route = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(w * .16f, h * .66f, w * .51f, h * .69f, w * .61f, h * .57f)
            cubicTo(w * .78f, h * .36f, w * .32f, h * .34f, w * .44f, h * .23f)
            cubicTo(w * .51f, h * .16f, w * .68f, h * .14f, destination.x, destination.y)
        }
        drawPath(route, GuideRoute.copy(alpha = .17f), style = Stroke(width = thickness * .27f, cap = StrokeCap.Round))
        drawPath(route, GuideRoute.copy(alpha = .16f), style = Stroke(width = thickness * .17f, cap = StrokeCap.Round))
        drawPath(
            route,
            GuideMint.copy(alpha = .65f),
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 8.dp.toPx())),
            ),
        )
        drawCircle(GuideMint, radius = 3.dp.toPx(), center = start)
        drawCircle(GuideRoute, radius = thickness * .064f, center = destination, style = Stroke(1.4.dp.toPx()))
        drawCircle(GuideRoute, radius = thickness * .025f, center = destination)

        val car = Offset(w * .55f, h * .62f)
        val carWidth = thickness * .14f
        val carLength = thickness * .25f
        rotate(52f, car) {
            drawRoundRect(
                GuideMint,
                topLeft = Offset(car.x - carWidth / 2, car.y - carLength / 2),
                size = Size(carWidth, carLength),
                cornerRadius = CornerRadius(carWidth * .24f),
            )
            drawRoundRect(
                GuideForest.copy(alpha = .75f),
                topLeft = Offset(car.x - carWidth * .34f, car.y - carLength * .27f),
                size = Size(carWidth * .68f, carLength * .29f),
                cornerRadius = CornerRadius(carWidth * .13f),
            )
            drawRoundRect(
                GuideForest.copy(alpha = .65f),
                topLeft = Offset(car.x - carWidth * .32f, car.y + carLength * .20f),
                size = Size(carWidth * .64f, carLength * .16f),
                cornerRadius = CornerRadius(carWidth * .12f),
            )
        }
    }
}

@Composable
internal fun GuideWelcome(onAgree: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 680.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = GuidePaper,
                border = BorderStroke(1.dp, GuideLine),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(if (wide) 22.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (wide) GuideConsentIllustration(Modifier.weight(.72f))
                    Column(Modifier.weight(1.3f)) {
                        Text(
                            "시작하기 전에 확인해 주세요",
                            color = GuideInk,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (wide) 30.sp else 25.sp,
                            lineHeight = if (wide) 38.sp else 33.sp,
                        )
                        Spacer(Modifier.height(15.dp))
                        GuideConsentItem("주행 중에는 코칭에 집중해요", "상세 기록과 영상은 정차 후에 볼 수 있어요.")
                        GuideConsentItem("연습 기록은 기기에 저장해요", "설정에서 보관 기간을 바꾸거나 삭제할 수 있어요.")
                        GuideConsentItem("영상 저장은 별도로 동의해요", "선택한 영상만 동의 후 기기에 저장해요.")
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "데모로 먼저 연습할 수 있어요. 점수는 참고용이며 신호가 부족하면 평가를 보류해요.",
                            color = GuideMuted,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Action("안내를 확인하고 시작하기", Modifier.fillMaxWidth().testTag("consent"), onClick = onAgree)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideConsentItem(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = GuideInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = GuideMuted, fontSize = 15.sp, lineHeight = 21.sp)
    }
    HorizontalDivider(color = GuideLine)
}

@Composable
private fun GuideConsentIllustration(modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(GuideForest).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(236.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val scale = size.minDimension
            for (fraction in listOf(.46f, .36f, .26f)) {
                drawCircle(GuideRoute.copy(alpha = .34f), scale * fraction, center, style = Stroke(1.4.dp.toPx()))
            }
            drawCircle(GuideMint, scale * .19f, center)
            drawCircle(GuideRoute, 4.dp.toPx(), Offset(center.x + scale * .30f, center.y - scale * .28f))
            drawCircle(GuideRoute, 4.dp.toPx(), Offset(center.x - scale * .28f, center.y + scale * .30f))
            val r = scale * .095f
            val shield = Path().apply {
                moveTo(center.x, center.y - r)
                lineTo(center.x + r, center.y - r * .62f)
                cubicTo(center.x + r, center.y + r * .35f, center.x + r * .45f, center.y + r * .80f, center.x, center.y + r)
                cubicTo(center.x - r * .45f, center.y + r * .80f, center.x - r, center.y + r * .35f, center.x - r, center.y - r * .62f)
                close()
            }
            drawPath(shield, GuideForest, style = Stroke(2.3.dp.toPx()))
            val check = Path().apply {
                moveTo(center.x - r * .43f, center.y)
                lineTo(center.x - r * .12f, center.y + r * .32f)
                lineTo(center.x + r * .47f, center.y - r * .32f)
            }
            drawPath(check, GuideForest, style = Stroke(width = 2.3.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("안전한 연습을 위해", color = GuideMint, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}
