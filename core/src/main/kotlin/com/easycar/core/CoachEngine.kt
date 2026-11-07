package com.easycar.core

import kotlin.math.abs

interface ManeuverRecognizer { fun accept(frame:VehicleFrame):List<ManeuverResult> }
/** All transitions use measurement/event time; input labels and playback wall time are not consulted. */
class CoachEngine(val profile:ReferenceProfile,val context:PracticeContext,val config:DetectionConfig=DetectionConfig(),val evaluationPolicy:EvaluationPolicy?=null):ManeuverRecognizer {
    private val documentEngine=evaluationPolicy?.documentDataset?.let {DocumentCoachEngine(profile,context,it)}
    init {require((profile.validationStatus=="DOCUMENT_DERIVED_DRAFT")== (documentEngine!=null)) {"새 인식·평가 기준을 함께 로드해야 합니다"}}
    private val pipeline=TelemetryPipeline(config)
    private val scorer=DeterministicScorer(profile,evaluationPolicy)
    private val recent=ArrayDeque<Motion>()
    private val completed=mutableListOf<ManeuverResult>()
    private var turn:MutableList<Motion>?=null
    private var launch:MutableList<Motion>?=null
    private var stopping:MutableList<Motion>?=null
    private var parking:MutableList<Motion>?=null
    private var parkingCounter=GearTransitionCounter(config.gearStableMs)
    private val stableGear=GearTransitionCounter(config.gearStableMs)
    private var launchOnset=0L
    private var stationarySince:Long?=null
    private var centeredSince:Long?=null
    private var parkSince:Long?=null
    private var wasStopped=false
    private var stopArmed=false
    private var suppressed=false
    private var parkingEntry:Double?=null
    private var parkingCanStart=true
    private var parkingCompletedAt:Long?=null
    private var parkingEvidenceSamples:List<Motion>?=null
    private var parkingEvidenceCount=0
    private var parkingEvidenceEntry:Double?=null
    private var parkingResultId:String?=null
    private var parkingScene:ParkingScene?=null
    private var parkingSceneConflict=false
    private var parkingSceneTimeMs:Long?=null
    private var parkingRecognition:RecognitionEvidence?=null
    private var completedParkingType:ManeuverType?=null
    private var rearmSince:Long?=null
    private var legacyLastMotion:Motion?=null
    var lastMotion:Motion?
        get()=documentEngine?.lastMotion ?: legacyLastMotion
        private set(value) {legacyLastMotion=value}
    val results get()=documentEngine?.results ?: completed.toList()
    val activeType get()=documentEngine?.activeType ?: when {parking!=null->"저속 조작·주차 후보 관측 중";turn!=null->"회전 중";launch!=null->"출발 중";stopping!=null->"정지 중";else->"다음 동작을 관측하고 있어요"}
    val reverseToDriveCount get()=documentEngine?.reverseToDriveCount ?: parkingCounter.reverseToDriveCount
    private fun emit(r:ManeuverResult) {if(completed.none {it.id==r.id}) completed+=r}
    override fun accept(frame:VehicleFrame):List<ManeuverResult> {
        documentEngine?.let {return it.accept(frame)}
        val before=completed.size
        val m=pipeline.accept(frame) ?: return emptyList()
        val previous=lastMotion;lastMotion=m
        if(m.gap || m.speedMps==null || m.gear==null) {
            abort("속도·기어 신호 공백 또는 연결 해제")
            stableGear.update(null,m.timeMs)
            recent.clear();stationarySince=null;wasStopped=false;stopArmed=false;suppressed=true
            return completed.drop(before)
        }
        recent+=m;while(recent.isNotEmpty() && m.timeMs-recent.first().timeMs>config.preRollMs) recent.removeFirst()
        val stopped=abs(m.speedMps)<=config.stoppedMps
        if(stopped) {
            if(stationarySince==null) stationarySince=m.timeMs
            if(m.timeMs-stationarySince!!>=config.standstillMs) {wasStopped=true;suppressed=false}
        } else stationarySince=null
        if(suppressed) return completed.drop(before)
        val gear=stableGear.update(m.gear,m.timeMs)
        // The same supervisor always monitors all seven classes. Legacy selection fields are ignored.
        val nearSpace=m.parkingScene!=null
        if(!parkingCanStart && gear==Gear.DRIVE && m.gear==Gear.DRIVE && !nearSpace && !stopped) {
            if(rearmSince==null) rearmSince=m.timeMs
            if(m.timeMs-rearmSince!!>=config.parkingRearmMs) {parkingCanStart=true;parkingCompletedAt=null}
        } else rearmSince=null
        val reverseCandidate=gear==Gear.REVERSE && m.gear==Gear.REVERSE
        val forwardParkingCandidate=nearSpace && gear==Gear.DRIVE && m.gear==Gear.DRIVE && !stopped
        if(parking==null && parkingCanStart && abs(m.speedMps)<config.maxParkingMps && (reverseCandidate || forwardParkingCandidate)) {
            val first=if(reverseCandidate) recent.toList().indexOfLast {it.gear!=Gear.REVERSE}+1 else recent.lastIndex
            parking=recent.toList().drop(first).toMutableList()
            val onset=parking!!.first().timeMs
            parkingEntry=recent.firstOrNull {it.timeMs<=onset-config.entryObservationMs && it.gear==Gear.DRIVE}?.headingDeg
            parkingCounter=GearTransitionCounter(config.gearStableMs)
            parking!!.forEach {parkingCounter.update(it.gear,it.timeMs)}
            parkingScene=m.parkingScene;parkingSceneTimeMs=m.parkingSceneTimeMs;parkingSceneConflict=false;parkSince=null
            turn=null;launch=null;stopping=null;centeredSince=null;stopArmed=false;wasStopped=false
        }
        if(parking!=null) {
            parking?.let {p->
                if(p.last().timeMs!=m.timeMs) p+=m
                parkingCounter.update(m.gear,m.timeMs)
                m.parkingScene?.let {scene->
                    if(parkingScene!=null && parkingScene!!.targetId!=scene.targetId) parkingSceneConflict=true
                    parkingScene=scene;parkingSceneTimeMs=m.parkingSceneTimeMs
                }
                if(m.gear==Gear.PARK && stopped) {
                    if(parkSince==null) parkSince=m.timeMs
                    if(m.timeMs-parkSince!!>=config.standstillMs) {
                        parkingEvidenceSamples=p.toList();parkingEvidenceCount=parkingCounter.reverseToDriveCount;parkingEvidenceEntry=parkingEntry
                        val decision=ParkingClassifier(config).classify(parkingScene.takeIf {parkingSceneTimeMs?.let {t->m.timeMs-t<=config.parkingSceneMaxAgeMs}==true},parkingSceneConflict)
                        completedParkingType=decision.type;parkingRecognition=decision.evidence
                        val r=scorer.evaluate(decision.type,p,context,parkingEvidenceCount,entryHeading=parkingEntry,
                            extraHolds=listOfNotNull(decision.evidence.reason),recognition=decision.evidence)
                        emit(r);parkingResultId=r.id;parkingCompletedAt=m.timeMs;parkingCanStart=false;parking=null;parkSince=null
                    }
                } else parkSince=null
                if(abs(m.speedMps)>config.maxParkingMps*config.parkingExitMultiplier) abort("저속 조작 관측 범위 이탈")
                else if(m.timeMs-p.first().timeMs>config.parkingTimeoutMs) abort("저속 조작 종료를 확인하지 못함")
            }
            return completed.drop(before)
        }
        if(wasStopped && abs(m.speedMps)>=config.movingMps && m.gear==Gear.DRIVE && launch==null) {
            launch=recent.toMutableList();launchOnset=m.timeMs;wasStopped=false
        }
        launch?.let {a->
            if(a.last().timeMs!=m.timeMs) a+=m
            val acceleratingEnded=m.timeMs-launchOnset>=config.startMinimumMs && (m.acceleration ?: 0.0)<-config.stopCandidateDeceleration
            if(m.timeMs-launchOnset>=config.startWindowMs || acceleratingEnded) {emit(scorer.evaluate(ManeuverType.START,a,context));launch=null}
        }
        if(abs(m.speedMps)>=config.stopArmMps) stopArmed=true
        if(stopping==null && stopArmed && (m.acceleration ?: 0.0)<-config.stopCandidateDeceleration) stopping=recent.toMutableList()
        stopping?.let {a->
            if(a.last().timeMs!=m.timeMs) a+=m
            if(stopped && stationarySince!=null && m.timeMs-stationarySince!!>=config.standstillMs) {
                emit(scorer.evaluate(ManeuverType.STOP,a,context));stopping=null;stopArmed=false
            } else if(m.timeMs-a.first().timeMs>config.stopTimeoutMs) stopping=null
        }
        if(turn==null && m.steeringDeg!=null && abs(m.steeringDeg)>=config.steeringStartDeg && abs(m.speedMps)>=config.movingMps && m.gear==Gear.DRIVE) {
            turn=recent.toMutableList();centeredSince=null
        }
        turn?.let {a->
            if(a.last().timeMs!=m.timeMs) a+=m
            val dt=previous?.let {(m.timeMs-it.timeMs)/1000.0} ?: 0.0
            val headingStable=if(m.headingDeg!=null && previous?.headingDeg!=null && dt>0) abs(headingDelta(previous.headingDeg,m.headingDeg))/dt<=config.exitHeadingRateDegPerSec else true
            if(m.steeringDeg!=null && abs(m.steeringDeg)<config.steeringCenteredDeg && headingStable) {
                if(centeredSince==null) centeredSince=m.timeMs
                if(m.timeMs-centeredSince!!>=config.stableExitMs && m.timeMs-a.first().timeMs>=config.minTurnMs) finishTurn(a)
            } else centeredSince=null
            if(turn!=null && m.timeMs-a.first().timeMs>config.turnTimeoutMs) finishTurn(a,listOf("회전 종료 조건을 확인하지 못함"))
        }
        return completed.drop(before)
    }
    private fun finishTurn(a:List<Motion>,extra:List<String> = emptyList()) {
        val headings=a.mapNotNull {it.headingDeg}
        val delta=headings.zipWithNext().sumOf {(x,y)->headingDelta(x,y)}
        val angles=a.mapNotNull {it.steeringDeg}.filter {abs(it)>=config.steeringStartDeg}
        val changes=angles.zipWithNext().count {(x,y)->x*y<0}
        val relevant=a.filter {abs(it.steeringDeg ?: 0.0)>=config.steeringCenteredDeg}
        val headingCoverage=if(relevant.isEmpty()) 0.0 else relevant.count {it.headingDeg!=null}.toDouble()/relevant.size
        val directionAgrees=angles.isNotEmpty() && angles.average()*delta>0
        val reliable=headingCoverage>=config.requiredHeadingCoverage && changes==0 && directionAgrees && extra.isEmpty()
        val type=when {
            !reliable->ManeuverType.UNKNOWN
            abs(delta) in config.uTurnMinHeading..config.uTurnMaxHeading->ManeuverType.U_TURN
            abs(delta) in config.turnMinHeading..config.turnMaxHeading->if(delta<0) ManeuverType.LEFT else ManeuverType.RIGHT
            else->ManeuverType.UNKNOWN
        }
        emit(scorer.evaluate(type,a,context,extraHolds=extra+if(type==ManeuverType.UNKNOWN) listOf("진행 방향·회전 맥락 부족 또는 곡선·차로 변경 · 회전 종류 미확정") else emptyList()))
        turn=null;centeredSince=null
    }
    /** Refine a completed parking event when an authorized final frame becomes available. Keep its ID stable. */
    fun refineParking(evidence:AlignmentEvidence):ManeuverResult? {
        if(documentEngine!=null) return null // v1 scores observed kinematics; optional pixels are kept separately, never legacy-rescored.
        val a=parkingEvidenceSamples ?: return null
        val id=parkingResultId ?: return null
        val r=scorer.evaluate(completedParkingType ?: ManeuverType.PARKING_UNKNOWN,a,context,parkingEvidenceCount,evidence,parkingEvidenceEntry,
            listOfNotNull(parkingRecognition?.reason),recognition=parkingRecognition).copy(id=id)
        val index=completed.indexOfFirst {it.id==id};if(index>=0) completed[index]=r
        return r
    }
    fun finish(alignment:AlignmentEvidence?=null,cancelled:Boolean=false):List<ManeuverResult> {
        documentEngine?.let {return it.finish(cancelled)}
        if(alignment!=null) refineParking(alignment)
        if(cancelled) abort("사용자가 동작을 중단함")
        else abort("동작 완료 전에 주행 세션이 종료됨")
        return results
    }
    private fun abort(reason:String) {
        val now=lastMotion?.timeMs ?: 0
        val decision=ParkingClassifier(config).classify(parkingScene.takeIf {parkingSceneTimeMs?.let {now-it in 0..config.parkingSceneMaxAgeMs}==true},parkingSceneConflict)
        listOf(turn to ManeuverType.UNKNOWN,launch to ManeuverType.START,stopping to ManeuverType.STOP,parking to decision.type).forEach {(a,type)->
            if(!a.isNullOrEmpty()) emit(scorer.evaluate(type,a,context,if(isParking(type)) parkingCounter.reverseToDriveCount else 0,
                extraHolds=listOf(reason)+if(isParking(type)) listOfNotNull(decision.evidence.reason) else emptyList(),
                recognition=if(isParking(type)) decision.evidence else null).copy(lifecycle=Lifecycle.INTERRUPTED))
        }
        turn=null;launch=null;stopping=null;parking=null;parkSince=null
    }
}
