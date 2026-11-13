package com.example.easycar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.easycar.BuildConfig
import com.example.easycar.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val mainItems = listOf("home" to "홈", "records" to "기록", "settings" to "설정")

@Composable
internal fun MainNavigation(
    page: String,
    disabled: Boolean,
    compact: Boolean = false,
    vehicleConnected: Boolean? = null,
    onSelect: (String) -> Unit
) {
    val selectedPage = if (page == "report" || page == "video" || page == "record-query") "records" else page
    if (compact) {
        Row(
            Modifier.fillMaxWidth().selectableGroup().testTag("main-navigation"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            mainItems.forEach { (id, title) ->
                GuideNavItem(id, title, id == selectedPage, !disabled, true, Modifier.weight(1f)) { onSelect(id) }
            }
        }
        return
    }
    Row(Modifier.width(208.dp).fillMaxHeight().background(GuidePaper)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 20.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .selectableGroup().testTag("main-navigation")
            ) {
                Row(
                    Modifier.padding(start = 9.dp, top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GuideMark()
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.app_name),
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuideInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(32.dp))
                mainItems.forEach { (id, title) ->
                    GuideNavItem(id, title, id == selectedPage, !disabled, false, Modifier.fillMaxWidth()) { onSelect(id) }
                    Spacer(Modifier.height(7.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(GuideLine))
            if (vehicleConnected != null) {
                Row(
                    Modifier.padding(start = 8.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (vehicleConnected) GuideGreen else GuideMuted)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (vehicleConnected) "차량 연결됨" else "차량 연결 확인",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GuideInk
                    )
                }
            }
            Text(
                if (BuildConfig.FLAVOR == "pleos") "Pleos Connect" else "데모",
                Modifier.padding(start = if (vehicleConnected != null) 24.dp else 8.dp, top = 6.dp),
                fontSize = 13.sp,
                color = GuideMuted
            )
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(GuideLine))
    }
}

@Composable
private fun GuideNavItem(
    id: String,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val color = when {
        !enabled -> GuideMuted.copy(alpha = .7f)
        selected -> GuideInk
        else -> GuideMuted
    }
    Row(
        modifier.heightIn(min = 52.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) GuideSelected else GuidePaper)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .testTag("nav-$id")
            .padding(horizontal = if (compact) 8.dp else 16.dp),
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!compact) {
            GuideNavIcon(id, color)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun GuideNavIcon(id: String, color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val u = size.width / 24f
        val stroke = 1.75f * u
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, at(x1, y1), at(x2, y2), stroke, StrokeCap.Round)
        }
        when (id) {
            "home" -> {
                val house = Path().apply {
                    moveTo(3f * u, 11f * u)
                    lineTo(12f * u, 3f * u)
                    lineTo(21f * u, 11f * u)
                    lineTo(19f * u, 11f * u)
                    lineTo(19f * u, 21f * u)
                    lineTo(5f * u, 21f * u)
                    lineTo(5f * u, 11f * u)
                    close()
                }
                drawPath(house, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
                line(10f, 21f, 10f, 15f)
                line(10f, 15f, 14f, 15f)
                line(14f, 15f, 14f, 21f)
            }
            "records" -> {
                drawRoundRect(
                    color, at(5f, 3f), Size(14f * u, 18f * u),
                    CornerRadius(2f * u),
                    style = Stroke(stroke)
                )
                line(8.5f, 8f, 15.5f, 8f)
                line(8.5f, 12f, 15.5f, 12f)
                line(8.5f, 16f, 12.5f, 16f)
            }
            else -> {
                val center = at(12f, 12f)
                drawCircle(color, radius = 7.5f * u, center = center, style = Stroke(stroke))
                drawCircle(color, radius = 2.8f * u, center = center, style = Stroke(stroke))
                repeat(8) { index ->
                    val angle = index * PI / 4
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(color, center + direction * (8f * u), center + direction * (10.4f * u), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
