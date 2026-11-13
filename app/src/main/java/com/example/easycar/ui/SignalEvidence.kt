package com.example.easycar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

@Composable fun FeedbackJournal(receipts:List<FeedbackReceipt>) {
    if(receipts.isEmpty()) return
    Spacer(Modifier.height(24.dp));Text("동작 직후 제공한 피드백",fontSize=25.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(14.dp))
    receipts.forEach {f->
        CardPanel(Modifier.fillMaxWidth()) {
            Row {Text(f.title,fontSize=23.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text(liveScoreCaption(f.score),fontSize=18.sp,color=reviewScoreColor(f.score))}
            Spacer(Modifier.height(10.dp));Text(feedbackReasonCaption(f),fontSize=20.sp,lineHeight=30.sp,color=GuideGreen)
            val voice=when(f.voiceState) {
                VoiceState.SPOKEN->"음성 안내 완료"
                VoiceState.SPEAKING->"음성 안내 중"
                VoiceState.MUTED->"음성 안내 꺼짐"
                VoiceState.WAITING,VoiceState.DEFERRED->"음성 안내 대기"
                VoiceState.FAILED->"음성 안내를 재생하지 못했어요"
                VoiceState.EXPIRED->"화면 안내를 확인해 주세요"
            }
            Spacer(Modifier.height(10.dp));Text(voice,fontSize=16.sp,lineHeight=24.sp,color=GuideMuted)
        };Spacer(Modifier.height(10.dp))
    }
}
@Composable fun AssessmentTable(rows:List<MetricAssessment>) {
    if(rows.isEmpty()) return
    CardPanel(Modifier.fillMaxWidth()) {
        Text("단계별 측정과 기준",fontSize=24.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(14.dp))
        rows.forEach {r->
            Row(Modifier.fillMaxWidth().padding(vertical=9.dp)) {
                Text("${r.phase} · ${r.title}",fontSize=18.sp,modifier=Modifier.weight(1f))
                Text(r.measured?.let {"${formatValue(it)} ${r.unit}"} ?: "측정 부족",fontSize=18.sp,modifier=Modifier.weight(.7f))
                Text(r.allowed?.let {"비교 ${formatValue(it)} ${r.unit}"} ?: "조건 미확인",fontSize=17.sp,color=Muted,modifier=Modifier.weight(.8f))
                Text(when(r.passed){true->if(r.measured!=null && r.allowed!=null && r.measured!!>r.allowed!!) "짧은 이탈·감점 없음" else "범위 안";false->"이탈 관측";null->"평가 보류"},fontSize=17.sp,color=if(r.passed==true) Mint else Amber,modifier=Modifier.weight(.65f))
            }
            r.reason?.let {Text(it,fontSize=16.sp,lineHeight=24.sp,color=if(r.passed==null) Amber else Muted,modifier=Modifier.padding(bottom=6.dp))}
            if(r.referenceValue!=null) Text("참고 ${formatValue(r.referenceValue!!)} ${r.unit} · ${r.referenceSource}",fontSize=15.sp,color=Muted,modifier=Modifier.padding(bottom=8.dp))
            r.deductionPoints?.let {Text("적용 감점 −${it}점",fontSize=15.sp,color=Muted)}
        }
    }
    Spacer(Modifier.height(16.dp))
}
@Composable fun SignalEvidenceCharts(result:ManeuverResult,frames:List<VehicleFrame>) {
    var motions by remember(result.id,frames.size) {mutableStateOf<List<Motion>>(emptyList())}
    LaunchedEffect(result.id,frames.size) {
        motions=withContext(Dispatchers.Default) {
            val pipeline=TelemetryPipeline(DetectionConfig())
            frames.mapNotNull {pipeline.accept(it)}.filter {it.timeMs in result.startMs..result.endMs}
        }
    }
    if(motions.size<2) {
        CardPanel(Modifier.fillMaxWidth()) {Text("구간 신호가 부족해 그래프를 표시할 수 없어요.",fontSize=19.sp,color=Muted)}
    } else {
        CardPanel(Modifier.fillMaxWidth()) {
            Text("이 동작의 실제 입력 신호",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("${formatValue(result.startMs/1000.0)}~${formatValue(result.endMs/1000.0)}초 · 출처 ${result.source}",fontSize=16.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
            val phases=result.phaseTimes.entries.sortedBy {it.value}.joinToString(" → ") {"${it.key} ${formatValue(it.value/1000.0)}초"}
            Text(phases,fontSize=16.sp,color=Muted,lineHeight=25.sp,modifier=Modifier.padding(top=8.dp))
            SignalPlot("속도","km/h",motions,result.phaseTimes.values.toList(),Mint) {it.speedMps?.let {v->abs(v)*3.6}}
            SignalPlot("가속 변화","m/s²",motions,result.phaseTimes.values.toList(),Amber) {it.acceleration}
            if(motions.any {it.steeringDeg!=null}) SignalPlot("핸들 각도","°",motions,result.phaseTimes.values.toList(),Color(0xffa6c7ff)) {it.steeringDeg}
            Text("필터·미분은 원래 입력 시각을 사용해요. 신호 공백은 선으로 연결하지 않아요.",fontSize=15.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
}
@Composable private fun SignalPlot(title:String,unit:String,samples:List<Motion>,phases:List<Long>,color:Color,value:(Motion)->Double?) {
    val values=samples.mapNotNull(value)
    if(values.isEmpty()) return
    val lower=minOf(0.0,values.min());val upper=max(lower+1.0,values.max())
    Spacer(Modifier.height(20.dp));Row {Text(title,fontSize=19.sp,modifier=Modifier.weight(1f));Text("${formatValue(lower)}~${formatValue(upper)} $unit",fontSize=16.sp,color=Muted)}
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical=10.dp)) {
        val begin=samples.first().timeMs;val span=(samples.last().timeMs-begin).coerceAtLeast(1)
        for(i in 0..2) {val y=size.height*i/2;drawLine(PanelLight,Offset(0f,y),Offset(size.width,y),1.dp.toPx())}
        fun x(t:Long)=((t-begin).toFloat()/span*size.width)
        fun y(v:Double)=size.height-((v-lower)/(upper-lower)*size.height).toFloat()
        phases.filter {it in begin..samples.last().timeMs}.forEach {drawLine(Muted.copy(alpha=.4f),Offset(x(it),0f),Offset(x(it),size.height),1.dp.toPx())}
        samples.zipWithNext().forEach {(a,b)->
            val av=value(a);val bv=value(b)
            if(av!=null && bv!=null && !b.gap && b.timeMs-a.timeMs<=1000) drawLine(color,Offset(x(a.timeMs),y(av)),Offset(x(b.timeMs),y(bv)),2.dp.toPx(),StrokeCap.Round)
        }
    }
}
