package com.example.easycar.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycar.core.*
import com.example.easycar.BuildConfig
import com.example.easycar.EasyCarViewModel
import com.example.easycar.R
import com.example.easycar.data.*
import com.example.easycar.runtime.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

@Composable fun EasyCarApp(model:EasyCarViewModel,miniWindow:StateFlow<MiniWindowState>,onMinimize:()->Boolean) {
    val appName=stringResource(R.string.app_name)
    val smallWindow by miniWindow.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val records by model.records.collectAsStateWithLifecycle()
    val voice by model.voiceStatus.collectAsStateWithLifecycle()
    val drive by model.drive.collectAsStateWithLifecycle()
    val platform by model.platform.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val videoAnalyzing by model.videoAnalyzing.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var calibrationPositionMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {if(page !in setOf("home","records","settings","report","video","record-query")) page="home"}
    val selected=records.firstOrNull { it.id==selectedId }
    // A detail id that is not in the selected record falls through to that record's report.
    val detail=selected?.results?.firstOrNull { it.id==detailId }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importVideo) }
    val calibrationPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->selectedId?.let {id->uri?.let {model.attachCalibration(id,it,calibrationPositionMs)}} }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.retryVehicle() }
    LaunchedEffect(drive.error) { drive.error?.let { model.message.value=it } }
    LaunchedEffect(voice,drive.running) { if(!drive.running && voice.phase==SpeechPhase.FAILED) model.message.value=voice.text }
    LaunchedEffect(drive.finishedId) { drive.finishedId?.let { if(model.claimFinishedReport(it)) { selectedId=it;detailId=null;page="report" } } }
    val locked=!platform.ux.allowsReview() || drive.simulatedRestriction
    val minimized = smallWindow.active || smallWindow.entering
    val driving = drive.running || locked
    val colors = if (driving || minimized) GuideDriveColors else GuideReviewColors
    val demoActions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("데모", Modifier.weight(1f).testTag("start-submission-demo"), secondary = true, onClick = model::startSubmissionDemo)
            Action("T자 데모", Modifier.weight(1f).testTag("start-t-park-demo"), secondary = true) { model.startParkingDemo("park-beginner") }
            Action("평행 데모", Modifier.weight(1f).testTag("start-parallel-park-demo"), secondary = true) { model.startParkingDemo("park-parallel") }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compactWindow = maxWidth < 900.dp || maxHeight < 500.dp
                if (minimized || (compactWindow && driving)) {
                    MiniCoachScreen(drive, minimized, model::stop, model.evaluationPolicy.datasetNotice, locked) {
                        model.setDemoPaused(!drive.paused)
                    }
                } else {
                    val showRail = !compactWindow && settings.consent && !driving
                    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        if (showRail) {
                            MainNavigation(page, false, vehicleConnected = platform.vehicleConnected) {
                                page = it
                                detailId = null
                            }
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().padding(
                                horizontal = if (compactWindow) 18.dp else 30.dp,
                                vertical = if (compactWindow) 12.dp else 24.dp,
                            )
                        ) {
                            if (!showRail) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    GuideMark()
                                    Spacer(Modifier.width(10.dp))
                                    Text(appName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                                    Spacer(Modifier.weight(1f))
                                    if (driving) {
                                        Badge(if (drive.running) "●  자동 코칭 중" else "주행 제한", if (drive.running) GuideMint else Amber)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            if (compactWindow && settings.consent) {
                                MainNavigation(page, false, compact = true) {
                                    page = it
                                    detailId = null
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when {
                                    driving -> DriveScreen(
                                        drive, locked, platform, model::stop, model::retryVehicle, voice.text,
                                        { model.setDemoPaused(!drive.paused) }, { onMinimize() }, smallWindow,
                                    )
                                    !settings.consent -> GuideWelcome {
                                        model.updateSettings(settings.copy(consent = true))
                                        if (android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    page == "home" && compactWindow -> SplitHomeScreen(platform, model::startCoaching, model::retryVehicle)
                                    page == "report" && selected != null && detail != null ->
                                        DetailScreen(detail, { detailId = null }, model::speakCoaching) { from, to -> model.frames(selected.id, from, to) }
                                    page == "record-query" && selected != null -> RecordQueryScreen(
                                        selected, locked, { page = "report" }, { json -> model.queryLocalRecord(selected.id, json) }, model::speak,
                                        gleoSelected = settings.gleoSessionId == selected.id,
                                        onGleoSelection = if (BuildConfig.FLAVOR == "pleos") ({ enabled -> model.selectGleoRecord(if (enabled) selected.id else null) }) else null,
                                    )
                                    page == "report" && selected != null -> ReportScreen(
                                        selected, { detailId = it }, { page = "video" }, { page = "home" }, model::speak,
                                        { model.delete(selected.id); selectedId = null; page = "records" },
                                        { selected.demoCaseId?.let { model.startDemo(it, 1, selected.id) } },
                                        { page = "record-query" },
                                    )
                                    page == "video" && selected != null -> VideoReview(
                                        selected, { page = "report" }, locked,
                                        { position -> calibrationPositionMs = position; calibrationPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                                        { ratio, position -> model.selectParkingTarget(selected.id, ratio, position) },
                                        videoAnalyzing,
                                        { position -> model.analyzeVideoWindow(selected.id, position) },
                                        { position -> model.clearParkingTarget(selected.id, position) },
                                        loadFrames = model::frames,
                                    )
                                    page == "records" || page == "report" -> RecordsScreen(
                                        records, { selectedId = it; page = "report" }, { filePicker.launch(arrayOf("video/*")) },
                                        { name -> model.createVideoExample(name) { id -> selectedId = id; page = "video" } },
                                    )
                                    page == "settings" -> SettingsScreen(
                                        settings, platform, model::updateSettings, model::deleteAll, model::retryVehicle,
                                        { permissions.launch(arrayOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_DRIVE", "pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.TTS_SERVICE")) },
                                        { model.selectGleoRecord(null) },
                                    )
                                    else -> GuideHome(records, model.liveFrame, model::startCoaching, { selectedId = it; page = "report" }, demoActions)
                                }
                            }
                            if (compactWindow && settings.consent && page == "home") {
                                Spacer(Modifier.height(8.dp))
                                demoActions()
                            }
                        }
                    }
                    message?.let { notice ->
                        AlertDialog(
                            onDismissRequest = { model.message.value = null },
                            title = { Text("$appName 안내") },
                            text = { Text(if (page == "settings") notice else userNoticeText(notice)) },
                            confirmButton = {
                                TextButton(onClick = { model.message.value = null }) { Text("확인", fontSize = 20.sp) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .13f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
fun Action(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick, modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondary) colors.secondaryContainer else colors.primary,
            contentColor = if (secondary) colors.onSecondaryContainer else colors.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CardPanel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier, shape = RoundedCornerShape(22.dp), color = color,
        contentColor = colors.onSurface, border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun Heading(title: String, subtitle: String? = null) {
    Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    if (subtitle != null) {
        Spacer(Modifier.height(7.dp))
        Text(subtitle, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 25.sp)
    }
}

fun dateText(time: Long) = SimpleDateFormat("M월 d일  HH:mm", Locale.KOREA).format(Date(time))

@Composable
fun RecordRow(r: SessionRecord, highlighted: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val interrupted = r.state in listOf(Lifecycle.INTERRUPTED, Lifecycle.CANCELLED)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (highlighted) GuideSelected else GuidePaper)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = GuideInk)
            Text(dateText(r.createdAt), fontSize = 14.sp, color = GuideMuted, modifier = Modifier.padding(top = 4.dp))
        }
        r.score?.let { score ->
            Text(
                if (r.results.any { it.scoreKind == "PROVISIONAL" }) "임시 $score" else "$score",
                fontSize = 21.sp, fontWeight = FontWeight.Bold, color = reviewScoreColor(score),
            )
        }
        Text(
            when {
                interrupted -> "중단됨"
                r.score == null -> "평가 보류"
                else -> "기록 보기"
            },
            fontSize = 14.sp, color = GuideMuted,
        )
        Text("›", fontSize = 25.sp, color = GuideMuted)
    }
}

@Composable
private fun RecordsScreen(
    records: List<SessionRecord>,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onExample: (String) -> Unit,
) {
    var examples by remember { mutableStateOf(false) }
    if (examples) {
        AlertDialog(
            onDismissRequest = { examples = false },
            title = { Text("영상 예제") },
            text = {
                Column {
                    listOf("aligned" to "정렬", "tilted" to "기울어짐", "intrusion" to "선 침범", "occluded" to "가림", "blank" to "선 없음")
                        .forEach { (id, title) ->
                            TextButton(onClick = { examples = false; onExample(id) }, modifier = Modifier.fillMaxWidth()) { Text(title) }
                        }
                }
            },
            confirmButton = { TextButton(onClick = { examples = false }) { Text("닫기") } },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 790.dp
        val inlineImport = maxWidth >= 560.dp
        val showExampleArt = maxHeight >= 500.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            if (inlineImport) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Heading("주행 기록", "동작별 점수와 평가 근거를 확인해 보세요.") }
                    Action("영상 가져오기", onClick = onImport)
                }
            } else {
                Heading("주행 기록")
                Action("영상 가져오기", onClick = onImport)
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RecordList(records, onSelect, Modifier.weight(2.1f))
                    Surface(
                        Modifier.weight(1f), shape = RoundedCornerShape(22.dp),
                        color = GuideForest, contentColor = GuidePaper,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("주차 영상 분석", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("정차 후 영상에서 주차선과 차량 위치를 살펴보세요.", color = GuideDriveMuted, fontSize = 16.sp)
                            if (showExampleArt) ParkingExampleArt(Modifier.fillMaxWidth().height(116.dp))
                            Button(
                                onClick = { examples = true },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("parking-video-examples"),
                                shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GuideMint, contentColor = GuideInk),
                            ) { Text("영상 분석 예제 보기", fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            } else {
                TextButton(onClick = { examples = true }, modifier = Modifier.testTag("parking-video-examples")) {
                    Text("영상 분석 예제 보기")
                }
                RecordList(records, onSelect, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun RecordList(records: List<SessionRecord>, onSelect: (String) -> Unit, modifier: Modifier) {
    CardPanel(modifier) {
        Text("저장된 연습", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (records.isEmpty()) {
            Text("저장된 연습이 없어요", fontSize = 19.sp)
            Text("홈에서 코칭 시작을 눌러 보세요.", fontSize = 16.sp, color = GuideMuted)
        } else {
            LazyColumn {
                items(records, key = { it.id }) { r ->
                    RecordRow(r, highlighted = r.id == records.first().id) { onSelect(r.id) }
                }
            }
        }
    }
}

@Composable
private fun ParkingExampleArt(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(GuideDriveRaised)) {
        val top = size.height * .20f
        val bottom = size.height * .80f
        val left = size.width * .13f
        val bay = size.width * .246f
        drawLine(GuideDriveMuted, Offset(left, top), Offset(left + bay * 3, top), 1.dp.toPx())
        for (i in 0..3) {
            val x = left + bay * i
            drawLine(GuideDriveMuted, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        }
        drawRoundRect(
            GuideRoute, topLeft = Offset(left + bay * 1.26f, size.height * .31f),
            size = Size(bay * .48f, size.height * .40f), cornerRadius = CornerRadius(6.dp.toPx()),
        )
        drawRoundRect(
            GuideForest, topLeft = Offset(left + bay * 1.32f, size.height * .38f),
            size = Size(bay * .36f, size.height * .12f), cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportScreen(
    r: SessionRecord,
    onDetail: (String) -> Unit,
    onVideo: () -> Unit,
    onHome: () -> Unit,
    onSpeak: (String) -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
    onQuery: () -> Unit,
) {
    var deleteDialog by remember { mutableStateOf(false) }
    val summary = userSessionSummary(r.results)
    val scoreCard: @Composable (Modifier) -> Unit = { modifier ->
        CardPanel(modifier, GuideSelected) {
            Text(if (r.results.any { it.scoreKind == "PROVISIONAL" }) "임시 점수" else "이번 연습", fontSize = 17.sp, color = GuideMuted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(r.score?.toString() ?: "보류", fontSize = 58.sp, color = GuideInk, fontWeight = FontWeight.Bold)
                if (r.score != null) Text(" / 100", fontSize = 19.sp, color = GuideMuted, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(if (r.score != null) "평가된 동작 점수의 평균" else "필수 평가 근거가 부족해요.", fontSize = 15.sp, color = GuideMuted)
            r.error?.let { Text(userNoticeText(it), fontSize = 16.sp, color = GuideWarning, modifier = Modifier.padding(top = 10.dp)) }
            if (r.state == Lifecycle.INTERRUPTED && r.demoCaseId != null) {
                Spacer(Modifier.height(10.dp))
                Action("저장 지점에서 다시 시작", onClick = onResume)
            }
        }
    }
    val nextCard: @Composable (Modifier) -> Unit = { modifier ->
        CardPanel(modifier) {
            Text("다음 연습", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            summary.forEach { Text(it, fontSize = 17.sp, color = GuideMuted, lineHeight = 25.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Action("요약 듣기", secondary = true) { summary.lastOrNull()?.let(onSpeak) }
                Action("저장 기록에 질문하기", Modifier.testTag("record-query-open"), secondary = true, onClick = onQuery)
                if (r.videoAsset != null || r.videoFile != null) {
                    Action("주차 영상 보기", Modifier.testTag("open-video"), secondary = true, onClick = onVideo)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 730.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Heading("연습 요약", "${r.title} · ${dateText(r.createdAt)}") }
                Action("새 연습", secondary = true, onClick = onHome)
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    scoreCard(Modifier.weight(.8f))
                    nextCard(Modifier.weight(1.6f))
                }
            } else {
                scoreCard(Modifier.fillMaxWidth())
                nextCard(Modifier.fillMaxWidth())
            }
            CardPanel(Modifier.fillMaxWidth()) {
                Text("동작별 평가 근거", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (r.results.isEmpty()) {
                    Text("아직 확정된 동작이 없어요", fontSize = 20.sp)
                    Text("주행 정보가 모이면 동작별 결과를 확인해요.", fontSize = 16.sp, color = GuideMuted)
                }
                r.results.forEachIndexed { index, result ->
                    if (index > 0) HorizontalDivider(color = GuideLine)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onDetail(result.id) }.padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(result.type.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            val reason = when {
                                result.deductions.isNotEmpty() -> result.deductions.joinToString(" · ") { "${userDeductionTitle(it)} −${it.points}점" }
                                result.score == null -> result.holdReasons.map(::userHoldReason).distinct().joinToString(" · ")
                                    .ifBlank { "주행 정보가 부족해 점수를 매기지 못했어요." }
                                else -> "감점 없이 마쳤어요."
                            }
                            Text(reason, fontSize = 15.sp, color = GuideMuted, modifier = Modifier.padding(top = 4.dp))
                            if (isParking(result.type)) {
                                Text("후진에서 전진으로 ${result.reverseToDriveCount}회", fontSize = 15.sp, color = GuideGreen)
                            }
                        }
                        Text(scoreCaption(result.score, result.scoreKind) + "  ›", fontSize = 20.sp, color = reviewScoreColor(result.score))
                    }
                }
            }
            FeedbackJournal(r.feedbacks.orEmpty())
            TextButton(onClick = { deleteDialog = true }, modifier = Modifier.heightIn(min = 52.dp)) {
                Text("이 연습 기록 삭제", color = GuideError)
            }
        }
    }
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("연습 기록을 삭제할까요?") },
            text = { Text("이 연습의 기록과 함께 저장된 사용자 영상이 기기에서 삭제돼요.") },
            confirmButton = { TextButton(onClick = { deleteDialog = false; onDelete() }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun DetailScreen(
    r: ManeuverResult,
    onBack: () -> Unit,
    onSpeak: (ManeuverResult) -> Unit,
    loadFrames: suspend (fromMs: Long, toMs: Long) -> List<VehicleFrame>,
) {
    val parking = isParking(r.type)
    val frames by produceState<List<VehicleFrame>?>(null, r.id) {
        if (parking) {
            // Load only this maneuver's window, even for long sessions.
            value = null
            value = loadFrames(r.startMs, r.endMs)
        }
    }
    val replay: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            val loaded = frames
            if (loaded != null) {
                ParkingReplayCard(r, loaded)
            } else {
                CardPanel(Modifier.fillMaxWidth().testTag("parking-replay")) {
                    Text("주차 궤적 다시 보기", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("궤적을 계산하고 있어요.", color = GuideMuted, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
    val assessment: @Composable (Modifier) -> Unit = { modifier ->
        CardPanel(modifier) {
            Text("동작 평가", fontSize = 18.sp, color = GuideMuted)
            Text(scoreCaption(r.score, r.scoreKind), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = reviewScoreColor(r.score))
            Text(userResultText(r), fontSize = 20.sp, lineHeight = 28.sp, modifier = Modifier.padding(vertical = 10.dp))
            r.deductions.forEach { deduction ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(userDeductionTitle(deduction), fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("−${deduction.points}점", fontSize = 16.sp, color = GuideError)
                }
            }
            HorizontalDivider(color = GuideLine, modifier = Modifier.padding(vertical = 12.dp))
            Text("연습 정보", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            r.measurements["maxSpeedMps"]?.takeIf { it.isFinite() }?.let { speed ->
                Text("최고 속도 ${formatValue(kotlin.math.abs(speed) * 3.6)} km/h", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (parking) Text("후진에서 전진으로 ${r.reverseToDriveCount}회", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Text("점수는 연습을 돌아보기 위한 참고용이에요.", fontSize = 14.sp, color = GuideMuted, modifier = Modifier.padding(vertical = 10.dp))
            Action("코칭 듣기", Modifier.fillMaxWidth(), secondary = true) { onSpeak(r) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("user-maneuver-detail")) {
        val wide = maxWidth >= 790.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            TextButton(onClick = onBack) { Text("← 연습 요약") }
            Heading(if (parking) "${r.type.title} 리플레이" else r.type.title)
            if (parking && wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    replay(Modifier.weight(2.1f))
                    assessment(Modifier.weight(1f))
                }
            } else {
                assessment(Modifier.fillMaxWidth())
                if (parking) replay(Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun reviewScoreColor(score: Int?): Color = when (FeedbackBand.from(score)) {
    FeedbackBand.PERFECT -> Color(0xff255e9c)
    FeedbackBand.CAUTION -> GuideWarning
    FeedbackBand.WARNING -> GuideError
    FeedbackBand.UNRATED -> GuideMuted
}

internal fun userNoticeText(raw:String):String {
    if(raw.contains("동의")) return "시작 안내와 저장 동의 여부를 확인해 주세요."
    if(raw.contains("권한")) return "앱 사용 권한을 확인해 주세요."
    if(raw.contains("250")) return "250MB 이하의 영상을 선택해 주세요."
    if(raw.contains("저장 공간")) return "영상을 저장할 공간이 부족해요."
    if(raw.contains("저장했") || raw.contains("분석했") || raw.contains("선택했") || raw.contains("해제했") || raw.contains("연결하고"))
        return if(raw.contains("영상") || raw.contains("구간") || raw.contains("보정")) "영상의 변경 내용을 저장했어요." else "변경 내용을 저장했어요."
    if(raw.contains("정차") || raw.contains("주행 제한")) return "정차 후 이용해 주세요."
    if(raw.contains("재생")) return "영상을 재생하지 못했어요. 다른 영상을 선택해 주세요."
    if(raw.contains("삭제")) return "기록을 확인해 주세요."
    if(raw.contains("음성") || raw.contains("엔진") || raw.contains("발화")) return "음성 안내를 사용할 수 없어 화면으로 알려드려요."
    if(raw.contains("영상") || raw.contains("보정") || raw.contains("파일")) return "영상을 처리하지 못했어요. 다른 영상을 선택하거나 잠시 후 다시 시도해 주세요."
    if(raw.contains("연결") || raw.contains("신호")) return "차량 연결을 확인한 뒤 다시 시도해 주세요."
    return "지금은 작업을 완료하지 못했어요. 잠시 후 다시 시도해 주세요."
}

fun userGear(gear:Gear?):String=when(gear) {
    Gear.PARK->"P";Gear.REVERSE->"R";Gear.NEUTRAL->"N";Gear.DRIVE->"D";else->"—"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    s: UserSettings,
    p: PlatformState,
    onSettings: (UserSettings) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onPermissions: () -> Unit,
    onDisconnectGleo: () -> Unit,
) {
    var delete by remember { mutableStateOf(false) }
    val coaching: @Composable (Modifier) -> Unit = { modifier ->
        CardPanel(modifier) {
            Text("코칭 안내", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            SettingToggle("음성 안내 끄기", "필요할 때 화면으로 코칭을 확인해요.", s.muted) { onSettings(s.copy(muted = it)) }
            HorizontalDivider(color = GuideLine)
            Spacer(Modifier.height(14.dp))
            Text("음성 안내 최소 간격", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf(3, 6, 15, 30).forEach { seconds ->
                    Action("${seconds}초", secondary = s.voiceIntervalSeconds != seconds) { onSettings(s.copy(voiceIntervalSeconds = seconds)) }
                }
            }
            Text("말하는 중이거나 회전·후진 중이면 잠시 기다려요.", fontSize = 15.sp, color = GuideMuted, modifier = Modifier.padding(vertical = 10.dp))
            HorizontalDivider(color = GuideLine)
            SettingToggle(
                "시작하면 작은 창으로",
                "지원 기기에서 코칭을 시작하거나 다른 앱으로 이동하면 작은 창으로 표시해요.",
                s.smallWindowOnStart,
            ) { onSettings(s.copy(smallWindowOnStart = it)) }
        }
    }
    val storage: @Composable (Modifier) -> Unit = { modifier ->
        CardPanel(modifier) {
            Text("기록과 영상", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            SettingToggle(
                "영상의 기기 내 저장에 동의",
                "사용자가 선택한 영상 파일만 저장해요. 내장 카메라 연동은 미확인 상태예요.",
                s.saveVideo,
            ) { onSettings(s.copy(saveVideo = it)) }
            HorizontalDivider(color = GuideLine)
            Spacer(Modifier.height(14.dp))
            Text("기록·영상 보관 기간", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                listOf(7, 30, 90).forEach { days ->
                    Action("${days}일", secondary = s.retentionDays != days) { onSettings(s.copy(retentionDays = days)) }
                }
            }
            if (BuildConfig.FLAVOR == "pleos") {
                HorizontalDivider(color = GuideLine, modifier = Modifier.padding(vertical = 10.dp))
                Text(if (s.gleoSessionId == null) "Gleo에 연결한 기록 없음" else "선택한 기록 1개가 Gleo에 연결됨", fontSize = 17.sp)
                Text("기록의 ‘저장 기록에 질문하기’ 화면에서 연결할 수 있어요.", fontSize = 15.sp, color = GuideMuted)
                if (s.gleoSessionId != null) {
                    TextButton(onClick = onDisconnectGleo, modifier = Modifier.testTag("gleo-disconnect")) { Text("Gleo 기록 연결 해제") }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { delete = true }, modifier = Modifier.heightIn(min = 52.dp)) {
                Text("모든 연습 기록·저장 영상 삭제", color = GuideError)
            }
        }
    }
    val vehicle: @Composable (Modifier) -> Unit = { modifier ->
        CardPanel(modifier) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("차량 연결 상태", Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Badge(if (p.vehicleConnected) "연결됨" else "연결 확인", if (p.vehicleConnected) GuideGreen else GuideMuted)
            }
            Spacer(Modifier.height(10.dp))
            listOf(
                "차량 서비스" to p.connection, "속도" to p.speed, "기어" to p.gear,
                "핸들 각도" to p.steering, "진행 방향" to p.heading,
                "어라운드뷰" to "공식 입력·녹화 허용 미확인",
            ).forEach { (label, value) ->
                Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, fontSize = 15.sp, color = GuideMuted, modifier = Modifier.weight(.34f))
                    Text(value, fontSize = 15.sp, modifier = Modifier.weight(.66f))
                }
            }
            Spacer(Modifier.height(9.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("연결 다시 확인", secondary = true, onClick = onRetry)
                if (BuildConfig.FLAVOR == "pleos") Action("차량 읽기 권한 요청", secondary = true, onClick = onPermissions)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 740.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Heading("설정", "코칭, 차량 연결, 기록 보관을 관리하세요.")
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        coaching(Modifier.fillMaxWidth())
                        vehicle(Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        storage(Modifier.fillMaxWidth())
                        EvaluationLimits()
                    }
                }
            } else {
                coaching(Modifier.fillMaxWidth())
                vehicle(Modifier.fillMaxWidth())
                storage(Modifier.fillMaxWidth())
                EvaluationLimits()
            }
        }
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("모든 기록을 삭제할까요?") },
            text = { Text("기기에 저장한 연습 기록과 사용자 영상이 삭제돼요.") },
            confirmButton = { TextButton(onClick = { delete = false; onDelete() }) { Text("모두 삭제") } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun EvaluationLimits() {
    CardPanel(Modifier.fillMaxWidth()) {
        Text("데이터셋과 점수의 한계", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(
            "평가 기준은 문헌 기반 초안이며, 임의 배점과 합성 검증 데이터는 개선이 필요합니다.\n" +
                "첨부 문서의 yaw 적분·속도·기어 규칙과 5/7/10점 제안 구조를 사용해요. 전문가 평가와 실차 검증 전이며, 공개 연구 데이터셋으로 학습하거나 보정하지 않았어요.\n" +
                "인식은 규칙 기반 시제품이에요. 현재 차량 연결에는 검증된 yaw rate가 없어 실시간 회전·주차 유형 평가는 보류돼요.\n" +
                "횡가속은 전용 센서가 없으면 v×yaw로 추정하며 경사·미끄러짐은 보정하지 않아요. 안전 맥락·주차 완성도·UBI 주행 점수는 보류해요.",
            fontSize = 16.sp, lineHeight = 24.sp, color = GuideWarning, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 14.sp, color = GuideMuted, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked, onChange, modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp))
    }
}
