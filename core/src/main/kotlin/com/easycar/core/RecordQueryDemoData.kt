package com.easycar.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

/** Explicit synthetic smoke fixture. Scores come from the active scorer, not from the query handler. */
object RecordQueryDemoData {
    fun session(profile:ReferenceProfile,dataset:DocumentDataset):RecordQuerySession {
        val scorer=DocumentScorer(profile,PracticeContext(null,null,dataSource="SYNTHETIC_QUERY_FIXTURE"),dataset)
        fun samples(offset:Long,yaw:Double?)=(0..40).map {i->Motion(offset+i*100L,5.0,100.0,Gear.DRIVE,null,0.0,0.0,null,false,yawRadps=yaw)}
        val start=scorer.evaluate(ManeuverType.START,samples(0,.1)).copy(id="fixture-start-100")
        val parking=scorer.evaluate(ManeuverType.T_PARK,samples(5000,.1),conversions=4).copy(id="fixture-tpark-85")
        val held=scorer.evaluate(ManeuverType.U_TURN,samples(10000,null)).copy(id="fixture-uturn-held")
        return RecordQuerySession("fixture-session",1000,profile.version,"SYNTHETIC_QUERY_FIXTURE",Lifecycle.COMPLETED,listOf(start,parking,held))
    }
}
object RecordQuerySmoke {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.single())
        fun read(path:String)=File(root,path).readText()
        val profile=ReferenceProfile.fromJson(read("app/src/main/assets/profiles/document-derived-v1.json"))
        val dataset=DocumentDataset.fromJson(read("app/src/main/assets/datasets/document-v1/recognition.json"),read("app/src/main/assets/datasets/document-v1/evaluation.json"))
        val session=RecordQueryDemoData.session(profile,dataset)
        val scope=RecordQueryAccess(setOf(session.id),true,true,profile.version,dataset.evaluationVersion)
        val requests=JsonParser.parseString(read("examples/gleo/mock-requests.json")).asJsonArray
        val rows=requests.map {request->
            val answer=RecordQueryEngine().execute(request.toString(),listOf(session),scope)
            check(answer.status==RecordQueryStatus.OK)
            mapOf("request" to request,"response" to answer,"callbackPreview" to JsonParser.parseString(RecordQueryProtocol.callbackPreview(answer)))
        }
        val gson=GsonBuilder().serializeNulls().setPrettyPrinting().create()
        File(root,"docs/validation/gleo-local-smoke-v011.json").writeText(gson.toJson(mapOf("source" to "SYNTHETIC_QUERY_FIXTURE","externallyDispatched" to false,"calls" to rows)))
        println("Local record query smoke: ${rows.size} calls passed; no external callback dispatched")
    }
}
