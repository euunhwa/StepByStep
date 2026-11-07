package com.easycar.core

import kotlin.math.*

const val PARKING_VISION_VERSION = "parking-vision-2.0"

/** Pixel geometry only. No maneuver labels, scores, filenames or camera assumptions are inputs. */
internal class ParkingGeometryAnalyzer : VideoAnalyzer {
    override fun analyze(frame:PixelFrame,calibration:Calibration?):AlignmentEvidence {
        fun held(reason:String,lines:List<DetectedLine> = emptyList()) = AlignmentEvidence(
            null,null,null,null,0.0,reason,lines,frame.timeMs,frame.source,algorithmVersion=PARKING_VISION_VERSION)
        val w=frame.width;val h=frame.height
        if(w !in 100..8192 || h !in 100..8192 || frame.argb.size.toLong()!=w.toLong()*h) return held("영상 해상도 또는 픽셀 정보 부족")
        val c=calibration?.forDimensions(w,h) ?: return held("탑뷰 보정·차량 기준축 또는 영상 비율 확인 필요")
        if(!c.topView || !c.undistorted || !c.vehicleAxisKnown) return held("왜곡 보정된 탑뷰와 수직 차량 기준축이 필요해요")
        val luminance=IntArray(frame.argb.size) {i->val p=frame.argb[i];(299*((p shr 16) and 255)+587*((p shr 8) and 255)+114*(p and 255))/1000}
        val mask=BooleanArray(frame.argb.size)
        val offset=max(6,w/80)
        fun body(x:Double,y:Double)=x in c.vehicleLeftPx..c.vehicleRightPx && y in c.vehicleTopPx..c.vehicleBottomPx
        for(y in 12 until h-12) for(x in offset until w-offset) {
            if(body(x.toDouble(),y.toDouble())) continue
            val i=y*w+x;val pixel=frame.argb[i]
            val red=(pixel shr 16) and 255;val green=(pixel shr 8) and 255;val blue=pixel and 255
            val white=maxOf(red,green,blue)-minOf(red,green,blue)<=55
            val yellow=min(red,green)-blue>=25 && green>=red*.65 && red>=green*.75
            val contrast=luminance[i]-(luminance[i-offset]+luminance[i+offset])/2
            mask[i]=luminance[i]>=48 && (white || yellow) && contrast>=22
        }
        fun marked(x:Int,y:Int)=x in 0 until w && y in 0 until h && mask[y*w+x]
        val pixels=ArrayList<Point>()
        for(y in 12 until h-12 step 2) for(x in offset until w-offset step 2) if(marked(x,y)) pixels+=Point(x.toDouble(),y.toDouble())
        if(pixels.size<h/2) return held("주차선 대비 부족·선 없음·가림")
        data class Line(val intercept:Double,val slope:Double,val votes:Int) {
            fun at(y:Double)=intercept+slope*(y-h/2.0)
        }
        val peaks=ArrayList<Line>()
        for(step in -50..50) {
            val slope=tan(Math.toRadians(step*.5));val votes=IntArray(w/3+2)
            pixels.forEach {p->val bin=((p.x-slope*(p.y-h/2.0))/3).roundToInt();if(bin in votes.indices) votes[bin]++}
            for(i in 1 until votes.lastIndex) if(votes[i]>=h*.20 && votes[i]>=votes[i-1] && votes[i]>=votes[i+1]) peaks+=Line(i*3.0,slope,votes[i])
        }
        val candidates=ArrayList<Line>()
        peaks.sortedByDescending {it.votes}.forEach {p->if(candidates.none {abs(it.intercept-p.intercept)<w*.12}) candidates+=p}
        fun refine(line:Line):Line {
            val points=pixels.filter {abs(it.x-line.at(it.y))<8}
            if(points.size<h/4) return line
            val mx=points.map {it.x}.average();val my=points.map {it.y}.average()
            val variance=points.sumOf {(it.y-my).pow(2)}
            if(variance<1) return line
            val slope=points.sumOf {(it.x-mx)*(it.y-my)}/variance
            return Line(mx-slope*(my-h/2.0),slope,line.votes)
        }
        val lines=candidates.map(::refine).sortedBy {it.intercept}
        fun detected(line:Line)=DetectedLine(Point(line.at(12.0),12.0),Point(line.at(h-12.0),h-12.0),line.votes)
        val overlay=lines.map(::detected)
        if(lines.size<2) return held("주차선 두 개를 확인할 수 없어요",overlay)
        if(lines.size>2 && c.targetCenterPx==null) return held("대상 주차칸 여러 개 · 영상을 멈추고 대상 칸을 선택해 주세요",overlay)
        val pair=if(c.targetCenterPx!=null) lines.zipWithNext().firstOrNull {(a,b)->c.targetCenterPx>a.intercept && c.targetCenterPx<b.intercept}
            ?: return held("선택한 위치에서 주차칸을 확인할 수 없어요",overlay) else lines[0] to lines[1]
        val (leftLine,rightLine)=pair
        if(abs(Math.toDegrees(atan(leftLine.slope)-atan(rightLine.slope)))>3.0) return held("주차선 방향 불일치 · 탑뷰 보정과 대상 칸을 확인해 주세요",overlay)
        val vehicleCenter=(c.vehicleLeftPx+c.vehicleRightPx)/2
        val bodyCenterY=(c.vehicleTopPx+c.vehicleBottomPx)/2
        if(c.targetCenterPx==null && !(leftLine.at(bodyCenterY)<vehicleCenter && rightLine.at(bodyCenterY)>vehicleCenter)) return held("차량 주변 대상 주차칸 미확인",overlay)
        if(listOf(c.vehicleTopPx,c.vehicleBottomPx).any {rightLine.at(it)-leftLine.at(it)<w*.18}) return held("주차칸 폭·선 교차 확인 필요",overlay)
        data class Support(val count:Int,val fraction:Double,val halfWidth:Double)
        fun support(line:Line):Support? {
            val rows=ArrayList<Int>();val widths=ArrayList<Double>();var visible=0
            for(y in 12 until h-12) {
                val x=line.at(y.toDouble()).roundToInt()
                if(x !in offset until w-offset || body(x.toDouble(),y.toDouble())) continue
                visible++
                val seed=(-4..4).map {x+it}.firstOrNull {marked(it,y)} ?: continue
                rows+=y
                var l=seed;var r=seed
                while(l>0 && marked(l-1,y) && seed-l<offset*2) l--
                while(r<w-1 && marked(r+1,y) && r-seed<offset*2) r++
                widths+=(r-l+1)/2.0
            }
            if(rows.size<(h-24)*.42 || visible==0 || rows.size.toDouble()/visible<.55) return null
            if(rows.last()-rows.first()<(h-24)*.65) return null
            if(rows.first()>c.vehicleTopPx || rows.last()<c.vehicleBottomPx) return null
            return Support(rows.size,rows.size.toDouble()/visible,widths.sorted()[widths.size/2])
        }
        val ls=support(leftLine) ?: return held("좌측 주차선 가림·대비 또는 길이 부족",overlay)
        val rs=support(rightLine) ?: return held("우측 주차선 가림·대비 또는 길이 부족",overlay)
        val ys=listOf(c.vehicleTopPx,c.vehicleBottomPx)
        val left=ys.minOf {(c.vehicleLeftPx-leftLine.at(it)-ls.halfWidth)/hypot(1.0,leftLine.slope)}
        val right=ys.minOf {(rightLine.at(it)-c.vehicleRightPx-rs.halfWidth)/hypot(1.0,rightLine.slope)}
        val slope=(leftLine.slope+rightLine.slope)/2
        val centerOffset=(vehicleCenter-(leftLine.at(bodyCenterY)+rightLine.at(bodyCenterY))/2)/hypot(1.0,slope)
        // A two-pixel band is deliberately unknown; pixel quantization cannot resolve contact.
        val intrusion=when {left < -2 || right < -2->true;left>2 && right>2->false;else->null}
        return AlignmentEvidence(Math.toDegrees(atan(slope)),intrusion,c.metersPerPixel?.times(left),c.metersPerPixel?.times(right),
            min(ls.fraction,rs.fraction),if(intrusion==null) "주차선 경계에 가까워 침범 여부를 확정하기 어려워요" else null,
            listOf(detected(leftLine),detected(rightLine)),frame.timeMs,frame.source,left,right,
            c.metersPerPixel?.times(centerOffset),centerOffset,PARKING_VISION_VERSION)
    }
}

