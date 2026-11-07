package com.easycar.core

import com.google.gson.Gson
import kotlin.math.*

/** Versioned development policy, separate from legacy maneuver scores. */
data class ParkingOutcomePolicy(val version:String="parking-outcome-draft-1.0",val freeReverseToDrive:Int=1,
    val extraConversionPoints:Int=5,val conversionCap:Int=30,val angleGoodDeg:Double=3.0,val angleWarningDeg:Double=8.0,
    val lateralGoodFraction:Double=.10,val lateralWarningFraction:Double=.20,val longitudinalGoodFraction:Double=.15,
    val longitudinalWarningFraction:Double=.30,val cautionPoints:Int=5,val dangerPoints:Int=10,
    val intrusionPoints:Int=20,val intrusionScoreCap:Int=59,val boundaryUncertaintyPx:Double=2.0) {
    fun validate() {require(version.isNotBlank() && freeReverseToDrive>=0 && extraConversionPoints>0 && conversionCap in 0..100)
        require(angleGoodDeg>0 && angleWarningDeg>angleGoodDeg && angleWarningDeg<90)
        require(lateralGoodFraction>0 && lateralWarningFraction>lateralGoodFraction && longitudinalGoodFraction>0 && longitudinalWarningFraction>longitudinalGoodFraction)
        require(listOf(angleGoodDeg,angleWarningDeg,lateralGoodFraction,lateralWarningFraction,longitudinalGoodFraction,longitudinalWarningFraction,boundaryUncertaintyPx).all {it.isFinite()})
        require(cautionPoints>0 && dangerPoints>=cautionPoints && intrusionPoints>0 && intrusionScoreCap in 0..99 && boundaryUncertaintyPx>0)}
    companion object {fun fromJson(json:String)=Gson().fromJson(json,ParkingOutcomePolicy::class.java).also {it.validate()}}
}
data class ParkingEpisode(val startedMs:Long,val endedMs:Long?,val reverseToDriveCount:Int,val signalsComplete:Boolean,
    val moved:Boolean,val automaticCompletion:Boolean,val finalSignalMs:Long?,val issues:List<String>)
/** Explicit start keeps preparation before the first reverse. Never infers intent from R alone. */
class ParkingEpisodeTracker(private val startedMs:Long) {
    private val counter=GearTransitionCounter(500)
    private var last:Long?=null;private var complete=true;private var moved=false;private var parkSince:Long?=null
    private var stoppedSince:Long?=null;private var finalTime:Long?=null;private var end:Long?=null
    private var valid=0;private val issues=linkedSetOf<String>()
    fun accept(f:VehicleFrame):ParkingEpisode {
        if(end!=null || f.timeMs<startedMs || last?.let {f.timeMs<=it}==true) return snapshot()
        if(last?.let {f.timeMs-it>1000}==true) {complete=false;issues+="차량 신호 수신 간격이 1초를 넘었어요.";counter.update(null,f.timeMs-1)}
        if(last==null && f.timeMs-startedMs>500) {complete=false;issues+="녹화 시작 시점의 차량 신호가 늦게 도착했어요."}
        last=f.timeMs
        val speed=f.speed?.takeIf {it.unit=="m/s" && it.usable(f.timeMs,750)}?.value?.takeIf {it.isFinite()}
        val gear=f.gear?.takeIf {it.unit=="gear" && it.usable(f.timeMs,750)}?.value?.takeUnless {it==Gear.UNKNOWN}
        if(!f.connected || speed==null || gear==null) {
            complete=false;issues+="속도 또는 기어 신호가 누락됐어요."
            counter.update(null,f.timeMs);parkSince=null;stoppedSince=null;return snapshot()
        }
        valid++;counter.update(gear,f.timeMs)
        if(gear in setOf(Gear.DRIVE,Gear.REVERSE) && abs(speed)>.15) moved=true
        if(abs(speed)<=.15) {if(stoppedSince==null) stoppedSince=f.timeMs} else stoppedSince=null
        if(moved && gear==Gear.PARK && abs(speed)<=.15) {
            if(parkSince==null) {parkSince=f.timeMs;finalTime=if(stoppedSince?.let {f.timeMs-it>=500}==true) (f.timeMs-150).coerceAtLeast(startedMs) else null}
            if(f.timeMs-parkSince!!>=1000) end=f.timeMs
        } else {parkSince=null;finalTime=null}
        return snapshot()
    }
    fun snapshot()=ParkingEpisode(startedMs,end,counter.reverseToDriveCount,complete && valid>=10,moved,end!=null,finalTime,issues.toList())
}

