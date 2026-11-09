package com.example.easycar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.min

private const val SLOW_KPH=5.0
/** Reverse speed above which the replay marks the path; same 7 km/h line as the parking speed design draft. */
private const val FAST_KPH=7.0
/** Length of the steering guide drawn ahead of the leading bumper. */
private const val GUIDE_M=2.0

/** Top-down replay of a parking manoeuvre rebuilt from speed, gear and steering. Display only; never scored. */
@Composable fun ParkingReplayCard(result:ManeuverResult,frames:List<VehicleFrame>,spec:VehicleSpec=VehicleSpec.GRANDEUR_GN7_PE) {
    val path by produceState<ReconstructedPath?>(null,result.id,frames.size) {
        value=withContext(Dispatchers.Default) {
            TrajectoryReconstructor(spec).reconstruct(frames.filter {it.timeMs in result.startMs..result.endMs}).anchoredToEnd()
        }
    }
    CardPanel(Modifier.fillMaxWidth().testTag("parking-replay")) {
        Text("주차 궤적 다시 보기",fontSize=24.sp,fontWeight=FontWeight.Bold)
        val p=path
        when {
            p==null->Text("궤적을 계산하고 있어요.",fontSize=18.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
            p.poses.size<2 || p.poses.none {it.headingSource!=null}->
                Text("속도·기어·핸들 신호가 부족해 궤적을 그릴 수 없어요.",fontSize=18.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
            else->ReplayBody(result,p,spec)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable private fun ReplayBody(result:ManeuverResult,path:ReconstructedPath,spec:VehicleSpec) {
    val parked=result.lifecycle!=Lifecycle.INTERRUPTED
    val recognized=when(result.type) {ManeuverType.T_PARK->ReferenceKind.T_REVERSE;ManeuverType.PARALLEL_PARK->ReferenceKind.PARALLEL_REVERSE;else->null}
    var choice by remember(result.id) {mutableStateOf(if(parked) recognized else null)}
    val side=remember(path) {
        val reverseStart=path.events.firstOrNull {it.kind==ReplayEventKind.REVERSE_START}?.let {path.poseAt(it.timeMs)} ?: path.poses.first()
        if(reverseStart.xM<0) -1 else 1
    }
    val reference=remember(choice,side) {choice?.let {ReferenceParkingPath.build(it,spec,side)}}
    // Playback time is read only by the canvas draw and the player row, so a tick does not recompose this body.
    val time=remember(result.id) {mutableLongStateOf(path.poses.first().timeMs)}
    var showModel by remember(result.id) {mutableStateOf(true)}
    var showGuide by remember(result.id) {mutableStateOf(true)}

    Spacer(Modifier.height(14.dp))
    // With no reference chosen the assumed slot still follows the recognised type (T-shaped otherwise).
    ReplayCanvas(path,{time.longValue},spec,parked,reference,choice ?: recognized ?: ReferenceKind.T_REVERSE,showModel,showGuide)
    Spacer(Modifier.height(12.dp))
    ReplayPlayer(path,time)
    Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("보기",fontSize=18.sp)
        FilterChip(showGuide,{showGuide=!showGuide},{Text("바퀴·예상 경로")},Modifier.testTag("parking-replay-guide"))
        if(reference!=null) FilterChip(showModel,{showModel=!showModel},{Text("모범 차")},Modifier.testTag("parking-replay-model"))
    }
    Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Legend(Mint,"${formatValue(SLOW_KPH)} km/h 이하");Legend(Amber,"${formatValue(FAST_KPH)} km/h 이하");Legend(ScoreRed,"${formatValue(FAST_KPH)} km/h 초과")
        Legend(Mint,"내 차");Legend(White.copy(alpha=.6f),"모범 경로·모범 차");Legend(Amber,"후진→전진 지점")
    }
    if(parked) Row(Modifier.padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("모범 경로 비교",fontSize=18.sp)
        FilterChip(choice==null,{choice=null},{Text("비교 안 함")})
        ReferenceKind.entries.forEach {k->FilterChip(choice==k,{choice=k},{Text(k.title)},Modifier.testTag("parking-reference-${k.name}"))}
    }
}

/** Play button, scrubber and the current time, gear and speed. The only composable that recomposes per tick. */
@Composable private fun ReplayPlayer(path:ReconstructedPath,time:MutableLongState) {
    val start=path.poses.first().timeMs;val end=path.poses.last().timeMs
    var playing by remember(path) {mutableStateOf(false)}
    LaunchedEffect(playing) {
        if(!playing) return@LaunchedEffect
        if(time.longValue>=end) time.longValue=start
        // Advance by the real time between display frames, at 2× speed, so motion follows the screen refresh.
        var last=withFrameNanos {it}
        while(time.longValue<end) {
            val frame=withFrameNanos {it}
            time.longValue=min(end,time.longValue+(frame-last)*2/1_000_000);last=frame
        }
        playing=false
    }
    val now=time.longValue
    Row(verticalAlignment=Alignment.CenterVertically) {
        Action(if(playing) "일시정지" else "재생 (2배속)",Modifier.testTag("parking-replay-play"),secondary=true) {playing=!playing}
        Spacer(Modifier.width(16.dp))
        Slider(value=(now-start).toFloat(),onValueChange={playing=false;time.longValue=start+it.toLong()},
            valueRange=0f..(end-start).coerceAtLeast(1).toFloat(),modifier=Modifier.weight(1f).testTag("parking-replay-slider"))
    }
    val pose=path.poseAt(now)
    Text("${formatValue((now-start)/1000.0)} / ${formatValue((end-start)/1000.0)}초 · 기어 ${userGear(pose?.gear)} · ${pose?.speedMps?.let {formatValue(it*3.6)} ?: "—"} km/h",
        fontSize=18.sp,modifier=Modifier.padding(top=4.dp))
}

@Composable private fun Legend(color:Color,label:String) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(color));Spacer(Modifier.width(6.dp));Text(label,fontSize=14.sp,color=Muted)
    }
}

