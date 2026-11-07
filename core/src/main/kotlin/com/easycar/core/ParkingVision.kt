package com.easycar.core

import kotlin.math.*

interface VideoAnalyzer { fun analyze(frame: PixelFrame, calibration: Calibration?): AlignmentEvidence }
/** Current implementation is versioned independently from maneuver scoring. */
class ParkingLineAnalyzer : VideoAnalyzer {
    override fun analyze(frame:PixelFrame,calibration:Calibration?) = ParkingGeometryAnalyzer().analyze(frame,calibration)
}

object SyntheticParkingFrames {
    const val WIDTH=480; const val HEIGHT=360
    val calibration=Calibration(true,true,true,190.0,290.0,95.0,265.0,.01,WIDTH,HEIGHT)
    /** Analytic renderer is the input generator, independent of the Hough analyzer. */
    fun render(timeMs: Long, angleDeg: Double, shiftPx: Double=0.0, occluded: Boolean=false, noLines: Boolean=false): PixelFrame {
        val w=WIDTH; val h=HEIGHT; val pixels=IntArray(w*h) { 0xff243139.toInt() }
        val slope=tan(Math.toRadians(angleDeg))
        for(y in 8 until h-8) for(x in 0 until w) {
            val line=abs(x-(135+shiftPx+slope*(y-h/2)))<3.5 || abs(x-(345+shiftPx+slope*(y-h/2)))<3.5
            if(!noLines && line && !(occluded && y in 40..285)) pixels[y*w+x]=0xfff3f4ee.toInt()
            if(x in 190..290 && y in 95..265) pixels[y*w+x]=if(x in 200..280 && y in 110..145) 0xff20353d.toInt() else 0xff69d9ba.toInt()
        }
        return PixelFrame(w,h,pixels,timeMs,"합성 탑뷰 데모 영상")
    }
}
