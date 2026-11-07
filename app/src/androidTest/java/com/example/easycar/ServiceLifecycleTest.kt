package com.example.easycar

import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.easycar.data.UserSettings
import com.example.easycar.runtime.CoachingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceLifecycleTest {
    private fun until(message:String,timeout:Long=30000,test:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!test() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
        assertTrue(message,test())
    }
    @Test fun backgroundAndActivityRecreationKeepSingleSession() {
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>()
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,smallWindowOnStart=false,muted=true))}
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val previous=app.runtime.state.value.record?.id
            scenario.onActivity {ContextCompat.startForegroundService(it,Intent(it,CoachingService::class.java).putExtra("demo","park-t_park-3-normal").putExtra("speed",2))}
            until("세션 시작") {app.runtime.state.value.running && app.runtime.state.value.record?.id!=null && app.runtime.state.value.record?.id!=previous}
            val id=app.runtime.state.value.record!!.id
            until("신호 입력 시작") {(app.runtime.state.value.record?.progressIndex ?: 0)>12}
            val before=app.runtime.state.value.record!!.progressIndex
            scenario.moveToState(Lifecycle.State.CREATED)
            until("화면이 없어도 분석 진행") {(app.runtime.state.value.record?.progressIndex ?: 0)>before+15}
            scenario.moveToState(Lifecycle.State.RESUMED);scenario.recreate()
            assertEquals(id,app.runtime.state.value.record?.id)
            until("원래 세션 완료") {app.runtime.state.value.finishedId==id}
            val saved=app.repository.records.value.filter {it.id==id}
            assertEquals(1,saved.size)
            assertEquals(saved.single().results.size,saved.single().results.distinctBy {it.id}.size)
            assertEquals(3,saved.single().results.single {com.easycar.core.isParking(it.type)}.reverseToDriveCount)
        }
        runBlocking {app.repository.updateSettings(UserSettings(consent=true,smallWindowOnStart=false,muted=false))}
    }
}
