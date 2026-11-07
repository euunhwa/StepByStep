package com.easycar.core

/** Read-only compatibility for retired 0.13 experimental records; no active capture or scoring. */
data class ParkingEpisode(val startedMs:Long,val endedMs:Long?,val reverseToDriveCount:Int,val signalsComplete:Boolean,
    val moved:Boolean,val automaticCompletion:Boolean,val finalSignalMs:Long?,val issues:List<String>)

data class ParkingPlacement(val slot:List<Point>,val vehicle:List<Point>,val angleDeg:Double,val lateralFraction:Double,
    val longitudinalFraction:Double,val minimumClearancePx:Double,val inside:Boolean?,val source:String)
data class ParkingOutcome(val policyVersion:String,val score:Int?,val status:String,val reverseToDriveCount:Int,
    val deductions:List<Deduction>,val holds:List<String>,val placement:ParkingPlacement?,val videoTimeMs:Long?,
    val notice:String="주차 조작·영상 기하의 개발용 임시 점수 · 배점 개선과 실측 검증이 필요합니다.")
