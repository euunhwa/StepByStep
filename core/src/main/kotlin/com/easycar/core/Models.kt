package com.easycar.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlin.math.abs

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
enum class Quality { VALID, ESTIMATED_TIME, LOW_CONFIDENCE, STALE, MISSING, DISCONNECTED, DENIED, UNSUPPORTED }
enum class SignalTiming { MEASUREMENT, CURRENT_STATE_READ, RECEIPT_ESTIMATE }
enum class ManeuverType(val title: String) {
    LEFT("좌회전"), RIGHT("우회전"), U_TURN("유턴"), START("출발"), STOP("정지"),
    ACCELERATION("주행 중 급가속"), DECELERATION("주행 중 급감속"),
    PARKING("주차"), T_PARK("T자 주차"), PARALLEL_PARK("평행주차"), PARKING_UNKNOWN("주차 유형 미확인"), UNKNOWN("동작 미확인")
}
enum class Lifecycle { CANDIDATE, ACTIVE, COMPLETED, INTERRUPTED, HELD, CANCELLED }
data class Signal<T>(val value: T, val measuredMs: Long, val receivedMs: Long,
                     val unit: String, val source: String, val quality: Quality = Quality.VALID,
                     val timing:SignalTiming = SignalTiming.MEASUREMENT) {
    fun usable(nowMs: Long, maxAgeMs: Long) = quality in setOf(Quality.VALID,Quality.ESTIMATED_TIME) &&
        measuredMs <= nowMs && receivedMs >= measuredMs && receivedMs <= nowMs &&
        if(timing in setOf(SignalTiming.CURRENT_STATE_READ,SignalTiming.RECEIPT_ESTIMATE)) nowMs-receivedMs<=maxAgeMs
        else nowMs - measuredMs <= maxAgeMs && receivedMs - measuredMs <= maxAgeMs
}
data class VehicleFrame(val timeMs: Long, val speed: Signal<Double>?, val steering: Signal<Double>?,
                        val gear: Signal<Gear>?, val heading: Signal<Double>?, val connected: Boolean = true, val parkingScene:Signal<ParkingScene>?=null,
                        val yawRate:Signal<Double>?=null,val lateralAcceleration:Signal<Double>?=null,val ignitionOn:Signal<Boolean>?=null)
data class Motion(val timeMs: Long, val speedMps: Double?, val steeringDeg: Double?, val gear: Gear?,
                  val headingDeg: Double?, val acceleration: Double?, val jerk: Double?,
                  val steeringRate: Double?, val gap: Boolean, val filterDelayMs: Long = 200, val steeringTimingReliable:Boolean=true,val parkingScene:ParkingScene?=null,val parkingSceneTimeMs:Long?=null,
                  val yawRadps:Double?=null,val lateralMps2:Double?=null,val ignitionOn:Boolean?=null)
data class PracticeContext(val courseId: String?, val vehicleId: String?, val controlled: Boolean = false,
                           val parkingType: ManeuverType? = null, val selectionSource: String = "선택 안 함",
                           val entryReferenceHeading: Double? = null, val dataSource:String="차량 관측 데이터",val focusType:ManeuverType?=null)
