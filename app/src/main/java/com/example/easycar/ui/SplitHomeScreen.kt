package com.example.easycar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.easycar.runtime.PlatformState

/** The split home keeps the primary action; raw signal panels and history stay in their full views. */
@Composable internal fun SplitHomeScreen(platform:PlatformState,onStart:()->Unit,onRetry:()->Unit) {
    val connected=platform.vehicleConnected
    BoxWithConstraints(Modifier.fillMaxSize().testTag("split-home")) {
        if(maxHeight<480.dp) Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center) {
            Text(if(connected) "차량 연결됨" else "차량 연결 확인 필요",fontSize=16.sp,lineHeight=23.sp,color=if(connected) Mint else Amber)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Action("코칭 시작",Modifier.weight(1f).testTag("start-coaching"),onClick=onStart)
                if(!connected) Action("연결 확인",Modifier.weight(1f),secondary=true,onClick=onRetry)
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center) {
            Text("운전에 집중하세요",fontSize=32.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text("한 번 시작하면 동작이 끝날 때 짧게 알려드려요.",fontSize=21.sp,lineHeight=31.sp,color=Muted)
            Spacer(Modifier.height(28.dp))
            CardPanel(Modifier.fillMaxWidth()) {
                Text(if(connected) "차량 연결됨" else "차량 연결 확인 필요",fontSize=21.sp,color=if(connected) White else Amber)
                Spacer(Modifier.height(24.dp))
                Action("코칭 시작",Modifier.fillMaxWidth().testTag("start-coaching"),onClick=onStart)
                if(!connected) {Spacer(Modifier.height(10.dp));Action("연결 다시 확인",Modifier.fillMaxWidth(),secondary=true,onClick=onRetry)}
            }
        }
    }
}
