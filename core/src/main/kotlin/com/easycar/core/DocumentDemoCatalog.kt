package com.easycar.core

import kotlin.math.*

/** Independent synthetic inputs for the submission demo. No truth labels are passed to the engine. */
object DocumentDemoCatalog {
    private class Wave {
        val frames=mutableListOf<VehicleFrame>();private var t=0L
        fun add(seconds:Double,gear:Gear,v:(Double)->Double={0.0},yaw:(Double)->Double={0.0},wheel:(Double)->Double={0.0}) {
            val count=(seconds*10).roundToInt()
            repeat(count) {i->
                val u=if(count>1) i.toDouble()/(count-1) else 0.0
                fun <T> s(value:T,unit:String)=Signal(value,t,t,unit,"SYNTHETIC_DOCUMENT_VALIDATION")
                frames+=VehicleFrame(t,s(v(u),"m/s"),s(wheel(u),"degree:steering-wheel"),s(gear,"gear"),null,
                    yawRate=s(yaw(u),"rad/s:vehicle-z-left-positive"),ignitionOn=s(true,"boolean:ignition"));t+=100
            }
        }
    }
    private fun smooth(x:Double)=x*x*x*(10-15*x+6*x*x)
    val automaticDrive:DemoCase by lazy {
        val w=Wave();w.add(5.0,Gear.DRIVE);w.add(6.0,Gear.DRIVE,{3*smooth(it)});w.add(3.0,Gear.DRIVE,{3.0})
        for((degrees,seconds) in listOf(90.0 to 8.0,-90.0 to 8.0,180.0 to 10.0)) {
            w.add(seconds,Gear.DRIVE,{3.0},{degrees/seconds*PI/180},{if(degrees>0) 140.0 else -140.0})
            w.add(4.0,Gear.DRIVE,{3.0})
        }
        w.add(6.0,Gear.DRIVE,{3*(1-smooth(it))});w.add(5.0,Gear.DRIVE)
        // T park: forward 40° + reverse 50°. Reverse-only integration must not classify it as 50°.
        w.add(4.0,Gear.DRIVE,{.6},{40.0/4*PI/180},{140.0});w.add(1.0,Gear.DRIVE)
        w.add(.8,Gear.REVERSE);w.add(6.0,Gear.REVERSE,{-.6},{50.0/6*PI/180},{140.0});w.add(1.0,Gear.REVERSE);w.add(1.5,Gear.PARK)
        // Re-arm, then parallel reverse S with net zero heading and both steering/yaw signs.
        w.add(2.0,Gear.DRIVE,{.5});w.add(1.0,Gear.DRIVE);w.add(.8,Gear.REVERSE)
        w.add(3.0,Gear.REVERSE,{-.5},{.16},{140.0});w.add(3.0,Gear.REVERSE,{-.5},{-.16},{-140.0})
        w.add(1.0,Gear.REVERSE);w.add(1.5,Gear.PARK)
        DemoCase("auto-drive","문헌 기준 · 일곱 동작 합성 검증 주행","자동 코칭",PracticeContext(null,null,false,dataSource="문헌 기준 합성 데모 · 실차 데이터 아님"),w.frames)
    }

    /** One continuous movement in one gear: arcs and straights of constant curvature (1/m, CCW positive). */
    private class Move(val gear:Gear,val maxMps:Double,val pieces:List<Pair<Double,Double>>)

    /** A brisk but ordinary parking steering rate; a full 540° lock takes 1.5 s. */
    private const val STEERING_RATE_DEG_S=360.0

    /** Planned curvature at distance [s] into a movement. */
    private fun Move.curvatureAt(s:Double):Double {
        var left=s
        for((length,curvature) in pieces) {if(left<length) return curvature;left-=length}
        return pieces.last().second
    }

