package com.example.easycar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycar.core.*
import kotlinx.coroutines.flow.StateFlow

@Composable fun LiveSignalPanel(stream:StateFlow<VehicleFrame?>) {
    val frame by stream.collectAsStateWithLifecycle()
    val f=frame
    fun <T> fresh(s:Signal<T>?)=f!=null && s?.usable(f.timeMs,750)==true
    val values=listOf(
        "속도" to if(fresh(f?.speed)) "${formatValue(kotlin.math.abs(f!!.speed!!.value)*3.6)} km/h" else "—",
        "기어" to if(fresh(f?.gear)) when(f!!.gear!!.value) {Gear.PARK->"P";Gear.REVERSE->"R";Gear.NEUTRAL->"N";Gear.DRIVE->"D";else->"—"} else "—",
        "핸들 각도" to if(fresh(f?.steering)) "${formatValue(f!!.steering!!.value)}°" else "—",
        "진행 방향" to if(fresh(f?.heading)) "${formatValue(f!!.heading!!.value)}°" else "—")
    CardPanel(Modifier.fillMaxWidth().testTag("live-signal-panel")) {
        Text("지금 들어오는 차량 신호",fontSize=23.sp,fontWeight=FontWeight.Bold)
        for(row in values.chunked(2)) Row(Modifier.fillMaxWidth().padding(top=18.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            row.forEach {(label,value)->Column(Modifier.weight(1f)) {Text(label,fontSize=15.sp,color=GuideMuted);Text(value,fontSize=23.sp,color=GuideForest,modifier=Modifier.padding(top=5.dp))}}
        }
    }
}
fun scoreCaption(score:Int?,kind:String?)=score?.let {if(kind=="PROVISIONAL") "임시 ${it}점" else "${it}점"} ?: "점수 보류"
