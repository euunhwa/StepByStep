package com.easycar.core

/** A caller receives no authority from request JSON; the host supplies the selected session scope. */
data class RecordQueryAccess(val sessionIds:Set<String>,val consent:Boolean,val reviewAllowed:Boolean,
    val currentProfileVersion:String,val currentPolicyVersion:String?)
data class RecordQuerySession(val id:String,val createdAt:Long,val profileVersion:String,val source:String,
    val state:Lifecycle,val results:List<ManeuverResult>)
enum class RecordQueryAction { GET_LAST_RESULT, EXPLAIN_RESULT, SUMMARIZE_SESSION }
enum class RecordQueryStatus { OK, HELD, IN_PROGRESS, NO_RESULTS, NOT_FOUND, SCOPE_REQUIRED,
    CONSENT_REQUIRED, REVIEW_UNAVAILABLE, INVALID_REQUEST, UNSUPPORTED_VERSION, INVALID_RECORD }
data class RecordQueryRequest(val requestId:String,val action:RecordQueryAction,val sessionId:String?=null,
    val resultId:String?=null,val maneuverType:ManeuverType?=null,val parkingOnly:Boolean=false)
data class QuerySessionInfo(val id:String,val createdAt:Long,val profileVersion:String,val source:String,val state:Lifecycle)
data class QueryDeduction(val key:String,val title:String,val points:Int,val measured:Double,val unit:String,
    val allowed:Double,val evidenceMs:Long,val durationMs:Long,val phase:String)
data class QueryUnobserved(val key:String,val title:String,val reason:String?)
data class QueryResult(val id:String,val type:ManeuverType,val startMs:Long,val endMs:Long,val lifecycle:Lifecycle,
    val score:Int?,val scoreKind:String?,val reverseToDriveCount:Int,val profileVersion:String,val evaluationPolicyVersion:String?,val source:String,
    val historical:Boolean,val finalResult:Boolean,val holdReasons:List<String>,val deductions:List<QueryDeduction>,
    val omittedDeductionCount:Int,val recordedDeductionTotal:Long,val appliedDeductionTotal:Int?,
    val arithmeticMatches:Boolean?,val unobserved:List<QueryUnobserved>,val omittedUnobservedCount:Int,val scoreBasis:String?)
data class QueryFinding(val key:String,val title:String,val occurrences:Int,val recordedPoints:Long,
    val profileVersion:String,val evaluationPolicyVersion:String?,val resultIds:List<String>)
data class QuerySummary(val totalResults:Int,val ratedResults:Int,val heldOrUnfinishedResults:Int,val averageScore:Int?,
    val mixedVersions:Boolean,val profileVersions:List<String>,val policyVersions:List<String>,
    val strengths:List<String>,val improvements:List<QueryFinding>,val nextSteps:List<String>,val omittedFindingCount:Int)
data class RecordQueryResponse(val schemaVersion:Int=1,val requestId:String?,val action:String?,val status:RecordQueryStatus,
    val answer:String,val warnings:List<String> = emptyList(),val session:QuerySessionInfo?=null,
    val result:QueryResult?=null,val summary:QuerySummary?=null,
    val transport:String="LOCAL_MOCK",val externallyDispatched:Boolean=false)

