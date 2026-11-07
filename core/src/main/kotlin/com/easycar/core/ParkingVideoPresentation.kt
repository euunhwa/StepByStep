package com.easycar.core

import kotlin.math.abs

/** A tap chooses a detected gap at that row, not an unrelated pair at the screen midpoint. */
fun parkingTargetAtTap(lines:List<DetectedLine>,width:Int,height:Int,xRatio:Double,yRatio:Double):Double? {
    if(width<=0 || height<=0 || !xRatio.isFinite() || !yRatio.isFinite() || xRatio !in 0.0..1.0 || yRatio !in 0.0..1.0) return null
    fun xAt(line:DetectedLine,y:Double):Double? {
        if(line.bottom.y<=line.top.y) return null
        return line.top.x+(line.bottom.x-line.top.x)*(y-line.top.y)/(line.bottom.y-line.top.y)
    }
    val row=yRatio*height;val x=xRatio*width
    val sorted=lines.mapNotNull {line->xAt(line,row)?.let {it to line}}.sortedBy {it.first}
    val pair=sorted.zipWithNext().firstOrNull {(l,r)->x>l.first+2 && x<r.first-2} ?: return null
    val left=xAt(pair.first.second,height/2.0) ?: return null
    val right=xAt(pair.second.second,height/2.0) ?: return null
    return ((left+right)/2/width).takeIf {it in 0.0..1.0}
}

fun parkingVideoAdvice(evidence:AlignmentEvidence?):String = when {
    evidence==null->"영상을 분석하면 관측 근거를 확인할 수 있어요."
    evidence.reason!=null->evidence.reason
    evidence.intrusion==true->"보정된 차량 영역이 주차선 안쪽 경계를 넘었어요. 최종 위치를 복기해 주세요."
    evidence.angleDeg==null->"주차선 방향을 확인할 정보가 부족해요."
    abs(evidence.angleDeg)>=3.0->"차량 기준축과 주차선에 기울기 차이가 있어요. 다음 연습에서 정렬을 확인해 보세요."
    evidence.centerOffsetPx?.let {abs(it)>4}==true->"차량이 주차칸 중앙에서 ${if(evidence.centerOffsetPx>0) "오른쪽" else "왼쪽"}으로 치우쳐 있어요. 좌우 여유를 비교해 보세요."
    else->"이 영상에서 주차선과 차량의 상대 위치를 확인했어요. 앞뒤 경계·장애물은 별도 확인이 필요해요."
}
