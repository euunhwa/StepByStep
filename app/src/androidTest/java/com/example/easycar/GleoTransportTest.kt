package com.example.easycar

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easycar.core.*
import com.example.easycar.data.*
import com.example.easycar.gleo.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GleoTransportTest {
    private val app get()=ApplicationProvider.getApplicationContext<EasyCarApplication>()
    @Test fun packagedSchemaResolvesToTheSignatureProtectedBroadcastReceiver() {
        val schema=JsonParser.parseString(app.assets.open("schema.json").bufferedReader().use {it.readText()}).asJsonObject
        assertEquals(app.packageName,schema["packageName"].asString);assertEquals(BuildConfig.VERSION_NAME,schema["version"].asString)
        assertTrue(GleoContract.available(app))
        @Suppress("DEPRECATION") val receiver=app.packageManager.getReceiverInfo(ComponentName(app,GleoRecordReceiver::class.java),0)
        assertTrue(receiver.exported);assertEquals(GleoContract.CALLER_PERMISSION,receiver.permission)
        schema["actionCalls"].asJsonArray.forEach {node->
            val function=node.asJsonObject["function"].asJsonObject
            assertEquals("service",function["component"].asString)
            @Suppress("DEPRECATION") val matches=app.packageManager.queryBroadcastReceivers(Intent(function["name"].asString).setPackage(app.packageName),0)
            assertEquals(listOf(receiver.name),matches.map {it.activityInfo.name})
            @Suppress("DEPRECATION") val qualified=app.packageManager.queryBroadcastReceivers(Intent(app.packageName+"."+function["name"].asString).setPackage(app.packageName),0)
            assertEquals(listOf(receiver.name),qualified.map {it.activityInfo.name})
        }
        val callback=GleoContract.callbackIntent(app,"{}")
        assertEquals(GleoContract.PACKAGE,callback.`package`);assertEquals(GleoContract.CALLBACK_ACTION,callback.action)
        assertEquals(app.packageName,callback.getStringExtra("packageName"));assertEquals("{}",callback.getStringExtra("response"))
    }
    @Test fun realReceiverReadsRoomDispatchesToInstalledGleoAndRevokesDeletedScope()=runBlocking {
        app.ready.await()
        val settings=app.repository.settings.first()
        val fixture=RecordQueryDemoData.session(app.profile,app.evaluationPolicy.documentDataset!!)
        val saved=SessionRecord(UUID.randomUUID().toString(),"Gleo 전송 합성 시험",System.currentTimeMillis(),fixture.source,fixture.profileVersion,fixture.state,fixture.results,emptyList(),videoFile="/PRIVATE_NOT_FOR_CALLBACK.mp4")
        val before=Gson().toJson(saved)
        val rows=mutableListOf<GleoDelivery>()
        suspend fun call(action:String,packed:String?=null,direct:Pair<String,String>?=null):GleoDelivery {
            val sequence=app.gleo.lastDelivery.value?.sequence ?: 0
            val intent=Intent(action).setPackage(app.packageName)
            if(packed!=null) intent.putExtra("ACTION_PARAM",packed)
            direct?.let {intent.putExtra(it.first,it.second)}
            app.sendBroadcast(intent) // Same-UID transport injection, explicitly not a voice-origin claim.
            return withTimeout(6500) {app.gleo.lastDelivery.first {it!=null && it.sequence>sequence}!!}.also {rows+=it;assertEquals("SENT",it.dispatch)}
        }
        try {
            app.repository.updateSettings(settings.copy(consent=true,muted=true));app.repository.save(saved);app.repository.selectGleoSession(saved.id)
            assertEquals("OK",call("GET_LAST_RESULT",direct="maneuverType" to "T_PARK").queryStatus)
            assertEquals("OK",call("EXPLAIN_RESULT","{\"resultId\":\"fixture-tpark-85\"}").queryStatus)
            assertEquals("OK",call(app.packageName+".GET_LAST_RESULT","{\"sessionId\":\"\",\"maneuverType\":\"PARKING\"}").queryStatus)
            assertEquals("HELD",call("GET_LAST_RESULT","{}").queryStatus)
            assertEquals("OK",call("SUMMARIZE_SESSION","{}").queryStatus)
            assertEquals("INVALID_REQUEST",call("EXPLAIN_RESULT","{\"score\":100}").queryStatus)
            assertEquals(before,Gson().toJson(app.repository.records.value.first {it.id==saved.id}))
            app.repository.selectGleoSession(null)
            assertEquals("SCOPE_REQUIRED",call("GET_LAST_RESULT").queryStatus)
            app.repository.selectGleoSession(saved.id);app.repository.delete(saved.id)
            assertNull(app.repository.settings.first().gleoSessionId)
            assertEquals("SCOPE_REQUIRED",call("GET_LAST_RESULT").queryStatus)
            File(app.filesDir,"validation-gleo-transport-v012.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "requestOrigin" to "SAME_UID_ANDROID_BROADCAST_TEST_NOT_VOICE","fixture" to "SYNTHETIC_QUERY_FIXTURE_SAVED_IN_ROOM",
                "callbackTarget" to GleoContract.PACKAGE,"callbackRecipientPermission" to GleoContract.CALLER_PERMISSION,
                "storedRecordUnchanged" to true,"automaticScopeRevokedOnDelete" to true,"deliveries" to rows)))
        } finally {
            app.repository.delete(saved.id);app.repository.updateSettings(settings)
            if(settings.consent) app.repository.selectGleoSession(settings.gleoSessionId)
        }
    }
    @Test fun malformedAndroidExtrasCannotChangeCallerScopeOrCallbackDestination() {
        val bad=listOf(Intent("GET_LAST_RESULT").putExtra("sessionId",22),
            Intent("GET_LAST_RESULT").putExtra("ACTION_PARAM","{}").putExtra("sessionId","another"),
            Intent("GET_LAST_RESULT").putExtra("callbackPackage","other.app"))
        bad.forEach {assertTrue(GleoContract.request(it) is QueryParseResult.Failure)}
        assertNull(GleoContract.normalizedAction("another.app.GET_LAST_RESULT"))
        assertEquals("GET_LAST_RESULT",GleoContract.normalizedAction(app.packageName+".GET_LAST_RESULT"))
    }
    @Test fun untrustedShellCannotReachTheProtectedReceiver()=runBlocking {
        val previous=app.gleo.lastDelivery.value
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am broadcast --user ${android.os.Process.myUserHandle().hashCode()} -a ${app.packageName}.GET_LAST_RESULT -p ${app.packageName}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}
        delay(600)
        assertEquals(previous,app.gleo.lastDelivery.value)
    }
}
