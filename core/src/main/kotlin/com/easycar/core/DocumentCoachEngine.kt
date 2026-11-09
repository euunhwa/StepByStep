package com.easycar.core

import kotlin.math.*

/** Three independent event paths: longitudinal, yaw bumps, and R→parking segments. No input labels. */
internal class DocumentCoachEngine(profile:ReferenceProfile,context:PracticeContext,private val d:DocumentDataset) {
    private val r=d.recognition
    private val parkingLimitMps=r.parkingMaxMps+r.parkingSpeedEpsilonMps
    private val pipeline=TelemetryPipeline(DetectionConfig(maxSignalAgeMs=r.maxSignalAgeMs,maxGapMs=r.maxFrameGapMs))
    private val scorer=DocumentScorer(profile,context,d)
    private val completed=mutableListOf<ManeuverResult>()
    val results get()=completed.toList()
    var lastMotion:Motion?=null;private set
    private val recent=ArrayDeque<Motion>();private val idle=ArrayDeque<Motion>();private val yawMean=ArrayDeque<Pair<Long,Double>>()
    private var lastSeenMs=Long.MIN_VALUE;private var lastSampleMs:Long?=null
    private var stoppedSince:Long?=null;private var wasIdle=false;private var stopArmed=false
    private var launch:MutableList<Motion>?=null;private var launchOnset=Long.MIN_VALUE/2
    private var stopping:MutableList<Motion>?=null;private var decQuiet:Long?=null
    private var acceleration:MutableList<Motion>?=null;private var accQuiet:Long?=null
    private var turn:MutableList<Motion>?=null;private var yawQuiet:Long?=null
    // An incomplete one-sign turn (e.g. paused to yield) may continue with a same-sign bump within turnPauseMaxMs.
    private var pendingTurn:List<Motion>?=null
    // A slow (≤ parking speed) LEFT/RIGHT may be the forward swing before reverse parking or a forward-in
    // park. It is held for turnParkingHoldMs: R absorbs it into the parking segment, P makes it a forward
    // park, and driving on (faster, or the hold expiring in D) emits it as the turn it is. Results that
    // complete meanwhile (e.g. a STOP while waiting) queue behind it, so the hold is not cut short and
    // the output keeps the order in which the manoeuvres happened.
    private class HeldTurn(val type:ManeuverType,val samples:List<Motion>,val flags:Map<String,Double>)
    private var heldTurn:HeldTurn?=null
    private val queuedBehindTurn=mutableListOf<ManeuverResult>()
    private var settledGear:Gear?=null;private var parkingFromPark=false
    private var yawReady=true;private var yawRecoverySince:Long?=null
    private var parking:MutableList<Motion>?=null;private var parkingOnset=0L;private var parkingStopped:Long?=null
    private var gearCounter=GearTransitionCounter(r.gearStableMs);private val stableGear=GearTransitionCounter(r.gearStableMs)
    private var parkBoundary:Long?=null;private var parkRearm=true;private var rearmSince:Long?=null
    val reverseToDriveCount get()=gearCounter.reverseToDriveCount
    val activeType get()=when {parking!=null->"주차 관측 중";turn!=null->"회전 중";launch!=null->"출발 중";stopping!=null->"감속 중";lastMotion?.yawRadps==null->"차량 관측 중 · yaw 신호 미수신";else->"다음 동작 대기 중"}
    private fun emit(type:ManeuverType,a:List<Motion>,count:Int=0,flags:Map<String,Double> = emptyMap(),holds:List<String> = emptyList(),interrupted:Boolean=false) {
        if(a.isEmpty()) return
        val result=scorer.evaluate(type,a,count,flags,holds,interrupted)
        if(heldTurn!=null) queuedBehindTurn+=result else completed+=result
    }
    /** Resolve the held turn: emit it ([keep]) or drop it into a parking segment, then release the queue. */
    private fun releaseHeldTurn(keep:Boolean) {
        val h=heldTurn ?: return
        heldTurn=null
        if(keep) completed+=scorer.evaluate(h.type,h.samples,0,h.flags,emptyList(),false)
        completed+=queuedBehindTurn;queuedBehindTurn.clear()
    }
    /** Recent samples since the car last exceeded parking speed: the pre-roll of a parking segment. */
    private fun lowSpeedTail():List<Motion> = recent.drop(recent.indexOfLast {abs(it.speedMps ?: 0.0)>parkingLimitMps}+1)
    /** Drop incomplete longitudinal candidates absorbed by a parking segment. Completed events are retained. */
    private fun clearLongitudinal() {launch=null;stopping=null;acceleration=null;decQuiet=null;accQuiet=null;stopArmed=false}
    fun accept(frame:VehicleFrame):List<ManeuverResult> {
        val before=completed.size
        if(frame.timeMs<=lastSeenMs) return emptyList()
        lastSeenMs=frame.timeMs
        if(frame.connected && lastSampleMs?.let {frame.timeMs-it<r.resamplePeriodMs}==true) return emptyList()
        lastSampleMs=frame.timeMs
        val raw=pipeline.accept(frame.copy(
            speed=frame.speed?.takeIf {it.unit=="m/s"},gear=frame.gear?.takeIf {it.unit=="gear"},
            steering=frame.steering?.takeIf {it.unit=="degree:steering-wheel"},
            yawRate=frame.yawRate?.takeIf {it.usable(frame.timeMs,r.maxSampleGapMs)},
            lateralAcceleration=frame.lateralAcceleration?.takeIf {it.usable(frame.timeMs,r.maxSampleGapMs)},
            ignitionOn=frame.ignitionOn?.takeIf {it.unit=="boolean:ignition" && it.quality==Quality.VALID && it.timing==SignalTiming.MEASUREMENT})) ?: return emptyList()
        if(raw.yawRadps!=null && !raw.gap) yawMean+=raw.timeMs to raw.yawRadps else yawMean.clear()
        while(yawMean.isNotEmpty() && raw.timeMs-yawMean.first().first>r.yawMeanWindowMs) yawMean.removeFirst()
        val yawContinuous=lastMotion?.let {raw.timeMs-it.timeMs<=r.maxSampleGapMs} ?: true
        val m=raw.copy(yawRadps=if(raw.yawRadps!=null && yawMean.isNotEmpty() && yawContinuous) yawMean.map {it.second}.average() else null)
        lastMotion=m
        if(m.gap || m.speedMps==null || m.gear==null) {
            abort("속도·기어 신호 공백 또는 1초 초과 입력 중단")
            recent.clear();idle.clear();yawMean.clear();wasIdle=false;stopArmed=false;stoppedSince=null
            stableGear.update(null,m.timeMs);yawReady=false;yawRecoverySince=null;parkRearm=false;settledGear=null
            return completed.drop(before)
        }
        recent+=m;while(recent.isNotEmpty() && m.timeMs-recent.first().timeMs>r.preRollMs) recent.removeFirst()
        // Retain the sample immediately before the window boundary. Requiring an exact 4000 ms
        // span after discarding older samples prevents idle detection under ordinary polling jitter.
        idle+=m
        while(idle.size>1 && m.timeMs-idle.elementAt(1).timeMs>=r.idleWindowMs) idle.removeFirst()
        val stopped=abs(m.speedMps)<=r.stoppedMps
        if(stopped) {
            if(stoppedSince==null) stoppedSince=m.timeMs
            val values=idle.mapNotNull {it.speedMps?.let(::abs)}
            val mean=values.average();val std=sqrt(values.map {(it-mean).pow(2)}.average())
            if(m.timeMs-idle.first().timeMs>=r.idleWindowMs && values.all {it<=r.stoppedMps} && std<=r.idleStdMaxMps) wasIdle=true
        } else stoppedSince=null
        val gear=stableGear.update(m.gear,m.timeMs)
        if(gear==Gear.PARK || gear==Gear.DRIVE) settledGear=gear
        heldTurn?.let {h->
            if(parking==null && gear==Gear.PARK && stopped) finishForwardParking()
            else if(abs(m.speedMps)>parkingLimitMps || (m.gear==Gear.DRIVE && m.timeMs-h.samples.last().timeMs>r.turnParkingHoldMs)) releaseHeldTurn(true)
        }
        if(!parkRearm && gear==Gear.DRIVE && m.gear==Gear.DRIVE && !stopped) {
            if(rearmSince==null) rearmSince=m.timeMs
            if(m.timeMs-rearmSince!!>=r.gearStableMs) parkRearm=true
        } else rearmSince=null
        if(!parkRearm && gear==Gear.PARK && stopped && wasIdle) parkRearm=true
        if(parking==null && parkRearm && gear==Gear.REVERSE && m.gear==Gear.REVERSE && abs(m.speedMps)<=parkingLimitMps) {
            parking=lowSpeedTail().toMutableList();parkingOnset=m.timeMs
            gearCounter=GearTransitionCounter(r.gearStableMs);parking!!.forEach {gearCounter.update(it.gear,it.timeMs)}
            parkBoundary=null;parkingStopped=null;pendingTurn=null
            releaseHeldTurn(false) // a slow swing just before R is part of this parking, already inside the pre-roll
            // R entered from a settled P (N ignored) is a departure candidate, not necessarily a parking attempt.
            parkingFromPark=settledGear==Gear.PARK
            // Absorb incomplete low-speed preparation into this parking episode. Completed events are retained.
            clearLongitudinal();wasIdle=false
        }
        parking?.let {a->
            append(a,m);gearCounter.update(m.gear,m.timeMs)
            if(stopped) {if(parkingStopped==null) parkingStopped=m.timeMs} else parkingStopped=null
            val longStop=parkingStopped?.let {m.timeMs-it>=r.parkingLongStopMs}==true
            val boundary=m.gear==Gear.PARK && stopped || m.ignitionOn==false || longStop
            if(boundary) {
                if(parkBoundary==null) parkBoundary=m.timeMs
                if(m.ignitionOn==false || longStop || m.timeMs-parkBoundary!!>=r.gearStableMs) finishParking(a)
            } else parkBoundary=null
            if(parking!=null && (abs(m.speedMps)>parkingLimitMps || m.timeMs-parkingOnset>r.parkingTimeoutMs)) {
                // Leaving a space (P → R → driving away in D) is not a parking attempt: record nothing.
                val departure=parkingFromPark && m.gear==Gear.DRIVE && abs(m.speedMps)>parkingLimitMps
                if(!departure) emit(ManeuverType.PARKING_UNKNOWN,a,gearCounter.reverseToDriveCount,holds=listOf("주차 속도 범위 이탈 또는 종료 시간 초과"),interrupted=true)
                parking=null;parkRearm=false
            }
        }
        // D-only speed events avoid reporting R/D parking shuffles as separate launches/stops.
        if(m.gear==Gear.DRIVE && parking==null) {
            if(wasIdle && abs(m.speedMps)>=r.movingMps && launch==null) {
                launch=recent.filter {m.timeMs-it.timeMs<=r.idleWindowMs}.toMutableList();launchOnset=m.timeMs;wasIdle=false
            }
            launch?.let {a->
                append(a,m)
                if(m.timeMs-launchOnset>=r.startWindowMs || m.acceleration?.let {it< -r.stopCandidateMps2}==true) {
                    emit(ManeuverType.START,a,flags=longitudinalFlags(a,true));launch=null
                }
            }
            if(abs(m.speedMps)>=r.stopArmMps) stopArmed=true
            if(stopping==null && stopArmed && m.acceleration?.let {it< -r.stopCandidateMps2}==true) {
                stopping=recent.filter {m.timeMs-it.timeMs<=r.stopPreRollMs}.toMutableList();decQuiet=null
            }
            stopping?.let {a->
                append(a,m)
                if(stopped && stoppedSince?.let {m.timeMs-it>=r.idleWindowMs}==true) {
                    emit(ManeuverType.STOP,a,flags=longitudinalFlags(a,false));stopping=null;stopArmed=false
                } else {
                    if((m.acceleration ?: -1.0)>= -r.stopCandidateMps2 && !stopped) {if(decQuiet==null) decQuiet=m.timeMs} else decQuiet=null
                    if(decQuiet?.let {m.timeMs-it>=r.decelerationMergeMs}==true) {
                        if(hasRun(a,r.harshDecelerationMps2,false)) emit(if(abs(m.speedMps)*3.6<=r.harshStopMaxKph) ManeuverType.STOP else ManeuverType.DECELERATION,a,flags=longitudinalFlags(a,false))
                        stopping=null;decQuiet=null
                    } else if(m.timeMs-a.first().timeMs>r.stopWindowMs) {stopping=null;decQuiet=null}
                }
            }
            if(acceleration==null && launch==null && m.timeMs-launchOnset>r.startWindowMs+r.accelerationMergeMs && abs(m.speedMps)*3.6>=r.harshAccelerationMinKph && (m.acceleration ?: 0.0)>=r.harshAccelerationMps2) {
                acceleration=mutableListOf(m);accQuiet=null
            }
            acceleration?.let {a->
                append(a,m)
                if((m.acceleration ?: 0.0)<r.harshAccelerationMps2) {if(accQuiet==null) accQuiet=m.timeMs} else accQuiet=null
                if(accQuiet?.let {m.timeMs-it>=r.accelerationMergeMs}==true || m.timeMs-a.first().timeMs>r.stopWindowMs) {
                    if(hasRun(a,r.harshAccelerationMps2,true)) emit(ManeuverType.ACCELERATION,a,flags=mapOf("harshAcceleration" to 1.0))
                    acceleration=null;accQuiet=null
                }
            }
        } else if(m.gear==Gear.PARK && parking==null) {
            // A switch to P after stopping still completes a D stop when the idle window is established.
            stopping?.let {a->append(a,m);if(stopped && wasIdle) {emit(ManeuverType.STOP,a,flags=longitudinalFlags(a,false));stopping=null;stopArmed=false}}
        }
        // Missing yaw cannot be replaced by the currently low-confidence GNSS heading or by steering.
        pendingTurn?.let {p->if(m.timeMs-p.last().timeMs>r.turnPauseMaxMs) pendingTurn=null}
        if(m.yawRadps==null) {
            yawReady=false;yawRecoverySince=null;pendingTurn=null
            turn?.let {emit(ManeuverType.UNKNOWN,it,holds=listOf("차량 yaw rate 신호 누락"),interrupted=true)};turn=null;yawQuiet=null
        } else if(!yawReady) {
            if(abs(m.yawRadps)<r.yawBumpRadps) {
                if(yawRecoverySince==null) yawRecoverySince=m.timeMs
                if(m.timeMs-yawRecoverySince!!>=r.yawQuietMs) yawReady=true
            } else yawRecoverySince=null
        } else if(m.gear==Gear.DRIVE && parking==null && abs(m.speedMps)>=r.movingMps) {
            val high=abs(m.yawRadps)>=r.yawBumpRadps
            if(turn==null && high) {
                // A pending segment is one-sign and starts on a high bump, so its first sample carries the sign.
                val resumed=pendingTurn?.takeIf {p->m.timeMs-p.first().timeMs<=r.turnTimeoutMs && (p.first().yawRadps!!>0)==(m.yawRadps>0)}
                turn=(resumed.orEmpty()+m).toMutableList();pendingTurn=null;yawQuiet=null
            }
            turn?.let {a->
                if(high) {append(a,m);yawQuiet=null} else if(yawQuiet==null) yawQuiet=m.timeMs
                if(m.timeMs-a.first().timeMs>r.turnTimeoutMs) {
                    emit(ManeuverType.UNKNOWN,a,holds=listOf("회전 종료 조건을 확인하지 못함"),interrupted=true);turn=null;yawQuiet=null
                } else if(yawQuiet?.let {m.timeMs-it>=r.yawQuietMs}==true) finishTurn(a)
            }
        } else if(turn!=null) {
            finishTurn(turn!!)
        }
        return completed.drop(before)
    }
    private fun append(a:MutableList<Motion>,m:Motion) {if(a.lastOrNull()?.timeMs!=m.timeMs) a+=m}
    private fun integral(a:List<Motion>):Double=a.zipWithNext().sumOf {(x,y)->if(x.yawRadps!=null && y.yawRadps!=null && y.timeMs-x.timeMs<=r.maxSampleGapMs) (x.yawRadps+y.yawRadps)*.5*(y.timeMs-x.timeMs)/1000.0 else 0.0}*180/PI
    private fun finishTurn(a:List<Motion>) {
        val duration=a.last().timeMs-a.first().timeMs;val delta=integral(a)
        val signs=a.mapNotNull {it.yawRadps}.filter {abs(it)>=r.yawBumpRadps}.map {it>0}.distinct()
        val peak=a.mapNotNull {it.yawRadps?.let(::abs)}.maxOrNull() ?: 0.0
        val type=when {
            duration<r.yawMinimumMs || peak<r.yawPeakRadps || signs.size!=1->ManeuverType.UNKNOWN
            abs(delta) in r.uTurnMinDeg..r.uTurnMaxDeg->ManeuverType.U_TURN
            abs(delta) in r.turnMinDeg..r.turnMaxDeg->if(delta>0) ManeuverType.LEFT else ManeuverType.RIGHT
            else->ManeuverType.UNKNOWN
        }
        // A one-sign segment short of turnMinDeg may be a turn interrupted by slowing (yielding). Keep it
        // briefly; a same-sign bump within turnPauseMaxMs continues it, otherwise it is dropped as before.
        pendingTurn=if(type==ManeuverType.UNKNOWN && signs.size==1 && abs(delta)<r.turnMinDeg) a.toList() else null
        // Small bumps include lane changes and curves; no road-width input means no lane-change claim.
        if(type!=ManeuverType.UNKNOWN) {
            val minKph=(a.mapNotNull {it.speedMps?.let(::abs)}.minOrNull() ?: 0.0)*3.6
            val flags=mapOf("yawDeltaDeg" to delta,"rapidTurn" to if(type!=ManeuverType.U_TURN && minKph>=r.rapidTurnMinKph && duration<=r.rapidTurnMaxMs) 1.0 else 0.0,
                "rapidUTurn" to if(type==ManeuverType.U_TURN && minKph>=r.rapidUTurnMinKph && duration<=r.rapidUTurnMaxMs) 1.0 else 0.0)
            val slow=a.all {abs(it.speedMps ?: 0.0)<=parkingLimitMps}
            if(slow && type!=ManeuverType.U_TURN) {releaseHeldTurn(true);heldTurn=HeldTurn(type,a,flags)}
            else emit(type,a,flags=flags)
        }
        turn=null;yawQuiet=null
    }
    private fun sustainedSigns(a:List<Motion>,value:(Motion)->Double?,threshold:Double):Set<Boolean> {
        val signs=mutableSetOf<Boolean>();var previous:Motion?=null;var run=0L;var sign:Boolean?=null
        for(m in a) {
            val v=value(m);val current=v?.takeIf {abs(it)>=threshold}?.let {it>0}
            val dt=previous?.let {m.timeMs-it.timeMs} ?: 0
            run=if(current!=null && current==sign && dt in 1..r.maxSampleGapMs) run+dt else 0
            if(current!=null && run>=r.harshRunMs) signs+=current
            sign=current;previous=m
        };return signs
    }
    private fun finishParking(a:List<Motion>) {
        val delta=integral(a);val coverage=a.count {it.yawRadps!=null}.toDouble()/a.size
        // Only moving reverse samples: turning the wheel while stopped in R does not shape the path.
        val reverse=a.filter {it.gear==Gear.REVERSE && abs(it.speedMps ?: 0.0)>r.stoppedMps}
        val steering=sustainedSigns(reverse,{it.steeringDeg},r.steeringNoiseDeg)
        val yaw=sustainedSigns(reverse,{it.yawRadps},r.yawBumpRadps)
        val type=when {
            coverage<r.minimumCoverage->ManeuverType.PARKING_UNKNOWN
            abs(delta) in r.parkingTMinDeg..r.parkingTMaxDeg && steering.size==1->ManeuverType.T_PARK
            abs(delta)<r.parkingParallelMaxDeg && steering.size==2 && yaw.size==2->ManeuverType.PARALLEL_PARK
            else->ManeuverType.PARKING_UNKNOWN
        }
        emit(type,a,gearCounter.reverseToDriveCount,mapOf("yawDeltaDeg" to delta),if(type==ManeuverType.PARKING_UNKNOWN) listOf("yaw·조향 패턴 부족 또는 주차 유형 경계") else emptyList())
        parking=null;parkRearm=false;parkBoundary=null;wasIdle=false;recent.clear()
    }
    /** A held slow turn followed by a stop in P without R: a forward-in park. The type is not classified. */
    private fun finishForwardParking() {
        releaseHeldTurn(false)
        val a=lowSpeedTail()
        clearLongitudinal();pendingTurn=null
        emit(ManeuverType.PARKING_UNKNOWN,a,0,mapOf("yawDeltaDeg" to integral(a),"forwardIn" to 1.0),listOf("후진 없이 전진으로 주차 · 주차 유형은 판정하지 않음"))
        parkRearm=false;wasIdle=false;recent.clear()
    }
    private fun hasRun(a:List<Motion>,threshold:Double,positive:Boolean):Boolean {
        var run=0L
        for((x,y) in a.zipWithNext()) {
            fun good(m:Motion)=m.acceleration?.let {if(positive) it>=threshold else -it>=threshold}==true
            val dt=y.timeMs-x.timeMs
            run=if(dt in 1..r.maxSampleGapMs && good(x) && good(y)) run+dt else 0
            if(run>=r.harshRunMs) return true
        };return false
    }
    private fun longitudinalFlags(a:List<Motion>,start:Boolean):Map<String,Double> {
        val initial=(a.mapNotNull {it.speedMps?.let(::abs)}.firstOrNull() ?: 0.0)*3.6
        val final=(a.mapNotNull {it.speedMps?.let(::abs)}.lastOrNull() ?: 0.0)*3.6
        return if(start) mapOf("harshStart" to if(initial<=r.harshStartMaxKph && hasRun(a,r.harshStartMps2,true)) 1.0 else 0.0)
        else mapOf("harshDeceleration" to if(hasRun(a,r.harshDecelerationMps2,false)) 1.0 else 0.0,"harshStop" to if(final<=r.harshStopMaxKph && hasRun(a,r.harshDecelerationMps2,false)) 1.0 else 0.0)
    }
    private fun abort(reason:String) {
        releaseHeldTurn(true) // a completed turn stays completed
        listOf(launch to ManeuverType.START,stopping to ManeuverType.STOP,acceleration to ManeuverType.ACCELERATION,turn to ManeuverType.UNKNOWN,parking to ManeuverType.PARKING_UNKNOWN).forEach {(a,type)->
            if(!a.isNullOrEmpty()) emit(type,a,holds=listOf(reason),interrupted=true)
        }
        launch=null;stopping=null;acceleration=null;turn=null;parking=null;decQuiet=null;accQuiet=null;yawQuiet=null;pendingTurn=null
    }
    fun finish(cancelled:Boolean):List<ManeuverResult> {abort(if(cancelled) "사용자가 동작을 중단함" else "동작 종료 전 코칭 종료");return results}
}
