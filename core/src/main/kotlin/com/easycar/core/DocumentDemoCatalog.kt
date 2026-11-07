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
}
