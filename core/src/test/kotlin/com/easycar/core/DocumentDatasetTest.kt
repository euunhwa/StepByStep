package com.easycar.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class DocumentDatasetTest {
    private val assets=File("../app/src/main/assets")
    private val root=File(assets,"datasets/document-v1")
    private val recognition=File(root,"recognition.json").readText();private val evaluation=File(root,"evaluation.json").readText()
    private val data=DocumentDataset.fromJson(recognition,evaluation)
    private val policy=EvaluationPolicy.fromDocumentJson(recognition,evaluation)
    private val profile=ReferenceProfile.fromJson(File(assets,"profiles/document-derived-v1.json").readText())
    private val context=PracticeContext(null,null,dataSource="TEST_SYNTHETIC")
    private fun engine()=CoachEngine(profile,context,evaluationPolicy=policy)
    @Test fun strictVersionedPairHasNoLegacyArbitraryFallback() {
        assertTrue(policy.budgets.isEmpty());assertTrue(policy.provisionalLimits.isEmpty());assertFalse(policy.realWorldScoringEnabled)
        assertEquals("recognition-document-1.0.2",data.recognitionVersion)
        assertTrue(runCatching {CoachEngine(profile,context)}.isFailure)
        assertTrue(runCatching {DocumentDataset.fromJson(recognition.replace("\"yawBumpRadps\"","\"typo\""),evaluation)}.isFailure)
        assertTrue(runCatching {DocumentDataset.fromJson(recognition,evaluation.replace("\"expertValidated\": false","\"expertValidated\": true"))}.isFailure)
        assertTrue(runCatching {DocumentDataset.fromJson(recognition,evaluation.replace(data.sourceSha256,"0".repeat(64)))}.isFailure)
    }
    @Test fun recognitionValidationPackUsesOnlySignalsAndTruthStaysOutsideFrames() {
        val pack=JsonParser.parseString(File(root,"recognition-validation.json").readText()).asJsonObject
        for(case in pack["cases"].asJsonArray) {
            val c=case.asJsonObject;val frames=Gson().fromJson(c["frames"],Array<VehicleFrame>::class.java)
            val e=engine();frames.forEach {e.accept(it)};e.finish()
            val completed=e.results.filter {it.lifecycle==Lifecycle.COMPLETED}
            assertEquals(c["id"].asString,c["expectedCompletedTypes"].asJsonArray.map {it.asString},completed.map {it.type.name})
            assertTrue(e.results.all {it.profileVersion=="document-derived-1.0" && it.evaluationPolicyVersion=="evaluation-document-1.0"})
        }
    }
    @Test fun sevenManeuversPublishBeforeSessionFinishAndNoDuplicationAtFinish() {
        val e=engine();val events=DocumentDemoCatalog.automaticDrive.frames.flatMap {e.accept(it)}
        assertEquals(listOf(ManeuverType.START,ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN,ManeuverType.STOP,ManeuverType.T_PARK,ManeuverType.PARALLEL_PARK),events.map {it.type})
        assertTrue(events.all {it.score==100 && it.scoreKind=="PROVISIONAL"})
        assertEquals(events.map {it.id},e.finish().map {it.id})
        assertTrue(events.first {it.type==ManeuverType.T_PARK}.measurements["yawDeltaDeg"]!! in 85.0..95.0)
    }
    @Test fun evaluationMetricVectorPackChecksBoundariesAndParkingWeights() {
        val pack=JsonParser.parseString(File(root,"evaluation-validation.json").readText()).asJsonObject
        for(node in pack["cases"].asJsonArray) {
            val c=node.asJsonObject;val type=ManeuverType.valueOf(c["type"].asString)
            val duration=c["durationMs"]?.asLong ?: 4000
            val samples=(0..(duration/100).toInt()).map {i->Motion(i*100L,5.0,140.0,Gear.DRIVE,null,c["acceleration"].asDouble,c["jerk"].asDouble,null,false,yawRadps=.1,lateralMps2=c["lateralG"]?.asDouble?.times(data.evaluation.gravityMps2))}
            val r=DocumentScorer(profile,context,data).evaluate(type,samples,c["conversions"]?.asInt ?: 0)
            assertEquals(c["id"].asString,c["expectedScore"].asInt,r.score)
            assertTrue(r.assessments!!.first {it.key=="safety_context"}.passed==null)
        }
    }
    private fun turnFrames(degrees:Double,seconds:Double=8.0,speed:Double=3.0):List<VehicleFrame> {
        val count=(seconds*10).toInt()
        return (0..count+20).map {i->val t=i*100L
            fun <T> s(v:T,u:String)=Signal(v,t,t,u,"TEST_SYNTHETIC")
            VehicleFrame(t,s(speed,"m/s"),s(0.0,"degree:steering-wheel"),s(Gear.DRIVE,"gear"),null,yawRate=s(if(i<count) degrees/seconds*PI/180 else 0.0,"rad/s:vehicle-z-left-positive"))}
    }
    @Test fun yawSignControlsDirectionEvenWhenSteeringIsZero() {
        for((angle,type) in listOf(90.0 to ManeuverType.LEFT,-90.0 to ManeuverType.RIGHT,180.0 to ManeuverType.U_TURN)) {
            val e=engine();turnFrames(angle).forEach {e.accept(it)}
            assertEquals(type,e.results.single().type)
        }
    }
    @Test fun shortSpikeCurveAndOppositeBumpsAreNotFalselyCalledTurn() {
        for(frames in listOf(turnFrames(90.0,1.0),turnFrames(25.0),turnFrames(30.0,3.0)+emptyList())) {
            val e=engine();frames.forEach {e.accept(it)};assertTrue(e.results.none {it.type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)})
        }
        val e=engine();turnFrames(90.0).mapIndexed {i,f->f.copy(yawRate=f.yawRate!!.copy(value=if(i in 40..79) -f.yawRate.value else f.yawRate.value))}.forEach {e.accept(it)}
        assertTrue(e.results.isEmpty())
    }
    @Test fun rapidTurnRequiresSpeedThroughoutBumpAndDuration() {
        val e=engine();turnFrames(90.0,2.0,10.0).forEach {e.accept(it)}
        assertEquals(1.0,e.results.single().measurements["rapidTurn"])
        assertTrue(e.results.single().deductions.any {it.key=="rapid_turn"})
        val slow=engine();turnFrames(90.0,2.0,3.0).forEach {slow.accept(it)}
        assertEquals(0.0,slow.results.single().measurements["rapidTurn"])
    }
    @Test fun staleYawGapAndReceiptEstimateNeverBecomeMeasuredYaw() {
        for(frames in listOf(turnFrames(90.0).map {it.copy(yawRate=it.yawRate!!.copy(measuredMs=it.timeMs-1000))},turnFrames(90.0).filter {it.timeMs !in 3000..3400})) {
            val e=engine();frames.forEach {e.accept(it)};e.finish();assertTrue(e.results.none {it.type==ManeuverType.LEFT && it.score!=null})
        }
    }
    @Test fun missingDerivativesHoldRatherThanAwardFullScore() {
        val samples=(0..40).map {Motion(it*100L,5.0,0.0,Gear.DRIVE,null,null,null,null,false)}
        val r=DocumentScorer(profile,context,data).evaluate(ManeuverType.START,samples)
        assertNull(r.score);assertTrue(r.holdReasons.any {it.contains("충족률")})
    }
    @Test fun sub600msSpikeHasNoGradePenaltyButSustained600msDoes() {
        fun samples(highCount:Int)=(0..40).map {i->Motion(i*100L,5.0,0.0,Gear.DRIVE,null,if(i in 10 until 10+highCount) 3.0 else 0.0,0.0,null,false)}
        assertEquals(100,DocumentScorer(profile,context,data).evaluate(ManeuverType.START,samples(6)).score)
        assertEquals(93,DocumentScorer(profile,context,data).evaluate(ManeuverType.START,samples(7)).score)
    }
    @Test fun realServiceRecordingCanBeReplayedWithoutInventingYaw() {
        val file=File("../docs/validation/localhost-live-v5-session.json")
        val frames=Gson().fromJson(JsonParser.parseString(file.readText()).asJsonObject["frames"],Array<VehicleFrame>::class.java)
        val e=engine();frames.forEach {e.accept(it)};e.finish()
        assertTrue(e.results.none {it.type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)})
        assertTrue(e.results.all {it.evaluationPolicyVersion=="evaluation-document-1.0"})
        assertTrue(e.results.none {r->r.holdReasons.any {it.contains("속도·기어 신호 공백")}})
        // Replay output is a build artifact; writing it under docs/ dirtied a tracked file on every run.
        File("build/document-replay").apply {mkdirs()}.resolve("real-recording-replay.json").writeText(Gson().toJson(e.results))
    }
    @Test fun onChangeGear500msPollingDoesNotExpireAt300ms() {
        val base=DocumentDemoCatalog.automaticDrive.frames.take(160)
        val e=engine()
        base.map {f->f.copy(gear=f.gear!!.copy(measuredMs=f.timeMs/500*500,receivedMs=f.timeMs/500*500,timing=SignalTiming.CURRENT_STATE_READ))}.forEach {e.accept(it)}
        assertEquals(ManeuverType.START,e.results.single().type);assertEquals(100,e.results.single().score)
    }
    @Test fun measured100HzInputsAreAlignedTo10HzWithoutChangingYawSign() {
        val low=turnFrames(90.0)
        val high=low.flatMap {f->(0..9).map {i->val t=f.timeMs+i*10L
            f.copy(timeMs=t,speed=f.speed!!.copy(measuredMs=t,receivedMs=t),gear=f.gear!!.copy(measuredMs=t,receivedMs=t),steering=f.steering!!.copy(measuredMs=t,receivedMs=t),yawRate=f.yawRate!!.copy(measuredMs=t,receivedMs=t))}}
        val e=engine();high.forEach {e.accept(it)};assertEquals(ManeuverType.LEFT,e.results.single().type)
    }
    @Test fun nominalTenKphFloatDoesNotAbortParkingButRealExcessStillDoes() {
        fun run(kph:Double):List<ManeuverResult> {
            val e=engine()
            for(t in 0L..4200 step 100) {
                val gear=when {t<1000->Gear.DRIVE;t<3200->Gear.REVERSE;else->Gear.PARK}
                val speed=if(t in 1000..3100) (if(t<2000) 1.0 else kph)/3.6 else 0.0
                val actual=speed.toFloat().toDouble()
                e.accept(VehicleFrame(t,Signal(actual,t,t,"m/s","BOUNDARY_TEST"),Signal(100.0,t,t,"degree:steering-wheel","BOUNDARY_TEST"),Signal(gear,t,t,"gear","BOUNDARY_TEST"),null,
                    yawRate=Signal(if(speed>0) .1 else 0.0,t,t,"rad/s:vehicle-z-left-positive","BOUNDARY_TEST")))
            }
            return e.results
        }
        assertTrue(run(10.0).none {r->r.holdReasons.any {it.contains("주차 속도 범위")}})
        assertTrue(run(10.01).any {r->r.holdReasons.any {it.contains("주차 속도 범위")}})
    }
    @Test fun provenanceManifestAndOriginalDocumentAreTraceable() {
        val sha=java.security.MessageDigest.getInstance("SHA-256")
        fun hash(file:File)=sha.digest(file.readBytes()).joinToString("") {"%02x".format(it)}
        assertEquals(data.sourceSha256,hash(File("../docs/datasets/sources/driving-recognition-evaluation-original-ko.md")))
        val manifest=JsonParser.parseString(File(root,"manifest.json").readText()).asJsonObject
        assertFalse(manifest["trainingPerformed"].asBoolean);assertFalse(manifest["expertValidated"].asBoolean)
        manifest["filesSha256"].asJsonObject.entrySet().forEach {(file,value)->assertEquals(file,value.asString,hash(File(root,file)))}
    }
    @Test fun yawTurnBlocksSpeechEvenWithCenteredWheelAndScorerUsesNewRules() {
        val m=Motion(0,3.0,0.0,Gear.DRIVE,null,2.4,0.0,null,false,yawRadps=.2)
        val g=guidanceConditions(m,true,false,yawThresholdRadps=data.recognition.yawBumpRadps)
        assertTrue(g.reversingOrTurning);assertFalse(g.stationaryOrStraight)
        val samples=(0..40).map {m.copy(timeMs=it*100L)}
        assertEquals(95,DeterministicScorer(profile,policy).evaluate(ManeuverType.START,samples,context).score)
        assertTrue(runCatching {DeterministicScorer(profile).evaluate(ManeuverType.START,samples,context)}.isFailure)
    }
    @Test fun yawLossInUturnNeverReclassifiesRemainingHalfAsLeftTurn() {
        val e=engine()
        turnFrames(180.0).mapIndexed {i,f->if(i==40) f.copy(yawRate=null) else f}.forEach {e.accept(it)}
        e.finish()
        assertTrue(e.results.none {it.type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)})
        assertTrue(e.results.any {it.lifecycle==Lifecycle.INTERRUPTED})
    }
    @Test fun reverseEntryAbsorbsUnfinishedLowSpeedParkingLaunch() {
        val frames=DocumentDemoCatalog.automaticDrive.frames;val firstR=frames.first {it.gear?.value==Gear.REVERSE}.timeMs
        val e=engine();frames.map {f->if(f.timeMs in (firstR-1500)..(firstR-100) && f.gear?.value==Gear.DRIVE) f.copy(speed=f.speed!!.copy(value=1.0)) else f}.forEach {e.accept(it)}
        e.finish();assertEquals(1,e.results.count {it.type==ManeuverType.START})
        assertTrue(e.results.none {it.lifecycle==Lifecycle.INTERRUPTED})
    }
}
