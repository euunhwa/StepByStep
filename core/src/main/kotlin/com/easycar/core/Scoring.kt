package com.easycar.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

interface ScoreCalculator {
    fun evaluate(type: ManeuverType, samples: List<Motion>, context: PracticeContext,
                 reverseToDriveCount: Int = 0, alignment: AlignmentEvidence? = null,
                 entryHeading: Double? = null, extraHolds: List<String> = emptyList(),recognition:RecognitionEvidence?=null): ManeuverResult
}
class DeterministicScorer(val profile: ReferenceProfile,private val policy:EvaluationPolicy?=null) : ScoreCalculator {
    override fun evaluate(type: ManeuverType, samples: List<Motion>, context: PracticeContext,
        reverseToDriveCount: Int, alignment: AlignmentEvidence?, entryHeading: Double?, extraHolds: List<String>,recognition:RecognitionEvidence?): ManeuverResult {
        if(profile.validationStatus=="DOCUMENT_DERIVED_DRAFT" || policy?.documentDataset!=null) {
            require(profile.validationStatus=="DOCUMENT_DERIVED_DRAFT")
            val d=requireNotNull(policy?.documentDataset) {"문서 기반 평가 쌍이 필요합니다"}
            return DocumentScorer(profile,context,d).evaluate(type,samples,reverseToDriveCount,holds=extraHolds)
        }
        if(policy!=null || profile.validationStatus in setOf("DRAFT_REQUIRES_EXPERT_VALIDATION","ARBITRARY_SYNTHETIC_PROTOTYPE")) return PolicyScorer(profile,policy ?: EvaluationPolicy.draft()).evaluate(type,samples,context,reverseToDriveCount,alignment,entryHeading,extraHolds,recognition)
        require(samples.isNotEmpty())
        val config = profile.scoring
        val start = samples.first().timeMs; val end = samples.last().timeMs
        val holds = extraHolds.toMutableList()
        val applicable=context.courseId==profile.courseId && context.vehicleId==profile.vehicleId
        val turning=type in listOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)
        val needsSteering=turning || isParking(type)
        if (!applicable) holds += "비교 가능한 차종·코스 조건 미확인"
        if (type.name !in profile.maneuverTypes) holds += "지원하지 않는 동작"
        if (samples.any { it.gap } || samples.count { it.speedMps == null || it.gear == null || (needsSteering && it.steeringDeg == null) } > samples.size / 20) holds += "필수 차량 신호 누락 또는 큰 공백"
        if (type in listOf(ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.U_TURN) && samples.any { it.headingDeg == null }) holds += "신뢰할 수 있는 진행 방향 부족"
        if (end - start < 1000) holds += "관측 구간이 너무 짧음"
        if(needsSteering && samples.any {!it.steeringTimingReliable}) holds+="핸들 측정 시각 미확인 · 조향 변화 평가는 보류"
        val deductions = mutableListOf<Deduction>()
        val assessments=mutableListOf<MetricAssessment>()
        val firstSteer=samples.indexOfFirst {abs(it.steeringDeg ?: 0.0)>=45}.takeIf {it>=0} ?: 0
        val lastSteer=samples.indexOfLast {abs(it.steeringDeg ?: 0.0)>=18}.takeIf {it>=0} ?: samples.lastIndex
        val phases=mapOf("진입" to samples.take((firstSteer+1).coerceAtMost(samples.size)),"회전" to samples.subList(firstSteer,lastSteer.coerceAtLeast(firstSteer)+1),"탈출" to samples.drop(lastSteer.coerceAtLeast(firstSteer)))
        fun exposure(key: String, title: String, band: Band, unit: String, phase: String,part:List<Motion> = samples, get: (Motion) -> Double?) {
            var run = 0L; var runIntegral = 0.0; var integral = 0.0; var peak = 0.0; var evidence = start; var duration = 0L
            fun flush() { if (run >= config.minExceedanceMs) { integral += runIntegral; duration += run }; run = 0; runIntegral = 0.0 }
            part.zipWithNext().forEach { (a, b) ->
                val dt = b.timeMs - a.timeMs
                val value = get(b)
                if (dt in 1..1000 && !b.gap && value != null && value.isFinite() && value > band.maxAbs) {
                    run += dt; runIntegral += (value - band.maxAbs) * dt / 1000.0
                    if (value > peak) { peak = value; evidence = b.timeMs }
                } else flush()
            }
            flush()
            val points = if(applicable) (band.weight * integral / max(5.0, (end - start) / 1000.0)).roundToInt().coerceIn(0, band.cap) else 0
            val measured=part.mapNotNull(get).maxOrNull()
            assessments+=MetricAssessment(key,title.removeSuffix(" 높음").removeSuffix(" 큼"),measured,unit,if(applicable) band.maxAbs else null,phase,if(applicable && measured!=null) points==0 else null,if(!applicable) "비교 조건 미확인" else if(measured==null) "측정값 부족" else null)
            if (points > 0) deductions += Deduction(key, title, points, peak, unit, band.maxAbs, evidence, duration, phase)
        }
        if (isParking(type) && (type!=ManeuverType.PARKING_UNKNOWN || recognition?.parkingEpisodeConfirmed==true)) {
            val points = config.countPenalty(reverseToDriveCount)
            if (points > 0) deductions += Deduction("gear_repetition", "전후진 반복", points, reverseToDriveCount.toDouble(), "회", 0.0, end, end - start, "주차")
        }
        val movementStart = deductions.size
        if (type !in listOf(ManeuverType.START, ManeuverType.STOP)) {
            if(turning && profile.phaseBands!=null) {
                listOf(Triple("진입","entrySpeed","진입 속도 높음"),Triple("회전","rotationSpeed","회전 중 속도 높음"),Triple("탈출","exitSpeed","탈출 속도 높음")).forEach {(phase,key,title)->
                    exposure("speed",title,profile.phaseBands[key] ?: config.speed,"m/s",phase,phases[phase] ?: samples) {it.speedMps?.let(::abs)}
                }
            } else exposure("speed",if(isParking(type)) "주차 이동 속도 높음" else "회전 구간 속도 높음",if(isParking(type)) config.parkingSpeed else config.speed,"m/s","이동") {it.speedMps?.let(::abs)}
        }
        if (type != ManeuverType.STOP || context.controlled) {
            exposure("acceleration", if (type == ManeuverType.STOP) "감속 변화 큼" else "가속 변화 큼", config.acceleration, "m/s²", "이동") {
                it.acceleration?.let { a -> if (type == ManeuverType.STOP) max(0.0, -a) else max(0.0, a) }
            }
            // Acceleration and jerk describe a shared cause. Keep the larger deduction only.
            val before = deductions.size
            exposure("jerk", "가속 변화가 급격함", config.jerk, "m/s³", "이동") { it.jerk?.let(::abs) }
            if (deductions.size > before) {
                val acceleration = deductions.indexOfFirst { it.key == "acceleration" }
                val jerk = deductions.lastIndex
                if (acceleration >= 0) deductions.removeAt(if (deductions[acceleration].points >= deductions[jerk].points) jerk else acceleration)
            }
        }
        if(turning && context.controlled && profile.phaseBands!=null) exposure("deceleration","회전 중 감속 변화 큼",profile.phaseBands["deceleration"] ?: config.acceleration,"m/s²","회전",phases["회전"] ?: samples) {it.acceleration?.let {a->max(0.0,-a)}}
        if (type != ManeuverType.START && type != ManeuverType.STOP) {
            exposure("steering", "핸들 조작이 급격함", config.steeringRate, "°/s", "회전") { it.steeringRate?.let(::abs) }
            if(turning && profile.phaseBands!=null) exposure("steering_exit","탈출 핸들 복원이 급격함",profile.phaseBands["exitSteeringRate"] ?: config.steeringRate,"°/s","탈출",phases["탈출"] ?: samples) {it.steeringRate?.let(::abs)}
        }
        var movementRemaining = config.maxMovementPenalty
        for (i in movementStart until deductions.size) {
            val d = deductions[i]; val pts = d.points.coerceAtMost(movementRemaining)
            deductions[i] = d.copy(points = pts); movementRemaining -= pts
        }
        if (type == ManeuverType.STOP && !context.controlled && samples.any { (it.acceleration ?: 0.0) < -config.acceleration.maxAbs }) holds += "급감속 원인 미확인 · 과실 판단 및 자동 감점 보류"
        if (isParking(type)) {
            val entryReference=recognition?.roadAxisDeg ?: context.entryReferenceHeading
            if (entryHeading == null || entryReference == null) holds += "주차 시작 전 진입 자세 관측 부족"
            else if(applicable) {
                val angle = abs(headingDelta(entryReference, entryHeading))
                val pts = ((angle - config.entryAngle.maxAbs).coerceAtLeast(0.0) * config.entryAngle.weight).roundToInt().coerceAtMost(config.entryAngle.cap)
                assessments+=MetricAssessment("entry","진입 자세",angle,"°",config.entryAngle.maxAbs,"진입",pts==0)
                if (pts > 0) deductions += Deduction("entry", "진입 자세 범위 이탈", pts, angle, "°", config.entryAngle.maxAbs, start, 0, "진입")
            }
            if (alignment == null || alignment.angleDeg == null || alignment.intrusion == null) holds += alignment?.reason ?: "최종 정렬 영상 없음"
            else if (alignment.frameTimeMs > end || end - alignment.frameTimeMs > 1500) holds += "최종 정차 프레임이 오래되었거나 동기화되지 않음"
            else if(applicable) {
                val a = abs(alignment.angleDeg)
                val pts = ((a - config.alignment.maxAbs).coerceAtLeast(0.0) * config.alignment.weight).roundToInt().coerceAtMost(config.alignment.cap)
                assessments+=MetricAssessment("alignment","최종 정렬",a,"°",config.alignment.maxAbs,"최종 정렬",pts==0)
                assessments+=MetricAssessment("intrusion","주차선 침범",if(alignment.intrusion) 1.0 else 0.0,"관측",0.0,"최종 정렬",!alignment.intrusion)
                if (pts > 0) deductions += Deduction("alignment", "최종 정렬 기울어짐", pts, a, "°", config.alignment.maxAbs, alignment.frameTimeMs, 0, "최종 정렬")
                if (alignment.intrusion) deductions += Deduction("intrusion", "주차선 침범 관측", config.intrusionPenalty, 1.0, "관측", 0.0, alignment.frameTimeMs, 0, "최종 정렬")
            }
        }
        // Multiple phases share a cause cap; phase breakdown must not multiply the speed/steering budget.
        var speedBudget=config.speed.cap;var steeringBudget=config.steeringRate.cap
        for(i in deductions.indices) {
            val d=deductions[i]
            if(d.key=="speed") {val p=d.points.coerceAtMost(speedBudget);deductions[i]=d.copy(points=p);speedBudget-=p}
            if(d.key.startsWith("steering")) {val p=d.points.coerceAtMost(steeringBudget);deductions[i]=d.copy(points=p);steeringBudget-=p}
        }
        var remaining = config.maxTotalPenalty
        val final = deductions.map { val pts = it.points.coerceAtMost(remaining); remaining -= pts; it.copy(points = pts) }.filter { it.points > 0 }
        val values = mapOf("maxSpeedMps" to (samples.mapNotNull { it.speedMps?.let(::abs) }.maxOrNull() ?: 0.0),
            "maxAcceleration" to (samples.mapNotNull { it.acceleration?.let(::abs) }.maxOrNull() ?: 0.0),
            "headingChangeDeg" to samples.mapNotNull { it.headingDeg }.zipWithNext().sumOf { (a,b) -> headingDelta(a,b) },
            "filterDelayMs" to 200.0) + (entryHeading?.let {mapOf("entryHeadingDeg" to it)} ?: emptyMap()) + (alignment?.angleDeg?.let { mapOf("alignmentDeg" to it) } ?: emptyMap())
        val realHolds = holds.distinct()
        val score = if (realHolds.isEmpty()) scoreFrom(final) else null
        val coverage=samples.count {it.speedMps!=null && it.gear!=null && (!needsSteering || it.steeringDeg!=null) && !it.gap}.toDouble()/samples.size
        val directionCoverage=if(type in listOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)) samples.count {it.headingDeg!=null}.toDouble()/samples.size else 1.0
        val quality=if(type==ManeuverType.UNKNOWN) 0.0 else coverage*directionCoverage*(if(isParking(type)) alignment?.quality ?: 0.0 else 1.0)
        return ManeuverResult("${type.name}-$start-$end", type, start, end, if (score == null) Lifecycle.HELD else Lifecycle.COMPLETED,
            quality.coerceIn(0.0,1.0), score, realHolds, final, reverseToDriveCount, profile.version,
            context.dataSource, context.courseId, context.vehicleId, values,
            mapOf("진입" to start, "이동" to (samples.firstOrNull { abs(it.steeringDeg ?: 0.0)>=45 || abs(it.speedMps ?: 0.0)>.7 }?.timeMs ?: start), "탈출" to (samples.lastOrNull { abs(it.steeringDeg ?: 0.0)>=18 }?.timeMs ?: end), "완료" to end),
            KoreanCoach.describe(type, final, realHolds),
            if (type == ManeuverType.STOP) "속도 신호에서 계산한 감속 관측값이며 실제 차체 충격 측정이 아닙니다." else null,assessments,recognition)
    }
}
interface CoachingText { fun text(result: ManeuverResult): String }
object KoreanCoach : CoachingText {
    override fun text(result: ManeuverResult) = result.coaching
    fun describe(type: ManeuverType, deductions: List<Deduction>, holds: List<String>,measurements:Map<String,Double> = emptyMap(),provisional:Boolean=false): String {
        if(type==ManeuverType.PARKING_UNKNOWN) return "저속 조작${measurements["observedRToDCount"]?.let {" 중 R→D ${it.toInt()}회"} ?: ""}을 기록했어요. 대상 주차 공간 정보가 없어 주차 종류와 점수는 보류했어요."
        if(type==ManeuverType.UNKNOWN) return "진행 방향·도로 맥락이 충분치 않아 회전 종류를 보류했어요. 주행 후 관측 정보를 확인해 주세요."
        val largest = deductions.maxByOrNull { it.points }
        if (largest == null) {
            val observation=measurements["maxSpeedMps"]?.let {" 관측 최고 속도는 ${formatValue(it*3.6)}km/h예요."} ?: ""
            if(holds.isNotEmpty()) return "${type.title} 구간을 기록했어요.$observation ${holdSummary(holds)}"
            return if(provisional) "이번 ${type.title}의 관측값은 임의 참조 범위 안이었어요.$observation" else "이번 ${type.title}은 관측한 항목이 허용 범위 안에 있었어요."
        }
        return when(largest.key) {
            "gear_repetition" -> "주차 중 전후진 전환이 ${largest.measured.toInt()}회 있었어요. 다음 연습에서는 진입 위치를 먼저 확인해 보세요."
            "speed" -> if(isParking(type)) "주차 중 이동 속도가 높았어요. 다음에는 저속으로 위치를 조정해 보세요." else "이번 ${type.title}은 ${largest.phase} 속도가 높았어요. 다음 연습에서 해당 구간의 속도를 조절해 보세요."
            "steering_exit" -> "탈출할 때 핸들 복원이 급격했어요. 다음에는 핸들을 부드럽게 복원해 보세요."
            "deceleration" -> "회전 중 감속 변화가 컸어요. 다음에는 진입 전에 속도를 조절해 보세요."
            "steering" -> "이번 ${type.title}에서는 핸들 변화가 급격했어요. 다음 연습에서 부드럽게 조작해 보세요."
            "alignment", "intrusion" -> "주차선과 차량 정렬을 복기해 주세요. 다음 연습에서는 최종 위치를 확인해 보세요."
            else -> if(type==ManeuverType.STOP) "이번 정지는 감속 변화가 컸어요. 다음 연습에서 속도를 부드럽게 줄여 보세요." else "이번 ${type.title}에서 가속 변화가 컸어요. 다음 연습에서 페달을 부드럽게 조작해 보세요."
        }
    }
    fun holdSummary(holds:List<String>):String = when {
        holds.any {it.contains("핸들 센서 측정 시각")}->"핸들 측정 시각이 확인되지 않아 조향 점수는 보류했어요."
        holds.any {it.contains("감속 원인")}->"급감속 원인을 확인하지 못해 관련 감점을 보류했어요."
        holds.any {(it.contains("주차") || it.contains("진입 자세")) && it.contains("공간")}->"공간 기준이 없어 주차 점수는 보류했어요."
        holds.any {it.contains("신호") || it.contains("측정값")}->"해당 구간의 필수 신호가 부족해 점수는 보류했어요."
        holds.any {it.contains("전문가") || it.contains("평가 기준 초안")}->"평가 기준이 전문가 검증 전이라 점수는 보류했어요."
        holds.any {it.contains("시각") || it.contains("영상") || it.contains("정렬")}->"최종 위치를 확인할 보정 영상이 없어 정렬 점수는 보류했어요."
        else->"${holds.firstOrNull() ?: "동작 종료 확인 필요"} 항목의 점수는 보류했어요."
    }
    fun summary(results: List<ManeuverResult>): List<String> {
        val good = results.firstOrNull { it.score != null && it.deductions.isEmpty() }
        val measuredGood=results.flatMap {it.assessments ?: emptyList()}.firstOrNull {it.passed==true && it.measured!=null && it.allowed!=null && it.measured<=it.allowed}
        val improve = results.flatMap { it.deductions }.maxByOrNull { it.points }
        return listOf(good?.let { "잘한 점 · ${it.type.title}의 관측 항목이 허용 범위 안에 있었어요." } ?: measuredGood?.let {"잘한 점 · ${it.phase}의 ${it.title} 관측값이 허용 범위 안에 있었어요."} ?: "잘한 점 · 확인할 평가 근거가 아직 부족해요.",
            improve?.let { "개선할 점 · ${it.title} 항목을 확인해 보세요." } ?: "개선할 점 · 확정된 감점이 없어요. 보류 항목을 먼저 확인해 주세요.",
            results.firstOrNull { it.deductions.isNotEmpty() }?.coaching?.let { "다음 연습 · $it" } ?: "다음 연습 · 같은 코스와 기준으로 한 번 더 연습해 보세요.")
    }
}
