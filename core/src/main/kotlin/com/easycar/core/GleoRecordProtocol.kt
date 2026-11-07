package com.easycar.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.UUID

/** Android transport adapter. Authority is supplied by the app, never by Intent parameters. */
object GleoRecordProtocol {
    private val gson=GsonBuilder().serializeNulls().create()
    const val MAX_CALLBACK_BYTES=49152

    fun parse(action:String?,packed:String?,direct:Map<String,String>):QueryParseResult {
        fun invalid()=QueryParseResult.Failure(RecordQueryStatus.INVALID_REQUEST,"Gleo 요청 형식을 확인해 주세요.")
        if(action !in RecordQueryAction.entries.map {it.name} || (packed!=null && direct.isNotEmpty())) return invalid()
        return try {
            require((packed?.length ?: 0)<=16384 && direct.size<=3 && direct.values.all {it.length<=128})
            val parameters=if(packed==null) gson.toJson(direct) else {
                // The guide describes both individual extras and ACTION_PARAM. Its example also
                // shows an encoded JSON string. Accept one string layer, never recursive decoding.
                val reader=JsonReader(StringReader(packed)).apply {strictness=Strictness.STRICT}
                if(reader.peek()==JsonToken.STRING) reader.nextString().also {require(reader.peek()==JsonToken.END_DOCUMENT)} else packed
            }
            RecordQueryProtocol.parse("""{"schemaVersion":1,"requestId":"gleo-${UUID.randomUUID()}","action":"$action","parameters":$parameters}""",allowEmptyOptionalValues=true)
        } catch(_:Exception) {invalid()}
    }

    /** Only the external callback uses voice mode. The internal mock remains silent. */
    fun callback(action:String,response:RecordQueryResponse,muted:Boolean):String {
        require(action in RecordQueryAction.entries.map {it.name})
        val local=JsonParser.parseString(RecordQueryProtocol.callbackPreview(response)).asJsonObject
        local.addProperty("functionName",action)
        local.addProperty("mode",if(muted) "silent" else "voice")
        if(response.status==RecordQueryStatus.CONSENT_REQUIRED) local.addProperty("message","Enable Gleo access to a selected practice record in EasyCar before requesting saved results.")
        if(response.status==RecordQueryStatus.SCOPE_REQUIRED) local.addProperty("message","Select a practice record and enable its Gleo access in EasyCar before requesting saved results.")
        val data=gson.toJsonTree(response).asJsonObject.apply {
            remove("transport");remove("externallyDispatched");remove("requestId");remove("action")
        }
        local.addProperty("data",gson.toJson(JsonArray().apply {add(data)}))
        if(gson.toJson(local).toByteArray(Charsets.UTF_8).size>MAX_CALLBACK_BYTES) {
            // Preserve the result's meaning; do not send a misleading partial explanation.
            return failure(action,"The saved explanation exceeds the response limit. Open the selected practice record in EasyCar to review it.",muted)
        }
        return gson.toJson(local)
    }
    fun failure(action:String,message:String,muted:Boolean=true):String=gson.toJson(JsonObject().apply {
        addProperty("functionName",action);addProperty("status","fail");addProperty("message",message)
        addProperty("mode",if(muted) "silent" else "voice")
    })
}
