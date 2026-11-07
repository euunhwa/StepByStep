package com.example.easycar.data

import com.easycar.core.*

/** Supply a fresh store snapshot for every call. No save/delete, frame, media or callback access. */
class LocalRecordQueryGateway(private val records:()->List<SessionRecord>) {
    fun execute(json:String,access:RecordQueryAccess):RecordQueryResponse {
        if(!access.consent || !access.reviewAllowed || access.sessionIds.isEmpty()) return RecordQueryEngine().execute(json,emptyList(),access)
        return try {
            val snapshots=records().filter {it.id in access.sessionIds}.map {r->
                RecordQuerySession(r.id,r.createdAt,r.profileVersion,r.source,r.state,r.results.toList())
            }
            RecordQueryEngine().execute(json,snapshots,access)
        } catch(_:RuntimeException) {
            val parsed=RecordQueryProtocol.parse(json)
            val request=(parsed as? QueryParseResult.Success)?.request
            RecordQueryResponse(requestId=request?.requestId,action=request?.action?.name,status=RecordQueryStatus.INVALID_RECORD,
                answer="저장된 연습 기록 형식을 확인할 수 없어 조회를 보류했어요.")
        }
    }
}
