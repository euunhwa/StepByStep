package com.easycar.core

import kotlin.math.*

enum class HeadingSource { YAW_RATE, STEERING_MODEL }
enum class ReplayEventKind { REVERSE_START, REVERSE_TO_DRIVE }

/** Rear-axle centre position (m) and heading (rad, counter-clockwise from +x) at one recorded frame. */
data class PathPose(val timeMs:Long,val xM:Double,val yM:Double,val headingRad:Double,
    val speedMps:Double?,val gear:Gear?,val headingSource:HeadingSource?,val afterGap:Boolean=false,
    /** Steering-wheel angle (deg, left positive) as recorded; drives the drawn front-wheel angle. */
    val steeringDeg:Double?=null)
data class ReplayEvent(val kind:ReplayEventKind,val timeMs:Long)

/** A point fixed to the car body, [along] ahead of and [across] left of the rear-axle centre. */
fun bodyPoint(x:Double,y:Double,headingRad:Double,along:Double,across:Double):Point {
    val c=cos(headingRad);val s=sin(headingRad)
    return Point(x+c*along-s*across,y+s*along+c*across)
}

data class ReconstructedPath(val poses:List<PathPose>,val events:List<ReplayEvent>,
    /** Share of moving steps whose heading came from a yaw signal rather than the steering model. */
    val yawShare:Double,
    /** Steps where the car may have moved but speed, gear or heading rate was missing. */
    val gapCount:Int) {
    /**
     * Re-express the path so the final pose sits at the origin facing +y. A parked car ends inside its
     * slot, so the end is the only point with a known relation to the (unobserved) slot. Dead-reckoning
     * error then accumulates toward the approach rather than the parked position.
     */
    fun anchoredToEnd():ReconstructedPath {
        val last=poses.lastOrNull() ?: return this
        val rotation=PI/2-last.headingRad;val c=cos(rotation);val s=sin(rotation)
        return copy(poses=poses.map {p->
            val dx=p.xM-last.xM;val dy=p.yM-last.yM
            p.copy(xM=dx*c-dy*s,yM=dx*s+dy*c,headingRad=p.headingRad+rotation)
        })
    }
    /** Linear interpolation between recorded poses; heading is unwrapped so plain interpolation is safe. */
    fun poseAt(timeMs:Long):PathPose? {
        if(poses.isEmpty()) return null
        if(timeMs<=poses.first().timeMs) return poses.first()
        if(timeMs>=poses.last().timeMs) return poses.last()
        val k=poses.binarySearchBy(timeMs) {it.timeMs}
        if(k>=0) return poses[k]
        val a=poses[-k-2];val b=poses[-k-1]
        if(b.afterGap) return a
        val f=(timeMs-a.timeMs).toDouble()/(b.timeMs-a.timeMs)
        val steering=if(a.steeringDeg!=null && b.steeringDeg!=null) a.steeringDeg+(b.steeringDeg-a.steeringDeg)*f else a.steeringDeg
        return a.copy(timeMs=timeMs,xM=a.xM+(b.xM-a.xM)*f,yM=a.yM+(b.yM-a.yM)*f,headingRad=a.headingRad+(b.headingRad-a.headingRad)*f,steeringDeg=steering)
    }
    /**
     * Each pose's steering-wheel angle averaged over ±300 ms, computed once. A window recomputed per animation
     * frame would step whenever a 10 Hz sample entered or left it; interpolating these values does not.
     */
    private val smoothedSteering:List<Double?> by lazy {
        var from=0
        poses.map {p->
            while(poses[from].timeMs<p.timeMs-STEERING_SMOOTH_HALF_MS) from++
            var sum=0.0;var count=0;var i=from
            while(i<poses.size && poses[i].timeMs<=p.timeMs+STEERING_SMOOTH_HALF_MS) {poses[i].steeringDeg?.let {sum+=it;count++};i++}
            if(count>0) sum/count else null
        }
    }
    /** Smoothed steering-wheel angle for display, continuous in time: drives the drawn wheels and guide. */
    fun steeringAt(timeMs:Long):Double? {
        if(poses.isEmpty()) return null
        val k=poses.binarySearchBy(timeMs) {it.timeMs}
        if(k>=0) return smoothedSteering[k]
        val after=-k-1
        if(after==0) return smoothedSteering.first()
        if(after==poses.size) return smoothedSteering.last()
        val a=smoothedSteering[after-1];val b=smoothedSteering[after]
        if(a==null || b==null) return a ?: b
        val f=(timeMs-poses[after-1].timeMs).toDouble()/(poses[after].timeMs-poses[after-1].timeMs)
        return a+(b-a)*f
    }
    private companion object {const val STEERING_SMOOTH_HALF_MS=300L}
}

