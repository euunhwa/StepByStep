package com.example.easycar.ui

import com.easycar.core.*

/** Display-only wording. Stored evidence, scoring, query responses and settings stay unchanged. */
fun userHoldReason(reason:String):String {
    val text=reason.lowercase()
    return when {
        listOf("중단","종료 전","동작 종료","종료 조건").any {it in text}->"동작을 끝까지 확인하지 못했어요."
        "주차" in text && listOf("속도 범위","시간 초과").any {it in text}->"주차를 끝까지 확인하지 못했어요."
        "주차" in text && listOf("유형","패턴","경계","종류").any {it in text}->"주차 종류를 구분할 정보가 부족해요."
        listOf("영상","정렬","픽셀","보정","공간","차량 외곽").any {it in text}->"영상에서 차량 위치를 확인하기 어려워요."
        listOf("jerk","가감속","미분","가속도").any {it in text}->"페달 움직임을 충분히 확인하지 못했어요."
        listOf("yaw","회전","방향").any {it in text}->"회전 방향을 충분히 확인하지 못했어요."
        listOf("핸들","조향").any {it in text}->"핸들 움직임을 충분히 확인하지 못했어요."
        listOf("보행자","제동 원인","안전 맥락","주변 상황","제한속도").any {it in text}->"주변 상황을 확인하지 못했어요."
        listOf("전문가","배점","초안").any {it in text}->"연습에 참고하는 점수예요."
        listOf("속도","기어","신호","시각","누락","연결","수신","충족률","단위").any {it in text}->"주행 정보를 충분히 받지 못했어요."
        else->"평가에 필요한 정보가 부족해요."
    }
}

fun userDeductionTitle(deduction:Deduction)=userDeductionTitle(deduction.key,deduction.title)
fun userDeductionTitle(key:String,title:String=""):String {
    val text=(key+" "+title).lowercase()
    return when {
        listOf("gear_repetition","parking_reversals","전후진").any {it in text}->"전후진 반복"
        listOf("parking_time","주차 시간","주차 소요").any {it in text}->"주차 시간 초과"
        listOf("intrusion","parking_boundary","선 침범","경계 침범").any {it in text}->"주차선 침범"
        listOf("parking_lateral","parking_longitudinal","치우침").any {it in text}->"주차 위치 치우침"
        listOf("jerk","가감속 변화").any {it in text}->"급격한 페달 조작"
        listOf("lateral","횡가속","쏠림").any {it in text}->"회전 중 쏠림"
        listOf("deceleration","감속","제동").any {it in text}->"급한 감속"
        listOf("acceleration","가속").any {it in text}->"급한 가속"
        listOf("rapid_turn","rapid_uturn","급회전","고속 회전").any {it in text}->"급한 회전"
        listOf("steering","조향","핸들").any {it in text}->"급한 핸들 조작"
        listOf("alignment","parking_angle","기울어짐","정렬").any {it in text}->"주차 위치 기울어짐"
        listOf("entry","진입").any {it in text}->"주차 진입 위치"
        listOf("speed","속도").any {it in text}->"빠른 이동 속도"
        else->"운전 조작"
    }
}

fun userFeedbackReason(reason:String):String {
    val text=reason.lowercase()
    return when {
        listOf("부족","미확인","보류","없음","누락","중단","미수신").any {it in text}->userHoldReason(reason)
        reason.isBlank()->"조작을 다시 확인해 보세요."
        else->userDeductionTitle("",reason)
    }
}

fun userResultText(result:ManeuverResult):String=buildString {
    if(result.score==null) {
        append("${result.type.title} 평가는 보류했어요. ")
        append(result.holdReasons.map(::userHoldReason).distinct().take(2).joinToString(" ").ifBlank {"동작을 끝까지 확인하지 못했어요."})
    } else {
        append("${result.type.title} ${result.score}점이에요. ")
        if(result.deductions.isEmpty()) append("감점된 항목이 없어요.")
        else append(result.deductions.joinToString(" · ") {"${userDeductionTitle(it)} ${it.points}점 감점"})
        if(result.lifecycle!=Lifecycle.COMPLETED || result.holdReasons.isNotEmpty()) append(" 완료하지 못한 연습의 저장 결과예요.")
    }
}

