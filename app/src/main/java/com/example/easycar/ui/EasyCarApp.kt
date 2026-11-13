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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow

val Ink=Color(0xff101b23);val Panel=Color(0xff192932);val PanelLight=Color(0xff223640)
val Mint=Color(0xff9ae7c4);val Muted=Color(0xffa6b8c2);val White=Color(0xffeef4f2)
val Amber=Color(0xffffd28a)
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
    MaterialTheme(colorScheme=darkColorScheme(primary=Mint,onPrimary=Ink,background=Ink,surface=Panel,onSurface=White,onBackground=White,secondary=Mint,outline=Muted),typography=Typography()) {
        Surface(Modifier.fillMaxSize(),color=Ink) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compactWindow=maxWidth<900.dp || maxHeight<500.dp
                val roomyHeader=maxWidth>=1000.dp
                val showConnectionBadge=maxWidth>=900.dp
                if(smallWindow.active || smallWindow.entering || (compactWindow && (drive.running || locked))) {
                    MiniCoachScreen(drive,smallWindow.active || smallWindow.entering,model::stop,model.evaluationPolicy.datasetNotice,locked,{model.setDemoPaused(!drive.paused)})
                } else {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if(!compactWindow) MainNavigation(page,drive.running || locked) { page=it;detailId=null }
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=if(compactWindow) 20.dp else 32.dp,vertical=if(compactWindow) 16.dp else 24.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(appName,fontSize=28.sp,fontWeight=FontWeight.Bold,color=Mint)
                        if(roomyHeader) Text("  /  나의 운전 연습",fontSize=16.sp,color=Muted)
                        Spacer(Modifier.weight(1f))
                        if(showConnectionBadge) Badge(if(drive.running) "●  자동 코칭 중" else if(locked) "주행 제한" else if(platform.vehicleConnected) "차량 연결됨" else "연결 확인",if(locked) Amber else Mint)
                        if(!drive.running && !locked && settings.consent) {Spacer(Modifier.width(12.dp));Action("T자 데모",Modifier.testTag("start-t-park-demo"),secondary=true) {model.startParkingDemo("park-beginner")};Spacer(Modifier.width(12.dp))
                            Action("평행 데모",Modifier.testTag("start-parallel-park-demo"),secondary=true) {model.startParkingDemo("park-parallel")};Spacer(Modifier.width(12.dp));Action("데모",Modifier.testTag("start-submission-demo"),secondary=true,onClick=model::startSubmissionDemo)}
                    }
                    if(compactWindow) {Spacer(Modifier.height(12.dp));MainNavigation(page,false,compact=true) {page=it;detailId=null}}
                    Spacer(Modifier.height(if(compactWindow) 16.dp else 26.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            drive.running || locked -> DriveScreen(drive,locked,platform,model::stop,model::retryVehicle,voice.text,{model.setDemoPaused(!drive.paused)},{onMinimize()},smallWindow)
                            !settings.consent -> Welcome { model.updateSettings(settings.copy(consent=true));if(android.os.Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            page=="home" && compactWindow -> SplitHomeScreen(platform,model::startCoaching,model::retryVehicle)
                            page=="home" -> HomeScreen(records,platform,model.liveFrame,model::startCoaching) { selectedId=it;page="report" }
                            page=="report" && selected!=null && detail!=null -> DetailScreen(detail,{ detailId=null },model::speakCoaching) {from,to->model.frames(selected.id,from,to)}
                            page=="record-query" && selected!=null -> RecordQueryScreen(selected,locked,{page="report"},{json->model.queryLocalRecord(selected.id,json)},model::speak,
                                gleoSelected=settings.gleoSessionId==selected.id,onGleoSelection=if(BuildConfig.FLAVOR=="pleos") ({enabled->model.selectGleoRecord(if(enabled) selected.id else null)}) else null)
                            page=="report" && selected!=null -> ReportScreen(selected,{ detailId=it },{ page="video" },{ page="home" },model::speak,{ model.delete(selected.id);selectedId=null;page="records" },{ selected.demoCaseId?.let { model.startDemo(it,1,selected.id) } },{page="record-query"})
                            page=="video" && selected!=null -> VideoReview(selected,{ page="report" },locked,{position->calibrationPositionMs=position;calibrationPicker.launch(arrayOf("application/json","text/*","application/octet-stream"))},{ratio,position->model.selectParkingTarget(selected.id,ratio,position)},videoAnalyzing,{position->model.analyzeVideoWindow(selected.id,position)},{position->model.clearParkingTarget(selected.id,position)},loadFrames=model::frames)
                            page=="records" || page=="report" -> RecordsScreen(records,{ selectedId=it;page="report" },{ filePicker.launch(arrayOf("video/*")) },{name->model.createVideoExample(name) {id->selectedId=id;page="video"}})
                            page=="settings" -> SettingsScreen(settings,platform,model::updateSettings,model::deleteAll,model::retryVehicle,{ permissions.launch(arrayOf("android.car.permission.CAR_SPEED","pleos.car.permission.CAR_DRIVE","pleos.car.permission.FUSED_LOCATION","pleos.car.permission.TTS_SERVICE")) },{model.selectGleoRecord(null)})
                            else -> HomeScreen(records,platform,model.liveFrame,model::startCoaching) { selectedId=it;page="report" }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("점수는 연습을 돌아보기 위한 참고용이에요.",fontSize=13.sp,color=Muted)
                }
            }
            if(message!=null) AlertDialog(onDismissRequest={model.message.value=null},title={Text("$appName 안내")},text={Text(if(page=="settings") message!! else userNoticeText(message!!))},confirmButton={TextButton(onClick={model.message.value=null}) { Text("확인",fontSize=20.sp) }})
                }
            }
        }
    }
}
@Composable fun Badge(text:String,color:Color=Mint) { Text(text,Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha=.12f)).padding(horizontal=16.dp,vertical=9.dp),color=color,fontSize=14.sp,fontWeight=FontWeight.Medium) }
@Composable fun Action(text:String,modifier:Modifier=Modifier,secondary:Boolean=false,onClick:()->Unit) {
    Button(onClick,modifier.heightIn(min=60.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=if(secondary) PanelLight else Mint,contentColor=if(secondary) White else Ink),contentPadding=PaddingValues(horizontal=24.dp,vertical=14.dp)) { Text(text,fontSize=19.sp,fontWeight=FontWeight.SemiBold) }
}
@Composable fun CardPanel(modifier:Modifier=Modifier,color:Color=Panel,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(color).padding(26.dp),content=content)
}
@Composable fun Heading(title:String,subtitle:String?=null) { Text(title,fontSize=34.sp,fontWeight=FontWeight.Bold,color=White);if(subtitle!=null) { Spacer(Modifier.height(8.dp));Text(subtitle,fontSize=18.sp,color=Muted,lineHeight=26.sp) } }
@Composable private fun Welcome(onAgree:()->Unit) {
    val appName=stringResource(R.string.app_name)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if(maxWidth<900.dp) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Heading("익숙해질 때까지, ${appName}과 함께.","출발부터 주차까지, 내 조작을 차분히 돌아보세요.")
                Spacer(Modifier.height(22.dp))
                CardPanel(Modifier.fillMaxWidth()) {
                    Text("시작하기 전에",fontSize=25.sp,fontWeight=FontWeight.Bold)
                    Text("데모로 사용 방법을 확인할 수 있어요. 연습 점수는 참고용이에요. 기록은 기기에 보관하며 언제든 삭제할 수 있어요. 영상 저장은 따로 동의한 뒤 진행하고, 주행 중에는 상세 화면과 영상을 제한해요.",fontSize=19.sp,color=Muted,lineHeight=30.sp,modifier=Modifier.padding(vertical=18.dp))
                    Action("안내를 확인하고 시작하기",Modifier.fillMaxWidth().testTag("consent"),onClick=onAgree)
                }
            }
        } else {
    Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(44.dp)) {
        Column(Modifier.weight(1f)) {
            Badge("처음 만나는 나의 운전 코치")
            Spacer(Modifier.height(24.dp));Text("익숙해질 때까지,\n${appName}과 함께.",fontSize=48.sp,lineHeight=62.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(20.dp));Text("출발부터 주차까지, 내 조작을 차분히 돌아보고\n다음 연습 목표를 찾아보세요.",fontSize=23.sp,color=Muted,lineHeight=34.sp)
            Spacer(Modifier.height(32.dp));RoadArt(Modifier.fillMaxWidth().height(210.dp))
        }
        CardPanel(Modifier.weight(.9f)) {
            Text("시작하기 전에",fontSize=27.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            listOf("데모로 사용 방법을 확인할 수 있어요. 연습 점수는 참고용이에요.","주행 정보가 부족하면 점수를 매기지 않아요.","연습 기록은 기기에만 보관해요. 언제든 삭제할 수 있어요.","영상 저장은 별도로 동의한 경우에만 진행해요.","주행 중에는 상세 화면과 영상을 제한해요.").forEach { Text("•  $it",fontSize=19.sp,color=Muted,lineHeight=29.sp,modifier=Modifier.padding(bottom=15.dp)) }
            Spacer(Modifier.height(14.dp));Action("안내를 확인하고 시작하기",Modifier.fillMaxWidth().testTag("consent"),onClick=onAgree)
        }
    }
        }
    }
}
@Composable private fun HomeScreen(records:List<SessionRecord>,platform:PlatformState,liveFrame:StateFlow<VehicleFrame?>,onStart:()->Unit,onReport:(String)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("coaching-home")) {
        Heading("한 번 시작하면, 주행을 함께 살펴봐요.","일곱 동작을 자동으로 인식하고, 끝나는 순간 화면과 음성으로 알려드려요.")
        Spacer(Modifier.height(22.dp))
        CardPanel(Modifier.fillMaxWidth(),Color(0xff234338)) {
            Text("코칭 시작 → 자동 인식 → 동작 완료 → 즉시 피드백",fontSize=26.sp,lineHeight=37.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("출발 · 정지 · 좌회전 · 우회전 · 유턴 · T자 주차 · 평행주차",fontSize=22.sp,lineHeight=33.sp,color=Color(0xffc5dcd0),modifier=Modifier.testTag("automatic-maneuvers"))
            Spacer(Modifier.height(20.dp))
            Action("코칭 시작",Modifier.fillMaxWidth().testTag("start-coaching"),onClick=onStart)
            Text("작은 창 지원 기기는 시작 후 자동으로 축소돼요. Pleos에서는 오른쪽 점 세 개 핸들을 왼쪽으로 끌어 내비 옆에 배치하세요.",fontSize=16.sp,lineHeight=25.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
            Text("사용 방법을 먼저 확인하려면 우측 상단의 데모를 눌러 주세요.",fontSize=16.sp,lineHeight=25.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        }
        Spacer(Modifier.height(18.dp))
        LiveSignalPanel(liveFrame)
        Spacer(Modifier.height(18.dp))
        Spacer(Modifier.height(26.dp));Text("최근 코칭",fontSize=24.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(14.dp))
        if(records.isEmpty()) CardPanel(Modifier.fillMaxWidth()) {Text("첫 코칭을 시작해 보세요",fontSize=22.sp);Text("완료한 동작과 화면·음성 피드백을 기록에서 확인해요.",fontSize=18.sp,color=Muted)}
        else records.take(2).forEach {r->RecordRow(r){onReport(r.id)};Spacer(Modifier.height(12.dp))}
    }
}
@Composable fun RoadArt(modifier:Modifier) {
    Canvas(modifier) {
        val w=size.width;val h=size.height
        val p=Path().apply { moveTo(w*.05f,h*.80f);cubicTo(w*.30f,h*.82f,w*.30f,h*.18f,w*.58f,h*.22f);cubicTo(w*.75f,h*.25f,w*.78f,h*.68f,w*.98f,h*.48f) }
        drawPath(p,Color(0xff5b8c78).copy(alpha=.3f),style=Stroke(width=62f,cap=StrokeCap.Round))
        drawPath(p,Mint.copy(alpha=.55f),style=Stroke(width=2f,cap=StrokeCap.Round,pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f,12f))))
        val cx=w*.56f;val cy=h*.23f
        drawRoundRect(Ink,androidx.compose.ui.geometry.Offset(cx-27,cy-45),androidx.compose.ui.geometry.Size(54f,90f),androidx.compose.ui.geometry.CornerRadius(13f))
        drawRoundRect(Mint,androidx.compose.ui.geometry.Offset(cx-22,cy-41),androidx.compose.ui.geometry.Size(44f,82f),androidx.compose.ui.geometry.CornerRadius(11f))
        drawRoundRect(Color(0xff285446),androidx.compose.ui.geometry.Offset(cx-17,cy-20),androidx.compose.ui.geometry.Size(34f,20f),androidx.compose.ui.geometry.CornerRadius(4f))
        drawCircle(Mint.copy(alpha=.2f),17f,androidx.compose.ui.geometry.Offset(w*.95f,h*.5f));drawCircle(Mint,6f,androidx.compose.ui.geometry.Offset(w*.95f,h*.5f))
    }
}
@Composable internal fun DriveScreen(d:DriveState,locked:Boolean,p:PlatformState,onStop:()->Unit,onRetry:()->Unit,voice:String="",onPause:()->Unit={},onMinimize:()->Unit={},miniWindow:MiniWindowState=MiniWindowState()) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("drive-screen")) {
        val compact=maxHeight<440.dp
        if(compact && d.running) CompactDriveScreen(d,locked,onStop,onPause,onMinimize,miniWindow)
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(d.record?.demoCaseId!=null) Badge(if(d.paused) "데모 일시 정지" else "데모")
                Spacer(Modifier.weight(1f))
                if(d.running) {Text(d.motion?.speedMps?.let {formatValue(abs(it)*3.6)} ?: "—",fontSize=if(compact) 38.sp else 58.sp,color=Mint);Text(" km/h",fontSize=18.sp,color=Muted);Spacer(Modifier.width(24.dp));Text(d.motion?.gear?.let {when(it){Gear.DRIVE->"D";Gear.REVERSE->"R";Gear.NEUTRAL->"N";Gear.PARK->"P";else->"—"}} ?: "—",fontSize=if(compact) 38.sp else 58.sp)}
            }
            Spacer(Modifier.height(if(compact) 16.dp else 28.dp))
            if(d.running) {
                val f=d.feedback
                if(f!=null) CoachFeedbackCard(f,compact)
                else CardPanel(Modifier.fillMaxWidth().testTag("waiting-next-maneuver")) {Text("다음 동작 대기 중",fontSize=if(compact) 24.sp else 32.sp,color=Mint)}
                Spacer(Modifier.height(if(compact) 12.dp else 24.dp))
                if(d.record?.demoCaseId!=null) LinearProgressIndicator(progress={d.progress},modifier=Modifier.fillMaxWidth().height(5.dp),color=Mint,trackColor=PanelLight)
                Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("동작을 마치면 짧게 안내해 드려요.",fontSize=15.sp,color=Muted,modifier=Modifier.weight(1f));if(d.feedbackHistory.isNotEmpty()) Text("완료 피드백 ${d.feedbackHistory.size}개",fontSize=17.sp,color=Muted)
                    Spacer(Modifier.width(20.dp));if(d.record?.demoCaseId!=null && !locked) Action(if(d.paused) "계속 재생" else "일시 정지",secondary=true,onClick=onPause)
                    if(miniWindow.supported) {Spacer(Modifier.width(12.dp));Action("작은 창",Modifier.testTag("minimize-coach"),secondary=true,onClick=onMinimize)}
                    Spacer(Modifier.width(12.dp));Action("주행 종료",Modifier.testTag("end-drive"),secondary=true,onClick=onStop)
                }
            } else {Text(if(p.ux.known) "정차 후 상세 기록과 설정을 사용할 수 있어요." else "주행 규제 상태가 확인되면 상세 화면을 열 수 있어요.",fontSize=23.sp,color=Muted);Spacer(Modifier.height(20.dp));Action("연결 다시 확인",secondary=true,onClick=onRetry)}
            miniWindow.message?.takeIf {miniWindow.supported}?.let {Text(it,color=Muted,fontSize=15.sp,lineHeight=22.sp,modifier=Modifier.padding(top=8.dp))}
            d.error?.let {Text(userNoticeText(it),color=Amber,fontSize=20.sp)}
        }
    }
}
@Composable private fun CompactDriveScreen(d:DriveState,locked:Boolean,onStop:()->Unit,onPause:()->Unit,onMinimize:()->Unit,miniWindow:MiniWindowState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center) {
        val f=d.feedback
        if(f!=null) CoachFeedbackCard(f,true)
        else Column(Modifier.fillMaxWidth().testTag("waiting-next-maneuver").padding(vertical=14.dp)) {
            Text("다음 동작 대기 중",fontSize=22.sp,color=Mint)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            val speed=d.motion?.speedMps?.let {formatValue(abs(it)*3.6)} ?: "—"
            Text("${if(d.record?.demoCaseId!=null) "데모" else "차량"} · $speed km/h · ${userGear(d.motion?.gear)}",fontSize=16.sp,color=Muted,modifier=Modifier.weight(1f),maxLines=1)
            if(d.record?.demoCaseId!=null && !locked) Action(if(d.paused) "계속 재생" else "일시 정지",secondary=true,onClick=onPause)
            if(miniWindow.supported) Action("작은 창",Modifier.testTag("minimize-coach"),secondary=true,onClick=onMinimize)
            Action("주행 종료",Modifier.testTag("end-drive"),secondary=true,onClick=onStop)
        }
        miniWindow.message?.takeIf {miniWindow.supported}?.let {Text(it,fontSize=13.sp,lineHeight=18.sp,color=Muted)}
    }
}
fun dateText(time:Long)=SimpleDateFormat("M월 d일  HH:mm",Locale.KOREA).format(Date(time))
@Composable fun RecordRow(r:SessionRecord,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).clickable(onClick=onClick).padding(22.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(17.dp)).background(PanelLight),contentAlignment=Alignment.Center) { Text(r.score?.let {if(r.results.any {it.scoreKind=="PROVISIONAL"}) "임시 $it" else it.toString()} ?: "—",fontSize=22.sp,fontWeight=FontWeight.Bold,color=scoreColor(r.score)) }
        Spacer(Modifier.width(20.dp));Column(Modifier.weight(1f)) { Text(r.title,fontSize=21.sp,fontWeight=FontWeight.Medium);Text(dateText(r.createdAt),fontSize=15.sp,color=Muted,modifier=Modifier.padding(top=5.dp)) }
        Text(if(r.state in listOf(Lifecycle.INTERRUPTED,Lifecycle.CANCELLED)) "중단됨  →" else if(r.score==null) "평가 보류  →" else "기록 보기  →",fontSize=18.sp,color=scoreColor(r.score))
    }
}
@Composable private fun RecordsScreen(records:List<SessionRecord>,onSelect:(String)->Unit,onImport:()->Unit,onExample:(String)->Unit) {
    var examples by remember {mutableStateOf(false)}
    if(examples) AlertDialog(onDismissRequest={examples=false},title={Text("영상 예제")},text={
        Column {listOf("aligned" to "정렬","tilted" to "기울어짐","intrusion" to "선 침범","occluded" to "가림","blank" to "선 없음").forEach {(id,title)->
            TextButton(onClick={examples=false;onExample(id)},modifier=Modifier.fillMaxWidth()) {Text(title)}
        }}
    },confirmButton={TextButton(onClick={examples=false}) {Text("닫기")}})
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Heading("주행 기록","점수보다 근거를 먼저 확인해 보세요.") };Action("영상 가져오기",secondary=true,onClick=onImport) }
        TextButton(onClick={examples=true},modifier=Modifier.testTag("parking-video-examples")) {Text("영상 분석 예제 보기")}
        Spacer(Modifier.height(12.dp))
        if(records.isEmpty()) CardPanel(Modifier.fillMaxWidth()) { Text("저장된 연습이 없어요",fontSize=25.sp);Text("홈에서 코칭 시작을 눌러 보세요.",fontSize=19.sp,color=Muted) }
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp)) { items(records,key={it.id}) { r->RecordRow(r) {onSelect(r.id)} } }
    }
}
@Composable private fun ReportScreen(r:SessionRecord,onDetail:(String)->Unit,onVideo:()->Unit,onHome:()->Unit,onSpeak:(String)->Unit,onDelete:()->Unit,onResume:()->Unit,onQuery:()->Unit) {
    var deleteDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Heading("오늘의 연습을 돌아봐요.","${r.title} · ${dateText(r.createdAt)}") };Action("새 연습",secondary=true,onClick=onHome) }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            CardPanel(Modifier.weight(.7f)) {
                Text(if(r.results.any {it.scoreKind=="PROVISIONAL"}) "이번 연습 · 임시 점수" else "이번 연습",fontSize=20.sp,color=Muted);Spacer(Modifier.height(12.dp))
                Row(verticalAlignment=Alignment.Bottom) { Text(r.score?.toString() ?: "보류",fontSize=64.sp,color=scoreColor(r.score),fontWeight=FontWeight.Bold);if(r.score!=null) Text(" / 100",fontSize=24.sp,color=Muted,modifier=Modifier.padding(bottom=10.dp)) }
                Text(if(r.score!=null) "평가된 동작 점수의 평균" else "필수 근거가 부족한 동작이 있어요.",fontSize=16.sp,color=Muted)
                Spacer(Modifier.height(20.dp));Text("연습 참고용 점수",fontSize=16.sp,color=Muted)
                r.error?.let { Text(userNoticeText(it),fontSize=17.sp,color=Amber,modifier=Modifier.padding(top=16.dp)) }
                if(r.state==Lifecycle.INTERRUPTED && r.demoCaseId!=null) { Spacer(Modifier.height(16.dp));Action("저장 지점에서 다시 시작",onClick=onResume) }
            }
            CardPanel(Modifier.weight(1.3f)) {
                Text("다음 연습을 위한 한 걸음",fontSize=24.sp,fontWeight=FontWeight.Bold)
                userSessionSummary(r.results).forEach { Text(it,fontSize=19.sp,color=Muted,lineHeight=29.sp,modifier=Modifier.padding(top=17.dp)) }
                Spacer(Modifier.height(18.dp));Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(r.videoAsset!=null || r.videoFile!=null) Action("주차 영상 보기  →",Modifier.testTag("open-video"),onClick=onVideo)
                    Action("요약 듣기",secondary=true) { onSpeak(userSessionSummary(r.results).last()) }
                }
            }
        }
        TextButton(onClick=onQuery,modifier=Modifier.testTag("record-query-open")) {Text("저장 기록에 질문하기")}
        FeedbackJournal(r.feedbacks ?: emptyList())
        Spacer(Modifier.height(26.dp));Text("동작별 평가 근거",fontSize=25.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(14.dp))
        if(r.results.isEmpty()) CardPanel(Modifier.fillMaxWidth()) { Text("아직 확정된 동작이 없어요",fontSize=22.sp);Text("주행 정보가 모이면 동작별 결과를 확인할 수 있어요.",fontSize=18.sp,color=Muted) }
        r.results.forEach { result ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable { onDetail(result.id) }.padding(22.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(result.type.title,fontSize=23.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(7.dp));Text(if(result.deductions.isNotEmpty()) result.deductions.joinToString(" · ") { "${userDeductionTitle(it)} −${it.points}점" } else if(result.score==null) result.holdReasons.map(::userHoldReason).distinct().joinToString(" · ").ifBlank {"주행 정보가 부족해 점수를 매기지 못했어요."} else "감점 없이 마쳤어요.",fontSize=17.sp,color=Muted)
                    if(isParking(result.type)) Text("후진에서 전진으로 ${result.reverseToDriveCount}회",fontSize=18.sp,color=Mint,modifier=Modifier.padding(top=7.dp))
                }
                Text(scoreCaption(result.score,result.scoreKind)+"  →",fontSize=25.sp,color=scoreColor(result.score))
            };Spacer(Modifier.height(12.dp))
        }
        TextButton(onClick={deleteDialog=true},modifier=Modifier.heightIn(min=60.dp)) { Text("이 연습 기록 삭제",color=Muted,fontSize=17.sp) }
    }
    if(deleteDialog) AlertDialog(onDismissRequest={deleteDialog=false},title={Text("연습 기록을 삭제할까요?")},text={Text("이 연습의 기록과 함께 저장된 사용자 영상이 기기에서 삭제돼요.")},confirmButton={TextButton(onClick={deleteDialog=false;onDelete()}) {Text("삭제")}},dismissButton={TextButton(onClick={deleteDialog=false}) {Text("취소")}})
}
@Composable private fun DetailScreen(r:ManeuverResult,onBack:()->Unit,onSpeak:(ManeuverResult)->Unit,loadFrames:suspend (fromMs:Long,toMs:Long)->List<VehicleFrame>) {
    // Read only this manoeuvre's window; a session can hold up to an hour of frames.
    val frames by produceState(emptyList<VehicleFrame>(),r.id) {if(isParking(r.type)) value=loadFrames(r.startMs,r.endMs)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("user-maneuver-detail")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Action("← 연습 요약",secondary=true,onClick=onBack)
            Spacer(Modifier.width(22.dp));Heading(r.type.title)
            Spacer(Modifier.width(16.dp));Text(scoreCaption(r.score,r.scoreKind),fontSize=34.sp,fontWeight=FontWeight.Bold,color=scoreColor(r.score))
        }
        Spacer(Modifier.height(22.dp))
        CardPanel(Modifier.fillMaxWidth()) {
            Text(userResultText(r),fontSize=25.sp,lineHeight=36.sp,color=Mint)
            Spacer(Modifier.height(16.dp));Action("코칭 듣기",secondary=true) {onSpeak(r)}
        }
        r.deductions.forEach {d->
            Spacer(Modifier.height(12.dp));CardPanel(Modifier.fillMaxWidth()) {
                Row {
                    Text(userDeductionTitle(d),fontSize=23.sp,modifier=Modifier.weight(1f))
                    Text("−${d.points}점",fontSize=25.sp,color=Amber)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if(isParking(r.type)) ParkingReplayCard(r,frames)
        CardPanel(Modifier.fillMaxWidth()) {
            Text("연습 정보",fontSize=23.sp,fontWeight=FontWeight.Bold)
            r.measurements["maxSpeedMps"]?.takeIf {it.isFinite()}?.let {speed->
                Text("최고 속도 ${formatValue(kotlin.math.abs(speed)*3.6)} km/h",fontSize=20.sp,modifier=Modifier.padding(top=12.dp))
            }
            if(isParking(r.type)) Text("후진에서 전진으로 ${r.reverseToDriveCount}회",fontSize=20.sp,modifier=Modifier.padding(top=12.dp))
            Text("점수는 연습을 돌아보기 위한 참고용이에요.",fontSize=16.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        }
    }
}

private fun userNoticeText(raw:String):String {
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

@Composable private fun SettingsScreen(s:UserSettings,p:PlatformState,onSettings:(UserSettings)->Unit,onDelete:()->Unit,onRetry:()->Unit,onPermissions:()->Unit,onDisconnectGleo:()->Unit) {
    var delete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading("나에게 맞는 연습 환경","기록과 영상은 기기 안에 보관해요. Gleo 연결을 켠 기록의 평가 근거는 음성 질문에 응답할 때 Gleo에 전달해요.")
        Spacer(Modifier.height(24.dp));CardPanel(Modifier.fillMaxWidth()) {
            Text("기본 입력: Pleos 차량 서비스 신호",fontSize=23.sp)
            Text("제출용 합성 주행 한 개는 우측 상단 데모에서 별도로 실행해요.",fontSize=17.sp,lineHeight=26.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
            HorizontalDivider(color=PanelLight,modifier=Modifier.padding(vertical=16.dp))
            SettingToggle("시작하면 작은 창으로","지원 기기에서 코칭을 시작하거나 다른 앱으로 이동하면 작은 창으로 표시해요.",s.smallWindowOnStart) {onSettings(s.copy(smallWindowOnStart=it))}
            HorizontalDivider(color=PanelLight,modifier=Modifier.padding(vertical=16.dp))
            SettingToggle("음성 안내 끄기","필요할 때 화면으로 코칭을 확인해요.",s.muted) { onSettings(s.copy(muted=it)) }
            HorizontalDivider(color=PanelLight,modifier=Modifier.padding(vertical=16.dp))
            SettingToggle("영상의 기기 내 저장에 동의","사용자가 선택한 영상 파일만 저장해요. 내장 카메라 연동은 미확인 상태예요.",s.saveVideo) { onSettings(s.copy(saveVideo=it)) }
            if(BuildConfig.FLAVOR=="pleos") {
                HorizontalDivider(color=PanelLight,modifier=Modifier.padding(vertical=16.dp))
                Text(if(s.gleoSessionId==null) "Gleo에 연결한 기록 없음" else "선택한 기록 1개가 Gleo에 연결됨",fontSize=21.sp)
                Text("기록의 ‘저장 기록에 질문하기’ 화면에서 연결할 수 있어요.",fontSize=16.sp,color=Muted)
                if(s.gleoSessionId!=null) TextButton(onClick=onDisconnectGleo,modifier=Modifier.testTag("gleo-disconnect")) {Text("Gleo 기록 연결 해제")}
            }
            HorizontalDivider(color=PanelLight,modifier=Modifier.padding(vertical=16.dp))
            Text("음성 안내 최소 간격",fontSize=23.sp);Spacer(Modifier.height(12.dp));Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {listOf(3,6,15,30).forEach {seconds->Action("${seconds}초",secondary=s.voiceIntervalSeconds!=seconds){onSettings(s.copy(voiceIntervalSeconds=seconds))}}}
            Text("동작 직후 안내하되, 말하는 중이거나 회전·후진 중이면 잠시 기다려요.",fontSize=16.sp,color=Muted,modifier=Modifier.padding(vertical=14.dp))
            Text("기록·영상 보관 기간",fontSize=23.sp);Spacer(Modifier.height(14.dp));Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { listOf(7,30,90).forEach { d->Action("${d}일",secondary=s.retentionDays!=d) { onSettings(s.copy(retentionDays=d)) } } }
        }
        Spacer(Modifier.height(22.dp));CardPanel(Modifier.fillMaxWidth()) {
            Text("데이터셋과 점수의 한계",fontSize=25.sp,fontWeight=FontWeight.Bold)
            Text("평가 기준은 문헌 기반 초안이며, 임의 배점과 합성 검증 데이터는 개선이 필요합니다.\n첨부 문서의 yaw 적분·속도·기어 규칙과 5/7/10점 제안 구조를 사용해요. 전문가 평가와 실차 검증 전이며, 공개 연구 데이터셋으로 학습하거나 보정하지 않았어요.\n인식은 규칙 기반 시제품이에요. 현재 차량 연결에는 검증된 yaw rate가 없어 실시간 회전·주차 유형 평가는 보류돼요.\n횡가속은 전용 센서가 없으면 v×yaw로 추정하며 경사·미끄러짐은 보정하지 않아요. 안전 맥락·주차 완성도·UBI 주행 점수는 보류해요.",fontSize=18.sp,lineHeight=29.sp,color=Amber,modifier=Modifier.padding(top=14.dp))
        }
        Spacer(Modifier.height(22.dp));CardPanel(Modifier.fillMaxWidth()) {
            Text("차량 연결 상태",fontSize=25.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(15.dp))
            listOf("차량 서비스" to p.connection,"속도" to p.speed,"기어" to p.gear,"핸들 각도" to p.steering,"진행 방향" to p.heading,"어라운드뷰" to "공식 입력·녹화 허용 미확인","글레오" to if(s.gleoSessionId==null) "기록 연결 꺼짐" else "선택한 기록 조회 허용 · Gleo 앱 등록 활성화 필요").forEach { (label,value)->Row(Modifier.padding(vertical=8.dp)) { Text(label,fontSize=18.sp,color=Muted,modifier=Modifier.width(150.dp));Text(value,fontSize=18.sp) } }
            Spacer(Modifier.height(18.dp));Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Action("연결 다시 확인",secondary=true,onClick=onRetry);if(BuildConfig.FLAVOR=="pleos") Action("차량 읽기 권한 요청",secondary=true,onClick=onPermissions) }
        }
        Spacer(Modifier.height(20.dp));Action("모든 연습 기록·저장 영상 삭제",secondary=true) { delete=true }
    }
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("모든 기록을 삭제할까요?")},text={Text("기기에 저장한 연습 기록과 사용자 영상이 삭제돼요.")},confirmButton={TextButton(onClick={delete=false;onDelete()}) {Text("모두 삭제")}},dismissButton={TextButton(onClick={delete=false}) {Text("취소")}})
}
@Composable private fun SettingToggle(title:String,subtitle:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) {Text(title,fontSize=23.sp);Text(subtitle,fontSize=17.sp,color=Muted,modifier=Modifier.padding(top=7.dp))};Switch(checked,onChange,modifier=Modifier.sizeIn(minWidth=64.dp,minHeight=64.dp)) }
}
