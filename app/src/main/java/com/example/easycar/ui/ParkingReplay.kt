package com.example.easycar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
private val ReplayMap=Color(0xFFE2EBDD)
private val ReplayGrid=Color(0xFFCCD9C8)
private val ReplayReference=Color(0xFF577D6C)
private val ReplayAmber=Color(0xFF92612D)
private val ReplayRed=Color(0xFFA13D37)
private enum class ReplayMark { DOT, DASH, RING }

/**
 * Top-down replay of a parking manoeuvre rebuilt from speed, gear and steering. Display only; never scored.
 * [frames] are the manoeuvre's own, from [ManeuverResult.startMs] to [ManeuverResult.endMs].
 */
@Composable fun ParkingReplayCard(result:ManeuverResult,frames:List<VehicleFrame>,spec:VehicleSpec=VehicleSpec.GRANDEUR_GN7_PE) {
    val path by produceState<ReconstructedPath?>(null,result.id,frames.size) {
        value=withContext(Dispatchers.Default) {TrajectoryReconstructor(spec).reconstruct(frames).anchoredToEnd()}
    }
    CardPanel(Modifier.fillMaxWidth().testTag("parking-replay")) {
        Text("주차 궤적 다시 보기",fontSize=24.sp,fontWeight=FontWeight.Bold,color=GuideInk)
        val p=path
        when {
            p==null->ReplayStatus(loading=true)
            p.poses.size<2 || p.poses.none {it.headingSource!=null}->ReplayStatus(loading=false)
            else->ReplayBody(result,p,spec)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable private fun ReplayStatus(loading:Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top=14.dp).background(ReplayMap,RoundedCornerShape(18.dp))
            .padding(horizontal=16.dp,vertical=32.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        Canvas(Modifier.size(54.dp).background(GuidePaper,RoundedCornerShape(50))) {
            val start=Offset(size.width*.31f,size.height*.64f)
            val finish=Offset(size.width*.69f,size.height*.36f)
            val route=Path().apply {
                moveTo(start)
                lineTo(Offset(size.width*.5f,start.y))
                lineTo(Offset(size.width*.5f,finish.y))
                lineTo(finish)
            }
            drawPath(route,GuideGreen,style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
            drawCircle(GuideGreen,3.dp.toPx(),start)
            drawCircle(GuideGreen,3.dp.toPx(),finish)
        }
        Text(if(loading) "궤적을 계산하고 있어요." else "궤적을 그릴 수 없어요.",
            fontSize=21.sp,fontWeight=FontWeight.Bold,color=GuideInk,textAlign=TextAlign.Center)
        if(!loading) Text("속도·기어·핸들 신호가 부족해요.",fontSize=17.sp,color=GuideMuted,
            textAlign=TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ReplayMap)) {
        val height=if(maxWidth<560.dp) 246.dp else 340.dp
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
            Text("센서 기반 추정",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=GuideMuted,
                modifier=Modifier.padding(start=4.dp,top=4.dp))
            // With no reference chosen the assumed slot still follows the recognised type (T-shaped otherwise).
            ReplayCanvas(path,{time.longValue},spec,parked,reference,choice ?: recognized ?: ReferenceKind.T_REVERSE,showModel,showGuide,height)
        }
    }
    Spacer(Modifier.height(12.dp))
    ReplayPlayer(path,time)
    FlowRow(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ReplayChip("바퀴·예상 경로",showGuide,{showGuide=!showGuide},Modifier.testTag("parking-replay-guide"))
        if(reference!=null) ReplayChip("모범 차",showModel,{showModel=!showModel},Modifier.testTag("parking-replay-model"))
    }
    FlowRow(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Legend(GuideGreen,"${formatValue(SLOW_KPH)} km/h 이하")
        Legend(ReplayAmber,"${formatValue(SLOW_KPH)} 초과 ~ ${formatValue(FAST_KPH)} km/h 이하")
        Legend(ReplayRed,"${formatValue(FAST_KPH)} km/h 초과")
        Legend(GuideForest,"내 차")
        if(reference!=null) Legend(ReplayReference,"모범 경로·모범 차",ReplayMark.DASH)
        Legend(ReplayAmber,"후진→전진 지점",ReplayMark.RING)
    }
    if(parked) {
        HorizontalDivider(Modifier.padding(vertical=12.dp),color=GuideLine)
        Text("모범 경로 비교",fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=GuideInk)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ReplayChip("비교 안 함",choice==null,{choice=null})
            ReferenceKind.entries.forEach { kind ->
                ReplayChip(kind.title,choice==kind,{choice=kind},Modifier.testTag("parking-reference-${kind.name}"))
            }
        }
    }
}

@Composable private fun ReplayChip(text:String,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    FilterChip(selected,onClick,{Text(text)},modifier,shape=RoundedCornerShape(12.dp),
        colors=FilterChipDefaults.filterChipColors(
            containerColor=GuideCanvas,labelColor=GuideInk,
            selectedContainerColor=GuideSelected,selectedLabelColor=GuideForest
        ))
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
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Button(onClick={playing=!playing},modifier=Modifier.heightIn(min=48.dp).testTag("parking-replay-play"),
            shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=GuideForest,contentColor=GuidePaper)) {
            Text(if(playing) "일시정지" else "재생 (2배속)")
        }
        Slider(value=(now-start).toFloat(),onValueChange={playing=false;time.longValue=start+it.toLong()},
            valueRange=0f..(end-start).coerceAtLeast(1).toFloat(),modifier=Modifier.weight(1f).testTag("parking-replay-slider"),
            colors=SliderDefaults.colors(thumbColor=GuideGreen,activeTrackColor=GuideGreen,inactiveTrackColor=GuideLine))
    }
    val pose=path.poseAt(now)
    Text("${formatValue((now-start)/1000.0)} / ${formatValue((end-start)/1000.0)}초 · 기어 ${userGear(pose?.gear)} · ${pose?.speedMps?.let {formatValue(it*3.6)} ?: "—"} km/h",
        fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=GuideInk,modifier=Modifier.padding(top=4.dp))
}

