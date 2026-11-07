package com.easycar.core

/** A short measured reason for the live card; the detailed explanation stays in the record. */
object FeedbackSummary {
    fun reason(result:ManeuverResult):String? {
        if(result.score==null) {
            val h=result.holdReasons
            return when {
                isParking(result.type) && h.any {it.contains("공간")}->"주차 공간 미확인"
                h.any {it.contains("핸들") || it.contains("조향")}->"조향 정보 부족"
                h.any {it.contains("yaw")}->"회전 신호 부족"
                h.any {it.contains("진행 방향")}->"방향 정보 부족"
                h.any {it.contains("영상") || it.contains("정렬")}->"정렬 정보 부족"
                h.any {it.contains("감속 원인")}->"제동 상황 미확인"
                h.any {it.contains("신호") || it.contains("측정값")}->"신호 부족"
                else->"필수 정보 부족"
            }
        }
        if(result.score==100) return null
        // A qualifying deceleration remains the measured cause even if shared jerk won deduplication.
        if(result.type==ManeuverType.STOP && result.assessments?.any {it.key=="deceleration_if_cause_known" && it.passed==false}==true) return "급감속"
        val d=result.deductions.maxByOrNull {it.points} ?: return "조작 변화"
        return when(d.key) {
            "acceleration","deceleration"->if(d.key=="deceleration" || d.title.contains("감속") || result.type==ManeuverType.STOP) "급감속" else "급가속"
            "rapid_turn"->"고속 회전"
            "lateral_acceleration"->"횡가속 큼"
            "parking_time"->"주차 시간 초과"
            "jerk"->"가감속 변화 큼"
            "speed"->"속도 높음"
            "steering","steering_exit"->"조향 변화 큼"
            "gear_repetition"->"전후진 반복"
            "alignment"->"정렬 오차"
            "intrusion"->"주차선 침범"
            "entry"->"진입 자세"
            else->d.title
        }
    }
}
