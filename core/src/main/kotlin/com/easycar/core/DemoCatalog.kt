package com.easycar.core

import kotlin.math.*

data class DemoCase(val id: String, val title: String, val group: String, val context: PracticeContext,
    val frames: List<VehicleFrame>, val videoAngle: Double?=null, val videoShift: Double=0.0,
    val videoOccluded: Boolean=false, val videoNoLines: Boolean=false, val fault: String?=null)
object DemoCatalog {
    private fun context(parking: ManeuverType?=null, controlled: Boolean=true)=PracticeContext("demo-pad-a","synthetic-sedan",controlled,null,"사용자 동작 선택 없이 자동 관측",if(parking!=null) 0.0 else null,"합성 데모 데이터",null)
    private class Wave {
        val frames=mutableListOf<VehicleFrame>(); var t=0L
        fun add(duration: Double,gear: Gear, speed:(Double)->Double={0.0}, steering:(Double)->Double={0.0}, heading:(Double)->Double={0.0}, headingValid: Boolean=true) {
            val count=(duration*10).roundToInt()
            repeat(count) { i ->
                val u=if(count>1) i.toDouble()/(count-1) else 0.0
                fun <T> sig(v:T,unit:String,q:Quality=Quality.VALID)=Signal(v,t,t,unit,"SYNTHETIC_DEMO",q)
                frames+=VehicleFrame(t,sig(speed(u),"m/s"),sig(steering(u),"degree:steering-wheel"),sig(gear,"gear"),sig((heading(u)+360)%360,"degree:heading",if(headingValid) Quality.VALID else Quality.LOW_CONFIDENCE)); t+=100
            }
        }
    }
    private fun turn(id:String,title:String,degrees:Double,fast:Boolean=false,rough:Boolean=false,headingValid:Boolean=true):DemoCase {
        val w=Wave(); val v=if(fast) 7.5 else 3.0; val sign=if(degrees<0) -1 else 1
        w.add(2.0,Gear.DRIVE)
        w.add(3.0,Gear.DRIVE,{v*it})
        w.add(1.0,Gear.DRIVE,{v})
        w.add(6.0,Gear.DRIVE,{v},{u->sign*(if(rough) 260*sin(PI*u).pow(.25) else 180*sin(PI*u))},{u->degrees*(u*u*(3-2*u))},headingValid)
        w.add(2.0,Gear.DRIVE,{v},heading={degrees},headingValid=headingValid)
        w.add(3.0,Gear.DRIVE,{v*(1-it)},heading={degrees},headingValid=headingValid)
        w.add(2.0,Gear.DRIVE,heading={degrees},headingValid=headingValid)
        val focus=when {abs(degrees) in 150.0..210.0->ManeuverType.U_TURN;degrees in 55.0..120.0->ManeuverType.RIGHT;degrees in -120.0..-55.0->ManeuverType.LEFT;else->null}
        return DemoCase(id,title,"회전",context(),w.frames)
    }
    private fun parking(n:Int,type:ManeuverType,variant:String="normal"):DemoCase {
        val w=Wave(); w.add(2.0,Gear.DRIVE,{.5}); w.add(1.5,Gear.DRIVE)
        val finalHeading=if(type==ManeuverType.T_PARK) 90.0 else 0.0
        var first=true
        fun leg(gear:Gear) {
            val entering=first;first=false
            val base=if(entering) 0.0 else finalHeading
            w.add(.8,gear,heading={base})
            val sign=if(gear==Gear.REVERSE) -1 else 1
            val seconds=if(entering) 8.0 else 2.0
            w.add(seconds,gear,{u->sign*(if(variant=="speed") 2.1 else if(entering) 1.3 else .6)*sin(PI*u)},
                {u->if(entering) (if(type==ManeuverType.T_PARK) 300*sin(PI*u) else 150*sin(2*PI*u)) else 30*sin(2*PI*u)},
                {u->if(entering) (if(type==ManeuverType.T_PARK) 90*u*u*(3-2*u) else 25*sin(2*PI*u)) else finalHeading+3*sin(2*PI*u)})
            w.add(.8,gear,heading={finalHeading})
        }
        leg(Gear.REVERSE)
        repeat(n) { i ->
            if(i%2==0) w.add(.8,Gear.NEUTRAL,heading={finalHeading})
            leg(Gear.DRIVE)
            if(i<n-1) leg(Gear.REVERSE)
        }
        w.add(2.0,Gear.PARK,heading={finalHeading})
        val observed=w.frames.map {f->if(f.timeMs<2000) f else f.copy(parkingScene=Signal(ParkingScene("synthetic-space",0.0,finalHeading,true,true,"합성 공간축 관측"),f.timeMs,f.timeMs,"degree:scene-axes","SYNTHETIC_DEMO"))}
        val a=when(variant) { "tilted"->10.0; else->1.0 }
        return DemoCase("park-${type.name.lowercase()}-$n-$variant", "${type.title} · R→D ${n}회"+when(variant){"tilted"->" · 정렬 기울어짐";"intrusion"->" · 선 침범";"occluded"->" · 선 가림";"missing"->" · 영상 없음";"speed"->" · 이동 속도 높음";else->""},"주차",context(type),observed,
            if(variant=="missing") null else a,if(variant=="intrusion") 75.0 else 0.0,variant=="occluded")
    }
    private fun launch(rough:Boolean,unknown:Boolean=false):DemoCase {
        val w=Wave(); w.add(2.0,Gear.DRIVE)
        w.add(if(rough) 1.0 else 4.0,Gear.DRIVE,{4*it})
        w.add(5.0,Gear.DRIVE,{4.0})
        w.add(if(rough) .8 else 4.0,Gear.DRIVE,{4*(1-it)})
        w.add(2.0,Gear.DRIVE)
        return DemoCase(if(unknown) "unknown-brake" else if(rough) "rough-start-stop" else "smooth-start-stop",if(unknown) "급감속 · 원인 미확인" else if(rough) "급출발과 거친 정지" else "부드러운 출발과 정지","출발·정지",context(controlled=!unknown),w.frames)
    }
    private fun completeDrive():DemoCase {
        val w=Wave();w.add(2.0,Gear.DRIVE);w.add(3.0,Gear.DRIVE,{3*it});w.add(6.0,Gear.DRIVE,{3.0})
        var heading=0.0
        for((angle,seconds) in listOf(-90.0 to 6.0,90.0 to 6.0,180.0 to 8.0)) {
            val base=heading
            w.add(seconds,Gear.DRIVE,{3.0},{u->(if(angle<0) -1 else 1)*180*sin(PI*u)},{u->base+angle*u*u*(3-2*u)})
            heading+=angle
            w.add(7.0,Gear.DRIVE,{3.0},heading={heading})
        }
        w.add(3.0,Gear.DRIVE,{3*(1-it)},heading={heading});w.add(2.0,Gear.DRIVE,heading={heading})
        return DemoCase("drive-course","전체 주행 · 출발부터 정지까지","전체 주행",context(),w.frames)
    }
    private fun automaticDrive():DemoCase {
        val all=completeDrive().frames.toMutableList()
        fun append(c:DemoCase) {
            val shift=all.last().timeMs+100
            val angle=all.last().heading!!.value
            fun <T> shifted(signal:Signal<T>?)=signal?.copy(measuredMs=signal.measuredMs+shift,receivedMs=signal.receivedMs+shift)
            all+=c.frames.map {f->
                val scene=shifted(f.parkingScene)?.let {s->s.copy(value=s.value.copy(roadAxisDeg=s.value.roadAxisDeg?.let {(it+angle)%360},slotAxisDeg=s.value.slotAxisDeg?.let {(it+angle)%360}))}
                f.copy(timeMs=f.timeMs+shift,speed=shifted(f.speed),steering=shifted(f.steering),gear=shifted(f.gear),heading=shifted(f.heading)?.let {it.copy(value=(it.value+angle)%360)},parkingScene=scene)
            }
        }
        append(parking(3,ManeuverType.T_PARK));append(parking(1,ManeuverType.PARALLEL_PARK))
        return DemoCase("auto-drive","자동 코칭 · 일곱 동작 연속 주행","자동 코칭",context(),all,videoAngle=1.0)
    }
    val cases: List<DemoCase> by lazy {
        val list=mutableListOf(
            automaticDrive(),completeDrive(),
            turn("left","좌회전 · 기본 연습",-90.0),turn("right","우회전 · 기본 연습",90.0),turn("uturn","유턴 · 제한된 데모 코스",180.0),
            turn("fast-left","좌회전 · 진입 속도 높음",-90.0,true),turn("rough-right","우회전 · 급격한 조향",90.0,rough=true),
            turn("curve","곡선 도로 · 회전 확정 안 함",25.0),turn("low-heading","진행 방향 신뢰도 부족",90.0,headingValid=false),
            turn("fast-right","우회전 · 진입 속도 높음",90.0,true),turn("fast-uturn","유턴 · 진입 속도 높음",180.0,true),
            turn("rough-left","좌회전 · 급격한 조향",-90.0,rough=true),turn("rough-uturn","유턴 · 급격한 조향",180.0,rough=true),
            launch(false),launch(true),launch(true,true))
        for((focus,kind) in listOf(ManeuverType.START to "start",ManeuverType.STOP to "stop")) {
            for(rough in listOf(false,true)) {
                val c=launch(rough)
                list+=c.copy(id="$kind-${if(rough) "rough" else "smooth"}",title="${focus.title} · ${if(rough) "급격한 속도 변화" else "부드러운 연습"}",context=c.context.copy(focusType=null))
            }
        }
        val lane=turn("lane-change","차로 변경 · S자 조향",0.0)
        list+=lane.copy(frames=lane.frames.map { f -> if(f.timeMs in 6000..12000) f.copy(steering=f.steering?.copy(value=130*sin((f.timeMs-6000)/6000.0*2*PI))) else f })
        for(type in listOf(ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK)) for(n in 0..5) list+=parking(n,type)
        for(v in listOf("tilted","intrusion","occluded","missing","speed")) list+=parking(3,ManeuverType.T_PARK,v)
        val noSpace=parking(2,ManeuverType.T_PARK)
        list+=noSpace.copy(id="parking-space-missing",title="공간 근거 없음 · 주차 유형 보류",frames=noSpace.frames.map {it.copy(parkingScene=null)})
        list+=noSpace.copy(id="parking-space-ambiguous",title="사선 주차 공간 · 유형 보류",frames=noSpace.frames.map {f->f.copy(parkingScene=f.parkingScene?.let {s->s.copy(value=s.value.copy(slotAxisDeg=45.0))})})
        val normal=turn("fault-base","",-90.0)
        list+=normal.copy(id="delayed",title="신호 지연 · 평가 보류",group="연결·제한",fault="delayed",frames=normal.frames.map { if(it.timeMs in 8500..10500) it.copy(speed=it.speed?.copy(measuredMs=it.timeMs-2000)) else it })
        list+=normal.copy(id="gap",title="신호 누락 · 공백 처리",group="연결·제한",fault="gap",frames=normal.frames.filter { it.timeMs !in 8500..10500 })
        list+=normal.copy(id="disconnect",title="연결 끊김 · 자동 데모 전환 없음",group="연결·제한",fault="disconnect",frames=normal.frames.map { if(it.timeMs in 8500..10500) it.copy(connected=false) else it })
        val disorder=normal.frames.toMutableList().also { it.add(90, it[85]); it.add(100,it[99]) }
        list+=normal.copy(id="out-of-order",title="역순·중복 신호",group="연결·제한",fault="out-of-order",frames=disorder)
        list+=normal.copy(id="restrictions",title="주행 제한·오디오 중단",group="연결·제한",fault="restrictions")
        list
    }
    fun case(id:String)=cases.first { it.id==id }
}