data class ParkingPlacement(val slot:List<Point>,val vehicle:List<Point>,val angleDeg:Double,val lateralFraction:Double,
    val longitudinalFraction:Double,val minimumClearancePx:Double,val inside:Boolean?,val source:String)
data class ParkingOutcome(val policyVersion:String,val score:Int?,val status:String,val reverseToDriveCount:Int,
    val deductions:List<Deduction>,val holds:List<String>,val placement:ParkingPlacement?,val videoTimeMs:Long?,
    val notice:String="주차 조작·영상 기하의 개발용 임시 점수 · 배점 개선과 실측 검증이 필요합니다.")

object ParkingPlacementGeometry {
    private fun cross(a:Point,b:Point,c:Point)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private fun center(p:List<Point>)=Point(p.sumOf {it.x}/p.size,p.sumOf {it.y}/p.size)
    fun validQuad(p:List<Point>):Boolean {
        if(p.size!=4 || p.any {!it.x.isFinite() || !it.y.isFinite()}) return false
        val signs=p.indices.map {cross(p[it],p[(it+1)%4],p[(it+2)%4])}
        if(!signs.all {it>1} && !signs.all {it< -1}) return false
        val edges=p.indices.map {Point(p[(it+1)%4].x-p[it].x,p[(it+1)%4].y-p[it].y)}
        if(edges.any {hypot(it.x,it.y)<10}) return false
        // This policy accepts a rectified top view, not arbitrary perspective quadrilaterals.
        return edges.indices.all {i->val a=edges[i];val b=edges[(i+1)%4];abs(a.x*b.x+a.y*b.y)/(hypot(a.x,a.y)*hypot(b.x,b.y))<.15}
    }
    fun measure(slot:List<Point>,vehicle:List<Point>,source:String,uncertaintyPx:Double=2.0):ParkingPlacement? {
        if(!validQuad(slot) || !validQuad(vehicle)) return null
        fun axis(p:List<Point>):Pair<Point,Pair<Double,Double>> {
            val i=p.indices.maxBy {hypot(p[(it+1)%4].x-p[it].x,p[(it+1)%4].y-p[it].y)}
            val a=p[i];val b=p[(i+1)%4];val len=hypot(b.x-a.x,b.y-a.y)
            val short=hypot(p[(i+2)%4].x-b.x,p[(i+2)%4].y-b.y)
            return Point((b.x-a.x)/len,(b.y-a.y)/len) to (len to short)
        }
        val (s,dim)=axis(slot);val (v,_)=axis(vehicle);val sc=center(slot);val vc=center(vehicle)
        val angle=Math.toDegrees(acos(abs(s.x*v.x+s.y*v.y).coerceIn(0.0,1.0)))
        val dx=vc.x-sc.x;val dy=vc.y-sc.y
        val orientation=if(cross(slot[0],slot[1],slot[2])>0) 1 else -1
        val clearance=slot.indices.minOf {i->val a=slot[i];val b=slot[(i+1)%4];vehicle.minOf {orientation*cross(a,b,it)/hypot(b.x-a.x,b.y-a.y)}}
        val inside=when {clearance>uncertaintyPx->true;clearance< -uncertaintyPx->false;else->null}
        return ParkingPlacement(slot,vehicle,angle,abs(dx*(-s.y)+dy*s.x)/dim.second,abs(dx*s.x+dy*s.y)/dim.first,clearance,inside,source)
    }
}

