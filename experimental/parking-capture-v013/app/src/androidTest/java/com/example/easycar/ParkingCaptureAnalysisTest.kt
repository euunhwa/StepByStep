package com.example.easycar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easycar.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParkingCaptureAnalysisTest {
    @Test fun actualDecodedIntrusionAndBlankClipsCannotReceiveAnInsidePassingScore()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<EasyCarApplication>();app.ready.await()
        val settings=app.repository.settings.first();val rows=mutableListOf<Any>()
        try {
            app.repository.updateSettings(settings.copy(consent=true,muted=true))
            for(kind in listOf("intrusion","blank")) {
                val previous=app.parking.state.value.finishedId
                withContext(Dispatchers.Main) {app.parking.runExample(kind,1)}
                val done=withTimeout(15000) {app.parking.state.first {!it.running && it.finishedId!=null && it.finishedId!=previous}}
                val record=app.repository.records.value.single {it.id==done.finishedId};val outcome=record.parkingCapture!!.outcome!!
                try {
                    assertTrue(record.parkingCapture!!.videoSaved)
                    if(kind=="intrusion") {assertEquals("OUTSIDE_SLOT",outcome.status);assertTrue(outcome.score!!<=59);assertFalse(outcome.placement!!.inside!!)}
                    else {assertNull(outcome.score);assertEquals("HELD",outcome.status);assertTrue(outcome.holds.isNotEmpty())}
                    rows+=mapOf("case" to kind,"outcome" to outcome)
                } finally {app.repository.delete(record.id)}
            }
            File(app.filesDir,"validation-parking-capture-analysis-v013.json").writeText(Gson().toJson(rows))
        } finally {app.repository.updateSettings(settings)}
    }
}