/**
 * Dead reckoning for display only. Distance comes from speed with the sign taken from the gear; heading
 * comes from a measured yaw rate when one is usable, otherwise from the kinematic bicycle model on the
 * steering-wheel angle. Steps with a missing input are not invented: the pose holds and the step is counted.
 */
class TrajectoryReconstructor(private val spec:VehicleSpec,private val maxSignalAgeMs:Long=750,
    private val maxGapMs:Long=1000,private val stoppedMps:Double=.15,private val gearStableMs:Long=500) {
    private class Sample(val timeMs:Long,val velocity:Double?,val speed:Double?,val gear:Gear?,val rate:Double?,val source:HeadingSource?,val steering:Double?)

    private fun sample(f:VehicleFrame):Sample {
        fun <T> read(s:Signal<T>?,unit:String):T? = s?.takeIf {f.connected && it.unit==unit && it.usable(f.timeMs,maxSignalAgeMs)}?.value
        val speed=read(f.speed,"m/s")?.takeIf {it.isFinite() && abs(it)<=90}?.let(::abs)
        val gear=read(f.gear,"gear")?.takeIf {it!=Gear.UNKNOWN}
        val velocity=when {
            speed==null || gear==null->null
            gear==Gear.DRIVE->speed
            gear==Gear.REVERSE->-speed
            speed<=stoppedMps->0.0
            else->null // P or N while moving: the direction is unknown.
        }
        // Same acceptance as TelemetryPipeline: a yaw with an estimated timestamp is not integrated.
        val yaw=f.yawRate?.takeIf {it.quality==Quality.VALID && it.timing==SignalTiming.MEASUREMENT}
            ?.let {read(it,"rad/s:vehicle-z-left-positive")}?.takeIf {it.isFinite() && abs(it)<=5}
        val steering=read(f.steering,"degree:steering-wheel")?.takeIf {it.isFinite() && abs(it)<=1080}
        var rate:Double?=null;var source:HeadingSource?=null
        if(yaw!=null) {rate=yaw;source=HeadingSource.YAW_RATE}
        else if(steering!=null && velocity!=null) {
            rate=velocity*spec.curvatureFor(steering);source=HeadingSource.STEERING_MODEL
        }
        return Sample(f.timeMs,velocity,speed,gear,rate,source,steering)
    }

    fun reconstruct(frames:List<VehicleFrame>):ReconstructedPath {
        val samples=frames.sortedBy {it.timeMs}.distinctBy {it.timeMs}.map(::sample)
        if(samples.isEmpty()) return ReconstructedPath(emptyList(),emptyList(),0.0,0)
        var x=0.0;var y=0.0;var psi=0.0;var gaps=0;var moving=0;var fromYaw=0
        val poses=ArrayList<PathPose>(samples.size);val events=mutableListOf<ReplayEvent>()
        val gears=GearTransitionCounter(gearStableMs);var reverseSeen=false
        samples.forEachIndexed {i,s->
            var gap=false
            if(i>0) {
                val p=samples[i-1];val dtMs=s.timeMs-p.timeMs
                val stillBoth=p.velocity==0.0 && s.velocity==0.0
                val usable=dtMs in 1..maxGapMs && p.velocity!=null && s.velocity!=null && p.rate!=null && s.rate!=null
                if(usable) {
                    val dt=dtMs/1000.0;val dPsi=(p.rate!!+s.rate!!)/2*dt;val v=(p.velocity!!+s.velocity!!)/2;val mid=psi+dPsi/2
                    x+=v*cos(mid)*dt;y+=v*sin(mid)*dt;psi+=dPsi
                    if(v!=0.0) {moving++;if(p.source==HeadingSource.YAW_RATE && s.source==HeadingSource.YAW_RATE) fromYaw++}
                } else if(!(stillBoth && dtMs in 1..maxGapMs)) {gap=true;gaps++}
            }
            val before=gears.reverseToDriveCount
            val stable=gears.update(s.gear,s.timeMs)
            if(gears.reverseToDriveCount>before) events+=ReplayEvent(ReplayEventKind.REVERSE_TO_DRIVE,s.timeMs)
            if(stable==Gear.REVERSE && !reverseSeen) {reverseSeen=true;events+=ReplayEvent(ReplayEventKind.REVERSE_START,s.timeMs)}
            poses+=PathPose(s.timeMs,x,y,psi,s.speed,s.gear,s.source,gap,s.steering)
        }
        return ReconstructedPath(poses,events,if(moving==0) 0.0 else fromYaw.toDouble()/moving,gaps)
    }
}

