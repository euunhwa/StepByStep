package com.easycar.core

import kotlin.math.*

data class ParkingSlotObservation(val placement:ParkingPlacement?,val reason:String?,val sideEvidence:AlignmentEvidence?)
/** Extends the side-line detector with two perpendicular end boundaries. */
class ParkingSlotAnalyzer {
    fun analyze(frame:PixelFrame,calibration:Calibration?):ParkingSlotObservation {
        val side=ParkingGeometryAnalyzer(.25,"parking-slot-final-1.0").analyze(frame,calibration)
        val c=calibration?.forDimensions(frame.width,frame.height)
            ?: return ParkingSlotObservation(null,"탑뷰 보정과 실제 차량 외곽을 연결해 주세요.",side)
        if(side.angleDeg==null || side.lines.size!=2 || side.reason!=null) return ParkingSlotObservation(null,side.reason ?: "주차칸 측면 경계 확인 필요",side)
        val (left,right)=side.lines.sortedBy {it.top.x}
        fun slope(l:DetectedLine)=(l.bottom.x-l.top.x)/(l.bottom.y-l.top.y)
        fun intercept(l:DetectedLine)=l.top.x-slope(l)*l.top.y
        val s=(slope(left)+slope(right))/2;val n=hypot(1.0,s)
        val axis=Point(s/n,1/n);val normal=Point(1/n,-s/n)
        val y=(c.vehicleTopPx+c.vehicleBottomPx)/2
        val origin=Point((slope(left)*y+intercept(left)+slope(right)*y+intercept(right))/2,y)
        val half=(slope(right)*y+intercept(right)-slope(left)*y-intercept(left))/(2*n)
        fun paint(x:Int,y:Int):Boolean {
            if(x !in 3 until frame.width-3 || y !in 3 until frame.height-3) return false
            fun light(x:Int,y:Int):Double {val p=frame.argb[y*frame.width+x];return ((p shr 16 and 255)*.299+(p shr 8 and 255)*.587+(p and 255)*.114)}
            val p=frame.argb[y*frame.width+x];val r=p shr 16 and 255;val g=p shr 8 and 255;val b=p and 255
            val color=maxOf(r,g,b)-minOf(r,g,b)<=55 || min(r,g)-b>=25 && g>=r*.65 && r>=g*.75
            val dx=(axis.x*5).roundToInt();val dy=(axis.y*5).roundToInt()
            val x1=(x+dx).coerceIn(0,frame.width-1);val y1=(y+dy).coerceIn(0,frame.height-1)
            val x2=(x-dx).coerceIn(0,frame.width-1);val y2=(y-dy).coerceIn(0,frame.height-1)
            return color && light(x,y)>=70 && light(x,y)-(light(x1,y1)+light(x2,y2))/2>=20
        }
        val candidates=mutableListOf<Int>();val length=hypot(frame.width.toDouble(),frame.height.toDouble()).toInt()
        for(t in -length/2..length/2) {
            var seen=0;var hit=0
            for(u in (-half*.88).toInt()..(half*.88).toInt() step 2) {
                val x=(origin.x+axis.x*t+normal.x*u).roundToInt();val yy=(origin.y+axis.y*t+normal.y*u).roundToInt()
                if(x !in 3 until frame.width-3 || yy !in 3 until frame.height-3 || x.toDouble() in c.vehicleLeftPx..c.vehicleRightPx && yy.toDouble() in c.vehicleTopPx..c.vehicleBottomPx) continue
                seen++;if(paint(x,yy)) hit++
            }
            if(seen>=half*.35 && seen>0 && hit.toDouble()/seen>=.60) candidates+=t
        }
        val groups=mutableListOf<MutableList<Int>>()
        for(t in candidates) {if(groups.isEmpty() || t-groups.last().last()>5) groups+=mutableListOf(t) else groups.last()+=t}
        val topCap=groups.filter {it.average()< -8}.maxByOrNull {it.average()}
        val bottomCap=groups.filter {it.average()>8}.minByOrNull {it.average()}
        val a=topCap?.let {it.average()+(it.last()-it.first()+1)/2.0};val b=bottomCap?.let {it.average()-(it.last()-it.first()+1)/2.0}
        if(a==null || b==null) return ParkingSlotObservation(null,"주차칸 앞뒤 경계를 모두 확인하지 못했어요. 최종 프레임에서 네 모서리를 지정할 수 있어요.",side)
        fun intersection(l:DetectedLine,t:Double):Point {
            val k=slope(l);val q=axis.x*origin.x+axis.y*origin.y+t
            val yy=(q-axis.x*intercept(l))/(axis.x*k+axis.y)
            return Point(k*yy+intercept(l),yy)
        }
        val leftGeometric=listOf(c.vehicleTopPx,c.vehicleBottomPx).minOf {(c.vehicleLeftPx-(slope(left)*it+intercept(left)))/hypot(1.0,slope(left))}
        val rightGeometric=listOf(c.vehicleTopPx,c.vehicleBottomPx).minOf {((slope(right)*it+intercept(right))-c.vehicleRightPx)/hypot(1.0,slope(right))}
        val li=(leftGeometric-(side.leftClearancePx ?: leftGeometric)).coerceIn(0.0,12.0)
        val ri=(rightGeometric-(side.rightClearancePx ?: rightGeometric)).coerceIn(0.0,12.0)
        fun inset(l:DetectedLine,d:Double)=DetectedLine(Point(l.top.x+normal.x*d,l.top.y+normal.y*d),Point(l.bottom.x+normal.x*d,l.bottom.y+normal.y*d),l.support)
        val innerLeft=inset(left,li);val innerRight=inset(right,-ri)
        val slot=listOf(intersection(innerLeft,a),intersection(innerRight,a),intersection(innerRight,b),intersection(innerLeft,b))
        val vehicle=listOf(Point(c.vehicleLeftPx,c.vehicleTopPx),Point(c.vehicleRightPx,c.vehicleTopPx),Point(c.vehicleRightPx,c.vehicleBottomPx),Point(c.vehicleLeftPx,c.vehicleBottomPx))
        val placement=ParkingPlacementGeometry.measure(slot,vehicle,"DETECTED_FOUR_BOUNDARIES")
        return ParkingSlotObservation(placement,if(placement==null) "주차칸 전체 경계의 기하가 보정된 직사각형과 맞지 않아요." else null,side)
    }
}