class ParkingOutcomeScorer(private val policy:ParkingOutcomePolicy=ParkingOutcomePolicy()) {
    init {policy.validate()}
    fun score(episode:ParkingEpisode,placement:ParkingPlacement?,videoTimeMs:Long?,videoAvailable:Boolean,
        extraHolds:List<String> = emptyList()):ParkingOutcome {
        val holds=episode.issues.toMutableList();holds+=extraHolds
        if(!episode.signalsComplete) holds+="전후진 전환 횟수의 전체 관측을 확인하지 못했어요."
        if(!episode.moved) holds+="이동한 주차 구간을 확인하지 못했어요."
        if(!episode.automaticCompletion) holds+="P단·정차 유지로 주차 완료를 확인하지 못했어요."
        if(episode.finalSignalMs==null) holds+="P단 전의 안정된 정차 구간이 부족해요."
        if(!videoAvailable) holds+="저장된 주차 영상이 없어요."
        if(placement==null) holds+="보정된 탑뷰의 차량 외곽과 주차칸 전체 경계가 필요해요."
        if(placement?.inside==null && placement!=null) holds+="차체가 주차선 오차 경계에 있어 포함 여부가 불확실해요."
        val deductions=mutableListOf<Deduction>();val time=episode.finalSignalMs ?: episode.startedMs
        fun add(key:String,title:String,points:Int,measured:Double,unit:String,allowed:Double) {if(points>0) deductions+=Deduction(key,title,points,measured,unit,allowed,time,0,"parking-outcome")}
        if(episode.signalsComplete) add("parking_reversals","전후진 반복",((episode.reverseToDriveCount-policy.freeReverseToDrive).coerceAtLeast(0).toLong()*policy.extraConversionPoints).coerceAtMost(policy.conversionCap.toLong()).toInt(),episode.reverseToDriveCount.toDouble(),"회",policy.freeReverseToDrive.toDouble())
        if(holds.isNotEmpty()) return ParkingOutcome(policy.version,null,"HELD",episode.reverseToDriveCount,deductions,holds.distinct(),placement,videoTimeMs)
        val p=placement!!
        fun band(v:Double,good:Double,warn:Double)=if(v<=good) 0 else if(v<=warn) policy.cautionPoints else policy.dangerPoints
        add("parking_angle","주차칸과 기울어짐",band(p.angleDeg,policy.angleGoodDeg,policy.angleWarningDeg),p.angleDeg,"degree",policy.angleGoodDeg)
        add("parking_lateral","좌우 치우침",band(p.lateralFraction,policy.lateralGoodFraction,policy.lateralWarningFraction),p.lateralFraction,"주차칸 폭 비율",policy.lateralGoodFraction)
        add("parking_longitudinal","앞뒤 치우침",band(p.longitudinalFraction,policy.longitudinalGoodFraction,policy.longitudinalWarningFraction),p.longitudinalFraction,"주차칸 길이 비율",policy.longitudinalGoodFraction)
        if(p.inside==false) {
            add("parking_boundary","주차칸 경계 침범",policy.intrusionPoints,-p.minimumClearancePx,"px",0.0)
            val current=100-deductions.sumOf {it.points}.coerceAtMost(100)
            add("parking_intrusion_cap","경계 침범 결과의 점수 상한",(current-policy.intrusionScoreCap).coerceAtLeast(0),current.toDouble(),"점",policy.intrusionScoreCap.toDouble())
        }
        return ParkingOutcome(policy.version,100-deductions.sumOf {it.points}.coerceAtMost(100),if(p.inside==false) "OUTSIDE_SLOT" else "SCORED",episode.reverseToDriveCount,deductions,emptyList(),p,videoTimeMs)
    }
}

fun ParkingOutcome.asManeuver(episode:ParkingEpisode,source:String,id:String)=ManeuverResult(id,ManeuverType.PARKING,
    episode.startedMs,episode.endedMs ?: episode.startedMs,if(score==null) Lifecycle.HELD else Lifecycle.COMPLETED,.75,score,holds,deductions,
    reverseToDriveCount,policyVersion,source,null,null,buildMap {placement?.let {put("angleDeg",it.angleDeg);put("lateralFraction",it.lateralFraction);put("longitudinalFraction",it.longitudinalFraction);put("minimumClearancePx",it.minimumClearancePx)}},emptyMap(),
    if(score==null) "주차 점수 보류. ${holds.take(2).joinToString(" · ")}" else "주차 $score 점. ${deductions.joinToString(" · ") {"${it.title} ${it.points}점 감점"}.ifBlank {"관측된 감점 항목이 없어요."}}",
    notice,evaluationPolicyVersion=policyVersion,scoreKind="PROVISIONAL",scoreBasis=notice)