enum class ReferenceKind(val title:String) { T_REVERSE("T자(직각) 후진 주차"), PARALLEL_REVERSE("평행 후진 주차") }
/** A pose on a planned path; [curvature] (1/m, CCW positive) is that of the segment ending here. */
data class PlanPose(val xM:Double,val yM:Double,val headingRad:Double,val reverse:Boolean,val curvature:Double=0.0)
data class SlotRect(val minX:Double,val maxX:Double,val minY:Double,val maxY:Double)
data class ReferencePath(val poses:List<PlanPose>,val slot:SlotRect,val neighbours:List<SlotRect>) {
    val reverseStart:PlanPose=reverseStartOf(poses)
}
/** The pose where the driver stops and shifts to R: the last one before the first reverse step. */
private fun reverseStartOf(poses:List<PlanPose>)=poses[poses.indexOfFirst {it.reverse}-1]

/**
 * An example of a model manoeuvre ending at the same final pose as [ReconstructedPath.anchoredToEnd]:
 * rear axle at the origin, facing +y. It shows one good way to park, not the only one, and is never scored.
 *
 * Perpendicular: pass the slot 0.5 m from its line, swing the nose away at 1.2× the minimum radius, stop where
 * the reverse-start angle meets the Ohama et al. (2021, IEEE Access 9:92003) single-attempt regression
 * θ = 9.67·x + 17.1, then reverse at 1.1× the minimum radius and straight in. About 29.5° and 1.3 m for the
 * Grandeur; it needs an aisle of roughly 7.8 m. Parallel: stop alongside 0.5 m from the slot line and reverse
 * along two opposite arcs at 1.05× the minimum radius (about 45° for the Grandeur).
 */
object ReferenceParkingPath {
    /** 주차장법 시행규칙 제3조: 직각 확장형 2.6×5.2 m, 평행 일반형 2.0×6.0 m. */
    private const val T_SLOT_W=2.6;private const val T_SLOT_D=5.2
    private const val P_SLOT_W=2.0;private const val P_SLOT_L=6.0
    /** Gap between the car body and the slot line while driving past. */
    private const val SIDE_GAP=.5
    /** Ohama et al. 2021: θ_DCP = 9.67·x_lat + 17.1 for real-car single-attempt perpendicular reverse parking. */
    private const val OHAMA_SLOPE=9.67;private const val OHAMA_INTERCEPT=17.1

    /** A constant-curvature piece of a planned path. */
    class Segment(val reverse:Boolean,val curvature:Double,val lengthM:Double)

    /** Integrate [segments] from [start] with the kinematic bicycle model, one pose per step of at most [stepM]. */
    fun drive(start:PlanPose,segments:List<Segment>,stepM:Double=.05):List<PlanPose> {
        val out=mutableListOf(start);var x=start.xM;var y=start.yM;var psi=start.headingRad
        for(s in segments) {
            val steps=max(1,ceil(s.lengthM/stepM).toInt());val h=s.lengthM/steps;val dir=if(s.reverse) -1.0 else 1.0
            repeat(steps) {
                val dPsi=dir*s.curvature*h;val mid=psi+dPsi/2
                x+=dir*cos(mid)*h;y+=dir*sin(mid)*h;psi+=dPsi
                out+=PlanPose(x,y,psi,s.reverse,s.curvature)
            }
        }
        return out
    }

