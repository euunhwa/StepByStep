package com.easycar.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DocumentIdleWindowTest {
    private val assets=File("../app/src/main/assets")
    private val profile=ReferenceProfile.fromJson(File(assets,"profiles/document-derived-v1.json").readText())
    private val policy=EvaluationPolicy.fromDocumentJson(
        File(assets,"datasets/document-v1/recognition.json").readText(),
        File(assets,"datasets/document-v1/evaluation.json").readText())

    private class Input(private val engine:CoachEngine) {
        var timeMs=0L;private set
        fun feed(speed:Double,dt:Long=101,connected:Boolean=true) {
            fun <T> signal(value:T,unit:String)=Signal(value,timeMs,timeMs,unit,"JITTER_TEST")
            engine.accept(VehicleFrame(timeMs,signal(speed,"m/s"),signal(0.0,"degree:steering-wheel"),
                signal(Gear.DRIVE,"gear"),null,connected,yawRate=signal(0.0,"rad/s:vehicle-z-left-positive")))
            timeMs+=dt
        }
    }

    private fun engine()=CoachEngine(profile,PracticeContext(null,null,dataSource="JITTER_TEST"),evaluationPolicy=policy)

    @Test fun constant101MillisecondPollingRecognizesIdleThenAScoredStart() {
        val engine=engine();val input=Input(engine)
        while(input.timeMs<15000) {
            val speed=if(input.timeMs<5000) 0.0 else ((input.timeMs-5000)/1000.0*.5).coerceAtMost(3.0)
            input.feed(speed)
        }
        val start=engine.results.single {it.type==ManeuverType.START}
        assertEquals(100,start.score)
        assertTrue(start.holdReasons.isEmpty())
    }

    @Test fun variablePollingDoesNotRequireTwoSamplesExactlyFourSecondsApart() {
        val engine=engine();val input=Input(engine);val intervals=listOf(100L,101L,103L,107L,102L)
        var index=0
        while(input.timeMs<15000) {
            val speed=if(input.timeMs<5000) 0.0 else ((input.timeMs-5000)/1000.0*.5).coerceAtMost(3.0)
            input.feed(speed,intervals[index++%intervals.size])
        }
        assertEquals(1,engine.results.count {it.type==ManeuverType.START})
        assertEquals(100,engine.results.single {it.type==ManeuverType.START}.score)
    }

    @Test fun shortStationaryObservationDoesNotArmAStart() {
        val engine=engine();val input=Input(engine)
        repeat(30) {input.feed(0.0)}
        repeat(100) {input.feed(1.0)}
        assertTrue(engine.results.none {it.type==ManeuverType.START})
    }

    @Test fun disconnectedInputCannotCombineTwoShortIdleWindows() {
        val engine=engine();val input=Input(engine)
        repeat(30) {input.feed(0.0)}
        input.feed(0.0,connected=false)
        repeat(30) {input.feed(0.0)}
        repeat(100) {input.feed(1.0)}
        assertTrue(engine.results.none {it.type==ManeuverType.START})
    }
}
