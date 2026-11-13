package com.example.easycar

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.easycar.runtime.CoachingService
import com.example.easycar.runtime.MiniWindowState
import com.example.easycar.ui.EasyCarApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity:ComponentActivity() {
    private val app get()=application as EasyCarApplication
    val miniWindow=MutableStateFlow(MiniWindowState())
    private var autoSmallWindow=true
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        miniWindow.value=MiniWindowState(supported=packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),active=isInPictureInPictureMode)
        val model=ViewModelProvider(this)[EasyCarViewModel::class.java]
        setContent { EasyCarApp(model,miniWindow,this::showSmallWindow) }
        onBackPressedDispatcher.addCallback(this) {
            if(app.runtime.state.value.running) {
                if(!showSmallWindow()) moveTaskToBack(true)
            } else {isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true}
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.runtime.state,app.repository.settings) {drive,settings->Triple(drive.running,drive.record?.id,settings.smallWindowOnStart)}
                    .distinctUntilChanged().collect {(running,id,automatic)->
                        autoSmallWindow=automatic
                        updatePipParams()
                        if(running && id!=null && automatic && app.miniWindowAttemptedSessionId!=id) {
                            app.miniWindowAttemptedSessionId=id
                            showSmallWindow()
                        }
                    }
            }
        }
    }
    private fun pipParams():PictureInPictureParams {
        val running=app.runtime.state.value.running
        val b=PictureInPictureParams.Builder().setAspectRatio(Rational(4,3))
        val bounds=Rect();if(window.decorView.getGlobalVisibleRect(bounds) && !bounds.isEmpty) b.setSourceRectHint(bounds)
        if(running) {
            val stop=PendingIntent.getService(this,25,Intent(this,CoachingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.setActions(listOf(RemoteAction(Icon.createWithResource(this,R.drawable.ic_coaching_stop),"코칭 종료","진행 중인 코칭을 종료합니다",stop)))
        } else b.setActions(emptyList())
        if(Build.VERSION.SDK_INT>=31) b.setAutoEnterEnabled(running && autoSmallWindow).setSeamlessResizeEnabled(false)
        if(Build.VERSION.SDK_INT>=33) b.setTitle("${getString(R.string.app_name)} 자동 코칭").setSubtitle(if(running) "동작 종료 후 짧은 피드백" else "코칭 완료")
        return b.build()
    }
    private fun updatePipParams() {
        if(miniWindow.value.supported) runCatching {setPictureInPictureParams(pipParams())}
    }
    fun showSmallWindow():Boolean {
        if(!app.runtime.state.value.running) return false
        if(isInPictureInPictureMode || miniWindow.value.entering) return true
        if(!miniWindow.value.supported) {
            miniWindow.value=miniWindow.value.copy(message="이 기기는 자동 작은 창을 지원하지 않아요. 오른쪽 점 세 개 핸들을 왼쪽으로 끌어 내비 옆에 배치하세요.")
            return false
        }
        miniWindow.value=miniWindow.value.copy(entering=true,message=null)
        window.decorView.postOnAnimation {
            if(isFinishing || isDestroyed || !app.runtime.state.value.running) {miniWindow.value=miniWindow.value.copy(entering=false);return@postOnAnimation}
            val entered=runCatching {enterPictureInPictureMode(pipParams())}.getOrDefault(false)
            if(!entered) miniWindow.value=miniWindow.value.copy(entering=false,message="작은 창 전환이 허용되지 않았어요. 코칭과 음성은 계속됩니다.")
        }
        return true
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if(Build.VERSION.SDK_INT<31 && autoSmallWindow && app.runtime.state.value.running) showSmallWindow()
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode:Boolean,newConfig:Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig)
        miniWindow.value=miniWindow.value.copy(active=isInPictureInPictureMode,entering=false)
    }
    override fun onStart() { super.onStart();app.also { it.visible=true;it.bridge.start() } }
    override fun onStop() { app.visible=false;if(!app.runtime.state.value.running) app.bridge.stop();super.onStop() }
}
