package com.example.easycar.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.easycar.core.*
import com.example.easycar.data.SessionRecord
import com.example.easycar.runtime.LocalVideoInput
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

@Composable fun VideoReview(
    record:SessionRecord,onBack:()->Unit,restricted:Boolean,onImportCalibration:(Long)->Unit,
    onSelectTarget:(Double,Long)->Unit,analyzing:Boolean=false,onAnalyzeWindow:(Long)->Unit={},onClearTarget:(Long)->Unit={},
    loadFrames:suspend (String)->List<VehicleFrame> = {emptyList()}
) {
    val context=LocalContext.current
    val file=remember(record.id,record.videoFile,record.videoAsset) {
        runCatching {record.videoFile?.let(::File) ?: record.videoAsset?.let {LocalVideoInput(context).cachedAsset(it)}}.getOrNull()
    }
    val duration=(record.videoDurationMs ?: 4000).coerceAtLeast(1)
    var position by remember(record.id) {mutableLongStateOf(record.videoAnalysisPositionMs ?: (duration-120).coerceAtLeast(0))}
    var player by remember(record.id) {mutableStateOf<VideoView?>(null)}
    var playing by remember(record.id) {mutableStateOf(false)}
    var playRequested by remember(record.id) {mutableStateOf(false)}
    var bitmap by remember(record.id) {mutableStateOf<Bitmap?>(null)}
    var evidence by remember(record.id) {mutableStateOf<AlignmentEvidence?>(null)}
    var error by remember(record.id) {mutableStateOf<String?>(null)}
    val isRestricted by rememberUpdatedState(restricted)
    val synthetic=record.parkingCapture?.inputSource=="SYNTHETIC_REPLAY" || (record.videoFile==null && record.videoAsset?.startsWith("demo/video/")==true)
    val calibration=record.calibration
    val signalTime=record.videoSync?.signalTime(position)
    // Stored records carry no frames in memory; read this session's frames once when the screen opens.
    val frames by produceState(record.frames,record.id) {if(value.isEmpty()) value=loadFrames(record.id)}
    val frame=remember(record.id,signalTime,frames) {signalTime?.let {t->nearestFrame(frames,t)}}
    DisposableEffect(record.id) {onDispose {player?.stopPlayback()}}
    LaunchedEffect(playing) {
        while(playing) {position=(player?.currentPosition?.toLong() ?: position).coerceIn(0,duration-1);delay(200)}
    }
    LaunchedEffect(record.id,file,position/200,restricted,calibration) {
        evidence=null
        if(restricted || file==null || !file.isFile) {
            player?.stopPlayback();playing=false;playRequested=false;bitmap=null
            return@LaunchedEffect
        }
        val previewTime=position
        var allocated:Bitmap?=null
        var published=false
        try {
            val result=withContext(Dispatchers.Default) {
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    val original=retriever.getFrameAtTime(previewTime*1000,MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: error("선택한 위치의 영상 프레임을 읽을 수 없어요")
                    val ratio=minOf(1.0,640.0/maxOf(original.width,original.height))
                    val scaled=if(ratio<1) Bitmap.createScaledBitmap(original,(original.width*ratio).toInt(),(original.height*ratio).toInt(),true).also {original.recycle()} else original
                    allocated=scaled
                    val pixels=IntArray(scaled.width*scaled.height)
                    scaled.getPixels(pixels,0,scaled.width,0,0,scaled.width,scaled.height)
                    val observed=ParkingLineAnalyzer().analyze(PixelFrame(scaled.width,scaled.height,pixels,previewTime,record.source),calibration)
                    scaled to observed
                } finally {retriever.release()}
            }
            bitmap=result.first;evidence=result.second;error=null;published=true
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {bitmap=null;evidence=null;error="영상을 확인하지 못했어요. 다른 장면이나 영상을 선택해 주세요."}
        finally {if(!published) allocated?.recycle()}
        // Displayed bitmaps become GC-owned when replaced; never recycle a bitmap still held by Compose.
    }
    if(restricted) {
        Text("정차 후 주차 영상을 확인할 수 있어요.",modifier=Modifier.testTag("parking-video-restricted"))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("parking-video-review")) {
        TextButton(onClick=onBack) {Text("← 연습 요약")}
        Heading("주차 영상 분석",if(synthetic) "예제 영상으로 주차 위치를 살펴보세요." else "주차선과 차량 위치를 살펴보세요.")
        Spacer(Modifier.height(12.dp))
        TextButton(onClick={onImportCalibration(position)},enabled=!restricted && !analyzing) {Text("영상 분석 설정 불러오기")}
        Text(if(calibration==null) "영상에 맞는 분석 설정을 먼저 불러와 주세요." else "여러 칸이 보이면 영상을 멈추고 확인할 칸 안쪽을 누르세요.",fontSize=17.sp,color=Muted)
        if(calibration?.targetCenterPx!=null) TextButton(onClick={onClearTarget(position)},enabled=!restricted && !analyzing) {Text("대상 칸 선택 해제")}
        Spacer(Modifier.height(16.dp))
        if(file==null || !file.isFile) {
            CardPanel(Modifier.fillMaxWidth()) {Text("영상을 열 수 없어요",fontSize=24.sp,color=Amber)}
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val videoPane:@Composable (Modifier)->Unit = {modifier->
                Column(modifier) {
                    Box(Modifier.fillMaxWidth().aspectRatio((bitmap?.width?.toFloat() ?: 480f)/(bitmap?.height?.toFloat() ?: 360f))
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .pointerInput(calibration,playing,analyzing,evidence) {
                            detectTapGestures {point->
                                if(calibration!=null && !playing && !restricted && !analyzing) {
                                    val selected=parkingTargetAtTap(evidence?.lines ?: emptyList(),bitmap?.width ?: 0,bitmap?.height ?: 0,
                                        point.x/size.width.toDouble(),point.y/size.height.toDouble())
                                    if(selected!=null) onSelectTarget(selected,position) else error="주차선 사이의 칸 안쪽을 선택해 주세요."
                                }
                            }
                        }.testTag("parking-video-frame"),contentAlignment=Alignment.Center) {
                        key(record.id,file.path) { AndroidView(factory={ctx->VideoView(ctx).also {view->
                            player=view;view.setVideoURI(Uri.fromFile(file))
                            view.setOnPreparedListener {mp->
                                mp.setVolume(0f,0f)
                                mp.setOnSeekCompleteListener {if(playRequested && !isRestricted) {view.start();playing=true;playRequested=false}}
                                view.seekTo(position.toInt())
                            }
                            view.setOnCompletionListener {playing=false;playRequested=false;position=(duration-120).coerceAtLeast(0)}
                            view.setOnErrorListener {_,_,_->error="동영상 재생을 지원하지 않는 형식이에요";playing=false;playRequested=false;true}
                        }},modifier=Modifier.fillMaxSize()) }
                        if(!playing) bitmap?.let {Image(it.asImageBitmap(),"선택한 주차 장면",Modifier.fillMaxSize())}
                        bitmap?.let {b->
                            Canvas(Modifier.fillMaxSize()) {
                                val sx=size.width/b.width;val sy=size.height/b.height
                                evidence?.lines?.forEach {line->drawLine(Mint,Offset(line.top.x.toFloat()*sx,line.top.y.toFloat()*sy),Offset(line.bottom.x.toFloat()*sx,line.bottom.y.toFloat()*sy),3.dp.toPx())}
                                calibration?.forDimensions(b.width,b.height)?.let {c->
                                    val left=c.vehicleLeftPx.toFloat()*sx;val top=c.vehicleTopPx.toFloat()*sy
                                    val width=(c.vehicleRightPx-c.vehicleLeftPx).toFloat()*sx;val height=(c.vehicleBottomPx-c.vehicleTopPx).toFloat()*sy
                                    drawRect((if(evidence?.intrusion==true) ScoreRed else Amber).copy(alpha=.18f),Offset(left,top),Size(width,height))
                                    val cx=left+width/2
                                    drawLine(Amber,Offset(cx,top),Offset(cx,top+height),2.dp.toPx())
                                }
                            }
                        }
                    }
                    Slider(value=position.toFloat().coerceIn(0f,(duration-1).toFloat()),onValueChange={position=it.toLong();player?.seekTo(position.toInt())},
                        valueRange=0f..(duration-1).coerceAtLeast(1).toFloat(),enabled=!restricted && !analyzing,modifier=Modifier.heightIn(min=56.dp))
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={
                            if(playing) {player?.pause();playing=false;playRequested=false}
                            else if(!restricted && !analyzing) {if(position>duration-300) position=0;playRequested=true;player?.seekTo(position.toInt())}
                        },enabled=!restricted && !analyzing) {Text(if(playing) "일시 정지" else if(playRequested) "재생 준비 중" else "영상 재생")}
                        Text("${formatValue(position/1000.0)} / ${formatValue(duration/1000.0)}초",fontSize=17.sp,color=Muted)
                    }
                    Button(onClick={player?.pause();playing=false;playRequested=false;onAnalyzeWindow(position)},enabled=!restricted && !analyzing && !playing,
                        modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("analyze-parking-window")) {Text(if(analyzing) "주차 장면 확인 중…" else "이 장면 분석 · 저장")}
                    error?.let {Text(it,color=Amber,fontSize=17.sp,modifier=Modifier.padding(top=8.dp).testTag("parking-video-error"))}
                }
            }
            val observations:@Composable (Modifier)->Unit = {modifier->
                CardPanel(modifier.testTag("parking-observations")) {
                    Text("선택한 주차 장면",fontSize=23.sp,fontWeight=FontWeight.Bold)
                    Text(videoPlacementTitle(evidence),fontSize=25.sp,color=if(evidence?.reason==null && evidence?.angleDeg!=null && evidence?.intrusion!=null) Mint else Amber,modifier=Modifier.padding(top=12.dp).testTag("parking-frame-angle"))
                    Text(videoPlacementAdvice(evidence),fontSize=18.sp,color=Muted,lineHeight=27.sp,modifier=Modifier.padding(top=12.dp).testTag("parking-frame-advice"))
                    Spacer(Modifier.height(18.dp));HorizontalDivider();Spacer(Modifier.height(18.dp))
                    Text("저장한 분석 결과",fontSize=22.sp,fontWeight=FontWeight.Bold)
                    val saved=record.alignment
                    Text(if(saved?.sampledFrames!=null) "영상 ${formatValue(saved.frameTimeMs/1000.0)}초에서 저장한 결과예요." else "차량이 멈춘 장면을 선택하고 분석 결과를 저장해 주세요.",fontSize=17.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
                    Text(if(saved?.sampledFrames!=null) videoPlacementAdvice(saved) else "아직 저장한 분석 결과가 없어요.",fontSize=18.sp,lineHeight=27.sp,color=if(saved?.reason!=null) Amber else Mint,modifier=Modifier.padding(top=10.dp).testTag("parking-window-result"))
                    Spacer(Modifier.height(16.dp))
                    if(frame!=null) {
                        val gear=when(frame.gear?.value) {Gear.PARK->"P";Gear.REVERSE->"R";Gear.NEUTRAL->"N";Gear.DRIVE->"D";else->"—"}
                        Text("이 장면의 주행 정보\n속도 ${frame.speed?.value?.let {formatValue(abs(it)*3.6)} ?: "—"} km/h · 기어 $gear",fontSize=17.sp,color=Muted)
                    }
                    Text("표시된 주차선과 차량 위치를 확인하세요.\n영상 분석 결과는 연습 점수와 별도로 저장해요.",fontSize=15.sp,color=Muted,lineHeight=24.sp,modifier=Modifier.padding(top=14.dp))
                }
            }
            if(maxWidth<850.dp) Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {videoPane(Modifier.fillMaxWidth());observations(Modifier.fillMaxWidth())}
            else Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {videoPane(Modifier.weight(1.2f));observations(Modifier.weight(1f))}
        }
    }
}

/** Frames are time-ordered: binary-search the insertion point and keep the closer neighbour within 1 s. */
internal fun nearestFrame(frames:List<VehicleFrame>,timeMs:Long):VehicleFrame? {
    val i=frames.binarySearchBy(timeMs) {it.timeMs}.let {if(it>=0) it else -it-1}
    return listOfNotNull(frames.getOrNull(i-1),frames.getOrNull(i)).minByOrNull {abs(it.timeMs-timeMs)}?.takeIf {abs(it.timeMs-timeMs)<=1000}
}