/** Reads stored values only. It never loads the current scorer or changes a stored record. */
class RecordQueryEngine {
    fun execute(json:String,sessions:List<RecordQuerySession>,access:RecordQueryAccess):RecordQueryResponse {
        fun denied(status:RecordQueryStatus,text:String)=RecordQueryResponse(requestId=null,action=null,status=status,answer=text)
        val parsed=RecordQueryProtocol.parse(json)
        if(parsed is QueryParseResult.Failure) return denied(parsed.status,parsed.message)
        val request=(parsed as QueryParseResult.Success).request
        fun reply(status:RecordQueryStatus,text:String)=RecordQueryResponse(requestId=request.requestId,action=request.action.name,status=status,answer=text)
        if(!access.consent) return reply(RecordQueryStatus.CONSENT_REQUIRED,"기록 조회에 대한 시작 안내 동의가 필요해요.")
        if(!access.reviewAllowed) return reply(RecordQueryStatus.REVIEW_UNAVAILABLE,"정차 상태가 확인된 뒤 저장 기록을 조회해 주세요.")
        if(access.sessionIds.isEmpty()) return reply(RecordQueryStatus.SCOPE_REQUIRED,"조회할 연습 기록을 먼저 선택해 주세요.")
        // An absent and an out-of-scope ID have the same response. Do not reveal other records.
        if(request.sessionId!=null && request.sessionId !in access.sessionIds) return reply(RecordQueryStatus.NOT_FOUND,"선택한 조회 범위에서 연습 기록을 찾을 수 없어요.")
        val allowed=sessions.filter {it.id in access.sessionIds}
        if(allowed.map {it.id}.distinct().size!=allowed.size) return reply(RecordQueryStatus.INVALID_RECORD,"동일한 식별자의 연습 기록이 중복되어 조회를 보류했어요.")
        val session=if(request.sessionId!=null) allowed.singleOrNull {it.id==request.sessionId}
            else allowed.sortedWith(compareByDescending<RecordQuerySession> {it.createdAt}.thenBy {it.id}).firstOrNull()
        if(session==null) return reply(RecordQueryStatus.NOT_FOUND,"선택한 조회 범위에서 연습 기록을 찾을 수 없어요.")
        return try {
            require(session.id.length in 1..128 && session.profileVersion.length in 1..128 && session.source.isNotBlank() && session.createdAt>=0)
            require(session.results.map {it.id}.distinct().size==session.results.size)
            session.results.forEach(::validate)
            val info=QuerySessionInfo(session.id,session.createdAt,session.profileVersion,short(session.source),session.state)
            val warnings=mutableListOf<String>()
            if(session.state in setOf(Lifecycle.ACTIVE,Lifecycle.CANDIDATE)) warnings+="진행 중인 세션의 현재 저장 지점이며 최종 결과가 달라질 수 있어요."
            if(session.state in setOf(Lifecycle.INTERRUPTED,Lifecycle.CANCELLED)) warnings+="중단된 연습 기록이에요."
            if(request.action==RecordQueryAction.SUMMARIZE_SESSION) summarize(request,session,info,access,warnings)
            else {
                val results=session.results.filter {if(request.parkingOnly) it.type in setOf(ManeuverType.PARKING,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK,ManeuverType.PARKING_UNKNOWN) else request.maneuverType==null || it.type==request.maneuverType}
                val stored=if(request.resultId!=null) results.singleOrNull {it.id==request.resultId}
                    else results.sortedWith(compareByDescending<ManeuverResult> {it.endMs}.thenByDescending {it.startMs}.thenBy {it.id}).firstOrNull()
                if(stored==null) reply(if(request.resultId==null) RecordQueryStatus.NO_RESULTS else RecordQueryStatus.NOT_FOUND,
                    if(request.resultId==null) "이 연습에서 ${if(request.parkingOnly) "주차" else request.maneuverType?.title ?: "동작"} 결과를 찾을 수 없어요." else "이 연습에서 요청한 동작 기록을 찾을 수 없어요.").copy(session=info,warnings=warnings)
                else explain(request,stored,info,access,warnings)
            }
        } catch(_:RuntimeException) {reply(RecordQueryStatus.INVALID_RECORD,"저장된 평가 근거 형식을 확인할 수 없어 설명을 보류했어요.")}
    }
    private fun validate(r:ManeuverResult) {
        require(r.id.length in 1..128 && r.profileVersion.length in 1..128 && r.source.isNotBlank() && (r.evaluationPolicyVersion?.length ?: 1) in 1..128)
        require(r.type in ManeuverType.entries && r.lifecycle in Lifecycle.entries)
        require(r.startMs>=0 && r.endMs>=r.startMs && r.reverseToDriveCount>=0 && (r.score==null || r.score in 0..100))
        require(r.deductions.size<=1000 && (r.assessments?.size ?: 0)<=1000 && r.holdReasons.size<=1000)
        r.deductions.forEach {d->require(d.key.isNotBlank() && d.title.isNotBlank() && d.points>=0 && d.measured.isFinite() && d.allowed.isFinite() && d.durationMs>=0 && d.evidenceMs>=0)}
    }
    private fun historical(r:ManeuverResult,access:RecordQueryAccess)=r.profileVersion!=access.currentProfileVersion ||
        (r.evaluationPolicyVersion!=null && access.currentPolicyVersion!=null && r.evaluationPolicyVersion!=access.currentPolicyVersion)
    private fun explain(request:RecordQueryRequest,r:ManeuverResult,info:QuerySessionInfo,access:RecordQueryAccess,warnings:MutableList<String>):RecordQueryResponse {
        val old=historical(r,access)
        if(old) warnings+="과거 평가 버전의 저장 결과를 그대로 설명해요. 현재 기준으로 다시 채점하지 않아요."
        if(r.profileVersion!=info.profileVersion) warnings+="세션과 동작의 프로필 버전이 달라요. 동작 기록의 기준을 확인해 주세요."
        if(r.evaluationPolicyVersion==null || r.scoreKind==null) warnings+="이전 저장 형식으로 평가 정책이나 점수 종류 정보가 일부 없어요."
        if(r.scoreKind=="PROVISIONAL") warnings+="관측된 조작 지표의 임시 점수이며 안전성이나 주차 성공을 보증하지 않아요."
        val total=r.deductions.sumOf {it.points.toLong()}
        val applied=r.score?.let {100-it}
        val matches=applied?.let {it.toLong()==total.coerceAtMost(100)}
        if(matches==false) warnings+="개별 감점 합계와 저장 점수에 차이가 있어요. 해당 버전의 상한·근거를 확인해 주세요."
        val final=r.lifecycle==Lifecycle.COMPLETED && r.score!=null && r.holdReasons.isEmpty()
        val unobserved=r.assessments.orEmpty().filter {it.passed==null}
        val result=QueryResult(r.id,r.type,r.startMs,r.endMs,r.lifecycle,r.score,r.scoreKind,r.reverseToDriveCount,r.profileVersion,r.evaluationPolicyVersion,short(r.source),old,final,
            r.holdReasons.take(20).map(::short),r.deductions.take(20).map {d->QueryDeduction(short(d.key),short(d.title),d.points,d.measured,short(d.unit),d.allowed,d.evidenceMs,d.durationMs,short(d.phase))},
            (r.deductions.size-20).coerceAtLeast(0),total,applied,matches,unobserved.take(20).map {QueryUnobserved(short(it.key),short(it.title),it.reason?.let(::short))},
            (unobserved.size-20).coerceAtLeast(0),r.scoreBasis?.take(2000))
        val status=when {r.lifecycle in setOf(Lifecycle.CANDIDATE,Lifecycle.ACTIVE)->RecordQueryStatus.IN_PROGRESS;!final->RecordQueryStatus.HELD;else->RecordQueryStatus.OK}
        val answer=buildString {
            append(if(status==RecordQueryStatus.IN_PROGRESS) "${r.type.title}의 진행 중인 기록이에요. " else if(!final) "${r.type.title} 평가는 보류된 기록이에요. " else "${r.type.title} 저장 점수는 ${r.score}점이에요. ")
            if(!final) append(r.holdReasons.take(3).joinToString(" · ").ifBlank {"동작 종료 또는 평가 근거를 확인해 주세요."}).append(' ')
            if(final && request.action==RecordQueryAction.EXPLAIN_RESULT) {
                if(r.deductions.isEmpty()) append("저장된 감점 항목이 없어요. ")
                else {
                    r.deductions.take(5).forEach {d->
                        if(d.key=="gear_repetition" && r.evaluationPolicyVersion=="evaluation-document-1.0" && d.measured==r.reverseToDriveCount.toDouble() && d.allowed>=0 && d.allowed%1==0.0 && d.measured>d.allowed) {
                            append("R→D ${d.measured.toInt()}회 중 기준 ${d.allowed.toInt()}회를 초과한 ${(d.measured-d.allowed).toInt()}회로 ${d.points}점 감점됐어요. ")
                        } else append("${short(d.title)} ${d.points}점: 관측 ${formatValue(d.measured)} ${short(d.unit)}, 기준 ${formatValue(d.allowed)} ${short(d.unit)}. ")
                    }
                    if(r.deductions.size>5) append("나머지 ${r.deductions.size-5}개 항목은 구조화된 근거에서 확인할 수 있어요. ")
                    append("저장 점수 기준 적용 감점은 ${applied}점이에요. ")
                }
            }
            if(!final && r.deductions.isNotEmpty()) append("계산된 감점 근거는 남아 있지만 확정 감점으로 설명하지 않아요. ")
            if(unobserved.isNotEmpty()) append("${unobserved.take(3).joinToString("·") {short(it.title)}} 항목은 미평가예요. ")
            if(old) append("기준은 과거 버전 ${short(r.profileVersion)}이에요.")
        }.trim()
        return RecordQueryResponse(requestId=request.requestId,action=request.action.name,status=status,answer=answer,warnings=warnings.toList(),session=info,result=result)
    }
    private fun summarize(request:RecordQueryRequest,session:RecordQuerySession,info:QuerySessionInfo,access:RecordQueryAccess,warnings:MutableList<String>):RecordQueryResponse {
        val results=session.results
        if(results.isEmpty()) return RecordQueryResponse(requestId=request.requestId,action=request.action.name,status=RecordQueryStatus.NO_RESULTS,
            answer="이 연습에는 요약할 동작 결과가 없어요.",warnings=warnings,session=info,
            summary=QuerySummary(0,0,0,null,false,emptyList(),emptyList(),emptyList(),emptyList(),listOf("완료한 동작 기록이 생긴 뒤 다시 조회해 주세요."),0))
        val rated=results.filter {it.lifecycle==Lifecycle.COMPLETED && it.score!=null && it.holdReasons.isEmpty()}
        val profiles=results.map {it.profileVersion}.distinct().sorted()
        val policies=results.map {it.evaluationPolicyVersion ?: "UNRECORDED"}.distinct().sorted()
        val mixed=results.map {it.profileVersion to it.evaluationPolicyVersion}.distinct().size>1
        if(mixed) warnings+="평가 버전이 섞여 있어 전체 평균을 만들지 않아요. 개선 항목도 버전별로 나눠 표시해요."
        if(results.any {historical(it,access)}) warnings+="과거 버전의 저장 결과가 포함되어 있어요. 현재 기준으로 다시 채점하지 않아요."
        if(results.any {it.scoreKind=="PROVISIONAL"}) warnings+="관측 조작의 임시 점수 요약이에요. 안전성이나 주차 성공을 판정하지 않아요."
        val average=if(!mixed && rated.size==results.size) rated.map {it.score!!}.average().toInt() else null
        val strengths=rated.filter {it.deductions.isEmpty()}.map {"${it.type.title}: 저장된 관측 조작 항목에 감점이 없어요."}.distinct().take(3)
        data class Group(val key:String,val title:String,val unit:String,val allowed:Double,val profile:String,val policy:String?)
        val groups=rated.flatMap {r->r.deductions.map {r to it}}.groupBy {(r,d)->Group(d.key,d.title,d.unit,d.allowed,r.profileVersion,r.evaluationPolicyVersion)}
        val findings=groups.map {(g,entries)->QueryFinding(short(g.key),short(g.title),entries.map {it.first.id}.distinct().size,
            entries.sumOf {it.second.points.toLong()},g.profile,g.policy,entries.map {it.first.id}.distinct().sorted().take(5))}
            .sortedWith(compareByDescending<QueryFinding> {it.occurrences}.thenByDescending {it.recordedPoints}.thenBy {it.key}.thenBy {it.profileVersion}.thenBy {it.evaluationPolicyVersion ?: ""})
        val next=if(findings.isEmpty()) listOf(if(rated.size<results.size) "보류된 동작의 입력·종료 근거를 먼저 확인해 주세요." else "동일한 조건과 기준으로 연습 기록을 더 비교해 보세요.")
            else findings.take(3).map {when(it.key) {
                "gear_repetition"->"전후진 전환이 반복된 구간의 진입 위치와 기동 순서를 복기해 보세요."
                "parking_time"->"주차 시간이 길었던 구간의 원인과 기동 순서를 정차 후 복기해 보세요."
                "deceleration"->"감속 변화가 컸던 구간의 제동 원인과 주변 상황을 함께 복기해 보세요."
                "jerk","acceleration"->"가감속 변화가 컸던 구간의 페달 조작을 복기해 보세요."
                else->"${it.title} 항목의 저장된 관측값과 기준을 확인해 보세요."
            }}.distinct()
        val summary=QuerySummary(results.size,rated.size,results.size-rated.size,average,mixed,profiles.take(20),policies.take(20),strengths,findings.take(10),next,(findings.size-10).coerceAtLeast(0))
        val answer=buildString {
            append("동작 ${results.size}개 중 평가된 동작은 ${rated.size}개, 보류·미완료는 ${results.size-rated.size}개예요. ")
            if(average!=null) append("저장된 동작 점수의 평균은 ${average}점이에요. ")
            if(findings.isNotEmpty()) append("${findings.first().title} 항목의 감점 기록이 ${findings.first().occurrences}개 동작에 있어요. ")
            append(next.first())
        }
        return RecordQueryResponse(requestId=request.requestId,action=request.action.name,status=RecordQueryStatus.OK,answer=answer,warnings=warnings.toList(),session=info,summary=summary)
    }
    private fun short(value:String)=value.take(256)
}
