package com.easycar.core

import java.io.File

/** The bundled document-derived profile and policy, as the app loads them. */
object DocumentFixtures {
    private val assets=File("../app/src/main/assets")
    val profile by lazy {ReferenceProfile.fromJson(File(assets,"profiles/document-derived-v1.json").readText())}
    val policy by lazy {EvaluationPolicy.fromDocumentJson(
        File(assets,"datasets/document-v1/recognition.json").readText(),
        File(assets,"datasets/document-v1/evaluation.json").readText())}
    fun engine(context:PracticeContext=PracticeContext(null,null,dataSource="TEST"))=CoachEngine(profile,context,evaluationPolicy=policy)
}