@Composable private fun Legend(color:Color,label:String,mark:ReplayMark=ReplayMark.DOT) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp)) {
            val width=2.dp.toPx()
            when(mark) {
                ReplayMark.DOT -> drawCircle(color,4.dp.toPx())
                ReplayMark.DASH -> {
                    drawLine(color,Offset(0f,center.y),Offset(size.width*.4f,center.y),width)
                    drawLine(color,Offset(size.width*.6f,center.y),Offset(size.width,center.y),width)
                }
                ReplayMark.RING -> drawCircle(color,4.dp.toPx(),style=Stroke(width))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label,fontSize=14.sp,color=GuideMuted)
    }
}

private fun Path.moveTo(p:Offset)=moveTo(p.x,p.y)
private fun Path.lineTo(p:Offset)=lineTo(p.x,p.y)
private fun speedColor(kph:Double?)=when {kph==null->GuideMuted;kph<=SLOW_KPH->GuideGreen;kph<=FAST_KPH->ReplayAmber;else->ReplayRed}

@Composable private fun ReplayCanvas(path:ReconstructedPath,time:()->Long,spec:VehicleSpec,parked:Boolean,reference:ReferencePath?,slotKind:ReferenceKind,showModel:Boolean,showGuide:Boolean,height:Dp) {
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
        SlotRect(minX-1.0,maxX+1.0,minY-1.0,maxY+1.0)
    }
    Spacer(Modifier.fillMaxWidth().height(height).testTag("parking-replay-canvas").drawWithCache {
        val (minX,maxX,minY,maxY)=bounds
        val scale=min(size.width/(maxX-minX),size.height/(maxY-minY)).toFloat()
        val ox=(size.width-((maxX-minX)*scale).toFloat())/2;val oy=(size.height-((maxY-minY)*scale).toFloat())/2
        fun o(x:Double,y:Double)=Offset(ox+((x-minX)*scale).toFloat(),size.height-oy-((y-minY)*scale).toFloat())

        // Built once per size and inputs; only the played part of the trail, the cars and the guide change with time.
        // 1 m grid for a sense of scale.
        val grid=Path().apply {
            var g=floor(minX);while(g<=maxX) {moveTo(o(g,minY));lineTo(o(g,maxY));g+=1.0}
            g=floor(minY);while(g<=maxY) {moveTo(o(minX,g));lineTo(o(maxX,g));g+=1.0}
        }
        val dash=PathEffect.dashPathEffect(floatArrayOf(18f,12f))
        val slotOutlines=slots.map {r->Path().apply {moveTo(o(r.minX,r.minY));lineTo(o(r.maxX,r.minY));lineTo(o(r.maxX,r.maxY));lineTo(o(r.minX,r.maxY));close()}}
        val referenceLine=reference?.let {r->Path().apply {moveTo(o(r.poses[0].xM,r.poses[0].yM));for(i in 1 until r.poses.size) lineTo(o(r.poses[i].xM,r.poses[i].yM))}}
        val points=path.poses.map {o(it.xM,it.yM)}
        val colors=path.poses.map {speedColor(it.speedMps?.times(3.6))}
        val markers=path.events.mapNotNull {e->path.poseAt(e.timeMs)?.let {p->(if(e.kind==ReplayEventKind.REVERSE_TO_DRIVE) ReplayAmber else GuideGreen) to o(p.xM,p.yM)}}
        val trailWidth=4.dp.toPx();val markerRadius=6.dp.toPx()
        onDrawBehind {
            val now=time()
            drawPath(grid,ReplayGrid,style=Stroke(1f))
            slotOutlines.forEachIndexed {i,outline->
                if(i==0) drawPath(outline,GuideRoute.copy(alpha=.26f))
                drawPath(outline,if(i==0) GuideGreen.copy(alpha=.72f) else ReplayReference.copy(alpha=.40f),style=Stroke(2.dp.toPx()))
            }
            reference?.let {r->
                drawPath(referenceLine!!,ReplayReference,style=Stroke(2.5.dp.toPx(),pathEffect=dash,cap=StrokeCap.Round))
                drawCar(spec,r.reverseStart.xM,r.reverseStart.yM,r.reverseStart.headingRad,::o,ReplayReference.copy(alpha=.38f),fill=false)
                if(showModel) {
                    // The model car moves along the reference in step with the replay so the two can be compared.
                    val start=path.poses.first().timeMs;val span=(path.poses.last().timeMs-start).coerceAtLeast(1)
                    val m=r.poses[(((now-start).toDouble()/span).coerceIn(0.0,1.0)*(r.poses.size-1)).toInt()]
                    drawCar(spec,m.xM,m.yM,m.headingRad,::o,ReplayReference.copy(alpha=.78f),fill=true,wheelRad=if(showGuide) atan(m.curvature*spec.wheelbaseM) else null)
                }
            }
            for(i in 1 until points.size) {
                val b=path.poses[i]
                if(b.afterGap) continue
                drawLine(colors[i],points[i-1],points[i],trailWidth,StrokeCap.Round,alpha=if(b.timeMs<=now) 1f else .22f)
            }
            markers.forEach {(color,at)->
                drawCircle(GuidePaper,markerRadius,at)
                drawCircle(color,markerRadius,at,style=Stroke(2.dp.toPx()))
            }
            path.poseAt(now)?.let {p->
                val steering=path.steeringAt(now)
                val reverse=when(p.gear) {Gear.DRIVE->false;Gear.REVERSE->true;else->null}
                if(showGuide && steering!=null && reverse!=null) drawGuide(spec,p,spec.curvatureFor(steering),reverse,::o)
                drawCar(spec,p.xM,p.yM,p.headingRad,::o,GuideForest,fill=true,wheelRad=if(showGuide) steering?.let {spec.roadWheelRad(it)} else null)
            }
        }
    })
}

