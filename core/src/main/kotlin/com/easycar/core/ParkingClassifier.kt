package com.easycar.core

import kotlin.math.abs

data class ParkingDecision(val type:ManeuverType,val evidence:RecognitionEvidence)
class ParkingClassifier(private val config:DetectionConfig) {
    fun classify(scene:ParkingScene?,conflictingTargets:Boolean=false):ParkingDecision {
        fun held(reason:String)=ParkingDecision(ManeuverType.PARKING_UNKNOWN,RecognitionEvidence(
            "공간축·도로축과 실제 저속 조작",scene?.source ?: "공간 입력 없음",scene?.roadAxisDeg,scene?.slotAxisDeg,
            parkingEpisodeConfirmed=scene?.geometryVerified==true && scene.targetAssociated && !conflictingTargets,reason=reason))
        if(conflictingTargets) return held("관측 중 대상 주차 공간이 바뀌어 유형 확인 보류")
        if(scene==null || !scene.geometryVerified || !scene.targetAssociated) return held("주차 공간 근거 없음 · 단순 후진/저속 조작과 구분 보류")
        val road=scene.roadAxisDeg;val slot=scene.slotAxisDeg
        if(road==null || slot==null || !road.isFinite() || !slot.isFinite() || road !in 0.0..360.0 || slot !in 0.0..360.0) return held("도로축·주차칸축을 확인할 수 없음")
        val delta=axisDifference(road,slot)
        val type=when {delta<=config.parkingAxisToleranceDeg->ManeuverType.PARALLEL_PARK
            abs(90-delta)<=config.parkingAxisToleranceDeg->ManeuverType.T_PARK
            else->return held("사선 또는 모호한 주차 공간 · T자/평행 유형 보류")}
        return ParkingDecision(type,RecognitionEvidence("검증된 대상 공간의 도로축·주차칸축",scene.source,road,slot,delta,true))
    }
}
