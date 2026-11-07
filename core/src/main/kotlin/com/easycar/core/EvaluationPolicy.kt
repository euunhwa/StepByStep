package com.easycar.core

import com.google.gson.JsonParser

data class PenaltyGroup(val key:String,val cap:Int,val metrics:List<String>)
data class MetricLimit(val bound:Double,val scale:Double,val tauSeconds:Double,val source:String)
data class EvaluationPolicy(val version:String,val realWorldScoringEnabled:Boolean,
    val budgets:Map<String,List<PenaltyGroup>>,val limits:Map<String,MetricLimit>,val researchBounds:Map<String,Double>,
    val minimumExceedanceMs:Long=300,val provisionalEnabled:Boolean=false,val provisionalLimits:Map<String,MetricLimit> = emptyMap(),val referenceDatasetId:String?=null,val datasetNotice:String?=null,
    val scoreUnconfirmedBrakingObservations:Boolean=false,val documentDataset:DocumentDataset?=null) {
    companion object {
        fun fromDocumentJson(recognitionJson:String,evaluationJson:String):EvaluationPolicy {
            val d=DocumentDataset.fromJson(recognitionJson,evaluationJson)
            return EvaluationPolicy(d.evaluationVersion,false,emptyMap(),emptyMap(),emptyMap(),
                d.evaluation.minimumExceedanceMs,referenceDatasetId=d.evaluationVersion,datasetNotice=d.notice,documentDataset=d)
        }

        fun fromJson(json:String,referenceDatasetJson:String?=null):EvaluationPolicy {
            val r=JsonParser.parseString(json).asJsonObject
            require(r["schema_version"].asInt==1)
            val version=r["version"].asString;require(version.isNotBlank())
            val budgets=r.getAsJsonObject("budgets").entrySet().associate {(type,v)->
                type to v.asJsonArray.map {node->val g=node.asJsonObject
                    PenaltyGroup(g["key"].asString,g["cap"].asInt,g["metrics"].asJsonArray.map {it.asString})}}
            val types=setOf("START","STOP","LEFT","RIGHT","U_TURN","T_PARK","PARALLEL_PARK")
            require(budgets.keys==types)
            budgets.values.forEach {groups->require(groups.isNotEmpty() && groups.sumOf {it.cap}==100 && groups.all {it.cap in 1..100 && it.metrics.isNotEmpty() && it.metrics.distinct().size==it.metrics.size} && groups.map {it.key}.distinct().size==groups.size && groups.flatMap {it.metrics}.distinct().size==groups.sumOf {it.metrics.size})}
            val known=draft().budgets.values.flatten().flatMap {it.metrics}.toSet()
            require(budgets.values.flatten().all {group->group.metrics.all {it in known}})
            val limits=r.getAsJsonObject("validated_limits")?.entrySet()?.associate {(key,v)->val a=v.asJsonObject
                require(a["validation_status"].asString=="EXPERT_VALIDATED")
                val limit=MetricLimit(a["bound"].asDouble,a["scale"].asDouble,a["tau_seconds"].asDouble,a["source"].asString)
                require(limit.bound.isFinite() && limit.bound>=0 && limit.scale.isFinite() && limit.scale>0 && limit.tauSeconds.isFinite() && limit.tauSeconds>0 && limit.source.isNotBlank())
                key to limit} ?: emptyMap()
            require(limits.keys.all {key->budgets.values.flatten().any {key in it.metrics}})
            val refs=mutableMapOf<String,Double>()
            r.getAsJsonObject("research_benchmark")?.getAsJsonArray("values")?.forEach {node->val a=node.asJsonObject
                when(a["id"].asString) {
                    "longitudinal_acceleration"->{refs["acceleration"]=a["upper"].asDouble;refs["deceleration_if_cause_known"]=kotlin.math.abs(a["lower"].asDouble)}
                    "longitudinal_jerk"->refs["longitudinal_jerk"]=a["max_abs"].asDouble
                }}
            require(refs.values.all {it.isFinite() && it>0})
            val ms=(r.getAsJsonObject("score_contract")["minimum_exceedance_run_s"].asDouble*1000).toLong()
            require(ms in 100..5000)
            val prototype=r.getAsJsonObject("provisional_scoring")
            val enabled=prototype?.get("enabled")?.asBoolean==true
            var datasetId:String?=null;var notice:String?=null
            val provisional=if(enabled) {
                require(referenceDatasetJson!=null) {"임의 평가 데이터셋 파일이 없습니다"}
                val d=JsonParser.parseString(referenceDatasetJson).asJsonObject
                require(d["arbitrary"].asBoolean && !d["expert_validated"].asBoolean && d["requires_improvement"].asBoolean)
                require(d["source_type"].asString=="ARBITRARY_SYNTHETIC")
                datasetId=d["id"].asString;require(datasetId==prototype!!["dataset_id"].asString)
                notice=d["app_notice"].asString;require(notice.contains("임의") && notice.contains("개선"))
                require(d["templates"].asJsonArray.map {it.asJsonObject["maneuver"].asString}.toSet()==types)
                d.getAsJsonObject("limits").entrySet().associate {(key,node)->
                    val a=node.asJsonObject;require(a["validation_status"].asString=="ARBITRARY_SYNTHETIC" && key in known)
                    val l=MetricLimit(a["bound"].asDouble,a["scale"].asDouble,a["tau_seconds"].asDouble,a["source"].asString)
                    require(l.bound.isFinite() && l.bound>=0 && l.scale.isFinite() && l.scale>0 && l.tauSeconds.isFinite() && l.tauSeconds>0 && l.source.isNotBlank())
                    key to l}
            } else emptyMap()
            if(enabled) require(provisional.keys.containsAll(known-setOf("confirmed_R_to_D_count")))
            val observations=prototype?.get("score_unconfirmed_braking_observations")?.asBoolean==true
            require(!observations || (enabled && prototype!!["score_meaning"]?.asString=="OBSERVED_CONTROL_SMOOTHNESS_NOT_FAULT"))
            return EvaluationPolicy(version,r["real_world_scoring_enabled"].asBoolean,budgets,limits,refs,ms,enabled,provisional,datasetId,notice,observations)
        }
        /** Safe fallback: no approved limits and no real-world score. */
        fun draft():EvaluationPolicy {
            val turn=listOf(PenaltyGroup("speed",40,listOf("entry_speed","rotation_speed","exit_speed")),PenaltyGroup("longitudinal_smoothness",30,listOf("acceleration","deceleration_if_cause_known","longitudinal_jerk")),PenaltyGroup("steering_smoothness",30,listOf("steering_rate","exit_steering_rate")))
            val park=listOf(PenaltyGroup("gear_repetition",30,listOf("confirmed_R_to_D_count")),PenaltyGroup("movement",30,listOf("parking_speed","acceleration","longitudinal_jerk","steering_rate")),PenaltyGroup("entry_pose",10,listOf("entry_pose_error_for_space")),PenaltyGroup("final_alignment",20,listOf("axis_angle_error")),PenaltyGroup("line_intrusion",10,listOf("verified_vehicle_footprint_outside_slot")))
            return EvaluationPolicy("evaluation-design-0.1",false,mapOf("START" to listOf(PenaltyGroup("launch_smoothness",100,listOf("acceleration","longitudinal_jerk"))),"STOP" to listOf(PenaltyGroup("stop_smoothness",100,listOf("deceleration_if_cause_known","longitudinal_jerk"))),"LEFT" to turn,"RIGHT" to turn,"U_TURN" to turn,"T_PARK" to park,"PARALLEL_PARK" to park),emptyMap(),mapOf("acceleration" to 2.40,"deceleration_if_cause_known" to 4.05,"longitudinal_jerk" to 4.13))
        }
    }
}
