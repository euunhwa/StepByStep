package com.example.easycar.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.easycar.R
import com.example.easycar.runtime.DriveState
import kotlinx.coroutines.delay

/** Shared by system PiP and the Pleos side pane. In PiP the system supplies all touch controls. */
@Composable fun MiniCoachScreen(drive:DriveState,inPip:Boolean,onStop:()->Unit,datasetNotice:String?=null,restricted:Boolean=false,onResume:()->Unit={}) {
    val appName=stringResource(R.string.app_name)
    var now by remember(drive.record?.id) {mutableLongStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(drive.record?.id,drive.feedback?.resultId,drive.running) {
        now=SystemClock.elapsedRealtime()
        val published=drive.feedback?.publishedAtMs
        if(published!=null && drive.running) {
            val remaining=MiniCoachContent.FEEDBACK_VISIBLE_MS-(now-published)
            if(remaining>0) {delay(remaining);now=SystemClock.elapsedRealtime()}
        }
    }
    val content=if(restricted && !drive.running) MiniCoachContent("주행에 집중해 주세요","정차 후 코칭을 시작할 수 있어요.",false,null) else MiniCoachContent.from(drive.running,drive.paused,drive.feedback,now)
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).testTag("mini-coach")) {
        val actions=!inPip && drive.running && maxHeight>=300.dp
        val controls=if(actions) (if(drive.paused) 140.dp else 72.dp) else 0.dp
        val contentHeight=maxHeight-controls-28.dp
        val dense=contentHeight<120.dp
        val tiny=contentHeight<200.dp || maxWidth<230.dp
        val pane=maxWidth>=480.dp && contentHeight>=480.dp
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(if(pane) 26.dp else if(dense) 6.dp else if(tiny) 8.dp else 14.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(if(pane) 24.dp else 0.dp))
                .background(if(pane) Panel else Ink).padding(if(pane) 28.dp else 0.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(if(pane) 9.dp else 6.dp).clip(RoundedCornerShape(50)).background(if(drive.running) Mint else Muted))
                    Spacer(Modifier.width(7.dp))
                    Text(if(drive.record?.demoCaseId!=null) "$appName · 데모" else appName,fontSize=if(pane) 16.sp else if(dense) 10.sp else if(tiny) 11.sp else 13.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    if(content.freshFeedback) Text(liveScoreCaption(content.score),fontSize=if(pane) 36.sp else if(dense) 10.sp else if(tiny) 11.sp else 13.sp,color=scoreColor(content.score),modifier=Modifier.testTag("mini-feedback-score"))
                }
                Spacer(Modifier.height(if(pane) 24.dp else if(dense) 4.dp else if(tiny) 6.dp else 10.dp))
                Text(content.title,fontSize=if(pane) 36.sp else if(dense) 14.sp else if(tiny) 16.sp else 21.sp,lineHeight=if(pane) 46.sp else if(dense) 18.sp else if(tiny) 20.sp else 27.sp,fontWeight=FontWeight.Bold,color=if(content.freshFeedback) Mint else White,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("mini-title"))
                if(content.body.isNotBlank()) {
                Spacer(Modifier.height(if(pane) 18.dp else if(tiny) 4.dp else 8.dp))
                Text(content.body,fontSize=if(pane) 25.sp else if(dense) 11.sp else if(tiny) 13.sp else 17.sp,lineHeight=if(pane) 37.sp else if(dense) 14.sp else if(tiny) 18.sp else 24.sp,color=White,maxLines=if(dense) 2 else 3,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("mini-feedback-text"))
                }
                if(datasetNotice!=null) {Spacer(Modifier.height(if(dense) 2.dp else 10.dp));Text("연습 참고용 점수",fontSize=if(pane) 15.sp else 10.sp,lineHeight=if(pane) 23.sp else 12.sp,color=Amber,maxLines=1,overflow=TextOverflow.Ellipsis)}
            }
            }
            if(actions) {
                Spacer(Modifier.height(12.dp))
                if(drive.paused) {Action("데모 계속",Modifier.fillMaxWidth().testTag("resume-demo"),onClick=onResume);Spacer(Modifier.height(8.dp))}
                Action("코칭 종료",Modifier.fillMaxWidth().testTag("end-drive"),secondary=true,onClick=onStop)
            }
        }
    }
}
