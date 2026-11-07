package com.example.easycar.runtime

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.easycar.EasyCarApplication
import com.example.easycar.MainActivity
import com.example.easycar.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CoachingService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val app get()=application as EasyCarApplication
    private var notificationJob:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    private fun notification(title:String,text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CoachingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"coaching").setSmallIcon(R.drawable.ic_easycar).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_coaching_stop,"코칭 종료",stop).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action in setOf("stop","cancel")) { scope.launch { app.runtime.stop(intent?.action=="cancel");stopSelf(startId) };return START_NOT_STICKY }
        if(intent?.action=="pause") {app.runtime.pauseDemo();return START_NOT_STICKY}
        if(app.runtime.state.value.running) return START_NOT_STICKY
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("coaching","EasyCar 코칭",NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this,110,notification("EasyCar · 일곱 동작 자동 코칭","다른 앱을 사용해도 코칭과 음성을 계속합니다"),if(android.os.Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        app.runtime.start(scope,intent?.getStringExtra("demo"),intent?.getIntExtra("speed",1) ?: 1,intent?.getStringExtra("resume")) { stopSelf(startId) }
        notificationJob?.cancel()
        notificationJob=scope.launch {
            app.runtime.state.map {state->state.feedback?.let {"EasyCar · 최근 ${it.title}" to it.text} ?: ("EasyCar · 자동 코칭 중" to "다른 앱을 사용해도 코칭과 음성을 계속합니다")}
                .distinctUntilChanged().collect {(title,text)->runCatching {nm.notify(110,notification(title,text))}}
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
}
