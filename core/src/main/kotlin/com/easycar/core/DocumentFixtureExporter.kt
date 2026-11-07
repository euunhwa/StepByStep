package com.easycar.core

import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest

/** Explicitly synthetic validation pack. No samples are portrayed as a real expert-driving reference. */
object DocumentFixtureExporter {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args[0]);val dest=File(root,"app/src/main/assets/datasets/document-v1").apply {mkdirs()}
        val gson=GsonBuilder().setPrettyPrinting().create()
        val base=DocumentDemoCatalog.automaticDrive.frames
        File(dest,"auto-drive.json").writeText(gson.toJson(base))
        val truth=listOf("START","LEFT","RIGHT","U_TURN","STOP","T_PARK","PARALLEL_PARK")
        val pack=listOf(
            mapOf("id" to "seven-normal","expectedCompletedTypes" to truth,"frames" to base),
            mapOf("id" to "no-yaw","expectedCompletedTypes" to listOf("START","STOP"),"frames" to base.map {it.copy(yawRate=null)}),
            mapOf("id" to "wrong-yaw-unit","expectedCompletedTypes" to listOf("START","STOP"),"frames" to base.map {it.copy(yawRate=it.yawRate?.copy(unit="degree/s"))}),
            mapOf("id" to "receipt-only-yaw","expectedCompletedTypes" to listOf("START","STOP"),"frames" to base.map {it.copy(yawRate=it.yawRate?.copy(timing=SignalTiming.RECEIPT_ESTIMATE))})
        )
        File(dest,"recognition-validation.json").writeText(gson.toJson(mapOf("id" to "recognition-synthetic-1.0","sourceType" to "ARBITRARY_SYNTHETIC_VALIDATION","expertValidated" to false,"requiresImprovement" to true,"cases" to pack)))
        val hashes=dest.listFiles()!!.filter {it.extension=="json" && it.name!="manifest.json"}.associate {file->file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)}}
        File(dest,"manifest.json").writeText(gson.toJson(mapOf("id" to "document-datasets-1.0","sourceType" to "DOCUMENT_RULES_AND_ARBITRARY_SYNTHETIC_VALIDATION","expertValidated" to false,"requiresImprovement" to true,"trainingPerformed" to false,"externalRawDatasetAcquired" to false,"recognitionCases" to pack.size,"autoDriveSamples" to base.size,"autoDriveDurationMs" to (base.last().timeMs-base.first().timeMs),"expectedTypes" to truth,"filesSha256" to hashes)))
        val dataset=DocumentDataset.fromJson(File(dest,"recognition.json").readText(),File(dest,"evaluation.json").readText())
        val profile=ReferenceProfile.fromJson(File(root,"app/src/main/assets/profiles/document-derived-v1.json").readText())
        val policy=EvaluationPolicy.fromDocumentJson(File(dest,"recognition.json").readText(),File(dest,"evaluation.json").readText())
        val engine=CoachEngine(profile,DocumentDemoCatalog.automaticDrive.context,evaluationPolicy=policy)
        base.forEach {engine.accept(it)};engine.finish()
        File(root,"docs/validation/document-v9-replay.json").writeText(gson.toJson(mapOf("recognitionVersion" to dataset.recognitionVersion,"evaluationVersion" to dataset.evaluationVersion,"results" to engine.results)))
        engine.results.forEach {println("${it.type}: ${it.score} ${it.holdReasons} yaw=${it.measurements["yawDeltaDeg"]}")}
    }
}
