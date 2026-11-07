package com.easycar.core

import kotlin.math.*

/** Verified scores and user-authorized arbitrary prototype scores remain explicitly distinct. */
class PolicyScorer(private val profile:ReferenceProfile,private val policy:EvaluationPolicy):ScoreCalculator {
    override fun evaluate(type:ManeuverType,samples:List<Motion>,context:PracticeContext,reverseToDriveCount:Int,
        alignment:AlignmentEvidence?,entryHeading:Double?,extraHolds:List<String>,recognition:RecognitionEvidence?):ManeuverResult {
        require(samples.isNotEmpty() && reverseToDriveCount>=0)
        val start=samples.first().timeMs;val end=samples.last().timeMs
        val holds=extraHolds.toMutableList();val assessments=mutableListOf<MetricAssessment>();val deductions=mutableListOf<Deduction>()
        val conditionsMatch=context.courseId==profile.courseId && context.vehicleId==profile.vehicleId
        val approved=profile.validationStatus=="EXPERT_VALIDATED" && policy.realWorldScoringEnabled
        val provisional=profile.validationStatus=="ARBITRARY_SYNTHETIC_PROTOTYPE" && policy.provisionalEnabled && policy.referenceDatasetId!=null && policy.datasetNotice!=null
        val comparable=conditionsMatch || provisional
        val canCompare=approved || provisional
        val activeLimits=if(provisional) policy.provisionalLimits else policy.limits
        if(!canCompare) holds+="평가 기준 초안 · 전문가 허용 범위 검증 대기"
        if(!comparable) holds+="비교 가능한 차종·공간 조건 미확인"
        if(type in setOf(ManeuverType.UNKNOWN,ManeuverType.PARKING_UNKNOWN)) holds+="동작 유형을 확정할 근거 부족"
        if(end-start<1000 || samples.any {it.gap}) holds+="관측 구간 부족 또는 신호 공백"
        val validBase:(Motion)->Boolean={it.speedMps?.isFinite()==true && it.gear!=null && it.gear!=Gear.UNKNOWN && !it.gap}
        if(samples.count(validBase).toDouble()/samples.size<.95) holds+="필수 속도·실제 기어 신호 충족률 부족"
        val critical=samples.filter {it.timeMs<=start+500 || it.timeMs>=end-500}
        if(critical.any {!validBase(it)}) holds+="진입·종료 핵심 구간의 필수 신호 부족"
        if(type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN) &&
            (samples.count {it.headingDeg!=null}.toDouble()/samples.size<.95 || critical.any {it.headingDeg==null})) holds+="회전 진행 방향의 필수 관측 부족"
        val steeringOnset=samples.indexOfFirst {abs(it.steeringDeg ?: 0.0)>=45}.takeIf {it>=0} ?: 0
        val steeringEnd=samples.indexOfLast {abs(it.steeringDeg ?: 0.0)>=18}.coerceAtLeast(steeringOnset)
        // A sample belongs to one speed phase only, so exposure is never counted twice.
        val entry=samples.take(steeringOnset)
        val rotation=samples.subList(steeringOnset,(steeringEnd+1).coerceAtMost(samples.size))
        val exit=samples.drop((steeringEnd+1).coerceAtMost(samples.size))
        val groups=policy.budgets[type.name] ?: if(isParking(type)) policy.budgets.getValue("T_PARK") else emptyList()
        data class Candidate(val id:String,val deduction:Deduction)
        val candidates=mutableListOf<Candidate>()
        val appliedByMetric=mutableMapOf<String,Int>()
        fun wouldExceed(part:List<Motion>,id:String,get:(Motion)->Double?):Boolean {
            val limit=activeLimits[id] ?: return false
            var run=0L;var exposure=0.0
            for((a,b) in part.zipWithNext()) {
                val value=get(b);val dt=b.timeMs-a.timeMs
                if(dt in 1..1000 && !b.gap && value!=null && value>limit.bound) {
                    run+=dt;exposure+=(value-limit.bound)/limit.scale*dt/1000.0
                    if(run>=policy.minimumExceedanceMs && (100*exposure/limit.tauSeconds).roundToInt()>0) return true
                } else {run=0;exposure=0.0}
            }
            return false
        }
        val movement=samples.filter {it.gear in setOf(Gear.DRIVE,Gear.REVERSE)}
        var runGear:Gear?=null;var runStart=start
        val gearAge=samples.associate {m->if(m.gear!=runGear) {runGear=m.gear;runStart=m.timeMs};m.timeMs to (m.timeMs-runStart)}
        val brakingPart=if(type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)) rotation else movement
        val unknownBraking=!context.controlled && (
            wouldExceed(brakingPart,"deceleration_if_cause_known") {it.acceleration?.let {v->max(0.0,-v)}} ||
            (samples.any {(it.acceleration ?: 0.0)<0} && wouldExceed(samples,"longitudinal_jerk") {it.jerk?.let(::abs)}))
        val observedBrakingScore=provisional && policy.scoreUnconfirmedBrakingObservations
        fun metric(id:String,title:String,unit:String,phase:String,cap:Int,partInput:List<Motion>,get:(Motion)->Double?,
            staticValue:Double?=null,staticReason:String?=null,isStatic:Boolean=false) {
            // Only the known derivative warm-up nulls after a gear transition are outside the metric window.
            // Missing values later in a segment still fail coverage; observed values are never discarded.
            val derivative=id in setOf("acceleration","deceleration_if_cause_known","longitudinal_jerk","steering_rate","exit_steering_rate")
            val part=if(derivative) partInput.filter {get(it)!=null || (gearAge[it.timeMs] ?: 0)>=200} else partInput
            val values=part.mapNotNull(get).filter {it.isFinite()};val measured=if(isStatic) staticValue else values.maxOrNull()
            val limit=activeLimits[id]
            val coverage=if(part.isEmpty()) 0.0 else values.size.toDouble()/part.size
            val timingBad=id.contains("steering") && partInput.any {!it.steeringTimingReliable}
            val reason=when {
                timingBad->"핸들 센서 측정 시각 미확인"
                measured==null->staticReason ?: "측정값 부족"
                !isStatic && (coverage<.95 || part.any {it.gap})->"필수 신호 충족률 부족 또는 공백"
                unknownBraking && !observedBrakingScore && id in setOf("acceleration","deceleration_if_cause_known","longitudinal_jerk")->"감속 원인 미확인 · 연결된 jerk 감점도 보류"
                !comparable->"차종·공간 비교 조건 미확인"
                !canCompare->"전문가 기준 검증 대기"
                limit==null->"항목별 검증된 허용 범위 없음"
                else->null
            }
            var points=0;var peakAt=end;var duration=0L
            if(reason==null && limit!=null && measured!=null) {
                if(isStatic) points=if(id=="verified_vehicle_footprint_outside_slot") {if(measured==1.0) cap else 0} else (cap*((measured-limit.bound)/limit.scale).coerceIn(0.0,1.0)).roundToInt()
                else {
                    var run=0L;var runExposure=0.0;var exposure=0.0;var peak=0.0
                    fun flush() {if(run>=policy.minimumExceedanceMs) {exposure+=runExposure;duration+=run};run=0;runExposure=0.0}
                    part.zipWithNext().forEach {(a,b)->val dt=b.timeMs-a.timeMs;val value=get(b)
                        if(dt in 1..1000 && !b.gap && value!=null && value>limit.bound) {
                            run+=dt;runExposure+=(value-limit.bound)/limit.scale*dt/1000.0
                            if(value>peak) {peak=value;peakAt=b.timeMs}
                        } else flush()}
                    flush();points=(cap*(exposure/limit.tauSeconds).coerceIn(0.0,1.0)).roundToInt()
                }
            }
            if(reason!=null) holds+="$title · $reason"
            assessments+=MetricAssessment(id,title,measured,unit,if(reason==null) limit?.bound else null,phase,
                if(reason==null) points==0 else null,reason,policy.researchBounds[id],policy.researchBounds[id]?.let {"nuPlan 승차감 참고 · 채점 기준 아님"},if(reason==null) points else null)
            if(points>0 && measured!=null && limit!=null) {
                val key=when(id) {"entry_speed","rotation_speed","exit_speed","parking_speed"->"speed";"longitudinal_jerk"->"jerk";"steering_rate"->"steering";"exit_steering_rate"->"steering_exit";"axis_angle_error"->"alignment";"verified_vehicle_footprint_outside_slot"->"intrusion";"entry_pose_error_for_space"->"entry";else->"acceleration"}
                candidates+=Candidate(id,Deduction(key,title,points,measured,unit,limit.bound,peakAt,duration,phase))
            }
        }
        for(group in groups) {
            val before=candidates.size
            for(id in group.metrics) when(id) {
                "confirmed_R_to_D_count"->{
                    val gearObserved=samples.any {it.gear!=null && it.gear!=Gear.UNKNOWN}
                    val confirmed=gearObserved && (type!=ManeuverType.PARKING_UNKNOWN || recognition?.parkingEpisodeConfirmed==true)
                    val p=profile.scoring.countPenalty(reverseToDriveCount).coerceAtMost(group.cap)
                    assessments+=MetricAssessment(id,"R→D 전환",if(gearObserved) reverseToDriveCount.toDouble() else null,"회",if(confirmed) 0.0 else null,"주차",if(confirmed) p==0 else null,
                        if(confirmed) "기존 시제품 횟수 배점 · 안전 능력 판정 아님" else if(gearObserved) "주차 여부 미확정 · 횟수만 보존" else "실제 기어 신호 없음 · 횟수 미확인",deductionPoints=if(confirmed) p else null)
                    if(confirmed) appliedByMetric[id]=p
                    if(confirmed && p>0) deductions+=Deduction("gear_repetition","전후진 반복",p,reverseToDriveCount.toDouble(),"회",0.0,end,end-start,"주차")
                    if(!confirmed) holds+="주차 여부 미확정 · 횟수 감점 적용 보류"
                }
                "entry_speed"->metric(id,"진입 속도","m/s","진입",group.cap,entry,{it.speedMps?.let(::abs)})
                "rotation_speed"->metric(id,"회전 중 속도","m/s","회전",group.cap,rotation,{it.speedMps?.let(::abs)})
                "exit_speed"->metric(id,"탈출 속도","m/s","탈출",group.cap,exit,{it.speedMps?.let(::abs)})
                "parking_speed"->metric(id,"주차 이동 속도","m/s","이동",group.cap,samples,{it.speedMps?.let(::abs)})
                "acceleration"->metric(id,"가속 변화","m/s²","이동",group.cap,movement,{it.acceleration?.let {a->max(0.0,a)}})
                "deceleration_if_cause_known"->metric(id,"감속 변화","m/s²","감속",group.cap,brakingPart,{it.acceleration?.let {a->max(0.0,-a)}})
                "longitudinal_jerk"->metric(id,"가감속 변화의 급격함","m/s³","이동",group.cap,movement,{it.jerk?.let(::abs)})
                "steering_rate"->metric(id,"핸들 변화 속도","°/s","회전",group.cap,movement,{it.steeringRate?.let(::abs)})
                "exit_steering_rate"->metric(id,"탈출 핸들 복원","°/s","탈출",group.cap,exit,{it.steeringRate?.let(::abs)})
                "entry_pose_error_for_space"->{val ref=recognition?.roadAxisDeg
                    metric(id,"진입 자세","°","진입",group.cap,samples,{null},if(entryHeading!=null && ref!=null) axisDifference(entryHeading,ref) else null,"진입 전 자세·공간 기준 없음",true)}
                "axis_angle_error","verified_vehicle_footprint_outside_slot"->{
                    val fresh=alignment!=null && alignment.reason==null && samples.last().gear==Gear.PARK && abs(samples.last().speedMps ?: Double.POSITIVE_INFINITY)<=.15 && alignment.frameTimeMs<=end && end-alignment.frameTimeMs<=1500 && alignment.quality>=.45
                    val v=if(!fresh) null else if(id=="axis_angle_error") alignment?.angleDeg?.let(::abs) else alignment?.intrusion?.let {if(it) 1.0 else 0.0}
                    metric(id,if(id=="axis_angle_error") "최종 정렬" else "주차선 침범","°".takeIf {id=="axis_angle_error"} ?: "관측","최종 정렬",group.cap,samples,{null},v,alignment?.reason ?: "보정된 최종 정차 영상 없음",true)
                }
            }
            var own=candidates.drop(before)
            // Acceleration/deceleration and jerk share a cause; all steering rates share restoration data.
            val smooth=own.filter {it.id in setOf("acceleration","deceleration_if_cause_known","longitudinal_jerk")}
            if(smooth.size>1) {val best=smooth.maxBy {it.deduction.points};own=own.filter {it !in smooth || it==best}}
            if(group.key=="steering_smoothness" && own.isNotEmpty()) own=listOf(own.maxBy {it.deduction.points})
            var remaining=group.cap
            own.forEach {c->val p=c.deduction.points.coerceAtMost(remaining);if(p>0) {deductions+=c.deduction.copy(points=p);appliedByMetric[c.id]=p};remaining-=p}
        }
        if(type==ManeuverType.UNKNOWN) holds+="회전 맥락·진행 방향 부족"
        val completeHolds=holds.distinct()
        val appliedAssessments=assessments.map {it.copy(deductionPoints=if(it.deductionPoints==null) null else appliedByMetric[it.key] ?: 0)}
        val score=if(completeHolds.isEmpty()) scoreFrom(deductions) else null
        val measurements=mutableMapOf<String,Double>()
        samples.mapNotNull {it.speedMps?.let(::abs)}.maxOrNull()?.let {measurements["maxSpeedMps"]=it}
        samples.mapNotNull {it.acceleration?.let(::abs)}.maxOrNull()?.let {measurements["maxAcceleration"]=it}
        if(isParking(type) && samples.any {it.gear!=null}) measurements["observedRToDCount"]=reverseToDriveCount.toDouble()
        entryHeading?.let {measurements["entryHeadingDeg"]=it}
        alignment?.angleDeg?.let {measurements["alignmentDeg"]=it}
        val coverage=samples.count {it.speedMps!=null && it.gear!=null && !it.gap}.toDouble()/samples.size
        return ManeuverResult("${type.name}-$start-$end",type,start,end,if(score==null) Lifecycle.HELD else Lifecycle.COMPLETED,
            if(type in setOf(ManeuverType.UNKNOWN,ManeuverType.PARKING_UNKNOWN)) 0.0 else coverage,score,completeHolds,deductions,reverseToDriveCount,profile.version,
            context.dataSource,context.courseId,context.vehicleId,measurements,mapOf("진입" to start,"회전" to (rotation.firstOrNull()?.timeMs ?: start),"탈출" to (exit.firstOrNull()?.timeMs ?: end),"완료" to end),
            KoreanCoach.describe(type,deductions,completeHolds,measurements,provisional),if(unknownBraking && observedBrakingScore) "급감속 상황의 원인은 미확인입니다. 점수는 관측된 조작 부드러움의 임시 비교이며 운전자 과실·위험 회피의 적절성 판단이 아닙니다." else if(type==ManeuverType.STOP) "속도 변화의 관측이며 차체 충격·과실 판단이 아니에요." else null,appliedAssessments,recognition,policy.version,if(provisional) "PROVISIONAL" else if(approved) "VALIDATED" else null,if(provisional) policy.datasetNotice else null)
    }
}
