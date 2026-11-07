package com.easycar.core

import com.google.gson.GsonBuilder
import java.io.File

object DemoExporter {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args[0]);val signals=File(root,"signals").apply { mkdirs() }
        val gson=GsonBuilder().setPrettyPrinting().create()
        DemoCatalog.cases.forEach { c->File(signals,"${c.id}.json").writeText(gson.toJson(c.frames)) }
        // Ground-truth labels are kept outside signal files and are never loaded by the recognizer.
        val labels=DemoCatalog.cases.map { c->mapOf("id" to c.id,"title" to c.title,"synthetic" to true,"parkingCountTruth" to if(c.id.startsWith("park-")) c.id.split('-')[2].toInt() else null,
            "maneuverOrderTruth" to if(c.id=="auto-drive") listOf("START","LEFT","RIGHT","U_TURN","STOP","T_PARK","PARALLEL_PARK") else null,
            "turnTruth" to when(c.id){"left","fast-left"->"LEFT";"right","rough-right"->"RIGHT";"uturn"->"U_TURN";else->null},"angleTruthDeg" to c.videoAngle,"context" to c.context,
            "sync" to if(c.videoAngle!=null) mapOf("videoStartMs" to 0,"videoEndMs" to 4000,"signalStartMs" to c.frames.first().timeMs,"signalEndMs" to c.frames.last().timeMs) else null) }
        File(root,"truth-labels.json").writeText(gson.toJson(labels))
        println("Exported ${DemoCatalog.cases.size} reproducible synthetic signal fixtures; truth labels are separate.")
    }
}
