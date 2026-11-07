package com.easycar.core

import com.google.gson.JsonParser
import kotlin.math.abs

data class VideoCalibrationDocument(val width:Int,val height:Int,val calibration:Calibration,val sync:VideoSync?,val source:String) {
    companion object {
        fun fromJson(json:String):VideoCalibrationDocument {
            val r=JsonParser.parseString(json).asJsonObject
            require(r.get("schemaVersion").asInt==1)
            require(r.get("projection").asString=="TOP_VIEW_UNDISTORTED") {"보정된 탑뷰 입력만 지원해요"}
            require(r.get("vehicleAxis").asString=="VERTICAL") {"차량 기준축을 수직으로 보정한 입력이 필요해요"}
            val w=r.get("videoWidth").asInt;val h=r.get("videoHeight").asInt
            require(w in 100..8192 && h in 100..8192)
            val box=r.getAsJsonObject("vehicleBounds")
            val l=box.get("left").asDouble;val right=box.get("right").asDouble;val t=box.get("top").asDouble;val b=box.get("bottom").asDouble
            require(listOf(l,right,t,b).all {it.isFinite()} && l>=0 && right<=w && l<right && t>=0 && b<=h && t<b)
            val scale=r.get("metersPerPixel")?.takeUnless {it.isJsonNull}?.asDouble
            require(scale==null || (scale.isFinite() && scale>0 && scale<1))
            val target=r.get("targetCenterX")?.takeUnless {it.isJsonNull}?.asDouble
            require(target==null || (target.isFinite() && target in 0.0..w.toDouble()))
            val sync=r.getAsJsonObject("sync")?.let {s->VideoSync(s.get("videoStartMs").asLong,s.get("videoEndMs").asLong,s.get("signalStartMs").asLong,s.get("signalEndMs").asLong)}
            require(sync==null || (sync.videoStartMs>=0 && sync.videoEndMs>sync.videoStartMs && sync.signalEndMs>sync.signalStartMs))
            return VideoCalibrationDocument(w,h,Calibration(true,true,true,l,right,t,b,scale,w,h,target),sync,r.get("source")?.asString ?: "사용자가 제공한 탑뷰 보정 정보")
        }
    }
}
fun Calibration.forDimensions(width:Int,height:Int):Calibration? {
    if(width<100 || height<100 || (referenceWidth==null)!=(referenceHeight==null)) return null
    val rw=referenceWidth ?: width;val rh=referenceHeight ?: height
    if(rw<=0 || rh<=0 || !listOf(vehicleLeftPx,vehicleRightPx,vehicleTopPx,vehicleBottomPx).all {it.isFinite()}) return null
    if(vehicleLeftPx<0 || vehicleRightPx>rw || vehicleLeftPx>=vehicleRightPx || vehicleTopPx<0 || vehicleBottomPx>rh || vehicleTopPx>=vehicleBottomPx) return null
    if(metersPerPixel?.let {!it.isFinite() || it<=0}==true || targetCenterPx?.let {!it.isFinite() || it !in 0.0..rw.toDouble()}==true) return null
    val sx=width.toDouble()/rw;val sy=height.toDouble()/rh
    if(abs(sx-sy)>maxOf(sx,sy)*.015) return null
    return copy(vehicleLeftPx=vehicleLeftPx*sx,vehicleRightPx=vehicleRightPx*sx,vehicleTopPx=vehicleTopPx*sy,vehicleBottomPx=vehicleBottomPx*sy,
        metersPerPixel=metersPerPixel?.div(sx),referenceWidth=width,referenceHeight=height,targetCenterPx=targetCenterPx?.times(sx))
}
