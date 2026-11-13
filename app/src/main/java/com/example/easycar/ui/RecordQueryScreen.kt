package com.example.easycar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easycar.core.*
import com.example.easycar.BuildConfig
import com.example.easycar.R
import com.example.easycar.data.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Suppress("UNUSED_PARAMETER")
@Composable fun RecordQueryScreen(record:SessionRecord,restricted:Boolean,onBack:()->Unit,
    execute:suspend (String)->RecordQueryResponse,onSpeak:(String)->Unit,showDebug:Boolean=BuildConfig.DEBUG,
    gleoSelected:Boolean=false,onGleoSelection:((Boolean)->Unit)?=null) {
    val scope=rememberCoroutineScope()
    var response by remember(record.id,record.revision,record.results) {mutableStateOf<RecordQueryResponse?>(null)}
    var busy by remember(record.id) {mutableStateOf(false)}
    var selectedAction by remember(record.id) {mutableStateOf<RecordQueryAction?>(null)}
    var selectedType by remember(record.id) {mutableStateOf<ManeuverType?>(null)}
    var selectedResult by remember(record.id) {mutableStateOf<String?>(null)}
    var typeMenu by remember {mutableStateOf(false)}
    var resultMenu by remember {mutableStateOf(false)}
    val locked by rememberUpdatedState(restricted)
    val currentRecord by rememberUpdatedState(record)
    fun query(next:RecordQueryAction) {
        if(busy || restricted) return
        val request=RecordQueryProtocol.request(
            next,record.id,
            if(next==RecordQueryAction.EXPLAIN_RESULT) selectedResult else null,
            if(next==RecordQueryAction.GET_LAST_RESULT) selectedType else null
        )
        selectedAction=next
        busy=true
        response=null
        val revision=record.revision;val id=record.id
        scope.launch {
            try {
                val result=execute(request)
                if(!locked && currentRecord.id==id && currentRecord.revision==revision) response=result
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {response=RecordQueryResponse(requestId=null,action=null,status=RecordQueryStatus.INVALID_RECORD,answer="저장된 기록을 조회하지 못했어요. 다시 시도해 주세요.")}
            finally {busy=false}
        }
    }
    LaunchedEffect(restricted) {if(restricted) response=null}
    if(restricted) {Text("정차 후 저장 기록을 조회할 수 있어요.",Modifier.testTag("record-query-restricted"));return}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("record-query-screen")) {
        TextButton(onClick=onBack) {Text("← 연습 요약")}
        Heading("기록에 질문하기",record.title)
        Spacer(Modifier.height(18.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val questions: @Composable (Modifier) -> Unit = { modifier ->
                CardPanel(modifier) {
                    Text("궁금한 내용 고르기",fontSize=22.sp,fontWeight=FontWeight.Bold,color=GuideInk)
                    Spacer(Modifier.height(12.dp))
                    val options=listOf(
                        Triple(RecordQueryAction.GET_LAST_RESULT,"최근 동작","record-query-last"),
                        Triple(RecordQueryAction.EXPLAIN_RESULT,"감점 이유","record-query-explain"),
                        Triple(RecordQueryAction.SUMMARIZE_SESSION,"이번 연습 요약","record-query-summary")
                    )
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        options.forEach { (action,label,tag) ->
                            val isSelected=selectedAction==action
                            Button(
                                onClick={query(action)},enabled=!busy,
                                modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag(tag).semantics { selected=isSelected },
                                shape=RoundedCornerShape(14.dp),
                                colors=ButtonDefaults.buttonColors(
                                    containerColor=if(isSelected) GuideSelected else GuideCanvas,contentColor=GuideInk,
                                    disabledContainerColor=if(isSelected) GuideSelected else GuideCanvas,disabledContentColor=GuideMuted
                                )
                            ) {
                                Text(label,fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                                Text("›",fontSize=22.sp,color=GuideMuted,modifier=Modifier.clearAndSetSemantics {})
                            }
                        }
                    }
                    onGleoSelection?.let { select ->
                        HorizontalDivider(Modifier.padding(vertical=18.dp),color=GuideLine)
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("이 기록을 Gleo에 연결",fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=GuideInk,modifier=Modifier.weight(1f))
                            Switch(checked=gleoSelected,onCheckedChange=select,modifier=Modifier.testTag("gleo-record-access"))
                        }
                        Text("켜면 Gleo에게 이 연습의 점수와 감점 이유를 물어볼 수 있어요. 영상은 보내지 않으며 선택한 기록만 연결해요.",fontSize=16.sp,lineHeight=24.sp,color=GuideMuted)
                        if(gleoSelected) {
                            val invokeWord=stringResource(R.string.gleo_invoke_word)
                            Text("Gleo에게 ‘$invokeWord 최근 주차 결과 알려줘’, ‘$invokeWord 감점 이유 알려줘’라고 말해 보세요.",fontSize=16.sp,lineHeight=24.sp,color=GuideGreen,modifier=Modifier.padding(top=10.dp))
                        }
                    }
                }
            }
            val evidence: @Composable (Modifier) -> Unit = { modifier ->
                CardPanel(modifier) {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Box {
                            OutlinedButton(onClick={typeMenu=true},enabled=!busy) {
                                Text("최근 동작 종류: ${selectedType?.title ?: "전체"}")
                            }
                            DropdownMenu(expanded=typeMenu,onDismissRequest={typeMenu=false}) {
                                DropdownMenuItem(text={Text("전체")},onClick={selectedType=null;typeMenu=false;response=null})
                                ManeuverType.entries.forEach { type ->
                                    DropdownMenuItem(text={Text(type.title)},onClick={selectedType=type;typeMenu=false;response=null})
                                }
                            }
                        }
                        Box {
                            OutlinedButton(onClick={resultMenu=true},enabled=!busy) {
                                Text("감점 설명 대상: ${record.results.firstOrNull {it.id==selectedResult}?.let {"${it.type.title} · ${record.results.indexOfFirst {r->r.id==it.id}+1}번째"} ?: "최근 동작"}")
                            }
                            DropdownMenu(expanded=resultMenu,onDismissRequest={resultMenu=false},modifier=Modifier.heightIn(max=360.dp)) {
                                DropdownMenuItem(text={Text("최근 동작")},onClick={selectedResult=null;resultMenu=false;response=null})
                                record.results.sortedByDescending {it.endMs}.take(20).forEach { result ->
                                    DropdownMenuItem(
                                        text={Text("${result.type.title} · ${record.results.indexOfFirst {it.id==result.id}+1}번째 · ${result.score?.let {"${it}점"} ?: "보류"}")},
                                        onClick={selectedResult=result.id;resultMenu=false;response=null}
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical=16.dp),color=GuideLine)
                    val answer=response
                    when {
                        busy -> Text("저장 기록을 조회하고 있어요…",color=GuideMuted,modifier=Modifier.testTag("record-query-loading"))
                        answer==null -> Text("확인할 내용을 골라 주세요.",color=GuideMuted,fontSize=17.sp)
                        else -> RecordQueryEvidence(answer,onSpeak)
                    }
                }
            }
            if(maxWidth<780.dp) {
                Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    questions(Modifier.fillMaxWidth())
                    evidence(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    questions(Modifier.weight(1f))
                    evidence(Modifier.weight(1.85f))
                }
            }
        }
    }
}

@Composable private fun RecordQueryEvidence(answer:RecordQueryResponse,onSpeak:(String)->Unit) {
    val status=when(answer.status) {
        RecordQueryStatus.OK -> "저장 근거 확인"
        RecordQueryStatus.HELD -> "평가 보류 기록"
        RecordQueryStatus.IN_PROGRESS -> "진행 중인 기록"
        RecordQueryStatus.NO_RESULTS,RecordQueryStatus.NOT_FOUND -> "결과 없음"
        else -> "조회 확인 필요"
    }
    Text(status,fontSize=16.sp,fontWeight=FontWeight.Bold,color=GuideForest,
        modifier=Modifier.testTag("record-query-status")
            .background(GuideSelected,RoundedCornerShape(14.dp)).padding(horizontal=13.dp,vertical=7.dp))
    Text(userQueryAnswer(answer),fontSize=24.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold,color=GuideInk,
        modifier=Modifier.padding(top=14.dp).testTag("record-query-answer"))
    userQueryWarnings(answer).forEach {
        Text(it,fontSize=16.sp,lineHeight=24.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=10.dp))
    }
    answer.result?.let { result ->
        if(result.deductions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top=14.dp).background(GuideCanvas,RoundedCornerShape(14.dp))
                .padding(horizontal=14.dp)) {
                result.deductions.forEachIndexed { index,deduction ->
                    if(index>0) HorizontalDivider(color=GuideLine)
                    Row(Modifier.fillMaxWidth().padding(vertical=10.dp).semantics(mergeDescendants=true) {},
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(userDeductionTitle(deduction.key,deduction.title),fontSize=17.sp,color=GuideInk,modifier=Modifier.weight(1f))
                        Text("−${deduction.points}점",fontSize=17.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if(result.omittedDeductionCount>0) Text("나머지 감점 ${result.omittedDeductionCount}개는 이 응답에서 생략했어요.",color=GuideMuted,modifier=Modifier.padding(top=10.dp))
        if(result.unobserved.isNotEmpty()) Text("확인하지 못한 항목은 평가를 보류했어요.",fontSize=16.sp,color=GuideMuted,modifier=Modifier.padding(top=12.dp))
    }
    answer.summary?.let { summary ->
        if(summary.strengths.isNotEmpty()) Text("감점 없이 마친 동작이 있어요.",fontSize=17.sp,color=GuideMuted,modifier=Modifier.padding(top=10.dp))
        summary.improvements.forEach {
            Text("복기 항목 · ${userDeductionTitle(it.key,it.title)}: ${it.occurrences}개 동작",fontSize=17.sp,color=GuideMuted,modifier=Modifier.padding(top=10.dp))
        }
        summary.improvements.take(3).map {userNextPractice(it.key)}.distinct().forEach {
            Text("다음 연습 · $it",fontSize=17.sp,color=GuideMuted,modifier=Modifier.padding(top=10.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Action("답변 듣기") {onSpeak(userQueryAnswer(answer))}
}
