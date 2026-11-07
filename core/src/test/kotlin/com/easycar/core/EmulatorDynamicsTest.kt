package com.easycar.core

import kotlin.math.PI
import kotlin.math.tan
import org.junit.Assert.*
import org.junit.Test

class EmulatorDynamicsTest {
    private val model=EmulatorDynamics()
    private fun frame(speed:Double=3.0,steering:Double=140.0,gear:Gear=Gear.DRIVE):VehicleFrame {
        fun <T> signal(value:T,unit:String)=Signal(value,1000,1000,unit,"TEST_CONSOLE")
        return VehicleFrame(1000,signal(speed,"m/s"),signal(steering,"degree:steering-wheel"),
            signal(gear,"gear"),null)
    }

    @Test fun usesBicycleEquationAndLabelsTheSimulationStepRatherThanClaimingASensor() {
        val input=frame()
        val yaw=model.yawRate(input)!!
        assertEquals(3.0/2.7*tan(140.0/15.0*PI/180),yaw.value,1e-12)
        assertEquals(EmulatorDynamics.SOURCE,yaw.source)
        assertEquals("rad/s:vehicle-z-left-positive",yaw.unit)
        assertEquals(1000L,yaw.measuredMs);assertEquals(1000L,yaw.receivedMs)
        assertEquals(Quality.VALID,yaw.quality);assertEquals(SignalTiming.MEASUREMENT,yaw.timing)
        assertEquals("TEST_CONSOLE",input.steering!!.source)
    }

    @Test fun positiveSteeringTurnsLeftInDriveAndRightInReverseRegardlessOfSpeedSign() {
        val forward=model.yawRate(frame())!!.value
        assertTrue(forward>0)
        assertEquals(-forward,model.yawRate(frame(gear=Gear.REVERSE))!!.value,1e-12)
        assertEquals(-forward,model.yawRate(frame(speed=-3.0,gear=Gear.REVERSE))!!.value,1e-12)
        assertEquals(-forward,model.yawRate(frame(steering=-140.0))!!.value,1e-12)
        assertEquals(0.0,model.yawRate(frame(steering=0.0))!!.value,0.0)
    }

    @Test fun missingStaleDisconnectedAndWrongUnitInputsDoNotInventYaw() {
        val f=frame()
        assertNull(model.yawRate(f.copy(speed=null)))
        assertNull(model.yawRate(f.copy(gear=null)))
        assertNull(model.yawRate(f.copy(steering=null)))
        assertNull(model.yawRate(f.copy(connected=false)))
        assertNull(model.yawRate(f.copy(speed=f.speed!!.copy(measuredMs=0,receivedMs=0))))
        assertNull(model.yawRate(f.copy(gear=f.gear!!.copy(measuredMs=0,receivedMs=0))))
        assertNull(model.yawRate(f.copy(steering=f.steering!!.copy(measuredMs=0,receivedMs=0))))
        assertNull(model.yawRate(f.copy(speed=f.speed!!.copy(unit="km/h"))))
        assertNull(model.yawRate(f.copy(steering=f.steering!!.copy(unit="rad"))))
        assertNull(model.yawRate(f.copy(gear=f.gear!!.copy(unit="unknown"))))
    }

    @Test fun receiptTimedControlsStayReceiptTimedAndOnlyTheirDerivedSimulationGetsANewTimestamp() {
        val f=frame()
        val input=f.copy(steering=f.steering!!.copy(measuredMs=0,receivedMs=1000,
            quality=Quality.ESTIMATED_TIME,timing=SignalTiming.RECEIPT_ESTIMATE),
            gear=f.gear!!.copy(measuredMs=0,receivedMs=1000,timing=SignalTiming.CURRENT_STATE_READ))
        val yaw=model.yawRate(input)!!
        assertEquals(1000L,yaw.measuredMs)
        assertEquals(0L,input.steering!!.measuredMs)
        assertEquals(SignalTiming.RECEIPT_ESTIMATE,input.steering!!.timing)
        assertEquals(Quality.ESTIMATED_TIME,input.steering!!.quality)
        assertNull(model.yawRate(input.copy(timeMs=1751)))
    }

    @Test fun parkAndNeutralOnlyProduceZeroWhileStopped() {
        for(gear in listOf(Gear.PARK,Gear.NEUTRAL)) {
            assertEquals(0.0,model.yawRate(frame(speed=0.0,gear=gear))!!.value,0.0)
            assertNull(model.yawRate(frame(speed=1.0,gear=gear)))
        }
        assertNull(model.yawRate(frame(gear=Gear.UNKNOWN)))
    }

    @Test fun invalidNumbersQualityOrImpossibleSteeringRemainMissing() {
        val f=frame()
        assertNull(model.yawRate(frame(speed=Double.NaN)))
        assertNull(model.yawRate(frame(speed=Double.POSITIVE_INFINITY)))
        assertNull(model.yawRate(frame(steering=Double.NaN)))
        assertNull(model.yawRate(frame(steering=1350.0)))
        assertNull(model.yawRate(f.copy(speed=f.speed!!.copy(quality=Quality.MISSING))))
        assertNull(model.yawRate(f.copy(steering=f.steering!!.copy(quality=Quality.LOW_CONFIDENCE))))
    }

    @Test fun declaredSimulationParametersChangeTheEquationAndInvalidParametersAreRejected() {
        val adjusted=EmulatorDynamics(wheelbaseM=3.0,steeringRatio=20.0)
        assertEquals(3.0/3.0*tan(140.0/20.0*PI/180),adjusted.yawRate(frame())!!.value,1e-12)
        assertTrue(runCatching {EmulatorDynamics(wheelbaseM=0.0)}.isFailure)
        assertTrue(runCatching {EmulatorDynamics(steeringRatio=Double.NaN)}.isFailure)
        assertTrue(runCatching {EmulatorDynamics(maxSignalAgeMs=-1)}.isFailure)
    }
}
