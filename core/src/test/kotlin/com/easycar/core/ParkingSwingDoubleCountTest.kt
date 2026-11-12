package com.easycar.core

import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

/**
 * How [DocumentCoachEngine] treats the forward "swing" before perpendicular parking and forward-in
 * parking. Parking segments open on stable REVERSE, while the D-gear yaw-bump path is active during
 * the swing; slow turns are therefore held briefly (recognition-document-1.0.3).
 */
class ParkingSwingDoubleCountTest {
    /** 10 Hz frames; yaw is left/CCW positive. [inputYawDeg] is the true rotation fed in. */
    private class Drive(private val engine:CoachEngine) {
        var timeMs=0L;private set
        var inputYawDeg=0.0;private set
        fun phase(durationMs:Long,gear:Gear,steeringDeg:Double=0.0,speed:(Double)->Double,yaw:(Double)->Double={0.0}) {
            val start=timeMs
            while(timeMs<start+durationMs) {
                val fraction=(timeMs-start).toDouble()/durationMs
                val v=speed(fraction);val w=yaw(v)
                fun <T> signal(value:T,unit:String)=Signal(value,timeMs,timeMs,unit,"SWING_TEST")
                engine.accept(VehicleFrame(timeMs,signal(v,"m/s"),signal(steeringDeg,"degree:steering-wheel"),
                    signal(gear,"gear"),null,yawRate=signal(w,"rad/s:vehicle-z-left-positive")))
                inputYawDeg+=w*.1*180/PI
                timeMs+=100
            }
        }
        fun hold(durationMs:Long,gear:Gear)=phase(durationMs,gear,speed={0.0})
    }

    private fun engine()=DocumentFixtures.engine()

    private fun describe(label:String,drive:Drive,results:List<ManeuverResult>):String {
        val text=buildString {
            append("[$label] input rotation=%.1f°\n".format(drive.inputYawDeg))
            results.forEach {append("  %s %d..%d yawDeltaDeg=%s\n".format(it.type,it.startMs,it.endMs,
                it.measurements["yawDeltaDeg"]?.let {d->"%.1f".format(d)} ?: "-"))}
        }
        println(text);return text
    }

    /**
     * Reverse perpendicular parking with a constant-curvature forward swing and the remaining
     * rotation in reverse (same CCW direction, as when the nose swings away from a slot on the
     * right and the car then backs in with right lock).
     */
    private fun reverseParking(swingMs:Long,reverseTurnMs:Long,waitInDriveMs:Long=1000):Pair<Drive,CoachEngine> {
        val engine=engine();val drive=Drive(engine)
        val curvature=.175 // rad/m: 0.35 rad/s at 2 m/s
        drive.hold(5000,Gear.DRIVE)
        drive.phase(1000,Gear.DRIVE,speed={2.0*it})
        drive.phase(3000,Gear.DRIVE,speed={2.0})
        drive.phase(swingMs,Gear.DRIVE,steeringDeg=300.0,speed={2.0},yaw={curvature*it})
        drive.phase(1000,Gear.DRIVE,steeringDeg=300.0,speed={2.0*(1-it)},yaw={curvature*it})
        drive.hold(waitInDriveMs,Gear.DRIVE)
        drive.hold(800,Gear.REVERSE)
        drive.phase(500,Gear.REVERSE,steeringDeg=-400.0,speed={1.0*it},yaw={.35*it})
        drive.phase(reverseTurnMs,Gear.REVERSE,steeringDeg=-400.0,speed={1.0},yaw={.35*it})
        drive.phase(3000,Gear.REVERSE,speed={1.0})
        drive.phase(500,Gear.REVERSE,speed={1.0*(1-it)})
        drive.hold(500,Gear.REVERSE)
        drive.hold(1500,Gear.PARK)
        return drive to engine
    }

