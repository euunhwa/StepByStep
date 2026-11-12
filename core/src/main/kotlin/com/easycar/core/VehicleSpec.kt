package com.easycar.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Body and steering geometry used only to draw and reconstruct a parking replay.
 * Published values carry a source; everything else is listed as an assumption.
 * This is not a measured vehicle calibration and is never used for recognition or scoring.
 */
data class VehicleSpec(
    val name:String,val lengthM:Double,val widthM:Double,val wheelbaseM:Double,val rearTrackM:Double,
    val rearOverhangM:Double,val minTurningRadiusM:Double,val steeringRatio:Double,
    val sources:List<String>,val assumptions:List<String>) {
    init {
        require(listOf(lengthM,widthM,wheelbaseM,rearTrackM,rearOverhangM,minTurningRadiusM,steeringRatio).all {it.isFinite() && it>0})
        require(wheelbaseM+rearOverhangM<lengthM && minTurningRadiusM>wheelbaseM)
        require(minRearAxleRadiusM>0)
    }
    val frontOverhangM get()=lengthM-wheelbaseM-rearOverhangM
    /** Distance from the rear axle forward to the body centre. */
    val rearAxleToCenterM get()=lengthM/2-rearOverhangM
    /** Distance from the rear axle forward to the front bumper. */
    val frontBumperM get()=lengthM-rearOverhangM
    /**
     * The published radius is read as the path of the outer front wheel. The bicycle model needs the
     * rear-axle centre: R_rear = sqrt(R² − L²) − rearTrack/2.
     */
    val minRearAxleRadiusM get()=sqrt(minTurningRadiusM*minTurningRadiusM-wheelbaseM*wheelbaseM)-rearTrackM/2
    /** Bicycle-model road-wheel lock angle that produces [minRearAxleRadiusM]. */
    val maxRoadWheelRad get()=atan(wheelbaseM/minRearAxleRadiusM)
    /** Bicycle-model road-wheel angle for a steering-wheel angle (deg, left positive), limited to the lock. */
    fun roadWheelRad(steeringDeg:Double)=(steeringDeg/steeringRatio*PI/180).coerceIn(-maxRoadWheelRad,maxRoadWheelRad)
    /** Path curvature (1/m, CCW positive) of the rear-axle centre for a steering-wheel angle: κ = tan δ / L. */
    fun curvatureFor(steeringDeg:Double)=tan(roadWheelRad(steeringDeg))/wheelbaseM
    /** Steering-wheel angle (deg) that drives a path of [curvature]; the inverse of [curvatureFor] within the lock. */
    fun steeringDegFor(curvature:Double)=atan(curvature*wheelbaseM)*180/PI*steeringRatio

    companion object {
        /** First Hyundai passenger car sold with Pleos Connect (Korea, 2026-05). */
        val GRANDEUR_GN7_PE=VehicleSpec("현대 더 뉴 그랜저(GN7 부분변경)",
            lengthM=5.050,widthM=1.880,wheelbaseM=2.895,rearTrackM=1.635,
            rearOverhangM=(5.050-2.895)/2,minTurningRadiusM=5.6,steeringRatio=15.0,
            sources=listOf(
                "전장 5,050·전폭 1,880·축거 2,895·후륜 윤거 1,635 mm(18인치): 나무위키 '현대 그랜저/7세대' 더 뉴 그랜저 제원표, 2026-11-10 확인",
                "최소회전반경 5.6 m: arabwheels.ae 'Hyundai Grandeur 2026 Specs'(중동 사양, 측정 기준 미기재)"),
            assumptions=listOf(
                "최소회전반경을 바깥 앞바퀴 궤적으로 보고 뒤차축 중심 반경 약 4.0 m, 바퀴 최대각 약 36°로 환산",
                "조향비 15: 공개 자료 없음. 에뮬레이터 핸들 범위 ±540°를 바퀴 최대각 36°로 나눈 값과 같음",
                "앞·뒤 오버행: 공개 자료 없음. 전장에서 축거를 뺀 길이를 반씩 나눔"))
    }
}