private fun Path.moveTo(p:Offset)=moveTo(p.x,p.y)
private fun Path.lineTo(p:Offset)=lineTo(p.x,p.y)
private fun speedColor(kph:Double?)=when {kph==null->Muted;kph<=SLOW_KPH->Mint;kph<=FAST_KPH->Amber;else->ScoreRed}

@Composable private fun ReplayCanvas(path:ReconstructedPath,time:()->Long,spec:VehicleSpec,parked:Boolean,reference:ReferencePath?,slotKind:ReferenceKind,showModel:Boolean,showGuide:Boolean) {
    val slots=remember(reference,parked,slotKind) {
        if(!parked) emptyList() else reference?.let {listOf(it.slot)+it.neighbours} ?: listOf(ReferenceParkingPath.slot(slotKind,spec))
    }
    // Fit every car body the replay can show, plus the target slot; neighbouring slots may run off the edge.
    val bounds=remember(path,reference,slots) {
        var minX=Double.MAX_VALUE;var maxX=-Double.MAX_VALUE;var minY=Double.MAX_VALUE;var maxY=-Double.MAX_VALUE
        fun include(x:Double,y:Double) {minX=minOf(minX,x);maxX=maxOf(maxX,x);minY=minOf(minY,y);maxY=maxOf(maxY,y)}
        fun body(x:Double,y:Double,h:Double) {
            for(along in listOf(-spec.rearOverhangM,spec.frontBumperM)) for(across in listOf(-spec.widthM/2,spec.widthM/2)) bodyPoint(x,y,h,along,across).let {include(it.x,it.y)}
        }
        path.poses.forEach {body(it.xM,it.yM,it.headingRad)}
        reference?.poses?.forEach {body(it.xM,it.yM,it.headingRad)}
        slots.firstOrNull()?.let {include(it.minX,it.minY);include(it.maxX,it.maxY)}
        listOf(minX-1.0,maxX+1.0,minY-1.0,maxY+1.0)
    }
    Canvas(Modifier.fillMaxWidth().height(480.dp).clip(RoundedCornerShape(16.dp)).background(Ink).testTag("parking-replay-canvas")) {
        val now=time()
        val (minX,maxX,minY,maxY)=bounds
        val scale=min(size.width/(maxX-minX),size.height/(maxY-minY)).toFloat()
        val ox=(size.width-((maxX-minX)*scale).toFloat())/2;val oy=(size.height-((maxY-minY)*scale).toFloat())/2
        fun o(x:Double,y:Double)=Offset(ox+((x-minX)*scale).toFloat(),size.height-oy-((y-minY)*scale).toFloat())

        // 1 m grid for a sense of scale.
        var grid=floor(minX);while(grid<=maxX) {drawLine(PanelLight.copy(alpha=.45f),o(grid,minY),o(grid,maxY),1f);grid+=1.0}
        grid=floor(minY);while(grid<=maxY) {drawLine(PanelLight.copy(alpha=.45f),o(minX,grid),o(maxX,grid),1f);grid+=1.0}

        val dash=PathEffect.dashPathEffect(floatArrayOf(18f,12f))
        slots.forEachIndexed {i,r->
            val outline=Path().apply {moveTo(o(r.minX,r.minY));lineTo(o(r.maxX,r.minY));lineTo(o(r.maxX,r.maxY));lineTo(o(r.minX,r.maxY));close()}
            drawPath(outline,if(i==0) Mint.copy(alpha=.7f) else Muted.copy(alpha=.35f),style=Stroke(2.dp.toPx(),pathEffect=dash))
        }
        reference?.let {r->
            val line=Path().apply {moveTo(o(r.poses[0].xM,r.poses[0].yM));for(i in 1 until r.poses.size) lineTo(o(r.poses[i].xM,r.poses[i].yM))}
            drawPath(line,White.copy(alpha=.5f),style=Stroke(3.dp.toPx(),pathEffect=dash,cap=StrokeCap.Round))
            drawCar(spec,r.reverseStart.xM,r.reverseStart.yM,r.reverseStart.headingRad,::o,White.copy(alpha=.2f),fill=false)
            if(showModel) {
                // The model car moves along the reference in step with the replay so the two can be compared.
                val start=path.poses.first().timeMs;val span=(path.poses.last().timeMs-start).coerceAtLeast(1)
                val m=r.poses[(((now-start).toDouble()/span).coerceIn(0.0,1.0)*(r.poses.size-1)).toInt()]
                drawCar(spec,m.xM,m.yM,m.headingRad,::o,White.copy(alpha=.7f),fill=true,wheelRad=if(showGuide) atan(m.curvature*spec.wheelbaseM) else null)
            }
        }
        for(i in 1 until path.poses.size) {
            val a=path.poses[i-1];val b=path.poses[i]
            if(b.afterGap) continue
            val color=speedColor(b.speedMps?.times(3.6)).copy(alpha=if(b.timeMs<=now) 1f else .22f)
            drawLine(color,o(a.xM,a.yM),o(b.xM,b.yM),4.dp.toPx(),StrokeCap.Round)
        }
        path.events.forEach {e->
            path.poseAt(e.timeMs)?.let {p->drawCircle(if(e.kind==ReplayEventKind.REVERSE_TO_DRIVE) Amber else White,6.dp.toPx(),o(p.xM,p.yM))}
        }
        path.poseAt(now)?.let {p->
            val steering=path.steeringAt(now)
            val reverse=when(p.gear) {Gear.DRIVE->false;Gear.REVERSE->true;else->null}
            if(showGuide && steering!=null && reverse!=null) drawGuide(spec,p,spec.curvatureFor(steering),reverse,::o)
            drawCar(spec,p.xM,p.yM,p.headingRad,::o,Mint,fill=true,wheelRad=if(showGuide) steering?.let {spec.roadWheelRad(it)} else null)
        }
    }
}

