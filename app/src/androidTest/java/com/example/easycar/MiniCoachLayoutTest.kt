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
import com.example.easycar.runtime.DriveState
import com.example.easycar.ui.MiniCoachScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniCoachLayoutTest {
    @get:Rule val ui=createComposeRule()
    @Test fun feedbackBoundsFitSmallAndMinimumWindowSizes() {
        val size=mutableStateOf(240 to 180)
        val now=android.os.SystemClock.elapsedRealtime()
        val f=FeedbackReceipt("left",ManeuverType.LEFT,"좌회전 완료","좌회전 구간을 기록했어요. 필요한 정보가 부족해 점수는 보류했어요.",null,0,now,VoiceState.WAITING,null)
        ui.setContent {Box(Modifier.size(size.value.first.dp,size.value.second.dp)) {MiniCoachScreen(DriveState(running=true,feedback=f),true,{},"평가용 데이터셋은 임의로 만든 자료이며 개선이 필요합니다.")}}
        for(dimensions in listOf(240 to 180,180 to 100,840 to 1000)) {
            ui.runOnIdle {size.value=dimensions}
            ui.onNodeWithTag("mini-title").assertIsDisplayed();ui.onNodeWithTag("mini-feedback-text").assertIsDisplayed()
            val view=ui.onNodeWithTag("mini-coach").getUnclippedBoundsInRoot()
            val body=ui.onNodeWithTag("mini-feedback-text").getUnclippedBoundsInRoot()
            assertTrue("text must stay within $dimensions",body.top>=view.top && body.bottom<=view.bottom && body.left>=view.left && body.right<=view.right)
            ui.onNodeWithTag("end-drive").assertDoesNotExist()
        }
    }
}
