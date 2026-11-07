package com.easycar.core

import kotlin.math.*

object SyntheticParkingSlotFrames {
    val calibration=SyntheticParkingFrames.calibration
    fun render(timeMs:Long=0,angleDeg:Double=0.0,shiftX:Double=0.0,shiftY:Double=0.0,blank:Boolean=false):PixelFrame {
        val w=480;val h=360;val data=IntArray(w*h) {0xff243139.toInt()};val a=Math.toRadians(angleDeg)
        for(y in 0 until h) for(x in 0 until w) {
            val dx=x-240-shiftX;val dy=y-180-shiftY;val u=dx*cos(a)+dy*sin(a);val v= -dx*sin(a)+dy*cos(a)
            val border=(abs(abs(u)-105)<3.5 && abs(v)<=138.5) || (abs(abs(v)-135)<3.5 && abs(u)<=108.5)
            if(!blank && border) data[y*w+x]=0xfff3f4ee.toInt()
            if(x in 190..290 && y in 95..265) data[y*w+x]=0xff69d9ba.toInt()
        }
        return PixelFrame(w,h,data,timeMs,"SYNTHETIC_PARKING_CAPTURE_FIXTURE")
    }
}
object ParkingCaptureFrameCheck {
    fun inspect(frames:List<PixelFrame>,slot:List<Point>,vehicle:List<Point>):String? {
        if(frames.size<3 || frames.last().timeMs-frames.first().timeMs<400 || frames.map {it.timeMs}.distinct().size!=frames.size) return "최종 정차 구간의 영상 관측이 부족해요."
        if(!ParkingPlacementGeometry.validQuad(slot) || !ParkingPlacementGeometry.validQuad(vehicle)) return "차량과 주차칸 모서리를 교차하지 않게 다시 지정해 주세요."
        val ref=frames.last();val w=ref.width;val h=ref.height
        if((slot+vehicle).any {it.x !in 0.0..w.toDouble() || it.y !in 0.0..h.toDouble()}) return "지정한 영역이 영상 밖에 있어요."
        if(frames.any {it.width!=w || it.height!=h || it.argb.size!=w*h}) return "영상 크기가 구간 안에서 바뀌었어요."
        fun light(f:PixelFrame,x:Int,y:Int):Int {val p=f.argb[y*w+x];return ((p shr 16 and 255)*.299+(p shr 8 and 255)*.587+(p and 255)*.114).toInt()}
        val left=(slot+vehicle).minOf {it.x}.toInt().coerceIn(0,w-1);val right=(slot+vehicle).maxOf {it.x}.toInt().coerceIn(0,w-1)
        val top=(slot+vehicle).minOf {it.y}.toInt().coerceIn(0,h-1);val bottom=(slot+vehicle).maxOf {it.y}.toInt().coerceIn(0,h-1)
        for(f in frames) {
            var sum=0L;var count=0
            for(y in top..bottom step 4) for(x in left..right step 4) {sum+=abs(light(f,x,y)-light(ref,x,y));count++}
            if(count==0 || sum.toDouble()/count>10) return "최종 영상 구간에서 위치나 화면이 변했어요. 정차 장면을 확인해 주세요."
            var supported=0
            for(i in slot.indices) {
                val a=slot[i];val b=slot[(i+1)%4];val length=hypot(b.x-a.x,b.y-a.y);val nx= -(b.y-a.y)/length;val ny=(b.x-a.x)/length
                var hit=0;var seen=0
                for(k in 3 until length.toInt()-3 step 3) {
                    val x=a.x+(b.x-a.x)*k/length;val y=a.y+(b.y-a.y)*k/length
                    if(x in vehicle.minOf {it.x}..vehicle.maxOf {it.x} && y in vehicle.minOf {it.y}..vehicle.maxOf {it.y}) continue
                    seen++
                    if((-5..5).any {d->
                        val px=(x+nx*d).roundToInt();val py=(y+ny*d).roundToInt()
                        if(px !in 10 until w-10 || py !in 10 until h-10) false else {
                            val p=f.argb[py*w+px];val r=p shr 16 and 255;val g=p shr 8 and 255;val blue=p and 255
                            val color=maxOf(r,g,blue)-minOf(r,g,blue)<=65 || min(r,g)-blue>=25 && g>=r*.65
                            val x1=(px+nx*9).roundToInt().coerceIn(0,w-1);val y1=(py+ny*9).roundToInt().coerceIn(0,h-1)
                            val x2=(px-nx*9).roundToInt().coerceIn(0,w-1);val y2=(py-ny*9).roundToInt().coerceIn(0,h-1)
                            color && light(f,px,py)>70 && light(f,px,py)-(light(f,x1,y1)+light(f,x2,y2))/2>20
                        }
                    }) hit++
                }
                if(seen>=8 && hit.toDouble()/seen>=.35) supported++
            }
            if(supported<3) return "지정한 주차칸 경계의 영상 근거가 부족해요. 선이 보이는 탑뷰를 사용해 주세요."
        }
        return null
    }
}