    /** The slot the car ends in, centred on the parked body. */
    fun slot(kind:ReferenceKind,spec:VehicleSpec):SlotRect {
        val cy=spec.rearAxleToCenterM
        return when(kind) {
            ReferenceKind.T_REVERSE->SlotRect(-T_SLOT_W/2,T_SLOT_W/2,cy-T_SLOT_D/2,cy+T_SLOT_D/2)
            ReferenceKind.PARALLEL_REVERSE->SlotRect(-P_SLOT_W/2,P_SLOT_W/2,cy-P_SLOT_L/2,cy+P_SLOT_L/2)
        }
    }

    private fun mirror(p:PlanPose)=p.copy(xM=-p.xM,headingRad=PI-p.headingRad,curvature=-p.curvature)
    private fun mirror(r:SlotRect)=SlotRect(-r.maxX,-r.minX,r.minY,r.maxY)
    /** Move a plan so that it ends at the origin. */
    private fun endAtOrigin(poses:List<PlanPose>):List<PlanPose> {
        val end=poses.last();return poses.map {it.copy(xM=it.xM-end.xM,yM=it.yM-end.yM)}
    }

    // Plans are deterministic per kind and vehicle; the perpendicular one searches 141 candidates.
    private val cache=java.util.concurrent.ConcurrentHashMap<Pair<ReferenceKind,VehicleSpec>,ReferencePath>()

    /** [side] > 0 puts the approach and reverse start on the +x side of the slot. */
    fun build(kind:ReferenceKind,spec:VehicleSpec,side:Int):ReferencePath {
        val path=cache.getOrPut(kind to spec) {when(kind) {ReferenceKind.T_REVERSE->perpendicular(spec);ReferenceKind.PARALLEL_REVERSE->parallel(spec)}}
        return if(side>=0) path else path.copy(poses=path.poses.map(::mirror),
            slot=mirror(path.slot),neighbours=path.neighbours.map(::mirror))
    }

    private fun perpendicular(spec:VehicleSpec):ReferencePath {
        val slot=slot(ReferenceKind.T_REVERSE,spec)
        val laneY=slot.maxY+SIDE_GAP+spec.widthM/2
        val reverseRadius=1.1*spec.minRearAxleRadiusM;val swingRadius=1.2*spec.minRearAxleRadiusM
        fun plan(thetaDeg:Double):List<PlanPose> {
            val theta=thetaDeg*PI/180
            val head=drive(PlanPose(0.0,laneY,0.0,false),listOf(Segment(false,0.0,4.0),
                Segment(false,1/swingRadius,swingRadius*(PI/2-theta)),Segment(true,-1/reverseRadius,reverseRadius*theta)))
            // Then straight back until the rear axle reaches the slot's parked position.
            return endAtOrigin(head.dropLast(1)+drive(head.last(),listOf(Segment(true,0.0,head.last().yM))))
        }
        /** Body-centre distance across the slot from its centre line, as in the regression. */
        fun lateral(p:PlanPose)=abs(bodyPoint(p.xM,p.yM,p.headingRad,spec.rearAxleToCenterM,0.0).x)
        // Choose the reverse-start angle where this geometry agrees with the observed regression.
        val thetaDeg=(20..160).map {it*.5}.minBy {t->abs(t-(OHAMA_SLOPE*lateral(reverseStartOf(plan(t)))+OHAMA_INTERCEPT))}
        val poses=plan(thetaDeg)
        return ReferencePath(poses,slot,
            listOf(slot.copy(minX=slot.minX-T_SLOT_W,maxX=slot.minX),slot.copy(minX=slot.maxX,maxX=slot.maxX+T_SLOT_W)))
    }

    private fun parallel(spec:VehicleSpec):ReferencePath {
        val slot=slot(ReferenceKind.PARALLEL_REVERSE,spec)
        val offset=P_SLOT_W/2+SIDE_GAP+spec.widthM/2
        val radius=1.05*spec.minRearAxleRadiusM
        val phi=acos(1-offset/(2*radius))
        val poses=endAtOrigin(drive(PlanPose(offset,0.0,PI/2,false),listOf(Segment(false,0.0,4.0),
            Segment(true,1/radius,radius*phi),Segment(true,-1/radius,radius*phi))))
        return ReferencePath(poses,slot,
            listOf(slot.copy(minY=slot.minY-P_SLOT_L,maxY=slot.minY),slot.copy(minY=slot.maxY,maxY=slot.maxY+P_SLOT_L)))
    }
}
