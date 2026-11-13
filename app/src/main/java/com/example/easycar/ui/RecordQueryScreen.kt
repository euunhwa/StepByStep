package com.example.easycar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    var selectedType by remember(record.id) {mutableStateOf<ManeuverType?>(null)}
    var selectedResult by remember(record.id) {mutableStateOf<String?>(null)}
    var typeMenu by remember {mutableStateOf(false)}
    var resultMenu by remember {mutableStateOf(false)}
    val locked by rememberUpdatedState(restricted)
    val currentRecord by rememberUpdatedState(record)
    fun run(request:String) {
        if(busy || restricted) return
        busy=true;response=null
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
    fun query(next:RecordQueryAction) {
        run(RecordQueryProtocol.request(next,record.id,if(next==RecordQueryAction.EXPLAIN_RESULT) selectedResult else null,
            if(next==RecordQueryAction.GET_LAST_RESULT) selectedType else null))
    }
    LaunchedEffect(restricted) {if(restricted) response=null}
    if(restricted) {Text("정차 후 저장 기록을 조회할 수 있어요.",Modifier.testTag("record-query-restricted"));return}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("record-query-screen")) {
        TextButton(onClick=onBack) {Text("← 연습 요약")}
        Heading("저장 기록에 질문하기",record.title)
        Text("선택한 연습의 점수와 감점 이유를 확인할 수 있어요.",fontSize=17.sp,color=Muted,lineHeight=25.sp,modifier=Modifier.padding(vertical=12.dp))
        onGleoSelection?.let {select->
            CardPanel(Modifier.fillMaxWidth().padding(bottom=16.dp)) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text("이 기록을 Gleo에 연결",fontSize=21.sp,modifier=Modifier.weight(1f))
                    Switch(checked=gleoSelected,onCheckedChange=select,modifier=Modifier.testTag("gleo-record-access"))
                }
                Text("켜면 Gleo에게 이 연습의 점수와 감점 이유를 물어볼 수 있어요. 영상은 보내지 않으며 선택한 기록만 연결해요.",fontSize=16.sp,lineHeight=24.sp,color=Muted)
                if(gleoSelected) {
                    val invokeWord=stringResource(R.string.gleo_invoke_word)
                    Text("Gleo에게 ‘$invokeWord 최근 주차 결과 알려줘’, ‘$invokeWord 감점 이유 알려줘’라고 말해 보세요.",fontSize=16.sp,lineHeight=24.sp,color=Mint,modifier=Modifier.padding(top=10.dp))
                }
            }
        }
        Box {
            TextButton(onClick={typeMenu=true},enabled=!busy) {Text("최근 동작 종류: ${selectedType?.title ?: "전체"}")}
            DropdownMenu(expanded=typeMenu,onDismissRequest={typeMenu=false}) {
                DropdownMenuItem(text={Text("전체")},onClick={selectedType=null;typeMenu=false;response=null})
                ManeuverType.entries.forEach {type->DropdownMenuItem(text={Text(type.title)},onClick={selectedType=type;typeMenu=false;response=null})}
            }
        }
        Box {
            TextButton(onClick={resultMenu=true},enabled=!busy) {Text("감점 설명 대상: ${record.results.firstOrNull {it.id==selectedResult}?.let {"${it.type.title} · ${record.results.indexOfFirst {r->r.id==it.id}+1}번째"} ?: "최근 동작"}")}
            DropdownMenu(expanded=resultMenu,onDismissRequest={resultMenu=false},modifier=Modifier.heightIn(max=360.dp)) {
                DropdownMenuItem(text={Text("최근 동작")},onClick={selectedResult=null;resultMenu=false;response=null})
                record.results.sortedByDescending {it.endMs}.take(20).forEach {result->
                    DropdownMenuItem(text={Text("${result.type.title} · ${record.results.indexOfFirst {it.id==result.id}+1}번째 · ${result.score?.let {"${it}점"} ?: "보류"}")},
                        onClick={selectedResult=result.id;resultMenu=false;response=null})
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val buttons:@Composable ()->Unit = {
                Button(onClick={query(RecordQueryAction.GET_LAST_RESULT)},enabled=!busy,modifier=Modifier.heightIn(min=54.dp).testTag("record-query-last")) {Text("최근 동작")}
                Button(onClick={query(RecordQueryAction.EXPLAIN_RESULT)},enabled=!busy,modifier=Modifier.heightIn(min=54.dp).testTag("record-query-explain")) {Text("감점 이유")}
                Button(onClick={query(RecordQueryAction.SUMMARIZE_SESSION)},enabled=!busy,modifier=Modifier.heightIn(min=54.dp).testTag("record-query-summary")) {Text("이번 연습 요약")}
            }
            if(maxWidth<550.dp) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {buttons()}
            else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {buttons()}
        }
        Spacer(Modifier.height(18.dp))
        if(busy) Text("저장 기록을 조회하고 있어요…",color=Muted,modifier=Modifier.testTag("record-query-loading"))
        response?.let {answer->
            CardPanel(Modifier.fillMaxWidth()) {
                Text(when(answer.status) {RecordQueryStatus.OK->"저장 근거 확인";RecordQueryStatus.HELD->"평가 보류 기록";RecordQueryStatus.IN_PROGRESS->"진행 중인 기록";RecordQueryStatus.NO_RESULTS,RecordQueryStatus.NOT_FOUND->"결과 없음";else->"조회 확인 필요"},fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("record-query-status"))
                Text(userQueryAnswer(answer),fontSize=21.sp,lineHeight=32.sp,color=Mint,modifier=Modifier.padding(top=12.dp).testTag("record-query-answer"))
                userQueryWarnings(answer).forEach {Text(it,fontSize=16.sp,lineHeight=24.sp,color=Amber,modifier=Modifier.padding(top=10.dp))}
                answer.result?.let {r->
                    r.deductions.forEach {d->Text("${userDeductionTitle(d.key,d.title)} −${d.points}점",fontSize=17.sp,color=Muted,lineHeight=25.sp,modifier=Modifier.padding(top=10.dp))}
                    if(r.omittedDeductionCount>0) Text("나머지 감점 ${r.omittedDeductionCount}개는 이 응답에서 생략했어요.",color=Muted)
                    if(r.unobserved.isNotEmpty()) Text("확인하지 못한 항목은 평가를 보류했어요.",fontSize=16.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
                }
                answer.summary?.let {s->
                    if(s.strengths.isNotEmpty()) Text("감점 없이 마친 동작이 있어요.",fontSize=17.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
                    s.improvements.forEach {Text("복기 항목 · ${userDeductionTitle(it.key,it.title)}: ${it.occurrences}개 동작",fontSize=17.sp,color=Muted,modifier=Modifier.padding(top=10.dp))}
                    s.improvements.take(3).map {userNextPractice(it.key)}.distinct().forEach {Text("다음 연습 · $it",fontSize=17.sp,color=Muted,modifier=Modifier.padding(top=10.dp))}
                }
                TextButton(onClick={onSpeak(userQueryAnswer(answer))}) {Text("답변 듣기")}
            }
        }
    }
}