    /**
     * Frames for a sequence of movements with a stop and gear change between them. Each movement follows
     * a half-sine speed profile. The planned curvature sets a target wheel angle, and the wheel turns
     * toward it at no more than [STEERING_RATE_DEG_S], as a driver turns it; yaw comes from the actual
     * wheel through the kinematic bicycle model of [spec], so the signals stay physically consistent.
     * The wheel starts turning while stopped before each movement.
     */
    private fun drive(moves:List<Move>,spec:VehicleSpec):List<VehicleFrame> {
        val frames=mutableListOf<VehicleFrame>();var t=0L;var wheel=0.0
        fun emit(gear:Gear,speed:Double,steering:Double,yaw:Double) {
            fun <T> s(value:T,unit:String)=Signal(value,t,t,unit,"SYNTHETIC_PARKING_DEMO")
            frames+=VehicleFrame(t,s(speed,"m/s"),s(steering,"degree:steering-wheel"),s(gear,"gear"),null,
                yawRate=s(yaw,"rad/s:vehicle-z-left-positive"),ignitionOn=s(true,"boolean:ignition"));t+=100
        }
        val step=STEERING_RATE_DEG_S/10
        fun turnToward(target:Double) {wheel+=(target-wheel).coerceIn(-step,step)}
        fun stop(gear:Gear,seconds:Double,target:Double=wheel) = repeat((seconds*10).roundToInt()) {turnToward(target);emit(gear,0.0,wheel,0.0)}
        var previous=Gear.DRIVE
        stop(previous,1.0)
        for(m in moves) {
            // Keep the old wheel angle while still in the old gear; turn it once the new gear is engaged.
            val target=spec.steeringDegFor(m.pieces.first().second)
            if(m.gear!=previous) {stop(previous,.6);stop(m.gear,1.0,target);previous=m.gear}
            else stop(m.gear,.6,target)
            val length=m.pieces.sumOf {it.first};val duration=length*PI/(2*m.maxMps);val steps=(duration*10).roundToInt()
            val direction=if(m.gear==Gear.REVERSE) -1.0 else 1.0
            for(i in 0 until steps) {
                val u=i/10.0/duration;val s=length*(1-cos(PI*u))/2;val v=m.maxMps*sin(PI*u)
                turnToward(spec.steeringDegFor(m.curvatureAt(s)))
                emit(m.gear,v,wheel,direction*v*spec.curvatureFor(wheel))
            }
        }
        stop(previous,.6);stop(Gear.PARK,2.0)
        return frames
    }

    /**
     * A beginner's perpendicular reverse park with two corrections: a shallow 25° swing, a reverse planned
     * at 41° that leaves the car short of square, a forward nudge, a reverse that ends 0.4 m off centre, a
     * forward S to re-centre and a final straight reverse a little above 7 km/h. With the
     * wheel turned at a finite rate the recorded net rotation is about 90°.
     */
    val beginnerTPark:DemoCase by lazy {
        val spec=VehicleSpec.GRANDEUR_GN7_PE;fun rad(d:Double)=d*PI/180
        val frames=drive(listOf(
            Move(Gear.DRIVE,1.5,listOf(6.0 to 0.0,8*rad(25.0) to 1/8.0)),
            Move(Gear.REVERSE,.8,listOf(4.3*rad(41.0) to -1/4.3)),
            Move(Gear.DRIVE,.6,listOf(4.4*rad(12.0) to 1/4.4)),
            Move(Gear.REVERSE,.8,listOf(4.5*rad(8.0) to -1/4.5,2.0 to 0.0)),
            Move(Gear.DRIVE,.8,listOf(6*rad(15.0) to 1/6.0,6*rad(15.0) to -1/6.0)),
            Move(Gear.REVERSE,2.1,listOf(5.1 to 0.0))),spec)
        DemoCase("park-beginner","초보 T자 주차 · 후진→전진 2회","주차",PracticeContext(null,null,false,dataSource="합성 주차 데모 · 실차 데이터 아님"),frames)
    }

    /**
     * A parallel reverse park into a space on the right: drive past and stop alongside, reverse with right
     * lock to about 45°, stop to counter-steer, reverse with left lock back to straight, then a short forward
     * move to centre the car. The counter arc is planned at 50° because the wheel turns at a finite rate;
     * the recorded net rotation is then about 0°.
     */
    val parallelPark:DemoCase by lazy {
        val spec=VehicleSpec.GRANDEUR_GN7_PE;fun rad(d:Double)=d*PI/180
        val r=1.1*spec.minRearAxleRadiusM
        val frames=drive(listOf(
            Move(Gear.DRIVE,1.5,listOf(9.0 to 0.0)),
            Move(Gear.REVERSE,.8,listOf(r*rad(PARALLEL_ARC_DEG) to -1/r)),
            Move(Gear.REVERSE,.8,listOf(r*rad(PARALLEL_COUNTER_DEG) to 1/r)),
            Move(Gear.DRIVE,.5,listOf(.8 to 0.0))),spec)
        DemoCase("park-parallel","평행 주차 · 후진→전진 1회","주차",PracticeContext(null,null,false,dataSource="합성 주차 데모 · 실차 데이터 아님"),frames)
    }
    private const val PARALLEL_ARC_DEG=45.0;private const val PARALLEL_COUNTER_DEG=50.0

    fun case(id:String):DemoCase?=when(id) {"auto-drive"->automaticDrive;"park-beginner"->beginnerTPark;"park-parallel"->parallelPark;else->null}
}
