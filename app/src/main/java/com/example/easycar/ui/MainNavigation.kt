package com.example.easycar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.easycar.BuildConfig

private val mainItems=listOf(Triple("home","⌂","홈"),Triple("records","▤","기록"),Triple("settings","⚙","설정"))

@Composable internal fun MainNavigation(page:String,disabled:Boolean,compact:Boolean=false,onSelect:(String)->Unit) {
    fun selected(id:String)=page==id || (id=="records" && page in listOf("report","video","record-query"))
    if(compact) Row(Modifier.fillMaxWidth().testTag("main-navigation"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        mainItems.forEach {(id,_,title)->
            TextButton(onClick={onSelect(id)},enabled=!disabled,modifier=Modifier.weight(1f).heightIn(min=52.dp).testTag("nav-$id"),
                shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.textButtonColors(containerColor=if(selected(id)) PanelLight else Ink,contentColor=if(selected(id)) Mint else Muted)) {Text(title,fontSize=18.sp,fontWeight=FontWeight.Medium)}
        }
    } else Column(Modifier.width(106.dp).fillMaxHeight().background(androidx.compose.ui.graphics.Color(0xff0c151c)).verticalScroll(rememberScrollState()).padding(vertical=30.dp).testTag("main-navigation"),horizontalAlignment=Alignment.CenterHorizontally) {
        Text("e",fontSize=42.sp,fontWeight=FontWeight.Black,color=Mint)
        Spacer(Modifier.height(40.dp))
        mainItems.forEach {(id,icon,title)->
            Column(Modifier.padding(vertical=8.dp).size(82.dp).clip(RoundedCornerShape(20.dp)).background(if(selected(id)) PanelLight else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(enabled=!disabled) {onSelect(id)}.testTag("nav-$id"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Text(icon,fontSize=28.sp,color=if(selected(id)) Mint else Muted)
                Text(title,fontSize=16.sp,color=if(selected(id)) Mint else Muted)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(if(BuildConfig.FLAVOR=="pleos") "PLEOS" else "DEMO",fontSize=12.sp,color=Muted,lineHeight=18.sp)
    }
}
