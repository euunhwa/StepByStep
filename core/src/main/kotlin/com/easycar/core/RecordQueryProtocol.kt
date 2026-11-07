package com.easycar.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

sealed interface QueryParseResult {
    data class Success(val request:RecordQueryRequest):QueryParseResult
    data class Failure(val status:RecordQueryStatus,val message:String):QueryParseResult
}

/** App-owned local protocol; it is not an exported Gleo receiver or a caller identity check. */
object RecordQueryProtocol {
    private val gson=GsonBuilder().serializeNulls().setPrettyPrinting().create()
    fun request(action:RecordQueryAction,sessionId:String,resultId:String?=null,maneuverType:ManeuverType?=null,requestId:String="local-query",parkingOnly:Boolean=false):String {
        val parameters=JsonObject().apply {addProperty("sessionId",sessionId);resultId?.let {addProperty("resultId",it)};if(parkingOnly) addProperty("maneuverType","PARKING") else maneuverType?.let {addProperty("maneuverType",it.name)}}
        return gson.toJson(JsonObject().apply {addProperty("schemaVersion",1);addProperty("requestId",requestId);addProperty("action",action.name);add("parameters",parameters)})
    }
    fun parse(json:String,allowEmptyOptionalValues:Boolean=false):QueryParseResult {
        if(json.length>16384) return QueryParseResult.Failure(RecordQueryStatus.INVALID_REQUEST,"조회 요청은 16,384자 이하로 입력해 주세요.")
        return try {
            val reader=JsonReader(StringReader(json)).apply {strictness=Strictness.STRICT}
            fun read(depth:Int):JsonElement {
                require(depth<=3)
                return when(reader.peek()) {
                    JsonToken.BEGIN_OBJECT->{val obj=JsonObject();reader.beginObject();while(reader.hasNext()) {val name=reader.nextName();require(!obj.has(name));obj.add(name,read(depth+1))};reader.endObject();obj}
                    JsonToken.STRING->JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER->JsonPrimitive(java.math.BigDecimal(reader.nextString()))
                    else->throw IllegalArgumentException("Unsupported JSON token")
                }
            }
            val root=read(0).asJsonObject
            require(reader.peek()==JsonToken.END_DOCUMENT)
            require(root.keySet()==setOf("schemaVersion","requestId","action","parameters"))
            val version=root["schemaVersion"]
            require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber)
            if(version.asBigDecimal!=java.math.BigDecimal.ONE) return QueryParseResult.Failure(RecordQueryStatus.UNSUPPORTED_VERSION,"지원하지 않는 조회 규격 버전이에요.")
            fun string(obj:JsonObject,key:String):String {
                val value=obj[key];require(value!=null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
                return value.asString
            }
            fun id(obj:JsonObject,key:String):String=string(obj,key).also {require(it.matches(Regex("[A-Za-z0-9._:-]{1,128}")))}
            val requestId=id(root,"requestId")
            val action=RecordQueryAction.valueOf(string(root,"action"))
            val parameters=root["parameters"].asJsonObject
            val allowed=when(action) {
                RecordQueryAction.GET_LAST_RESULT->setOf("sessionId","maneuverType")
                RecordQueryAction.EXPLAIN_RESULT->setOf("sessionId","resultId","maneuverType")
                RecordQueryAction.SUMMARIZE_SESSION->setOf("sessionId")
            }
            require(parameters.keySet().all {it in allowed})
            fun optionalId(key:String):String?=if(!parameters.has(key)) null else {
                val value=string(parameters,key)
                if(allowEmptyOptionalValues && value.isBlank()) null else id(parameters,key)
            }
            val type=if(parameters.has("maneuverType")) string(parameters,"maneuverType").takeUnless {allowEmptyOptionalValues && it.isBlank()} else null
            QueryParseResult.Success(RecordQueryRequest(requestId,action,optionalId("sessionId"),optionalId("resultId"),
                if(type==null || type=="PARKING") null else ManeuverType.valueOf(type),parkingOnly=type=="PARKING"))
        } catch(_:Exception) {QueryParseResult.Failure(RecordQueryStatus.INVALID_REQUEST,"조회 요청의 함수·필드·값 형식을 확인해 주세요.")}
    }
    fun responseJson(response:RecordQueryResponse):String=gson.toJson(response)
    /** Callback-format preview only. No package, broadcast or network dispatch exists here. */
    fun callbackPreview(response:RecordQueryResponse):String {
        val status=when(response.status) {
            RecordQueryStatus.SCOPE_REQUIRED->"asking"
            RecordQueryStatus.INVALID_REQUEST,RecordQueryStatus.UNSUPPORTED_VERSION,RecordQueryStatus.CONSENT_REQUIRED,
            RecordQueryStatus.REVIEW_UNAVAILABLE,RecordQueryStatus.INVALID_RECORD->"fail"
            else->"success"
        }
        val message=when(status) {"asking"->"Select an authorized local practice session before querying saved results.";"fail"->"The local record query could not be completed. Refer to the structured query status.";else->"The saved local record query completed. The data preserves stored scores, holds and evaluation versions."}
        val callback=JsonObject().apply {
            addProperty("functionName",response.action ?: "UNKNOWN_LOCAL_ACTION")
            addProperty("status",status);addProperty("message",message);addProperty("mode","silent")
            addProperty("data",gson.toJson(JsonArray().apply {add(gson.toJsonTree(response))}))
        }
        return gson.toJson(callback)
    }
}
