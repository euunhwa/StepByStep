package com.easycar.core
import java.io.File
object ScenarioAudit {
    @JvmStatic fun main(args:Array<String>) {
        val profile=ReferenceProfile.fromJson(File(args[0]).readText())
        println("scenario\tevents BEFORE session finish [type@seconds:score]")
        for(c in DemoCatalog.cases) {
            val engine=CoachEngine(profile,c.context)
            val events=c.frames.flatMap {engine.accept(it)}
            println("${c.id}\t"+events.joinToString {"${it.type}@${it.endMs/1000.0}:${it.score ?: "보류"}"})
        }
    }
}
