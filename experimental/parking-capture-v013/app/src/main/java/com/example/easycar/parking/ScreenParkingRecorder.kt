package com.example.easycar.parking

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import java.io.File
import kotlin.math.roundToInt

data class SavedParkingVideo(val file:File?,val durationMs:Long?,val error:String?)
/** User-selected screen capture. This class does not access EVS or bypass secure content. */
class ScreenParkingRecorder(private val context:Context,private val destination:File,private val onInterrupted:(String)->Unit) {
    private var projection:MediaProjection?=null;private var display:VirtualDisplay?=null;private var recorder:MediaRecorder?=null
    private var stopping=false
    var startedElapsedMs=0L;private set
    fun start(resultCode:Int,data:Intent) {
        require(resultCode==Activity.RESULT_OK)
        destination.parentFile?.mkdirs()
        require(context.filesDir.usableSpace>100_000_000) {"영상 저장 공간이 부족해요."}
        val wm=context.getSystemService(WindowManager::class.java)
        val bounds=if(Build.VERSION.SDK_INT>=30) wm.maximumWindowMetrics.bounds else android.graphics.Rect().also {
            @Suppress("DEPRECATION") val metrics=android.util.DisplayMetrics().also {wm.defaultDisplay.getRealMetrics(it)}
            it.set(0,0,metrics.widthPixels,metrics.heightPixels)
        }
        val ratio=minOf(1.0,1280.0/maxOf(bounds.width(),bounds.height()))
        val width=((bounds.width()*ratio).roundToInt()/2*2).coerceAtLeast(320)
        val height=((bounds.height()*ratio).roundToInt()/2*2).coerceAtLeast(240)
        @Suppress("DEPRECATION") val m=if(Build.VERSION.SDK_INT>=31) MediaRecorder(context) else MediaRecorder()
        recorder=m
        try {
            m.setVideoSource(MediaRecorder.VideoSource.SURFACE);m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            m.setVideoEncoder(MediaRecorder.VideoEncoder.H264);m.setVideoSize(width,height);m.setVideoFrameRate(15);m.setVideoEncodingBitRate(3_000_000)
            m.setMaxDuration(300_000);m.setMaxFileSize(250_000_000)
            m.setOnInfoListener {_,what,_->if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onInterrupted("녹화 시간 또는 파일 크기 한도에 도달했어요.")}
            m.setOnErrorListener {_,_,_->onInterrupted("화면 녹화 중 오류가 발생했어요.")}
            m.setOutputFile(destination.path);m.prepare()
            val p=context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode,data) ?: error("화면 공유 승인을 확인하지 못했어요.")
            projection=p
            p.registerCallback(object:MediaProjection.Callback() {override fun onStop() {if(!stopping) onInterrupted("화면 공유가 중단됐어요.")}},Handler(Looper.getMainLooper()))
            display=p.createVirtualDisplay("EasyCar parking recording",width,height,context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,m.surface,null,Handler(Looper.getMainLooper()))
            m.start();startedElapsedMs=SystemClock.elapsedRealtime()
        } catch(e:Exception) {stop();throw e}
    }
    fun stop():SavedParkingVideo {
        if(stopping) return SavedParkingVideo(null,null,"이미 녹화를 종료했어요.")
        stopping=true;var failure:String?=null
        try {recorder?.stop()} catch(_:Exception) {failure="재생 가능한 영상으로 녹화를 마무리하지 못했어요."}
        runCatching {display?.release()};display=null
        runCatching {projection?.stop()};projection=null
        runCatching {recorder?.reset();recorder?.release()};recorder=null
        if(failure!=null || !destination.isFile || destination.length()<1000) {destination.delete();return SavedParkingVideo(null,null,failure ?: "녹화된 영상이 없어요.")}
        val r=MediaMetadataRetriever()
        return try {
            r.setDataSource(destination.path);val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            require(duration!=null && duration>=300)
            val final=File(destination.parentFile,destination.name.removeSuffix(".pending.mp4")+".mp4")
            check(destination.renameTo(final));SavedParkingVideo(final,duration,null)
        } catch(_:Exception) {destination.delete();SavedParkingVideo(null,null,"녹화 파일의 재생 정보를 확인하지 못했어요.")}
        finally {r.release()}
    }
}
