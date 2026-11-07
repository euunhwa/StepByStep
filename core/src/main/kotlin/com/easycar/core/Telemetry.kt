package com.easycar.core

import kotlin.math.abs

/** Event-time processing. Never uses playback wall time. A 3-sample median adds <= 200 ms at 10 Hz. */
class TelemetryPipeline(private val config: DetectionConfig) {
    private var previous: Motion? = null
    private var lastTime = -1L
    private val speeds = ArrayDeque<Double>()
    private val angles = ArrayDeque<Double>()
    var rejectedFrames = 0; private set
    fun accept(frame: VehicleFrame): Motion? {
        if (frame.timeMs <= lastTime) { rejectedFrames++; return null }
        val gap = !frame.connected || (lastTime >= 0 && frame.timeMs - lastTime > config.maxGapMs)
        lastTime = frame.timeMs
        fun <T> valid(s: Signal<T>?) = if (frame.connected && s?.usable(frame.timeMs, config.maxSignalAgeMs) == true) s.value else null
        val speed = valid(frame.speed)?.takeIf { it.isFinite() && abs(it) <= 90 }
        val angle = valid(frame.steering)?.takeIf { it.isFinite() && abs(it) <= 1080 }
        val gear = valid(frame.gear)?.takeIf { it != Gear.UNKNOWN }
        val heading = valid(frame.heading)?.takeIf { it.isFinite() && it in 0.0..360.0 }
        fun measured(s:Signal<Double>?,unit:String)=valid(s)?.takeIf { it.isFinite() && s?.unit==unit && s.quality==Quality.VALID && s.timing==SignalTiming.MEASUREMENT }
        val yaw=measured(frame.yawRate,"rad/s:vehicle-z-left-positive")?.takeIf {abs(it)<=5}
        val lateral=measured(frame.lateralAcceleration,"m/s2:vehicle-y-left-positive")?.takeIf {abs(it)<=30}
        val p = previous
        if (gap || p?.gear != gear) { speeds.clear(); angles.clear() }
        if(speed==null) speeds.clear()
        if(angle==null) angles.clear()
        fun median(q: ArrayDeque<Double>, v: Double?): Double? {
            if (v == null) return null
            q.addLast(v); if (q.size > 3) q.removeFirst()
            return q.sorted()[q.size / 2]
        }
        val v = median(speeds, speed); val a = median(angles, angle)
        val dt = if (p != null) (frame.timeMs - p.timeMs) / 1000.0 else 0.0
        val derivativeOk = !gap && dt in .05..1.0 && p?.gear == gear && gear in listOf(Gear.DRIVE, Gear.REVERSE)
        val acc = if (derivativeOk && v != null && p?.speedMps != null) (abs(v) - abs(p.speedMps)) / dt else null
        val jerk = if (acc != null && p?.acceleration != null) (acc - p.acceleration) / dt else null
        val rate = if (frame.steering?.quality==Quality.VALID && frame.steering.timing==SignalTiming.MEASUREMENT && p?.steeringTimingReliable==true && derivativeOk && a != null && p?.steeringDeg != null) (a - p.steeringDeg) / dt else null
        return Motion(frame.timeMs, v, a, gear, heading, acc, jerk, rate, gap,steeringTimingReliable=frame.steering?.quality==Quality.VALID && frame.steering.timing==SignalTiming.MEASUREMENT,parkingScene=valid(frame.parkingScene)?.takeIf {frame.parkingScene?.quality==Quality.VALID && it.geometryVerified && it.targetAssociated && it.targetId.isNotBlank() && it.source.isNotBlank()},parkingSceneTimeMs=frame.parkingScene?.let {if(it.timing==SignalTiming.CURRENT_STATE_READ) it.receivedMs else it.measuredMs},yawRadps=yaw,lateralMps2=lateral,ignitionOn=valid(frame.ignitionOn)).also { previous = it }
    }
}

/** Neutral is not a driving gear; retain last stable R/D across neutral. */
class GearTransitionCounter(private val stableMs: Long = 500) {
    private var candidate: Gear? = null
    private var since = 0L
    private var lastTime = -1L
    private var lastDrivingGear: Gear? = null
    var stableGear: Gear? = null; private set
    var reverseToDriveCount = 0; private set
    fun update(gear: Gear?, timeMs: Long): Gear? {
        if (timeMs <= lastTime) return stableGear
        if (lastTime >= 0 && timeMs - lastTime > 1000) { candidate = null; lastDrivingGear = null; stableGear = null }
        lastTime = timeMs
        if (gear == null || gear == Gear.UNKNOWN) { candidate = null; lastDrivingGear = null; stableGear = null; return null }
        if (candidate != gear) { candidate = gear; since = timeMs }
        if (timeMs - since < stableMs || stableGear == gear) return stableGear
        stableGear = gear
        if (gear == Gear.DRIVE || gear == Gear.REVERSE) {
            if (lastDrivingGear == Gear.REVERSE && gear == Gear.DRIVE) reverseToDriveCount++
            lastDrivingGear = gear
        }
        if (gear == Gear.PARK) lastDrivingGear = null
        return stableGear
    }
}
