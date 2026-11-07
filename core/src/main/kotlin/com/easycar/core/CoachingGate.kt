package com.easycar.core

interface TimeProvider { fun nowMs(): Long }
data class GuidanceConditions(val stationaryOrStraight: Boolean, val reversingOrTurning: Boolean,
    val priorityAudioIdle: Boolean?, val audioFocusGranted: Boolean, val muted: Boolean)
fun guidanceConditions(m:Motion?,priorityAudioIdle:Boolean?,muted:Boolean,config:DetectionConfig=DetectionConfig(),yawThresholdRadps:Double?=null):GuidanceConditions {
    val speed=m?.speedMps
    val stopped=speed!=null && kotlin.math.abs(speed)<=config.stoppedMps
    val yawTurning=yawThresholdRadps!=null && m?.yawRadps?.let {kotlin.math.abs(it)>=yawThresholdRadps}==true
    val turning=speed!=null && !stopped && (kotlin.math.abs(m.steeringDeg ?: 0.0)>=config.steeringStartDeg || yawTurning)
    val straight=!yawTurning && speed!=null && m.steeringDeg?.let {kotlin.math.abs(it)<config.steeringCenteredDeg}==true
    return GuidanceConditions(stopped || straight,m?.gear==Gear.REVERSE || turning,priorityAudioIdle,false,muted)
}
data class PendingGuidance(val id: String, val text: String, val createdMs: Long)
class CoachingGate(private val clock: TimeProvider, private val minimumIntervalMs: Long=30000, private val expiresMs: Long=45000) {
    private val spoken=mutableSetOf<String>()
    private val pending=ArrayDeque<PendingGuidance>()
    private var lastSpoken=Long.MIN_VALUE/2
    fun enqueue(id: String,text: String) {
        if(id !in spoken && pending.none { it.id==id }) pending.addLast(PendingGuidance(id,text,clock.nowMs()))
    }
    fun next(c: GuidanceConditions): PendingGuidance? {
        val now=clock.nowMs()
        while(pending.isNotEmpty() && now-pending.first().createdMs>expiresMs) pending.removeFirst()
        if(c.muted || !c.stationaryOrStraight || c.reversingOrTurning || c.priorityAudioIdle!=true || !c.audioFocusGranted || now-lastSpoken<minimumIntervalMs) return null
        return pending.removeFirstOrNull()?.also { spoken+=it.id; lastSpoken=now }
    }
    /** Output must request real audio focus and return true only when synthesis was accepted. */
    fun deliver(c:GuidanceConditions,output:(PendingGuidance)->Boolean):PendingGuidance? {
        val now=clock.nowMs()
        while(pending.isNotEmpty() && now-pending.first().createdMs>expiresMs) pending.removeFirst()
        if(c.muted || !c.stationaryOrStraight || c.reversingOrTurning || c.priorityAudioIdle!=true || now-lastSpoken<minimumIntervalMs) return null
        val candidate=pending.firstOrNull() ?: return null
        if(!output(candidate)) return null
        pending.removeFirst();spoken+=candidate.id;lastSpoken=now
        return candidate
    }
}
data class UxState(val known: Boolean=false, val restricted: Boolean=true) {
    fun allowsReview()=known && !restricted
}
data class GrowthPoint(val type: ManeuverType,val score: Int,val profileVersion: String,val courseId: String?,val vehicleId: String?)
fun comparableGrowth(results: List<ManeuverResult>, target: ManeuverResult) = results.filter {
    it.type==target.type && it.profileVersion==target.profileVersion && it.courseId!=null && it.courseId==target.courseId && it.vehicleId!=null && it.vehicleId==target.vehicleId && it.score!=null
}
