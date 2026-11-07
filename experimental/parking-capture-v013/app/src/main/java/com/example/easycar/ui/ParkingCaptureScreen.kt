package com.example.easycar.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.parking.ParkingCaptureState
import com.example.easycar.runtime.LocalVideoInput
import kotlinx.coroutines.*
import kotlin.math.*

@Composable fun ParkingCaptureSetup(settings:UserSettings,onConsent:()->Unit,onStart:()->Unit,onExample:((String)->Unit)?=null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("parking-capture-setup")) {
        Heading("주차 시작부터 영상으로", "녹화 → 전후진 집계 → 최종 배치 채점 → 점수와 영상 저장")
        Spacer(Modifier.height(20.dp))
        CardPanel(Modifier.fillMaxWidth()) {
            Text("주차할 화면을 선택해 주세요",fontSize=25.sp,fontWeight=FontWeight.Bold)
            Text("시작하면 Android 화면 공유 창이 열려요. 녹화할 어라운드뷰 화면을 선택하세요. 선택한 화면의 영상만 기기에 저장하며 소리는 녹음하지 않아요.",fontSize=18.sp,lineHeight=28.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
            Text("실제 어라운드뷰 입력 연동은 미확인 상태예요. 보호된 화면은 녹화되지 않을 수 있어요. 화면 공유가 지원되는 환경에서 사용할 수 있어요.",fontSize=16.sp,lineHeight=25.sp,color=Amber,modifier=Modifier.padding(top=12.dp))
            Text("주차 중에는 안정된 R→D 전환을 세어요. 이동 후 멈추고 P단을 1초 유지하면 녹화가 자동으로 끝나요. P단 전에는 약 1초 이상 정차해 최종 장면을 남겨 주세요.",fontSize=18.sp,lineHeight=28.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
            if(!settings.saveVideo) TextButton(onClick=onConsent,modifier=Modifier.testTag("parking-video-consent")) {Text("영상의 기기 내 저장에 동의")}
            Spacer(Modifier.height(16.dp))
            Button(onClick=onStart,enabled=settings.saveVideo,modifier=Modifier.fillMaxWidth().heightIn(min=60.dp).testTag("parking-record-start")) {Text("주차 화면 선택 · 녹화 시작")}
        }
        Spacer(Modifier.height(18.dp))
        CardPanel(Modifier.fillMaxWidth()) {
            Text("주차 점수의 개발 기준",fontSize=23.sp,fontWeight=FontWeight.Bold)
            Text("100점에서 R→D 추가 전환과 최종 배치의 감점을 빼요. R→D 1회는 무료, 초과 1회당 5점(최대30점)이에요. 기울어짐·좌우/앞뒤 치우침·선 침범도 따로 설명해요. 배점은 개발용 초안이며 개선과 실측 검증이 필요해요.",fontSize=17.sp,lineHeight=27.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
            Text("차량 신호나 최종 영상 근거가 부족하면 관측된 횟수와 영상은 보존하고 점수는 보류해요.",fontSize=17.sp,lineHeight=27.sp,color=Amber,modifier=Modifier.padding(top=10.dp))
        }
        onExample?.let {run->
            Spacer(Modifier.height(18.dp));Text("합성 영상·신호로 흐름 확인",fontSize=22.sp,fontWeight=FontWeight.Bold)
            Text("실제 어라운드뷰 녹화가 아닌 개발용 예제예요.",fontSize=16.sp,color=Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TextButton(onClick={run("aligned")},modifier=Modifier.testTag("parking-flow-example")) {Text("정렬 예제")}
                TextButton(onClick={run("intrusion")}) {Text("선 침범 예제")}
                TextButton(onClick={run("blank")}) {Text("선 없음 예제")}
            }
        }
    }
}
@Composable fun ParkingCaptureRunning(state:ParkingCaptureState,onStop:()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    if(maxHeight<350.dp || maxWidth<550.dp) {
        Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(state.processing) "영상 저장 중" else if(state.record==null) "녹화 준비 중" else if(state.record.parkingCapture?.inputSource=="SYNTHETIC_REPLAY") "합성 흐름 · R→D ${state.count}회" else "주차 녹화 · R→D ${state.count}회",fontSize=20.sp,color=Mint)
            Button(onClick=onStop,enabled=!state.processing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("parking-record-stop")) {Text("녹화 종료 · 저장")}
        }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("parking-capture-running")) {
        Heading(if(state.processing) "주차 기록 저장 중" else if(state.record==null) "녹화 준비 중" else if(state.record?.parkingCapture?.inputSource=="SYNTHETIC_REPLAY") "합성 주차 흐름 확인 중" else "주차 화면 녹화 중",state.stage)
        Spacer(Modifier.height(30.dp));Text("R→D ${state.count}회",fontSize=48.sp,fontWeight=FontWeight.Bold,color=Mint,modifier=Modifier.testTag("parking-live-count"))
        Text("이동 후 정차하고 P단을 유지하면 자동 완료해요. 다른 앱을 열어도 녹화와 집계를 계속해요.",fontSize=21.sp,lineHeight=32.sp,color=Muted,modifier=Modifier.padding(vertical=24.dp))
        Button(onClick=onStop,enabled=!state.processing,modifier=Modifier.heightIn(min=64.dp).testTag("parking-record-stop")) {Text("녹화 종료 · 현재 기록 저장")}
        Text("직접 종료하면 완료 여부를 확인할 수 없어 통합 점수는 보류될 수 있어요.",fontSize=16.sp,color=Amber,modifier=Modifier.padding(top=16.dp))
    }
    }
}