/** Five requested samples of the preceding second; never borrows a successful earlier scene. */
class ParkingVideoWindowAnalyzer {
    fun analyze(evidence:List<AlignmentEvidence>,requestedFrames:Int=5):AlignmentEvidence {
        require(requestedFrames in 3..9)
        val ordered=evidence.distinctBy {it.frameTimeMs}.sortedBy {it.frameTimeMs}
        val last=ordered.lastOrNull()
        fun held(reason:String)=AlignmentEvidence(null,null,null,null,0.0,reason,last?.lines ?: emptyList(),last?.frameTimeMs ?: 0,last?.source ?: "영상 입력 없음",
            algorithmVersion=PARKING_VISION_VERSION,sampledFrames=requestedFrames,acceptedFrames=ordered.count {it.reason==null && it.angleDeg!=null && it.intrusion!=null},windowStartMs=ordered.firstOrNull()?.frameTimeMs)
        if(ordered.isEmpty()) return held("분석할 영상 프레임이 없어요")
        if(ordered.map {it.source}.distinct().size!=1) return held("서로 다른 영상의 프레임은 합칠 수 없어요")
        if(ordered.last().frameTimeMs-ordered.first().frameTimeMs !in 400..1500) return held("연속 프레임 관측 시간이 부족하거나 간격이 너무 커요")
        val valid=ordered.filter {it.angleDeg!=null && it.intrusion!=null && it.reason==null && it.algorithmVersion==PARKING_VISION_VERSION}
        if(valid.size<max(3,ceil(requestedFrames*.6).toInt()) || last !in valid) return held(last?.reason ?: "최근 프레임의 주차선을 확인할 수 없어요")
        if(valid.map {it.intrusion}.distinct().size!=1) return held("프레임마다 침범 관측이 달라요 · 정차 장면을 확인해 주세요")
        fun spread(value:(AlignmentEvidence)->Double?)=valid.mapNotNull(value).let {if(it.size==valid.size) it.max()-it.min() else Double.POSITIVE_INFINITY}
        if(spread {it.angleDeg}>2.0 || spread {it.centerOffsetPx}>8.0 || spread {it.leftClearancePx}>8.0 || spread {it.rightClearancePx}>8.0) return held("차량·주차선 위치가 변하고 있어요 · 정차한 구간에서 다시 분석해 주세요")
        return last!!.copy(quality=valid.minOf {it.quality},sampledFrames=requestedFrames,acceptedFrames=valid.size,windowStartMs=ordered.first().frameTimeMs)
    }
}
