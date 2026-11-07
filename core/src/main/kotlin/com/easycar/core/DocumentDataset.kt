package com.easycar.core

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Executable document-derived rules. Not a measured driving corpus or a trained model. */
data class DocumentRecognition(
    val maxSignalAgeMs:Long,val maxFrameGapMs:Long,val maxSampleGapMs:Long,val gearStableMs:Long,
    val resamplePeriodMs:Long,val derivativeWarmupMs:Long,val accelerationMergeMs:Long,val decelerationMergeMs:Long,val stopPreRollMs:Long,
    val stoppedMps:Double,val idleWindowMs:Long,val idleStdMaxMps:Double,val movingMps:Double,
    val preRollMs:Long,val startWindowMs:Long,val stopWindowMs:Long,val stopCandidateMps2:Double,val stopArmMps:Double,
    val yawMeanWindowMs:Long,val yawBumpRadps:Double,val yawPeakRadps:Double,val yawMinimumMs:Long,
    val yawQuietMs:Long,val turnTimeoutMs:Long,val turnMinDeg:Double,val turnMaxDeg:Double,
    val uTurnMinDeg:Double,val uTurnMaxDeg:Double,val harshRunMs:Long,
    val harshStartMaxKph:Double,val harshStartMps2:Double,val harshAccelerationMinKph:Double,
    val harshAccelerationMps2:Double,val harshDecelerationMps2:Double,val harshStopMaxKph:Double,
    val rapidTurnMinKph:Double,val rapidTurnMaxMs:Long,val rapidUTurnMinKph:Double,val rapidUTurnMaxMs:Long,
    val parkingMaxMps:Double,val parkingSpeedEpsilonMps:Double,val parkingTMinDeg:Double,val parkingTMaxDeg:Double,val parkingParallelMaxDeg:Double,
    val steeringNoiseDeg:Double,val parkingLongStopMs:Long,val parkingTimeoutMs:Long,val minimumCoverage:Double)
data class DocumentEvaluation(
    val accelerationGood:Double,val accelerationDanger:Double,val decelerationGood:Double,val decelerationDanger:Double,
    val jerkGood:Double,val jerkDanger:Double,val lateralGoodG:Double,val lateralDangerG:Double,val gravityMps2:Double,
    val cautionPoints:Int,val dangerPoints:Int,val safetyPoints:Int,val parkingTFreeReverseToDrive:Int,
    val parkingParallelFreeReverseToDrive:Int,val parkingExtraConversionPoints:Int,val parkingTimeBlockMs:Long,
    val parkingExtraTimePoints:Int,val minimumExceedanceMs:Long)
data class DocumentDataset(val recognitionVersion:String,val evaluationVersion:String,val sourceSha256:String,
    val notice:String,val recognition:DocumentRecognition,val evaluation:DocumentEvaluation) {
    companion object {
        fun fromJson(recognitionJson:String,evaluationJson:String):DocumentDataset {
            val a=JsonParser.parseString(recognitionJson).asJsonObject
            val b=JsonParser.parseString(evaluationJson).asJsonObject
            for(root in listOf(a,b)) {
                require(root.keySet()==setOf("schemaVersion","id","sourceSha256","validationStatus","expertValidated","requiresImprovement","appNotice","config"))
                require(root["schemaVersion"].asInt==1 && root["validationStatus"].asString=="DOCUMENT_DERIVED_DRAFT")
                require(!root["expertValidated"].asBoolean && root["requiresImprovement"].asBoolean)
                require(root["sourceSha256"].asString.matches(Regex("[0-9a-f]{64}")))
                require(root["id"].asString.isNotBlank() && root["appNotice"].asString.contains("개선"))
            }
            require(a["sourceSha256"]==b["sourceSha256"] && a["appNotice"]==b["appNotice"])
            fun <T> parse(root:com.google.gson.JsonObject,cls:Class<T>):T {
                val config=root.getAsJsonObject("config")
                require(config.keySet()==cls.declaredFields.filterNot {it.isSynthetic}.map {it.name}.toSet()) {"기준 파일 필드 누락/오타"}
                require(config.entrySet().all {(_,v)->v.isJsonPrimitive && v.asJsonPrimitive.isNumber && v.asDouble.isFinite() && v.asDouble>0})
                return Gson().fromJson(config,cls)
            }
            val r=parse(a,DocumentRecognition::class.java);val e=parse(b,DocumentEvaluation::class.java)
            require(r.minimumCoverage in .9..1.0 && r.turnMinDeg<r.turnMaxDeg && r.turnMaxDeg<r.uTurnMinDeg && r.uTurnMaxDeg<=220)
            require(r.resamplePeriodMs==100L && r.derivativeWarmupMs>=200)
            require(r.yawBumpRadps<r.yawPeakRadps && r.maxSampleGapMs<=200 && r.idleWindowMs in 4000..5000 && r.harshRunMs>=600)
            require(r.parkingSpeedEpsilonMps<=0.00001)
            require(r.parkingTMinDeg<r.parkingTMaxDeg && r.parkingParallelMaxDeg<r.parkingTMinDeg)
            require(e.accelerationGood<e.accelerationDanger && e.decelerationGood<e.decelerationDanger && e.jerkGood<e.jerkDanger && e.lateralGoodG<e.lateralDangerG)
            require(e.cautionPoints<e.dangerPoints && e.dangerPoints<e.safetyPoints && e.safetyPoints<=100 && e.minimumExceedanceMs>=600)
            return DocumentDataset(a["id"].asString,b["id"].asString,a["sourceSha256"].asString,a["appNotice"].asString,r,e)
        }
    }
}