fun userQueryAnswer(response:RecordQueryResponse):String {
    val result=response.result
    if(result!=null) return buildString {
        when {
            response.status==RecordQueryStatus.IN_PROGRESS->append("${result.type.title}의 진행 중인 기록이에요. 완료한 뒤 다시 확인해 주세요. ")
            !result.finalResult->append("${result.type.title} 평가는 보류된 기록이에요. ")
            else->append("${result.type.title} 저장 점수는 ${result.score}점이에요. ")
        }
        if(!result.finalResult) {
            append(result.holdReasons.map(::userHoldReason).distinct().take(2).joinToString(" ").ifBlank {"동작을 끝까지 확인하지 못했어요."})
            if(result.score!=null) append(" 남아 있는 점수는 ${result.score}점이며 완료 결과로 확인되지는 않았어요.")
        } else if(response.action==RecordQueryAction.EXPLAIN_RESULT.name) {
            if(result.deductions.isEmpty()) append("저장된 감점 항목이 없어요.")
            result.deductions.take(5).forEach {d->
                val count=result.reverseToDriveCount
                if(d.key=="gear_repetition" && result.evaluationPolicyVersion=="evaluation-document-1.0" && d.unit=="회" && d.measured==count.toDouble() && d.allowed>=0 && d.allowed%1==0.0 && d.measured>d.allowed) {
                    append("후진에서 전진으로 바꾼 ${count}회 중 기준 ${d.allowed.toInt()}회를 초과한 ${(d.measured-d.allowed).toInt()}회로 ${d.points}점 감점됐어요. ")
                } else append("${userDeductionTitle(d.key,d.title)} 항목에서 ${d.points}점 감점됐어요. ")
            }
            if(result.omittedDeductionCount>0 || result.deductions.size>5) append("다른 감점 항목도 아래에서 확인할 수 있어요.")
        }
        if(result.unobserved.isNotEmpty()) append(" 확인하지 못한 항목은 평가를 보류했어요.")
        if(result.historical) append(" 예전에 저장한 결과를 그대로 보여드려요.")
    }.trim()
    response.summary?.let {s->
        return buildString {
            append("동작 ${s.totalResults}개 중 평가된 동작은 ${s.ratedResults}개, 보류·미완료는 ${s.heldOrUnfinishedResults}개예요. ")
            s.averageScore?.let {append("저장된 동작 점수의 평균은 ${it}점이에요. ")}
            s.improvements.firstOrNull()?.let {append("${userDeductionTitle(it.key,it.title)} 항목의 감점 기록이 ${it.occurrences}개 동작에 있어요. ")}
            append(if(s.ratedResults<s.totalResults) "보류된 동작을 먼저 확인해 보세요." else if(s.improvements.isEmpty()) "같은 조건에서 연습을 이어가 보세요." else "해당 조작을 다음 연습에서 다시 살펴보세요.")
        }
    }
    return when(response.status) {
        RecordQueryStatus.CONSENT_REQUIRED->"시작 안내에 동의한 뒤 기록을 확인해 주세요."
        RecordQueryStatus.SCOPE_REQUIRED->"조회할 연습 기록을 먼저 선택해 주세요."
        RecordQueryStatus.REVIEW_UNAVAILABLE->"정차 후 저장 기록을 확인할 수 있어요."
        RecordQueryStatus.NO_RESULTS,RecordQueryStatus.NOT_FOUND->"선택한 연습에서 확인할 동작 기록이 없어요."
        RecordQueryStatus.IN_PROGRESS->"아직 연습 중이에요. 완료한 뒤 다시 확인해 주세요."
        RecordQueryStatus.HELD->"평가에 필요한 정보가 부족해 점수를 보류했어요."
        RecordQueryStatus.INVALID_REQUEST,RecordQueryStatus.UNSUPPORTED_VERSION,RecordQueryStatus.INVALID_RECORD->"저장된 기록을 확인하지 못했어요. 다시 선택해 주세요."
        RecordQueryStatus.OK->"저장된 기록을 확인했어요."
    }
}

fun userQueryWarnings(response:RecordQueryResponse):List<String> = buildList {
    if(response.session?.state in setOf(Lifecycle.INTERRUPTED,Lifecycle.CANCELLED)) add("중간에 마친 연습 기록이에요.")
    if(response.summary?.mixedVersions==true) add("서로 다른 기준으로 평가한 기록이 있어 전체 평균은 표시하지 않았어요.")
    response.result?.let {r->
        if(r.arithmeticMatches==false) add("저장된 점수와 감점 내역이 맞지 않아 확인이 필요해요.")
        if(r.evaluationPolicyVersion==null || r.scoreKind==null) add("오래된 기록이라 일부 설명이 남아 있지 않아요.")
    }
}.distinct()

fun userNextPractice(key:String):String=when(key) {
    "gear_repetition","parking_reversals"->"주차에 들어가기 전 차량 위치와 움직일 순서를 확인해 보세요."
    "parking_time"->"주차 중 시간이 오래 걸린 움직임을 다시 살펴보세요."
    "deceleration"->"브레이크를 부드럽게 밟으며 주변 상황도 함께 살펴보세요."
    "acceleration","jerk"->"페달을 조금 더 부드럽게 움직여 보세요."
    "lateral_acceleration","rapid_turn"->"회전하기 전에 속도를 충분히 줄여 보세요."
    else->"감점된 조작을 다음 연습에서 다시 확인해 보세요."
}

fun userSessionSummary(results:List<ManeuverResult>):List<String> {
    if(results.isEmpty()) return listOf("아직 완료한 동작이 없어요.","연습을 마친 뒤 기록을 확인해 보세요.")
    val rated=results.filter {it.score!=null && it.lifecycle==Lifecycle.COMPLETED && it.holdReasons.isEmpty()}
    val held=results.size-rated.size
    val findings=rated.flatMap {r->r.deductions.map {r.id to it}}.groupBy {it.second.key}
        .entries.sortedWith(compareByDescending<Map.Entry<String,List<Pair<String,Deduction>>>> {entry->entry.value.map {it.first}.distinct().size}.thenBy {it.key})
    return buildList {
        rated.firstOrNull {it.deductions.isEmpty()}?.let {add("${it.type.title}에서 감점된 항목이 없었어요.")}
            ?: rated.maxByOrNull {it.score!!}?.let {add("${it.type.title}의 저장 점수는 ${it.score}점이에요.")}
        findings.firstOrNull()?.let {entry->
            add("${userDeductionTitle(entry.value.first().second)} 항목이 ${entry.value.map {it.first}.distinct().size}개 동작에서 감점됐어요.")
        }
        if(held>0) add("${held}개 동작은 끝까지 확인하지 못했거나 점수를 보류했어요.")
        add(if(findings.isNotEmpty()) userNextPractice(findings.first().key) else if(held>0) "보류된 동작부터 확인해 보세요." else "같은 조건에서 연습을 이어가 보세요.")
    }
}