/** Body outline around the rear-axle centre; the front edge is drawn brighter to show which way the car faces. */
private fun DrawScope.drawCar(spec:VehicleSpec,x:Double,y:Double,heading:Double,o:(Double,Double)->Offset,color:Color,fill:Boolean,wheelRad:Double?=null) {
    fun corner(along:Double,across:Double)=bodyPoint(x,y,heading,along,across).let {o(it.x,it.y)}
    val back=-spec.rearOverhangM;val front=spec.frontBumperM;val half=spec.widthM/2
    val body=Path().apply {moveTo(corner(back,half));lineTo(corner(front,half));lineTo(corner(front,-half));lineTo(corner(back,-half));close()}
    if(fill) drawPath(body,color.copy(alpha=.27f))
    drawPath(body,color,style=Stroke(2.dp.toPx()))
    drawLine(color.copy(alpha=if(fill) 1f else .5f),corner(front,half),corner(front,-half),4.dp.toPx(),StrokeCap.Round)
    if(wheelRad==null) return
    // Four wheels on the axles; the front pair turns by the road-wheel angle.
    val wheelHalfLength=.33;val wheelHalfWidth=.12;val track=spec.rearTrackM/2
    for((along,angle) in listOf(0.0 to 0.0,spec.wheelbaseM to wheelRad)) for(across in listOf(-track,track)) {
        val c=bodyPoint(x,y,heading,along,across)
        fun p(a:Double,b:Double)=bodyPoint(c.x,c.y,heading+angle,a,b).let {o(it.x,it.y)}
        val tyre=Path().apply {moveTo(p(-wheelHalfLength,-wheelHalfWidth));lineTo(p(wheelHalfLength,-wheelHalfWidth));lineTo(p(wheelHalfLength,wheelHalfWidth));lineTo(p(-wheelHalfLength,wheelHalfWidth));close()}
        drawPath(tyre,GuideInk);drawPath(tyre,color,style=Stroke(1.5.dp.toPx()))
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
        drawPath(Path().apply {moveTo(edge.first());edge.drop(1).forEach {lineTo(it)}},ReplayAmber.copy(alpha=.85f),style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))
    }
}
