package com.example.easycar.parking

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.easycar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ParkingRecordingService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val app get()=application as EasyCarApplication
    private var observer:Job?=null
    override fun onBind(intent:Intent?)=null
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,40,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,41,Intent(this,ParkingRecordingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"parking-recording").setSmallIcon(R.drawable.ic_easycar).setContentTitle("EasyCar · 주차 화면 녹화")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(R.drawable.ic_coaching_stop,"녹화 종료",stop).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {app.scope.launch {app.parking.finish("사용자가 녹화를 종료했어요.");stopSelf()};return START_NOT_STICKY}
        if(app.parking.state.value.running) return START_NOT_STICKY
        val data=if(android.os.Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("projectionData",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("projectionData")
        val code=intent?.getIntExtra("projectionCode",Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if(code!=Activity.RESULT_OK || data==null) {stopSelf();return START_NOT_STICKY}
        try {
            val nm=getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("parking-recording","주차 화면 녹화",NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this,140,notification("녹화를 준비하고 있어요"),if(android.os.Build.VERSION.SDK_INT>=29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            scope.launch {app.parking.start(code,data);if(!app.parking.state.value.running) stopSelf()}
            observer=scope.launch {app.parking.state.map {Triple(it.running,it.processing,it.count)}.distinctUntilChanged().collect {(running,processing,count)->
                if(!running && app.parking.state.value.finishedId!=null) stopSelf()
                else nm.notify(140,notification(if(processing) "영상 저장 중" else "R→D $count 회 · 이동 후 P단 정차로 자동 완료"))
            }}
        } catch(e:Exception) {app.scope.launch {app.parking.finish("녹화 서비스를 시작하지 못했어요: ${e.message}")};stopSelf()}
        return START_NOT_STICKY
    }
    override fun onDestroy() {observer?.cancel();scope.cancel();if(app.parking.state.value.running && !app.parking.state.value.processing) app.scope.launch {app.parking.finish("녹화 서비스가 종료됐어요.")};super.onDestroy()}
}
