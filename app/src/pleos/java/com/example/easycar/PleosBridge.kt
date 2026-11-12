package com.example.easycar

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.*
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.SteeringWheel
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.easycar.core.*
import com.example.easycar.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// Pleos Connect v2.1 runs Android 14 car services. The non-deprecated replacements
// (subscribePropertyEvents, getPropertyStatus) are Android 15+ and would fail there.
@Suppress("DEPRECATION")
class PleosBridge(private val context:Context):PlatformBridge {
    private val emulated=Build.HARDWARE in setOf("ranchu","goldfish") || Build.MODEL.contains("emulator",true)
    // This explicitly labelled debug simulation is never enabled on a physical vehicle.
    private val emulatorDynamics=if(BuildConfig.DEBUG && emulated) EmulatorDynamics() else null
    private val modelNotice="에뮬레이터 회전 모델 · yaw 센서 아님 · 축거2.7m/조향비15"
    private val mutable=MutableStateFlow(PlatformState(isEmulator=emulated));override val state=mutable.asStateFlow()
    private val latest=MutableStateFlow<VehicleFrame?>(null);override val latestFrame=latest.asStateFlow()
    private val bus=MutableSharedFlow<VehicleFrame>(extraBufferCapacity=64);override val frames=bus.asSharedFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var car:Car?=null;private var properties:CarPropertyManager?=null;private var ux:CarUxRestrictionsManager?=null
    private var sdk:Vehicle?=null;private var wheel:SteeringWheel?=null;private var location:FusedLocation?=null
    private var polling:Job?=null;private var started=false;private var epoch=0
    private var speed:Signal<Double>?=null;private var gear:Signal<Gear>?=null;private var steering:Signal<Double>?=null;private var heading:Signal<Double>?=null
    private fun now()=SystemClock.elapsedRealtime()
    private fun error(e:Exception)=when(e) { is SecurityException->"권한 없음";else->e.message?.take(90) ?: "미지원 또는 연결 실패" }
    private val restrictionsListener=CarUxRestrictionsManager.OnUxRestrictionsChangedListener { applyUx(it) }
    private fun applyUx(r:CarUxRestrictions?) { mutable.value=mutable.value.copy(ux=if(r==null) UxState() else UxState(true,r.isRequiresDistractionOptimization || r.activeRestrictions!=CarUxRestrictions.UX_RESTRICTIONS_BASELINE)) }
    private val callback=object:CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v:CarPropertyValue<*>) {
            if(v.status!=CarPropertyValue.STATUS_AVAILABLE) { unavailable(v.propertyId,"신호 사용 불가");return }
            val t=v.timestamp/1000000L;val received=now()
            when(v.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> { speed=Signal((v.value as Number).toDouble(),t,received,"m/s","AAOS PERF_VEHICLE_SPEED");mutable.value=mutable.value.copy(speed="${formatValue(kotlin.math.abs(speed!!.value)*3.6)} km/h") }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val g=when((v.value as Number).toInt()) { 1->Gear.NEUTRAL;2->Gear.REVERSE;4->Gear.PARK;8->Gear.DRIVE;else->Gear.UNKNOWN }
                    gear=Signal(g,t,received,"gear","AAOS GEAR_SELECTION");mutable.value=mutable.value.copy(gear=g.name)
                }
            }
        }
        override fun onErrorEvent(propertyId:Int,zone:Int) { unavailable(propertyId,"신호 오류 또는 접근 불가") }
    }
    private fun unavailable(id:Int,message:String) { if(id==VehiclePropertyIds.PERF_VEHICLE_SPEED) { speed=null;mutable.value=mutable.value.copy(speed=message) } else { gear=null;mutable.value=mutable.value.copy(gear=message) } }
    private val wheelListener=object:SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle:Float?) { receiveWheel(angle) }
        override fun onFailed(e:Exception) { steering=null;mutable.value=mutable.value.copy(steering=error(e)) }
    }
    private fun receiveWheel(value:Float?) {
        val t=now()
        // This SDK callback has no sensor measurement timestamp. Preserve that limitation in source/quality.
        steering=value?.let { Signal(it.toDouble(),t,t,"degree:steering-wheel","Pleos Vehicle 2.3.2 · 수신 시각만 제공",Quality.ESTIMATED_TIME,SignalTiming.RECEIPT_ESTIMATE) }
        mutable.value=mutable.value.copy(steering=value?.let { "${formatValue(it.toDouble())}° · 측정 시각 미제공" } ?: "조향 신호 없음")
    }
    private val locationListener=object:FusedLocationListener {
        override fun onChanged(location:LocationData?) {
            if(location==null) { heading=null;mutable.value=mutable.value.copy(heading="위치·진행 방향 없음");return }
            // SDK field units exist, but timestamp clock domain and confidence semantics need OEM verification.
            heading=Signal(location.heading.toDouble(),now(),now(),"degree:heading","Pleos FusedLocation 1.1.3 · 원시 시각=${location.timestampMillis}, 신뢰도=${location.confidence}",Quality.LOW_CONFIDENCE)
            mutable.value=mutable.value.copy(heading="${formatValue(location.heading.toDouble())}° · 품질 기준 확인 필요")
        }
        override fun onError(exception:Exception) { heading=null;mutable.value=mutable.value.copy(heading=error(exception)) }
    }
    override fun start() {
        if(started) return;started=true;val generation=++epoch
        if(!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) { mutable.value=PlatformState(connection="일반 Android 기기 · 데모를 선택해 주세요",ux=UxState(true,false));return }
        mutable.value=mutable.value.copy(connection="Pleos 차량 서비스 연결 중",vehicleConnected=false)
        try {
            car=Car.createCar(context,Handler(Looper.getMainLooper()),Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected,ready ->
                if(!started || generation!=epoch) return@createCar
                if(!ready) { speed=null;gear=null;applyUx(null);mutable.value=mutable.value.copy(connection="차량 연결 끊김",vehicleConnected=false);return@createCar }
                mutable.value=mutable.value.copy(connection=if(emulatorDynamics!=null) "AAOS 연결됨 · $modelNotice" else "AAOS 연결됨 · 신호별 지원 상태 확인",vehicleConnected=true)
                runCatching {
                    ux=connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    ux?.registerListener(restrictionsListener);applyUx(ux?.currentCarUxRestrictions)
                }.onFailure { applyUx(null) }
                runCatching {
                    properties=connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    for(id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED,VehiclePropertyIds.GEAR_SELECTION)) {
                        try {
                            val config=properties?.getCarPropertyConfig(id)
                            if(config==null) unavailable(id,"미지원 또는 권한 없음")
                            else if(properties?.registerCallback(callback,id,10f)!=true) unavailable(id,"구독 거부됨")
                        } catch(e:Exception) { unavailable(id,error(e)) }
                    }
                }.onFailure { mutable.value=mutable.value.copy(connection="차량 속성 서비스 실패",vehicleConnected=false) }
            }
        } catch(e:Exception) { mutable.value=mutable.value.copy(connection=error(e),vehicleConnected=false) }
        try {
            sdk=Vehicle(context).also { it.initialize() };wheel=sdk?.getSteeringWheel()
            scope.launch {
                delay(800)
                if(!started || generation!=epoch) return@launch
                wheel?.checkSteeringWheelAngleCapabilities(onComplete={ c,_ ->
                    if(started && generation==epoch && c.accessType in listOf(AccessType.READ,AccessType.READ_WRITE)) {
                        wheel?.registerSteeringWheelAngle(wheelListener)
                        wheel?.getCurrentSteeringWheelAngle(onComplete={ receiveWheel(it) },onFailed={ wheelListener.onFailed(it) })
                    } else mutable.value=mutable.value.copy(steering="조향각 읽기 미지원")
                },onFailed={ wheelListener.onFailed(it) })
            }
        } catch(e:Exception) { mutable.value=mutable.value.copy(steering=error(e)) }
        try {
            location=FusedLocation(context).also { it.initialize() }
            if(location?.isSupported()==true) location?.registerFusedLocationCallback(locationListener) else mutable.value=mutable.value.copy(heading="FusedLocation 미지원")
        } catch(e:Exception) { mutable.value=mutable.value.copy(heading=error(e)) }
        polling=scope.launch {
            var tick=0
            while(isActive && started) {
                if(tick++%(if(emulatorDynamics!=null) 2 else 5)==0) {
                    // Current-state reads keep ON_CHANGE steering usable for recognition;
                    // receipt estimates remain ineligible for sensor-rate scoring.
                    runCatching {wheel?.getCurrentSteeringWheelAngle(onComplete={if(started && generation==epoch) receiveWheel(it)},onFailed={if(started && generation==epoch) wheelListener.onFailed(it)})}
                    // GEAR_SELECTION is ON_CHANGE. A fresh successful read confirms the current state
                    // while preserving its original last-change measurement timestamp.
                    try {
                        val value=withContext(Dispatchers.IO) {properties?.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION,0)}
                        if(value!=null) {callback.onChangeEvent(value);gear=gear?.copy(timing=SignalTiming.CURRENT_STATE_READ)}
                    } catch(e:Exception) {unavailable(VehiclePropertyIds.GEAR_SELECTION,error(e))}
                }
                val t=now()
                // Real devices have no verified yaw provider. Debug emulators alone use the declared
                // bicycle model driven by the actual console controls, never maneuver labels/scores.
                val observed=VehicleFrame(t,speed,steering,gear,heading,car?.isConnected==true,yawRate=null,lateralAcceleration=null,ignitionOn=null)
                val frame=if(emulatorDynamics!=null) observed.copy(yawRate=emulatorDynamics.yawRate(observed)) else observed
                if(emulatorDynamics!=null) mutable.value=mutable.value.copy(heading=frame.yawRate?.let {
                    "${formatValue(it.value)} rad/s · 모델 계산 · 센서 아님"
                } ?: "회전 모델 입력 확인 중 · 센서 아님")
                latest.value=frame;bus.emit(frame)
                delay(100)
            }
        }
    }
    override fun stop() {
        if(!started) return;started=false;epoch++;polling?.cancel();polling=null
        runCatching { properties?.unregisterCallback(callback) };runCatching { ux?.unregisterListener() }
        runCatching { wheel?.unregisterSteeringWheelAngle(wheelListener) };runCatching { sdk?.release() }
        runCatching { location?.unregisterFusedLocationCallback(locationListener);location?.release() }
        runCatching { car?.disconnect() };car=null;sdk=null;wheel=null;properties=null;ux=null;location=null
        speed=null;gear=null;steering=null;heading=null;latest.value=null;mutable.value=mutable.value.copy(ux=UxState(),connection="차량 연결 중지됨",vehicleConnected=false)
    }
    override fun release() { stop();scope.cancel() }
}