    private val turns=setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)

    /**
     * Scenario A (fixed in recognition-document-1.0.3). A ~65° forward swing in D below 10 km/h used to
     * be emitted as LEFT and then integrated again into the T_PARK. The slow turn is now held and
     * absorbed when R follows, so the rotation is reported once.
     */
    @Test fun forwardSwingBeforeReverseParkingIsReportedOnlyAsParking() {
        val (drive,engine)=reverseParking(swingMs=2800,reverseTurnMs=1100)
        val results=engine.results
        describe("A swing≈65°",drive,results)
        assertTrue(results.none {it.type in turns})
        val park=results.single {isParking(it.type)}
        assertEquals(ManeuverType.T_PARK,park.type)
        assertTrue(park.measurements.getValue("yawDeltaDeg") in 85.0..95.0)
    }

    /** Scenario B, control: a ~40° swing never reached turnMinDeg; the ~90° is reported once as T_PARK. */
    @Test fun shortSwingBeforeReverseParkingIsReportedOnlyAsParking() {
        val (drive,engine)=reverseParking(swingMs=1500,reverseTurnMs=2300)
        val results=engine.results
        describe("B swing≈40°",drive,results)
        assertTrue(results.none {it.type in turns})
        val park=results.single {it.type==ManeuverType.T_PARK}
        assertTrue(park.measurements.getValue("yawDeltaDeg") in 85.0..95.0)
    }

    /**
     * Scenario C (fixed in 1.0.3). Forward-in perpendicular parking (slow ~90° bump, stop, P, never R)
     * used to be a LEFT turn and no parking. It is now one parking episode; its type is not classified.
     */
    @Test fun forwardInPerpendicularParkingIsAParkingEpisodeNotATurn() {
        val engine=engine();val drive=Drive(engine)
        drive.hold(5000,Gear.DRIVE)
        drive.phase(1000,Gear.DRIVE,speed={1.5*it})
        drive.phase(2000,Gear.DRIVE,speed={1.5})
        drive.phase(4500,Gear.DRIVE,steeringDeg=350.0,speed={1.5},yaw={.35})
        drive.phase(1000,Gear.DRIVE,speed={1.5*(1-it)})
        drive.hold(1000,Gear.DRIVE)
        drive.hold(1500,Gear.PARK)
        val results=engine.results
        describe("C forward-in",drive,results)
        assertTrue(results.none {it.type in turns})
        val park=results.single {isParking(it.type)}
        assertEquals(ManeuverType.PARKING_UNKNOWN,park.type)
        assertEquals(1.0,park.measurements.getValue("forwardIn"),0.0)
        assertTrue(park.measurements.getValue("yawDeltaDeg") in 80.0..95.0)
        assertTrue(park.holdReasons.any {it.contains("전진으로 주차")})
    }

    /**
     * Turning the wheel the other way while stopped in R (here +400° held 1.5 s before moving with −400°)
     * must not read as the two-sign S pattern of parallel parking (fixed in 1.0.3).
     */
    @Test fun stationarySteeringInReverseDoesNotLookLikeParallelParking() {
        val engine=engine();val drive=Drive(engine)
        drive.hold(5000,Gear.DRIVE)
        drive.phase(1000,Gear.DRIVE,speed={1.0*it})
        drive.phase(2000,Gear.DRIVE,steeringDeg=400.0,speed={1.0},yaw={.12})
        drive.phase(1000,Gear.DRIVE,steeringDeg=400.0,speed={1.0*(1-it)},yaw={.12*it})
        drive.hold(800,Gear.DRIVE)
        drive.phase(1500,Gear.REVERSE,steeringDeg=400.0,speed={0.0})
        drive.phase(6000,Gear.REVERSE,steeringDeg=-400.0,speed={1.0},yaw={.2})
        drive.phase(2000,Gear.REVERSE,speed={1.0})
        drive.hold(500,Gear.REVERSE)
        drive.hold(1500,Gear.PARK)
        val results=engine.results
        describe("F stationary counter-steer in R",drive,results)
        assertEquals(ManeuverType.T_PARK,results.single {isParking(it.type)}.type)
    }

    /**
     * Waiting in D after the swing (here 6 s, longer than the 4 s that completes a STOP) must not cut the
     * hold short: the STOP queues behind the held turn and the swing is still absorbed by the R that follows.
     */
    @Test fun waitingInDriveBeforeReverseStillAbsorbsTheSwing() {
        val (drive,engine)=reverseParking(swingMs=2800,reverseTurnMs=1100,waitInDriveMs=6000)
        val results=engine.results
        describe("G swing, 6 s wait, R",drive,results)
        assertTrue(results.none {it.type in turns})
        assertEquals(ManeuverType.T_PARK,results.single {isParking(it.type)}.type)
        assertTrue(results.indexOfFirst {it.type==ManeuverType.STOP}<results.indexOfFirst {isParking(it.type)})
    }

    /** Same for a forward-in park where the driver waits in D for 5 s before shifting to P. */
    @Test fun waitingInDriveBeforeParkStillMakesAForwardPark() {
        val engine=engine();val drive=Drive(engine)
        drive.hold(5000,Gear.DRIVE)
        drive.phase(1000,Gear.DRIVE,speed={2.0*it})
        drive.phase(3500,Gear.DRIVE,steeringDeg=350.0,speed={2.0},yaw={.45})
        drive.phase(1000,Gear.DRIVE,speed={2.0*(1-it)})
        drive.hold(5000,Gear.DRIVE)
        drive.hold(1500,Gear.PARK)
        val results=engine.results
        describe("H forward-in, 5 s wait, P",drive,results)
        assertTrue(results.none {it.type in turns})
        assertEquals(1.0,results.single {isParking(it.type)}.measurements.getValue("forwardIn"),0.0)
    }

    /** A slow turn that is not followed by R or P is still a turn, emitted once when the hold expires. */
    @Test fun slowTurnThatDrivesOnIsEmittedAfterTheHold() {
        val engine=engine();val drive=Drive(engine)
        drive.hold(5000,Gear.DRIVE)
        drive.phase(1000,Gear.DRIVE,speed={1.5*it})
        drive.phase(4500,Gear.DRIVE,steeringDeg=350.0,speed={1.5},yaw={.35})
        drive.phase(5000,Gear.DRIVE,speed={1.5})
        assertTrue("held during the window",engine.results.none {it.type in turns})
        drive.phase(4000,Gear.DRIVE,speed={1.5})
        val results=engine.results
        describe("D slow turn, drive on",drive,results)
        assertEquals(ManeuverType.LEFT,results.single {it.type in turns}.type)
        assertTrue(results.none {isParking(it.type)})
    }

    /**
     * A STOP completed during the hold queues behind the held turn; once the hold expires in D both are
     * emitted, turn first, so results keep the order the manoeuvres happened.
     */
    @Test fun heldTurnIsReportedBeforeTheStopThatFollowsIt() {
        val engine=engine();val drive=Drive(engine)
        drive.hold(5000,Gear.DRIVE)
        drive.phase(1000,Gear.DRIVE,speed={2.0*it})
        drive.phase(3500,Gear.DRIVE,steeringDeg=300.0,speed={2.0},yaw={.45})
        drive.phase(1500,Gear.DRIVE,speed={2.0*(1-it)})
        drive.hold(6000,Gear.DRIVE)
        assertTrue("both still held 6 s after the turn",engine.results.none {it.type==ManeuverType.LEFT || it.type==ManeuverType.STOP})
        drive.hold(4000,Gear.DRIVE)
        val results=engine.results
        describe("E slow turn, stop in D",drive,results)
        val turn=results.indexOfFirst {it.type==ManeuverType.LEFT};val stop=results.indexOfFirst {it.type==ManeuverType.STOP}
        assertTrue(turn>=0 && stop>turn)
        assertTrue(results.none {isParking(it.type)})
    }
}
