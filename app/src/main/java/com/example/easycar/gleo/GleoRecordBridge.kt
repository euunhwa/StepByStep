package com.example.easycar.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.easycar.core.*
import com.example.easycar.EasyCarApplication
import com.example.easycar.BuildConfig
import com.example.easycar.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

object GleoContract {
    const val PACKAGE="ai.umos.ambientai"
    const val CALLER_PERMISSION="ai.umos.gleo.permission.APP_ACTION"
    const val CALLBACK_ACTION="ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    // Gleo 4.2.0.15 prefixes function actions, although the public example uses bare names.
    fun normalizedAction(action:String?):String?=action?.removePrefix(BuildConfig.APPLICATION_ID+".")?.takeIf {it in RecordQueryAction.entries.map {a->a.name}}
    fun request(intent:Intent):QueryParseResult = try {
        val extras=intent.extras
        val values=extras?.keySet().orEmpty().associateWith {key->
            @Suppress("DEPRECATION")
            val value=extras?.get(key)
            require(value is String);value
        }
        GleoRecordProtocol.parse(normalizedAction(intent.action),values["ACTION_PARAM"],values.filterKeys {it!="ACTION_PARAM"})
    } catch(_:Exception) {QueryParseResult.Failure(RecordQueryStatus.INVALID_REQUEST,"Gleo 요청 형식을 확인해 주세요.")}

    fun available(context:Context):Boolean = try {
        val pm=context.packageManager
        @Suppress("DEPRECATION") val permission=pm.getPermissionInfo(CALLER_PERMISSION,0)
        permission.packageName==PACKAGE && permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE==PermissionInfo.PROTECTION_SIGNATURE &&
            pm.checkPermission(CALLER_PERMISSION,PACKAGE)==PackageManager.PERMISSION_GRANTED
    } catch(_:Exception) {false}

    fun callbackIntent(context:Context,response:String)=Intent(CALLBACK_ACTION).setPackage(PACKAGE)
        .putExtra("packageName",context.packageName).putExtra("response",response)

    /** SENT is Android dispatch, not an acknowledgement from the assistant or proof of speech. */
    fun send(context:Context,response:String):String = try {
        if(!available(context)) "UNAVAILABLE" else {
            context.sendBroadcast(callbackIntent(context,response),CALLER_PERMISSION);"SENT"
        }
    } catch(_:Exception) {"FAILED"}
}

/** Testable read-only policy shared by the actual receiver. Rechecks access before disclosure. */
class GleoQueryHandler(private val settings:suspend ()->UserSettings,private val reviewAllowed:suspend ()->Boolean,
    private val records:()->List<SessionRecord>,private val profileVersion:String,private val policyVersion:String?) {
    suspend fun execute(action:String,parsed:QueryParseResult):Pair<RecordQueryResponse,Boolean> {
        fun denied(status:RecordQueryStatus,text:String)=RecordQueryResponse(requestId=null,action=action,status=status,answer=text)
        if(parsed is QueryParseResult.Failure) return denied(parsed.status,parsed.message) to true
        val initial=settings()
        if(!initial.consent) return denied(RecordQueryStatus.CONSENT_REQUIRED,"EasyCar에서 시작 안내에 동의한 뒤 기록을 연결해 주세요.") to initial.muted
        val id=initial.gleoSessionId ?: return denied(RecordQueryStatus.SCOPE_REQUIRED,"EasyCar에서 조회할 기록을 선택하고 Gleo 연결을 켜 주세요.") to initial.muted
        if(!reviewAllowed()) return denied(RecordQueryStatus.REVIEW_UNAVAILABLE,"정차 상태가 확인된 뒤 저장 기록을 조회해 주세요.") to initial.muted
        val original=records().firstOrNull {it.id==id} ?: return denied(RecordQueryStatus.NOT_FOUND,"연결한 연습 기록이 삭제되었어요. 다른 기록을 선택해 주세요.") to initial.muted
        val request=(parsed as QueryParseResult.Success).request
        val json=RecordQueryProtocol.request(request.action,request.sessionId ?: id,request.resultId,request.maneuverType,request.requestId,request.parkingOnly)
        val result=withContext(Dispatchers.Default) {LocalRecordQueryGateway {listOf(original)}.execute(json,RecordQueryAccess(setOf(id),true,true,profileVersion,policyVersion))}
        val current=settings()
        if(!current.consent || current.gleoSessionId!=id) return denied(RecordQueryStatus.CONSENT_REQUIRED,"기록 연결이 해제되어 결과를 전달하지 않았어요.") to current.muted
        if(!reviewAllowed()) return denied(RecordQueryStatus.REVIEW_UNAVAILABLE,"정차 상태가 확인된 뒤 저장 기록을 조회해 주세요.") to current.muted
        val latest=records().firstOrNull {it.id==id}
        if(latest==null) return denied(RecordQueryStatus.NOT_FOUND,"연결한 연습 기록이 삭제되었어요.") to current.muted
        if(latest!=original) return denied(RecordQueryStatus.INVALID_RECORD,"조회 중 기록이 변경되었어요. 다시 요청해 주세요.") to current.muted
        return result to current.muted
    }
}

data class GleoDelivery(val sequence:Long,val action:String,val queryStatus:String,val dispatch:String)
class GleoRecordBridge(private val app:EasyCarApplication) {
    private val mutex=Mutex()
    private val delivery=MutableStateFlow<GleoDelivery?>(null)
    val lastDelivery=delivery.asStateFlow()
    suspend fun receive(intent:Intent) {
        val action=GleoContract.normalizedAction(intent.action) ?: return
        val parsed=GleoContract.request(intent)
        if(!mutex.tryLock()) {
            dispatch(action,"BUSY",GleoRecordProtocol.failure(action,"Another record query is in progress. Please try again."));return
        }
        try {
            // Leave headroom for Android IPC inside Gleo's documented seven second deadline.
            val result=withTimeoutOrNull(5000) {
                app.ready.await()
                GleoQueryHandler({app.repository.settings.first()},{
                    if(!app.bridge.state.value.ux.known) {
                        app.bridge.start()
                        withTimeoutOrNull(1800) {app.bridge.state.first {it.ux.known}}
                    }
                    app.bridge.state.value.ux.allowsReview() && !app.runtime.state.value.running
                },{app.repository.records.value},app.profile.version,app.evaluationPolicy.version).execute(action,parsed)
            }
            if(result==null) dispatch(action,"TIMEOUT",GleoRecordProtocol.failure(action,"The record query timed out. Please try again after opening EasyCar."))
            else dispatch(action,result.first.status.name,GleoRecordProtocol.callback(action,result.first,result.second))
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {dispatch(action,"ERROR",GleoRecordProtocol.failure(action,"The saved record could not be read. Please open EasyCar and try again."))}
        finally {
            if(!app.visible && !app.runtime.state.value.running) app.bridge.stop()
            mutex.unlock()
        }
    }
    private fun dispatch(action:String,status:String,json:String) {
        val outcome=GleoContract.send(app,json)
        delivery.value=GleoDelivery((delivery.value?.sequence ?: 0)+1,action,status,outcome)
        // No result payload, record identifier or private data is written to logcat.
        android.util.Log.i("EasyCarGleo","action=$action status=$status dispatch=$outcome")
    }
}

/** The OS enforces Gleo's signature permission on this exported component. */
class GleoRecordReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(GleoContract.normalizedAction(intent.action)==null) return
        val pending=goAsync()
        val app=context.applicationContext as EasyCarApplication
        app.scope.launch {try {app.gleo.receive(intent)} finally {pending.finish()}}
    }
}
