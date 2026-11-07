package com.easycar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * A bicycle simulation driven by the emulator's observed speed, gear and steering controls.
 * The parameters are demonstration assumptions, not measured vehicle calibration. The returned
 * timestamp describes this simulation step; VALID/MEASUREMENT do not make this a physical sensor.
 * A platform adapter must enable this only for an explicitly labelled emulator simulation.
 */
class EmulatorDynamics(
    val wheelbaseM:Double=2.7,
    val steeringRatio:Double=15.0,
    private val maxSignalAgeMs:Long=750,
    private val stoppedMps:Double=.15
) {
    init {
        require(wheelbaseM.isFinite() && wheelbaseM>0)
        require(steeringRatio.isFinite() && steeringRatio>0)
        require(maxSignalAgeMs>=0 && stoppedMps.isFinite() && stoppedMps>=0)
    }

    fun yawRate(frame:VehicleFrame):Signal<Double>? {
        if(!frame.connected || frame.timeMs<0) return null
        fun <T> input(signal:Signal<T>?,unit:String):T? =
            signal?.takeIf {it.unit==unit && it.usable(frame.timeMs,maxSignalAgeMs)}?.value
        val speed=input(frame.speed,"m/s")?.takeIf {it.isFinite() && abs(it)<=90} ?: return null
        val steering=input(frame.steering,"degree:steering-wheel")?.takeIf {it.isFinite()} ?: return null
        val gear=input(frame.gear,"gear") ?: return null
        val velocity=when(gear) {
            Gear.DRIVE->abs(speed)
            Gear.REVERSE->-abs(speed)
            Gear.PARK,Gear.NEUTRAL->if(abs(speed)<=stoppedMps) 0.0 else return null
            Gear.UNKNOWN->return null
        }
        val roadWheelRad=steering/steeringRatio*PI/180
        if(abs(roadWheelRad)>=PI/2) return null
        val yaw=velocity/wheelbaseM*tan(roadWheelRad)
        if(!yaw.isFinite()) return null
        return Signal(yaw,frame.timeMs,frame.timeMs,"rad/s:vehicle-z-left-positive",SOURCE,
            Quality.VALID,SignalTiming.MEASUREMENT)
    }

    companion object {
        const val SOURCE="EMULATOR_BICYCLE_MODEL_NOT_SENSOR"
    }
}