data class Band(val maxAbs: Double, val weight: Double, val cap: Int)
data class ScoringConfig(
    val speed: Band, val parkingSpeed: Band, val acceleration: Band, val jerk: Band, val steeringRate: Band,
    val entryAngle: Band, val alignment: Band, val intrusionPenalty: Int,
    val parkingCountScale: Double, val parkingCountCap: Int, val maxMovementPenalty: Int,
    val minExceedanceMs: Long, val maxTotalPenalty: Int
) {
    fun countPenalty(reverseToDriveCount: Int) =
        (parkingCountScale * reverseToDriveCount.toDouble() * (reverseToDriveCount + 1)).toInt().coerceIn(0, parkingCountCap)
}
data class ReferenceProfile(val schemaVersion: Int, val version: String, val validationStatus: String,
    val source: String, val vehicleId: String, val courseId: String, val conditions: String,
    val maneuverTypes: List<String>, val phases: Map<String, String>, val scoring: ScoringConfig, val phaseBands:Map<String,Band>?=null) {
    companion object {
        fun fromJson(json: String): ReferenceProfile {
            val root = JsonParser.parseString(json).asJsonObject
            val required = setOf("schemaVersion", "version", "validationStatus", "source", "vehicleId", "courseId", "conditions", "maneuverTypes", "phases", "scoring")
            require(root.keySet().containsAll(required) && (root.keySet()-required).all {it=="phaseBands"}) { "기준 프로필 필드가 누락되었거나 알 수 없는 필드가 있습니다" }
            val p = Gson().fromJson(json, ReferenceProfile::class.java)
            require(p.schemaVersion == 1 && p.version.isNotBlank() && p.source.isNotBlank())
            require(p.vehicleId.isNotBlank() && p.courseId.isNotBlank() && p.conditions.isNotBlank())
            require(p.validationStatus in setOf("SYNTHETIC_DEMO", "EXPERT_VALIDATED", "DRAFT_REQUIRES_EXPERT_VALIDATION", "ARBITRARY_SYNTHETIC_PROTOTYPE", "DOCUMENT_DERIVED_DRAFT"))
            require(p.maneuverTypes.isNotEmpty() && p.maneuverTypes.all { n -> ManeuverType.entries.any { it.name == n } })
            require(p.phases.keys.containsAll(listOf("entry", "turn", "exit", "parking")))
            val s = root.getAsJsonObject("scoring")
            require(s.keySet() == setOf("speed", "parkingSpeed", "acceleration", "jerk", "steeringRate", "entryAngle", "alignment", "intrusionPenalty", "parkingCountScale", "parkingCountCap", "maxMovementPenalty", "minExceedanceMs", "maxTotalPenalty"))
            listOf("speed", "parkingSpeed", "acceleration", "jerk", "steeringRate", "entryAngle", "alignment").forEach { k ->
                require(s.getAsJsonObject(k).keySet() == setOf("maxAbs", "weight", "cap"))
            }
            listOf(p.scoring.speed, p.scoring.parkingSpeed, p.scoring.acceleration, p.scoring.jerk, p.scoring.steeringRate, p.scoring.entryAngle, p.scoring.alignment).forEach {
                require(it.maxAbs.isFinite() && it.maxAbs > 0 && it.weight.isFinite() && it.weight >= 0 && it.cap in 0..100)
            }
            require(p.scoring.parkingCountScale.isFinite() && p.scoring.parkingCountScale > 0)
            require(p.scoring.parkingCountCap in 1..100 && p.scoring.maxMovementPenalty in 1..100)
            require(p.scoring.intrusionPenalty in 0..100 && p.scoring.minExceedanceMs in 100..5000)
            require(p.scoring.maxTotalPenalty in 1..100)
            p.phaseBands?.forEach {(key,band)->
                require(key in setOf("entrySpeed","rotationSpeed","exitSpeed","exitSteeringRate","deceleration"))
                require(band.maxAbs.isFinite() && band.maxAbs>0 && band.weight.isFinite() && band.weight>=0 && band.cap in 0..100)
            }
            return p
        }
    }
}
data class DetectionConfig(val maxSignalAgeMs: Long = 750, val maxGapMs: Long = 1000,
    val gearStableMs: Long = 500, val stoppedMps: Double = .15, val movingMps: Double = .7,
    val standstillMs: Long = 1200, val steeringStartDeg: Double = 45.0, val steeringCenteredDeg: Double = 18.0,
    val minTurnMs: Long = 1800, val stableExitMs: Long = 1000, val turnMinHeading: Double = 55.0,
    val turnMaxHeading: Double = 120.0, val uTurnMinHeading: Double = 150.0, val uTurnMaxHeading: Double = 210.0,
    val maxParkingMps: Double = 2.2, val startWindowMs: Long = 4000,
    val preRollMs:Long=3000,val entryObservationMs:Long=1000,val startMinimumMs:Long=1000,
    val stopCandidateDeceleration:Double=.3,val stopArmMps:Double=2.0,val stopTimeoutMs:Long=20000,
    val turnTimeoutMs:Long=18000,val exitHeadingRateDegPerSec:Double=5.0,
    val requiredHeadingCoverage:Double=.95,val parkingExitMultiplier:Double=2.0,
    val parkingAxisToleranceDeg:Double=15.0,val parkingTimeoutMs:Long=300000,val parkingRearmMs:Long=1000,val parkingSceneMaxAgeMs:Long=1500)