/** Body outline around the rear-axle centre; the front edge is drawn brighter to show which way the car faces. */
private fun DrawScope.drawCar(spec:VehicleSpec,x:Double,y:Double,heading:Double,o:(Double,Double)->Offset,color:Color,fill:Boolean,wheelRad:Double?=null) {
    fun corner(along:Double,across:Double)=bodyPoint(x,y,heading,along,across).let {o(it.x,it.y)}
    val back=-spec.rearOverhangM;val front=spec.frontBumperM;val half=spec.widthM/2
    val body=Path().apply {moveTo(corner(back,half));lineTo(corner(front,half));lineTo(corner(front,-half));lineTo(corner(back,-half));close()}
    if(fill) drawPath(body,color.copy(alpha=.25f))
    drawPath(body,color,style=Stroke(2.dp.toPx()))
    drawLine(White.copy(alpha=if(fill) 1f else .5f),corner(front,half),corner(front,-half),4.dp.toPx(),StrokeCap.Round)
    if(wheelRad==null) return
    // Four wheels on the axles; the front pair turns by the road-wheel angle.
    val wheelHalfLength=.33;val wheelHalfWidth=.12;val track=spec.rearTrackM/2
    for((along,angle) in listOf(0.0 to 0.0,spec.wheelbaseM to wheelRad)) for(across in listOf(-track,track)) {
        val c=bodyPoint(x,y,heading,along,across)
        fun p(a:Double,b:Double)=bodyPoint(c.x,c.y,heading+angle,a,b).let {o(it.x,it.y)}
        val tyre=Path().apply {moveTo(p(-wheelHalfLength,-wheelHalfWidth));lineTo(p(wheelHalfLength,-wheelHalfWidth));lineTo(p(wheelHalfLength,wheelHalfWidth));lineTo(p(-wheelHalfLength,wheelHalfWidth));close()}
        drawPath(tyre,Ink);drawPath(tyre,color,style=Stroke(1.5.dp.toPx()))
    }
}

/**
 * Where the two leading bumper corners (front in D, rear in R) would go in the next [GUIDE_M] if the wheel stayed
 * where it is, like a parking-camera guide. The corners ride on the rear-axle arc, so the lines show their real
 * swept paths rather than an offset of the axle path.
 */
private fun DrawScope.drawGuide(spec:VehicleSpec,p:PathPose,curvature:Double,reverse:Boolean,o:(Double,Double)->Offset) {
    val arc=ReferenceParkingPath.drive(PlanPose(p.xM,p.yM,p.headingRad,reverse),listOf(ReferenceParkingPath.Segment(reverse,curvature,GUIDE_M)),.1)
    val along=if(reverse) -spec.rearOverhangM else spec.frontBumperM
    for(across in listOf(spec.widthM/2,-spec.widthM/2)) {
        val edge=arc.map {bodyPoint(it.xM,it.yM,it.headingRad,along,across).let {q->o(q.x,q.y)}}
        drawPath(Path().apply {moveTo(edge.first());edge.drop(1).forEach {lineTo(it)}},Amber.copy(alpha=.85f),style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))
    }
}
