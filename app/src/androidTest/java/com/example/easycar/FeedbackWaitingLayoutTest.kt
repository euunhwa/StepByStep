package com.example.easycar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.example.easycar.runtime.*
import com.example.easycar.ui.DriveScreen
import com.example.easycar.ui.MiniCoachScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackWaitingLayoutTest {
    @get:Rule val ui=createComposeRule()
    @Test fun fullCompactAndMiniViewsAllReturnToWaiting() {
        val size=mutableStateOf(1280 to 800)
        val f=FeedbackReceipt("stop",ManeuverType.STOP,"정지 완료","이전 정지 피드백",100,0,android.os.SystemClock.elapsedRealtime(),VoiceState.MUTED,null)
        val state=mutableStateOf(DriveState(running=true,feedback=f,feedbackHistory=listOf(f)))
        ui.setContent {Box(Modifier.size(size.value.first.dp,size.value.second.dp)) {
            if(size.value.first<900) MiniCoachScreen(state.value,false,{})
            else DriveScreen(state.value,false,PlatformState(),{},{})
        }}
        for(dimensions in listOf(1280 to 800,1280 to 360,840 to 1000)) {
            ui.runOnIdle {size.value=dimensions;state.value=state.value.copy(feedback=f.copy(publishedAtMs=android.os.SystemClock.elapsedRealtime()))}
            ui.onNodeWithText("정지 완료").assertExists()
            ui.runOnIdle {state.value=state.value.copy(feedback=null)}
            ui.onNodeWithText("정지 완료").assertDoesNotExist()
            ui.onNodeWithText("다음 동작 대기 중").assertIsDisplayed()
        }
    }
}