data class Deduction(val key: String, val title: String, val points: Int, val measured: Double,
    val unit: String, val allowed: Double, val evidenceMs: Long, val durationMs: Long, val phase: String)
data class MetricAssessment(val key:String,val title:String,val measured:Double?,val unit:String,val allowed:Double?,val phase:String,val passed:Boolean?,val reason:String?=null,val referenceValue:Double?=null,val referenceSource:String?=null,val deductionPoints:Int?=null)
data class ManeuverResult(val id: String, val type: ManeuverType, val startMs: Long, val endMs: Long,
    val lifecycle: Lifecycle, val confidence: Double, val score: Int?, val holdReasons: List<String>,
    val deductions: List<Deduction>, val reverseToDriveCount: Int = 0, val profileVersion: String,
    val source: String, val courseId: String?, val vehicleId: String?, val measurements: Map<String, Double>,
    val phaseTimes: Map<String, Long>, val coaching: String, val observation: String? = null,val assessments:List<MetricAssessment>?=emptyList(),val recognition:RecognitionEvidence?=null, val evaluationPolicyVersion:String?=null,val scoreKind:String?=null,val scoreBasis:String?=null)
data class Point(val x: Double, val y: Double)
data class DetectedLine(val top: Point, val bottom: Point, val support: Int)
data class AlignmentEvidence(val angleDeg: Double?, val intrusion: Boolean?, val leftClearanceM: Double?,
    val rightClearanceM: Double?, val quality: Double, val reason: String?, val lines: List<DetectedLine>,
    val frameTimeMs: Long, val source: String,
    val leftClearancePx:Double?=null,val rightClearancePx:Double?=null,
    val centerOffsetM:Double?=null,val centerOffsetPx:Double?=null,
    val algorithmVersion:String?=null,val sampledFrames:Int?=null,val acceptedFrames:Int?=null,
    val windowStartMs:Long?=null)
data class PixelFrame(val width: Int, val height: Int, val argb: IntArray, val timeMs: Long, val source: String)
data class Calibration(val topView: Boolean, val undistorted: Boolean, val vehicleAxisKnown: Boolean,
    val vehicleLeftPx: Double, val vehicleRightPx: Double, val vehicleTopPx: Double, val vehicleBottomPx: Double,
    val metersPerPixel: Double? = null,val referenceWidth:Int?=null,val referenceHeight:Int?=null,val targetCenterPx:Double?=null)
fun headingDelta(a: Double, b: Double): Double = ((b - a + 540) % 360) - 180
fun isParking(type: ManeuverType) = type == ManeuverType.PARKING || type == ManeuverType.T_PARK || type == ManeuverType.PARALLEL_PARK || type == ManeuverType.PARKING_UNKNOWN
fun scoreFrom(deductions: List<Deduction>, cap: Int = 100) = 100 - deductions.sumOf { it.points }.coerceAtMost(cap)
fun formatValue(v: Double): String = String.format(java.util.Locale.KOREA, "%.1f", v)

data class VideoSync(val videoStartMs:Long,val videoEndMs:Long,val signalStartMs:Long,val signalEndMs:Long) {
    fun videoTime(signalMs:Long):Long?=if(videoEndMs>videoStartMs && signalEndMs>signalStartMs && signalMs in signalStartMs..signalEndMs) videoStartMs+(signalMs-signalStartMs)*(videoEndMs-videoStartMs)/(signalEndMs-signalStartMs) else null
    fun signalTime(videoMs:Long):Long?=if(videoEndMs>videoStartMs && signalEndMs>signalStartMs && videoMs in videoStartMs..videoEndMs) signalStartMs+(videoMs-videoStartMs)*(signalEndMs-signalStartMs)/(videoEndMs-videoStartMs) else null
}

/** Spatial observations from an approved map/vision provider; never a user-selected maneuver label. */
data class ParkingScene(val targetId:String,val roadAxisDeg:Double?,val slotAxisDeg:Double?,
    val targetAssociated:Boolean,val geometryVerified:Boolean,val source:String)
data class RecognitionEvidence(val method:String,val source:String,val roadAxisDeg:Double?=null,
    val slotAxisDeg:Double?=null,val axisDifferenceDeg:Double?=null,val parkingEpisodeConfirmed:Boolean=false,val reason:String?=null)
fun axisDifference(a:Double,b:Double):Double = kotlin.math.abs(((a-b+90)%180+180)%180-90)
