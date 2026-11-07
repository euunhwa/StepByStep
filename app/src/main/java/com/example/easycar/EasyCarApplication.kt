package com.example.easycar
import android.app.Application
import com.easycar.core.ReferenceProfile
import com.easycar.core.EvaluationPolicy
import com.example.easycar.data.AppRepository
import com.example.easycar.runtime.DriveRuntime
import kotlinx.coroutines.*
class EasyCarApplication:Application() {
    var visible=false
    var miniWindowAttemptedSessionId:String?=null
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val ready=CompletableDeferred<Unit>()
    val repository by lazy { AppRepository(this) }
    val profile by lazy { ReferenceProfile.fromJson(assets.open("profiles/document-derived-v1.json").bufferedReader().use { it.readText() }) }
    val evaluationPolicy by lazy {EvaluationPolicy.fromDocumentJson(
        assets.open("datasets/document-v1/recognition.json").bufferedReader().use {it.readText()},
        assets.open("datasets/document-v1/evaluation.json").bufferedReader().use {it.readText()})}
    val bridge by lazy { PlatformFactory.bridge(this) }
    val runtime by lazy { DriveRuntime(this) }
    val gleo by lazy { com.example.easycar.gleo.GleoRecordBridge(this) }
    override fun onCreate() { super.onCreate();scope.launch {try {repository.load();repository.recoverInterrupted();ready.complete(Unit)} catch(e:Exception) {ready.completeExceptionally(e)} } }
}