@Composable fun ParkingCaptureReview(record:SessionRecord,restricted:Boolean,busy:Boolean,onBack:()->Unit,onVideo:()->Unit,
    onAnalyze:(Calibration,List<Point>?)->Unit) {
    val context=LocalContext.current;val capture=record.parkingCapture ?: return
    var frame by remember(record.id) {mutableStateOf<PixelFrame?>(null)}
    var bitmap by remember(record.id) {mutableStateOf<Bitmap?>(null)}
    var error by remember(record.id) {mutableStateOf<String?>(null)}
    var points by remember(record.id) {mutableStateOf<List<Point>>(emptyList())}
    var confirmed by remember(record.id) {mutableStateOf(false)}
    LaunchedEffect(record.id,restricted,capture.finalVideoMs) {
        if(restricted) {frame=null;bitmap=null;return@LaunchedEffect}
        try {val p=withContext(Dispatchers.Default) {capture.finalVideoMs?.let {LocalVideoInput(context).pixelAtVideoTime(record,it)}}
            frame=p;bitmap=p?.let {Bitmap.createBitmap(it.argb,it.width,it.height,Bitmap.Config.ARGB_8888)}
            if(p==null) error="최종 정차 장면을 확인할 수 없어요. 저장된 전체 영상을 확인해 주세요." else error=null
        } catch(e:Exception) {error=e.message;frame=null;bitmap=null}
    }
    if(restricted) {Text("정차 후 주차 결과와 영상을 확인할 수 있어요.");return}
    val outcome=capture.outcome;val editable=outcome?.score==null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("parking-capture-review")) {
        TextButton(onClick=onBack) {Text("← 기록")}
        Heading("주차 기록과 최종 배치",record.source)
        Spacer(Modifier.height(18.dp))
        CardPanel(Modifier.fillMaxWidth()) {
            Text(outcome?.score?.let {"${it}점 / 100 · 임시 점수"} ?: "주차 점수 보류",fontSize=34.sp,fontWeight=FontWeight.Bold,color=scoreColor(outcome?.score),modifier=Modifier.testTag("parking-outcome-score"))
            Text("R→D ${capture.episode?.reverseToDriveCount ?: 0}회 · ${if(capture.videoSaved) "영상 ${formatValue((record.videoDurationMs ?: 0)/1000.0)}초 저장됨" else "영상 저장 미완료"}",fontSize=19.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
            outcome?.deductions?.forEach {d->Text("${d.title} −${d.points}점 · 관측 ${formatValue(d.measured)} ${d.unit} / 기준 ${formatValue(d.allowed)}",fontSize=18.sp,color=Muted,modifier=Modifier.padding(top=10.dp))}
            outcome?.holds?.forEach {Text(it,fontSize=17.sp,color=Amber,lineHeight=26.sp,modifier=Modifier.padding(top=8.dp))}
            outcome?.placement?.let {p->Text("기울어짐 ${formatValue(p.angleDeg)}° · 좌우 편향 ${formatValue(p.lateralFraction*100)}% · 앞뒤 편향 ${formatValue(p.longitudinalFraction*100)}%\n${if(p.inside==true) "차체 외곽이 지정한 칸 안쪽에 있어요." else "차체 외곽과 주차칸 경계를 확인해 주세요."}\n근거: ${if(p.source.startsWith("USER")) "사용자 지정 주차칸·차량 외곽" else "영상에서 검출한 네 경계"}",fontSize=17.sp,lineHeight=26.sp,color=Muted,modifier=Modifier.padding(top=10.dp))}
            Text(outcome?.notice ?: "개발용 임시 평가 · 개선과 실측 검증이 필요해요.",fontSize=15.sp,color=Amber,modifier=Modifier.padding(top=16.dp))
            if(capture.videoSaved) TextButton(onClick=onVideo,modifier=Modifier.testTag("parking-open-video")) {Text("저장한 전체 주차 영상 보기")}
        }
        val p=frame;val b=bitmap
        if(p!=null && b!=null) {
            Spacer(Modifier.height(18.dp));Text("최종 정차 장면 · ${formatValue((capture.finalVideoMs ?: 0)/1000.0)}초",fontSize=21.sp,fontWeight=FontWeight.Bold)
            if(editable) {
                Text(when(points.size) {0->"먼저 실제 차체 외곽의 왼쪽 위 모서리를 누르세요.";1->"차체 외곽의 오른쪽 아래 모서리를 누르세요.";in 2..5->"주차칸 모서리 ${points.size-1}/4 · 선 안쪽의 네 모서리를 둘레 순서로 누르세요.";else->"차량과 주차칸을 확인한 뒤 분석하세요."},fontSize=18.sp,color=Mint,modifier=Modifier.padding(vertical=12.dp).testTag("parking-mark-instruction"))
            }
            Box(Modifier.fillMaxWidth().aspectRatio(p.width.toFloat()/p.height).background(Ink)
                .pointerInput(editable,busy,points) {detectTapGestures {o->if(editable && !busy && points.size<6) points=points+Point((o.x/size.width*p.width).toDouble(),(o.y/size.height*p.height).toDouble())}}
                .testTag("parking-final-frame")) {
                Image(b.asImageBitmap(),"주차 최종 장면",Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val sx=size.width/p.width;val sy=size.height/p.height
                    val shown=if(points.isNotEmpty()) points else outcome?.placement?.let {listOf(it.vehicle[0],it.vehicle[2])+it.slot} ?: emptyList()
                    if(shown.size>=2) {
                        val a=shown[0];val z=shown[1]
                        drawRect(Amber,Offset(min(a.x,z.x).toFloat()*sx,min(a.y,z.y).toFloat()*sy),Size(abs(z.x-a.x).toFloat()*sx,abs(z.y-a.y).toFloat()*sy),style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    }
                    val slot=shown.drop(2)
                    slot.zipWithNext().forEach {(a,z)->drawLine(Mint,Offset(a.x.toFloat()*sx,a.y.toFloat()*sy),Offset(z.x.toFloat()*sx,z.y.toFloat()*sy),3.dp.toPx())}
                    if(slot.size==4) drawLine(Mint,Offset(slot.last().x.toFloat()*sx,slot.last().y.toFloat()*sy),Offset(slot.first().x.toFloat()*sx,slot.first().y.toFloat()*sy),3.dp.toPx())
                    shown.forEachIndexed {i,a->drawCircle(if(i<2) Amber else Mint,6.dp.toPx(),Offset(a.x.toFloat()*sx,a.y.toFloat()*sy))}
                }
            }
            if(editable) {
                Row {Checkbox(checked=confirmed,onCheckedChange={confirmed=it},modifier=Modifier.testTag("parking-calibration-confirm"));Text("차량이 위아래 방향인 왜곡 보정 탑뷰이며 지정한 영역이 실제 차체 외곽과 맞는지 확인했어요.",fontSize=16.sp,lineHeight=24.sp,modifier=Modifier.padding(top=10.dp))}
                fun calibration():Calibration {val a=points[0];val z=points[1];return Calibration(true,true,true,min(a.x,z.x),max(a.x,z.x),min(a.y,z.y),max(a.y,z.y),referenceWidth=p.width,referenceHeight=p.height)}
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick={points=emptyList();confirmed=false},enabled=!busy) {Text("다시 지정")}
                    Button(onClick={onAnalyze(calibration(),points.drop(2))},enabled=points.size==6 && confirmed && !busy,modifier=Modifier.testTag("parking-final-score")) {Text(if(busy) "분석 중…" else "최종 배치 분석 · 점수 공개")}
                }
                if(points.size>=2) TextButton(onClick={onAnalyze(calibration(),null)},enabled=confirmed && !busy,modifier=Modifier.testTag("parking-detect-slot")) {Text("주차칸 네 경계 자동 검출로 분석")}
                Text("보정 정보와 점수는 이 기록에만 적용돼요. 이미 공개한 점수는 다시 분석해 바꾸지 않아요.",fontSize=15.sp,color=Muted)
            }
        }
        error?.let {Text(it,fontSize=18.sp,color=Amber,modifier=Modifier.padding(vertical=16.dp))}
    }
}
