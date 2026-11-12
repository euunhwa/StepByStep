package com.easycar.core

import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

/** recognition-document-1.0.2: turns paused by slowing, and leaving a parking space. */
class TurnPauseAndDepartureTest {
    private val yaw90=(PI/2)/6.0 // 90° over 6 s

    private inner class Drive {
        val engine=DocumentFixtures.engine(PracticeContext(null,null,dataSource="TURN_PAUSE_TEST"))
        var t=0L;private set
        /** Each sample is 100 ms; [at] maps seconds into the segment to (speed, steering, yaw). */
        fun seg(seconds:Double,gear:Gear,at:(Double)->Triple<Double,Double,Double>) {
            repeat((seconds*10).toInt()) {i->
                val (v,steer,yaw)=at(i/10.0)
                fun <T> s(value:T,unit:String)=Signal(value,t,t,unit,"TURN_PAUSE_TEST")
                engine.accept(VehicleFrame(t,s(v,"m/s"),s(steer,"degree:steering-wheel"),s(gear,"gear"),null,
                    yawRate=s(yaw,"rad/s:vehicle-z-left-positive")))
                t+=100
            }
        }
        fun idle(seconds:Double,gear:Gear=Gear.DRIVE)=seg(seconds,gear) {Triple(0.0,0.0,0.0)}
        fun accelerate(seconds:Double,to:Double)=seg(seconds,Gear.DRIVE) {s->Triple(minOf(to,1.5*s),0.0,0.0)}
        fun brake(seconds:Double,from:Double)=seg(seconds,Gear.DRIVE) {s->Triple(maxOf(0.0,from-1.5*s),0.0,0.0)}
        fun results()=engine.finish(null,false)
    }

    private fun drive()=Drive()

    @Test fun leftTurnPausedToYieldIsStillOneScoredLeftTurn() {
        val d=drive()
        d.idle(5.0);d.accelerate(4.0,6.0)
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,90.0,yaw90)}
        d.seg(1.0,Gear.DRIVE) {Triple(0.5,90.0,0.5/6*yaw90)} // slows below movingMps mid-turn
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,90.0,yaw90)}
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,0.0,0.0)};d.brake(4.0,6.0);d.idle(6.0)
        val turns=d.results().filter {it.type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.UNKNOWN)}
        assertEquals(listOf(ManeuverType.LEFT),turns.map {it.type})
        assertNotNull(turns.single().score)
        assertTrue(turns.single().measurements["yawDeltaDeg"]!! in 80.0..100.0)
    }

    @Test fun fullStopWhileYieldingWithinPauseWindowStillMerges() {
        val d=drive()
        d.idle(5.0);d.accelerate(4.0,6.0)
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,90.0,yaw90)}
        d.seg(4.0,Gear.DRIVE) {Triple(0.0,90.0,0.0)} // full stop for pedestrians
        d.seg(3.0,Gear.DRIVE) {s->Triple(minOf(6.0,1.0+2*s),90.0,minOf(6.0,1.0+2*s)/6*yaw90)}
        d.seg(2.0,Gear.DRIVE) {Triple(6.0,90.0,yaw90)}
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,0.0,0.0)}
        assertEquals(1,d.results().count {it.type==ManeuverType.LEFT})
    }

    @Test fun smallBumpWithoutContinuationIsStillDiscarded() {
        val d=drive()
        d.idle(5.0);d.accelerate(4.0,6.0)
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,30.0,yaw90)} // ~45°, then straight for longer than the pause window
        d.seg(15.0,Gear.DRIVE) {Triple(6.0,0.0,0.0)}
        assertTrue(d.results().none {it.type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.UNKNOWN)})
    }

    @Test fun oppositeSignPartialsDoNotMergeIntoATurn() {
        val d=drive()
        d.idle(5.0);d.accelerate(4.0,6.0)
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,90.0,yaw90)}
        d.seg(1.0,Gear.DRIVE) {Triple(6.0,0.0,0.0)}
        d.seg(3.0,Gear.DRIVE) {Triple(6.0,-90.0,-yaw90)}
        d.seg(15.0,Gear.DRIVE) {Triple(6.0,0.0,0.0)}
        assertTrue(d.results().none {it.type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT)})
    }

    @Test fun completedTurnFollowedByStopIsReportedWithoutWaitingForThePauseWindow() {
        val d=drive()
        d.idle(5.0);d.accelerate(4.0,6.0)
        d.seg(6.0,Gear.DRIVE) {Triple(6.0,90.0,yaw90)}
        val before=d.engine.results.size
        d.seg(1.0,Gear.DRIVE) {Triple(6.0,0.0,0.0)}
        assertEquals(ManeuverType.LEFT,d.engine.results.drop(before).single().type)
    }

    @Test fun leavingAParkingSpaceRecordsNoParkingResult() {
        val d=drive()
        d.idle(5.0,Gear.PARK);d.idle(1.0,Gear.REVERSE)
        d.seg(4.0,Gear.REVERSE) {Triple(-1.0,-200.0,-0.1)}
        d.idle(1.0,Gear.REVERSE);d.idle(1.0)
        d.seg(12.0,Gear.DRIVE) {s->Triple(minOf(5.0,1.0*s),0.0,0.0)}
        assertTrue(d.results().none {isParking(it.type)})
    }

    @Test fun parkingAttemptFromDriveThatDrivesAwayIsStillHeld() {
        val d=drive()
        d.idle(5.0);d.accelerate(2.0,2.0);d.brake(2.0,2.0);d.idle(1.0)
        d.idle(1.0,Gear.REVERSE)
        d.seg(4.0,Gear.REVERSE) {Triple(-1.0,-200.0,-0.1)}
        d.idle(1.0,Gear.REVERSE);d.idle(1.0)
        d.seg(12.0,Gear.DRIVE) {s->Triple(minOf(5.0,1.0*s),0.0,0.0)}
        val parking=d.results().filter {isParking(it.type)}
        assertEquals(listOf(ManeuverType.PARKING_UNKNOWN),parking.map {it.type})
        assertNull(parking.single().score)
    }
}
