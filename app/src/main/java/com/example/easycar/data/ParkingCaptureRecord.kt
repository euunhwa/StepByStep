package com.example.easycar.data

import com.easycar.core.*

/** Legacy read-only metadata. The 0.13 capture feature is no longer shipped. */
data class ParkingCaptureRecord(val inputSource:String,val captureStatus:String,val startedElapsedMs:Long,
    val episode:ParkingEpisode?=null,val outcome:ParkingOutcome?=null,val finalVideoMs:Long?=null,
    val videoSaved:Boolean=false,val interruptedReason:String?=null,val calibrationConfirmed:Boolean=false,
    val timeMappingKind:String="ELAPSED_REALTIME_APPROXIMATE_NOT_CAMERA_PTS")
