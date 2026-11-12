package com.example.easycar.runtime

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.easycar.core.*
import com.example.easycar.data.SessionRecord
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface VideoInput { fun finalFrame(record:SessionRecord):PixelFrame? }
class LocalVideoInput(private val context:Context):VideoInput {
    fun assetFor(c:DemoCase):String?=when {
        c.videoAngle==null -> null
        c.videoOccluded -> "demo/video/occluded.mp4"
        c.videoNoLines -> "demo/video/blank.mp4"
        c.videoShift!=0.0 -> "demo/video/intrusion.mp4"
        (c.videoAngle ?: 0.0)>3 -> "demo/video/tilted.mp4"
        else -> "demo/video/aligned.mp4"
    }
    fun cachedAsset(asset:String):File {
        val f=File(context.cacheDir,asset.substringAfterLast('/'))
        if(!f.exists()) context.assets.open(asset).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }
    override fun finalFrame(record:SessionRecord):PixelFrame? {
        val time=record.results.lastOrNull {isParking(it.type)}?.endMs ?: record.frames.lastOrNull()?.timeMs ?: record.videoSync?.signalEndMs
        return if(time!=null && record.videoSync!=null) frameAt(record,time) else decode(record,null)
    }
    fun frameAt(record:SessionRecord,signalTimeMs:Long):PixelFrame? {
        if(record.videoSync?.videoTime(signalTimeMs)==null) return null
        return decode(record,signalTimeMs)
    }
    /** Video-clock observations for review only. This path never re-scores a maneuver. */
    suspend fun analyzeWindow(record:SessionRecord,endVideoMs:Long):AlignmentEvidence {
        val file=record.videoFile?.let(::File) ?: record.videoAsset?.let(::cachedAsset)
        val results=mutableListOf<AlignmentEvidence>()
        val retriever=MediaMetadataRetriever()
        try {
            require(file!=null && file.isFile) {"영상을 열 수 없어요"}
            retriever.setDataSource(file.path)
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("영상 길이 확인 실패")
            require(endVideoMs in 0 until duration) {"분석 위치가 영상 범위를 벗어났어요"}
            val start=(endVideoMs-1000).coerceAtLeast(0)
            for(index in 0..4) {
                currentCoroutineContext().ensureActive()
                val time=start+(endVideoMs-start)*index/4
                // OPTION_CLOSEST requests a decoded frame, rather than a distant sync/key frame.
                val original=retriever.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST)
                if(original==null) {
                    results+=AlignmentEvidence(null,null,null,null,0.0,"영상 프레임을 읽지 못했어요",emptyList(),time,record.source,algorithmVersion=PARKING_VISION_VERSION)
                    continue
                }
                val ratio=minOf(1.0,640.0/maxOf(original.width,original.height))
                val bitmap=if(ratio<1) Bitmap.createScaledBitmap(original,(original.width*ratio).toInt(),(original.height*ratio).toInt(),true).also {original.recycle()} else original
                try {
                    val pixels=IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    results+=ParkingLineAnalyzer().analyze(PixelFrame(bitmap.width,bitmap.height,pixels,time,record.source),record.calibration)
                } finally {bitmap.recycle()}
            }
        } finally {retriever.release()}
        return ParkingVideoWindowAnalyzer().analyze(results)
    }
    private fun decode(record:SessionRecord,signalTimeMs:Long?):PixelFrame? {
        val file=record.videoFile?.let(::File) ?: record.videoAsset?.let(::cachedAsset) ?: return null
        val r=MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val videoTime=if(signalTimeMs!=null) record.videoSync?.videoTime(signalTimeMs) ?: return null else (duration-100).coerceAtLeast(0)
            if(videoTime !in 0..duration) return null
            val original=r.getFrameAtTime(videoTime*1000,MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val ratio=minOf(1.0,640.0/maxOf(original.width,original.height))
            val b=if(ratio<1) Bitmap.createScaledBitmap(original,(original.width*ratio).toInt(),(original.height*ratio).toInt(),true).also {original.recycle()} else original
            val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
            PixelFrame(b.width,b.height,pixels,signalTimeMs ?: videoTime,record.source).also {b.recycle()}
        } catch(_:Exception) {null} finally {r.release()}
    }
}
