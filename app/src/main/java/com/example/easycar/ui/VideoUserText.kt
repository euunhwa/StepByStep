package com.example.easycar.ui

import com.easycar.core.AlignmentEvidence
import kotlin.math.abs

/** Presentation only. Raw observations and their original reasons remain in the saved record. */
internal fun videoPlacementTitle(evidence:AlignmentEvidence?):String = when {
    evidence==null -> "영상을 확인하고 있어요"
    evidence.reason!=null || evidence.angleDeg==null || evidence.intrusion==null -> "주차 위치 확인 보류"
    evidence.intrusion==true -> "차량이 주차선을 넘어 보여요"
    evidence.angleDeg?.let {abs(it)>=3.0}==true -> "차량이 기울어져 보여요"
    evidence.centerOffsetPx?.let {abs(it)>4}==true -> "차량이 한쪽으로 치우쳐 보여요"
    else -> "주차선과 차량 위치를 확인했어요"
}

internal fun videoPlacementAdvice(evidence:AlignmentEvidence?):String {
    if(evidence==null) return "주차한 장면을 선택하면 차량 위치를 확인할 수 있어요."
    val reason=evidence.reason
    if(reason!=null) return when {
        listOf("보정","기준축","차량축","영상 비율").any {reason.contains(it)} ->
            "영상에 맞는 분석 설정을 불러와 주세요."
        reason.contains("여러 개") -> "영상을 멈추고 확인할 주차칸을 눌러 주세요."
        reason.contains("선택한 위치") || reason.contains("대상 주차칸") ->
            "확인할 주차칸 안쪽을 다시 선택해 주세요."
        reason.contains("경계에 가까워") ->
            "차량이 주차선에 가까워 위치를 확실히 판단하기 어려워요."
        reason.contains("변하고") || reason.contains("마다") || reason.contains("간격") || reason.contains("시간이 부족") ->
            "차량이 멈춘 장면에서 다시 분석해 주세요."
        reason.contains("주차선") -> "주차선이 잘 보이는 장면을 선택해 주세요."
        else -> "이 장면에서는 주차 위치를 확인하기 어려워요. 다른 정차 장면을 선택해 주세요."
    }
    return when {
        evidence.intrusion==true -> "주차선 밖으로 나온 부분이 있는지 영상을 확인해 주세요."
        evidence.angleDeg==null || evidence.intrusion==null -> "주차 위치를 확실히 판단할 정보가 부족해요."
        evidence.angleDeg?.let {abs(it)>=3.0}==true -> "차량과 주차선이 나란한지 확인해 보세요."
        evidence.centerOffsetPx?.let {abs(it)>4}==true ->
            "차량이 주차칸 ${if((evidence.centerOffsetPx ?: 0.0)>0) "오른쪽" else "왼쪽"}으로 치우쳐 보여요. 양쪽 여유를 확인해 보세요."
        else -> "영상에서 보이는 주차선과 차량 위치를 확인했어요. 앞뒤 여유와 주변 장애물도 직접 확인해 주세요."
    }
}
