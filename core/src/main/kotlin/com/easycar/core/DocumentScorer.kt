package com.easycar.core

import kotlin.math.*
import java.util.UUID

/** Provisional observed-control score only. Missing safety context never becomes a passed safety check. */
class DocumentScorer(private val profile:ReferenceProfile,private val context:PracticeContext,private val dataset:DocumentDataset) {
    private val e=dataset.evaluation;private val r=dataset.recognition
    fun evaluate(type:ManeuverType,samples:List<Motion>,conversions:Int=0,flags:Map<String,Double> = emptyMap(),holds:List<String> = emptyList(),interrupted:Boolean=false):ManeuverResult {
        require(samples.isNotEmpty())
        val missing=holds.toMutableList();val assessments=mutableListOf<MetricAssessment>();val deductions=mutableListOf<Deduction>()
        val first=samples.first().timeMs;val last=samples.last().timeMs
        val baseCoverage=samples.count {it.speedMps!=null && it.gear!=null && !it.gap}.toDouble()/samples.size
        if(baseCoverage<r.minimumCoverage) missing+="속도·기어 신호 충족률 부족"
        if(type in setOf(ManeuverType.UNKNOWN,ManeuverType.PARKING_UNKNOWN)) missing+="yaw·조향 패턴으로 동작 유형을 확정하지 못함"
        // Exclude only the first 300 ms after each gear transition, where differences are undefined.
        var gear:Gear?=null;var gearSince=first
        val derivatives=samples.filter {m->if(m.gear!=gear) {gear=m.gear;gearSince=m.timeMs};abs(m.speedMps ?: 0.0)>r.stoppedMps && m.gear in setOf(Gear.DRIVE,Gear.REVERSE) && m.timeMs-gearSince>=r.derivativeWarmupMs}
        if(derivatives.isEmpty() || derivatives.count {it.acceleration!=null && it.jerk!=null}.toDouble()/derivatives.size<r.minimumCoverage) missing+="가감속·jerk 측정 시각/신호 충족률 부족"
        fun sustained(bound:Double,inclusive:Boolean=false,value:(Motion)->Double?):Boolean {
            var duration=0L;var previous:Motion?=null
            for(m in derivatives) {
                val p=previous;val dt=if(p!=null) m.timeMs-p.timeMs else 0
                fun above(x:Motion)=value(x)?.let {if(inclusive) it>=bound else it>bound}==true
                duration=if(p!=null && dt in 1..r.maxSampleGapMs && above(p) && above(m)) duration+dt else 0
                if(duration>=e.minimumExceedanceMs) return true
                previous=m
            };return false
        }
        fun band(key:String,title:String,good:Double,danger:Double,unit:String,inclusive:Boolean=false,value:(Motion)->Double?) {
            val peak=derivatives.mapNotNull(value).maxOrNull()
            val points=when {peak==null->null;sustained(danger,inclusive,value)->e.dangerPoints;sustained(good,false,value)->e.cautionPoints;else->0}
            assessments+=MetricAssessment(key,title,peak,unit,good,"maneuver",points?.let {it==0},"연속 ${e.minimumExceedanceMs}ms 초과 · 문헌 기반 초안",referenceSource=dataset.evaluationVersion,deductionPoints=points)
            if(points!=null && points>0) deductions+=Deduction(key,title,points,peak!!,unit,good,last,e.minimumExceedanceMs,"maneuver")
        }
        band("acceleration","가속 강도",e.accelerationGood,e.accelerationDanger,"m/s²",true){it.acceleration?.coerceAtLeast(0.0)}
        band("deceleration","감속 강도",e.decelerationGood,e.decelerationDanger,"m/s²"){it.acceleration?.let {a->(-a).coerceAtLeast(0.0)}}
        // Forward acceleration and braking share a group: charge the worse observed direction once.
        val longitudinal=deductions.filter {it.key in setOf("acceleration","deceleration")}
        if(longitudinal.size>1) {deductions.removeAll(longitudinal.toSet());deductions+=longitudinal.maxBy {it.points}}
        band("jerk","가감속 변화",e.jerkGood,e.jerkDanger,"m/s³"){it.jerk?.let(::abs)}
        val turns=type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)
        val parking=isParking(type)
        if(turns || parking) {
            val coverage=samples.count {it.yawRadps!=null}.toDouble()/samples.size
            if(coverage<r.minimumCoverage) missing+="차량 좌표계·측정 시각이 검증된 yaw rate 신호 부족"
        }
        if(turns) {
            val lat=samples.mapNotNull {m->m.lateralMps2?.let(::abs) ?: if(m.yawRadps!=null && m.speedMps!=null) abs(m.speedMps*m.yawRadps) else null}.maxOrNull()?.div(e.gravityMps2)
            val points=lat?.let {when {it>e.lateralDangerG->e.dangerPoints;it>e.lateralGoodG->e.cautionPoints;else->0}}
            assessments+=MetricAssessment("lateral_acceleration","횡가속",lat,"g",e.lateralGoodG,"turn",points?.let {it==0},"전용 센서 우선, 없으면 시간 정렬된 v×yaw 운동학 추정 · 경사/미끄러짐 미보정",referenceSource=dataset.evaluationVersion,deductionPoints=points)
            if(lat==null) missing+="횡가속 측정·추정 신호 부족"
            val rapid=flags["rapidTurn"]==1.0 || flags["rapidUTurn"]==1.0
            val cornerPoints=max(points ?: 0,if(rapid) e.dangerPoints else 0)
            if(cornerPoints>0) deductions+=Deduction(if(rapid) "rapid_turn" else "lateral_acceleration",if(rapid) "짧은 시간 고속 회전" else "횡가속",cornerPoints,lat ?: 0.0,"g",e.lateralGoodG,last,last-first,"turn")
            if(type==ManeuverType.U_TURN) assessments+=MetricAssessment("u_turn_radius_speed","반경별 유턴 속도",null,"km/h",null,"turn",null,"회전 반경 R 미수신: √(0.2gR) 평가 보류; 6m를 가정하지 않음")
        }
        if(parking && type!=ManeuverType.PARKING_UNKNOWN) {
            val free=if(type==ManeuverType.T_PARK) e.parkingTFreeReverseToDrive else e.parkingParallelFreeReverseToDrive
            val points=((conversions-free).coerceAtLeast(0).toLong()*e.parkingExtraConversionPoints).coerceAtMost(100).toInt()
            assessments+=MetricAssessment("confirmed_R_to_D_count","전후진 반복",conversions.toDouble(),"회",free.toDouble(),"parking",points==0,"R→D만 세며 N 경유 포함, 안정 ${r.gearStableMs}ms",referenceSource=dataset.evaluationVersion,deductionPoints=points)
            if(points>0) deductions+=Deduction("gear_repetition","전후진 반복",points,conversions.toDouble(),"회",free.toDouble(),last,last-first,"parking")
            val blocks=ceil(((last-first-e.parkingTimeBlockMs).coerceAtLeast(0)).toDouble()/e.parkingTimeBlockMs).toLong()
            val timePoints=(blocks*e.parkingExtraTimePoints).coerceAtMost(100).toInt()
            assessments+=MetricAssessment("parking_time","주차 소요시간",(last-first)/1000.0,"s",e.parkingTimeBlockMs/1000.0,"parking",timePoints==0,"초과한 120초 블록마다 10점 · 앱 설계안, 면허시험 판정 아님",referenceSource=dataset.evaluationVersion,deductionPoints=timePoints)
            if(timePoints>0) deductions+=Deduction("parking_time","주차 소요시간",timePoints,(last-first)/1000.0,"s",e.parkingTimeBlockMs/1000.0,last,last-first,"parking")
            assessments+=MetricAssessment("parking_geometry","주차 완성도·연석 접촉",null,"—",null,"parking",null,"구획·차체 위치·연석 관측 미통합: 평가 보류, 유형은 운동 패턴 추정")
        }
        assessments+=MetricAssessment("safety_context","안전 맥락",null,"—",null,"maneuver",null,"방향지시등·신호·보행자·제한속도·위반 맥락 미수신: 10점 위반/실격 판정 보류")
        val measurements=flags+mapOf("maxSpeedMps" to (samples.mapNotNull {it.speedMps?.let(::abs)}.maxOrNull() ?: 0.0),"baseCoverage" to baseCoverage)
        val score=if(missing.isEmpty() && !interrupted) scoreFrom(deductions) else null
        val explanation=if(score==null) "${type.title} 평가를 보류했어요. ${missing.distinct().joinToString(" · ")}" else
            "${type.title} ${score}점. "+if(deductions.isEmpty()) "관측된 조작 지표가 초안 범위 안이에요." else deductions.joinToString(". ") {"${it.title} ${it.points}점 감점"}
        return ManeuverResult(UUID.randomUUID().toString(),type,first,last,if(interrupted) Lifecycle.INTERRUPTED else if(score==null) Lifecycle.HELD else Lifecycle.COMPLETED,
            if(missing.isEmpty()) .75 else baseCoverage.coerceAtMost(.5),score,missing.distinct(),deductions,conversions,profile.version,context.dataSource,context.courseId,context.vehicleId,measurements,emptyMap(),
            explanation,"문헌·추론에 기반한 관측 조작 점수. 제동 원인·운전자 과실·안전운전 적합성을 판단하지 않아요. "+dataset.notice,assessments,
            if(turns || parking) RecognitionEvidence("YAW_INTEGRAL_KINEMATIC_CANDIDATE",dataset.recognitionVersion,parkingEpisodeConfirmed=parking,reason="차량 동작 패턴 추정; 도로·주차 공간 확인 아님") else RecognitionEvidence("SPEED_EVENT",dataset.recognitionVersion),
            dataset.evaluationVersion,"PROVISIONAL",dataset.notice)
    }
}
