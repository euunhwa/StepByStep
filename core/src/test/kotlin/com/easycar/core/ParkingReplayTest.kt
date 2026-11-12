package com.easycar.core

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ParkingReplayTest {
    private val spec=VehicleSpec.GRANDEUR_GN7_PE
    private val reconstructor=TrajectoryReconstructor(spec)

    private fun frame(t:Long,speed:Double?,gear:Gear,steering:Double?=0.0,yaw:Double?=null):VehicleFrame {
        fun <T> signal(value:T,unit:String)=Signal(value,t,t,unit,"TEST")
        return VehicleFrame(t,speed?.let {signal(it,"m/s")},steering?.let {signal(it,"degree:steering-wheel")},signal(gear,"gear"),null,
            yawRate=yaw?.let {signal(it,"rad/s:vehicle-z-left-positive")})
    }
    private fun run(fromMs:Long,durationMs:Long,speed:Double,gear:Gear,steering:Double=0.0,yaw:Double?=null)=
        (0..durationMs step 100).map {frame(fromMs+it,speed,gear,steering,yaw)}

    /**
     * Drive a reference plan as recorded frames, one per plan step at 20 Hz, with speed and steering taken
     * from that step. Where the step type changes, an extra frame 1 ms later carries the new values so the
     * reconstructor's averaging across the boundary covers 1 ms instead of a whole step.
     */
    private fun framesFor(plan:List<PlanPose>):List<VehicleFrame> {
        fun stepFrame(t:Long,k:Int):VehicleFrame {
            val a=plan[k-1];val b=plan[k]
            return frame(t,hypot(b.xM-a.xM,b.yM-a.yM)/.05,if(b.reverse) Gear.REVERSE else Gear.DRIVE,spec.steeringDegFor(b.curvature))
        }
        val out=mutableListOf(stepFrame(0,1))
        for(k in 1 until plan.size) {
            if(k>1 && (plan[k].reverse!=plan[k-1].reverse || plan[k].curvature!=plan[k-1].curvature))
                out+=stepFrame(50L*(k-1)+1,k)
            out+=stepFrame(50L*k,k)
        }
        return out
    }

    @Test fun grandeurSpecConvertsTheOuterWheelRadiusToARearAxleRadiusAndLockAngle() {
        assertEquals(3.976,spec.minRearAxleRadiusM,.005)
        assertEquals(36.06,spec.maxRoadWheelRad*180/PI,.05)
        // The emulator's ±540° wheel range at ratio 15 reaches the same lock angle.
        assertEquals(540.0/spec.steeringRatio,spec.maxRoadWheelRad*180/PI,.1)
        assertEquals(spec.lengthM,spec.rearOverhangM+spec.wheelbaseM+spec.frontOverhangM,1e-9)
    }

    @Test fun straightDriveAndReverseUseTheGearForDirection() {
        val forward=reconstructor.reconstruct(run(0,5000,2.0,Gear.DRIVE))
        assertEquals(10.0,forward.poses.last().xM,1e-9);assertEquals(0.0,forward.poses.last().yM,1e-9)
        val backward=reconstructor.reconstruct(run(0,4000,1.0,Gear.REVERSE))
        assertEquals(-4.0,backward.poses.last().xM,1e-9)
        assertEquals(0,forward.gapCount)
    }

    @Test fun fullLockSteeringIsClampedToTheVehicleTurningCircle() {
        val radius=spec.minRearAxleRadiusM;val lap=(2*PI*radius*1000).toLong()
        for(wheel in listOf(560.0,900.0)) {
            val path=reconstructor.reconstruct(run(0,lap-lap%100,1.0,Gear.DRIVE,wheel)+frame(lap,1.0,Gear.DRIVE,wheel))
            assertEquals(2*PI,path.poses.last().headingRad,.01)
            assertEquals(0.0,hypot(path.poses.last().xM,path.poses.last().yM),.03)
            assertEquals(radius,path.poses.maxOf {it.yM}/2,.02)
            assertEquals(0.0,path.yawShare,0.0)
        }
    }

    @Test fun measuredYawWinsOverTheSteeringModel() {
        val path=reconstructor.reconstruct(run(0,3000,1.0,Gear.DRIVE,steering=300.0,yaw=.2))
        assertEquals(.6,path.poses.last().headingRad,1e-9)
        assertEquals(1.0,path.yawShare,0.0)
        assertTrue(path.poses.drop(1).all {it.headingSource==HeadingSource.YAW_RATE})
        // The recorded wheel angle travels with each pose for the drawn front wheels, limited to the lock.
        assertEquals(300.0,path.poses.last().steeringDeg!!,0.0)
        assertEquals(20.0*PI/180,spec.roadWheelRad(300.0),1e-12)
        assertEquals(spec.maxRoadWheelRad,spec.roadWheelRad(900.0),0.0);assertEquals(-spec.maxRoadWheelRad,spec.roadWheelRad(-900.0),0.0)

        // Display smoothing: a step from 0° to 300° is averaged around it and changes continuously in time.
        val step=reconstructor.reconstruct(run(0,1000,1.0,Gear.DRIVE,0.0)+run(1100,1000,1.0,Gear.DRIVE,300.0))
        assertEquals(150.0,step.steeringAt(1050)!!,1e-9)
        assertTrue((700L..1400L).all {t->kotlin.math.abs(step.steeringAt(t+1)!!-step.steeringAt(t)!!)<1.0})
        assertEquals(150.0,step.poseAt(1050)!!.steeringDeg!!,1e-9)
        assertEquals(300.0,step.steeringAt(2100)!!,0.0)
    }

    @Test fun missingSpeedHoldsThePoseAndCountsAGapInsteadOfInventingMotion() {
        val frames=run(0,1000,1.0,Gear.DRIVE)+frame(1100,null,Gear.DRIVE)+run(1200,1000,1.0,Gear.DRIVE)
        val path=reconstructor.reconstruct(frames)
        assertEquals(2,path.gapCount)
        assertEquals(2.0,path.poses.last().xM,1e-9)
        assertTrue(path.poses.single {it.timeMs==1100L}.afterGap)
        // Standing still with P engaged is not a gap.
        assertEquals(0,reconstructor.reconstruct(run(0,2000,0.0,Gear.PARK,steering=0.0)).gapCount)
    }

    @Test fun gearEventsMarkTheFirstReverseAndEachReverseToDrive() {
        val frames=run(0,2000,1.0,Gear.DRIVE)+run(2100,2000,1.0,Gear.REVERSE)+run(4200,2000,1.0,Gear.DRIVE)+
            run(6300,2000,1.0,Gear.REVERSE)+run(8400,1000,0.0,Gear.PARK)
        val events=reconstructor.reconstruct(frames).events
        assertEquals(listOf(ReplayEventKind.REVERSE_START,ReplayEventKind.REVERSE_TO_DRIVE),events.map {it.kind})
        assertEquals(2600L,events[0].timeMs);assertEquals(4700L,events[1].timeMs)
    }

    @Test fun anchoringPutsTheParkedPoseAtTheOriginFacingUpAndKeepsDistances() {
        val path=reconstructor.reconstruct(run(0,3000,1.0,Gear.DRIVE,200.0)).anchoredToEnd()
        val last=path.poses.last()
        assertEquals(0.0,last.xM,1e-9);assertEquals(0.0,last.yM,1e-9);assertEquals(PI/2,last.headingRad,1e-9)
        val raw=reconstructor.reconstruct(run(0,3000,1.0,Gear.DRIVE,200.0))
        assertEquals(hypot(raw.poses[0].xM-raw.poses.last().xM,raw.poses[0].yM-raw.poses.last().yM),hypot(path.poses[0].xM,path.poses[0].yM),1e-9)
        assertEquals(path.poses[10].xM,path.poseAt(path.poses[10].timeMs)!!.xM,1e-12)
    }

    @Test fun perpendicularReferenceEndsParkedAndStartsReversingWhereOhamaPredicts() {
        val ref=ReferenceParkingPath.build(ReferenceKind.T_REVERSE,spec,1)
        val end=ref.poses.last()
        assertEquals(0.0,end.xM,.01);assertEquals(0.0,end.yM,.01);assertEquals(PI/2,end.headingRad,1e-9)
        assertEquals(0.0,ref.poses.first().headingRad,1e-9)
        val start=ref.reverseStart;assertFalse(start.reverse)
        val theta=(PI/2-start.headingRad)*180/PI
        val lateral=abs(start.xM+spec.rearAxleToCenterM*cos(start.headingRad))
        assertEquals(9.67*lateral+17.1,theta,.6)
        assertTrue("reverse start lies in the aisle",start.yM>ref.slot.maxY)
        assertTrue(ref.poses.dropWhile {!it.reverse}.all {it.reverse})
        val mirrored=ReferenceParkingPath.build(ReferenceKind.T_REVERSE,spec,-1)
        assertEquals(-start.xM,mirrored.reverseStart.xM,1e-9);assertEquals(PI/2,mirrored.poses.last().headingRad,1e-9)
    }

    @Test fun parallelReferenceShiftsSidewaysIntoTheSlotWithTwoOppositeArcs() {
        val ref=ReferenceParkingPath.build(ReferenceKind.PARALLEL_REVERSE,spec,1)
        val end=ref.poses.last()
        assertEquals(0.0,end.xM,.01);assertEquals(0.0,end.yM,1e-9);assertEquals(PI/2,end.headingRad,1e-6)
        assertEquals(1.0+.5+spec.widthM/2,ref.reverseStart.xM,.01);assertEquals(PI/2,ref.reverseStart.headingRad,1e-9)
        assertTrue(ref.poses.minOf {it.headingRad}<PI/2-.5)
    }

    @Test fun reconstructingTheReferenceFromSpeedGearAndSteeringReproducesIt() {
        for(kind in ReferenceKind.entries) {
            val ref=ReferenceParkingPath.build(kind,spec,1)
            val path=reconstructor.reconstruct(framesFor(ref.poses)).anchoredToEnd()
            val byTime=path.poses.associateBy {it.timeMs}
            val error=ref.poses.indices.maxOf {k->val p=byTime.getValue(50L*k);hypot(p.xM-ref.poses[k].xM,p.yM-ref.poses[k].yM)}
            assertTrue("$kind max error $error",error<.02)
            assertEquals(0,path.gapCount)
            assertEquals(1,path.events.count {it.kind==ReplayEventKind.REVERSE_START})
        }
    }

    @Test fun parallelDemoIsRecognisedAsAParallelParkThatShiftsSideways() {
        val engine=DocumentFixtures.engine(DocumentDemoCatalog.parallelPark.context)
        val frames=DocumentDemoCatalog.parallelPark.frames
        frames.forEach {engine.accept(it)}
        val park=engine.results.single {isParking(it.type)}
        assertEquals(ManeuverType.PARALLEL_PARK,park.type)
        assertEquals(1,park.reverseToDriveCount)
        assertEquals(0.0,park.measurements.getValue("yawDeltaDeg"),2.0)
        // Approached in the lane about 2.8 m to the left of the parked position, facing the same way.
        val start=reconstructor.reconstruct(frames).anchoredToEnd().poses.first()
        assertEquals(-2.8,start.xM,.3);assertEquals(PI/2,start.headingRad,.05)
    }

    @Test fun beginnerDemoIsRecognisedAsATParkWithTwoCorrectionsAndReplaysFromTheAisle() {
        val engine=DocumentFixtures.engine(DocumentDemoCatalog.beginnerTPark.context)
        val frames=DocumentDemoCatalog.beginnerTPark.frames
        frames.forEach {engine.accept(it)}
        val park=engine.results.single {isParking(it.type)}
        assertEquals(ManeuverType.T_PARK,park.type)
        assertEquals(2,park.reverseToDriveCount)
        assertTrue(engine.results.none {it.type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)})
        assertEquals(90.0,park.measurements.getValue("yawDeltaDeg"),2.0)

        val path=reconstructor.reconstruct(frames).anchoredToEnd()
        assertEquals(1.0,path.yawShare,0.0);assertEquals(0,path.gapCount)
        // Designed approach: rear axle about 6.9 m to the left of the slot and 5.4 m up, in the aisle.
        assertEquals(-6.93,path.poses.first().xM,.3);assertEquals(5.4,path.poses.first().yM,.3)
        assertEquals(2,path.events.count {it.kind==ReplayEventKind.REVERSE_TO_DRIVE})
        assertTrue(path.poses.any {it.gear==Gear.REVERSE && (it.speedMps ?: 0.0)*3.6>7.0})
        // Steering and yaw agree, so the steering-only reconstruction lands in the same place.
        val noYaw=reconstructor.reconstruct(frames.map {it.copy(yawRate=null)}).anchoredToEnd()
        assertEquals(path.poses.first().xM,noYaw.poses.first().xM,.05);assertEquals(path.poses.first().yM,noYaw.poses.first().yM,.05)
    }
}
