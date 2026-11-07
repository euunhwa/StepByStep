// macOS standalone fixture generator. No camera input. Outputs H.264 MP4 synthetic top-view clips.
import Foundation
import AVFoundation
import CoreVideo

let output = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: output, withIntermediateDirectories: true)
let width=480, height=360, fps:Int32=15, frameCount=60
let variants:[(String,Double,Double,Bool,Bool)]=[("aligned",1,0,false,false),("tilted",10,0,false,false),("intrusion",1,75,false,false),("occluded",1,0,true,false),("blank",0,0,false,true)]
for (name,finalAngle,shift,occluded,blank) in variants {
    let url=URL(fileURLWithPath: output).appendingPathComponent(name+".mp4")
    if FileManager.default.fileExists(atPath:url.path) { try FileManager.default.removeItem(at:url) }
    let writer=try AVAssetWriter(outputURL:url,fileType:.mp4)
    let input=AVAssetWriterInput(mediaType:.video,outputSettings:[AVVideoCodecKey:AVVideoCodecType.h264,AVVideoWidthKey:width,AVVideoHeightKey:height])
    input.expectsMediaDataInRealTime=false
    let adapter=AVAssetWriterInputPixelBufferAdaptor(assetWriterInput:input,sourcePixelBufferAttributes:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32ARGB,kCVPixelBufferWidthKey as String:width,kCVPixelBufferHeightKey as String:height])
    writer.add(input);writer.startWriting();writer.startSession(atSourceTime:.zero)
    for frameIndex in 0..<frameCount {
        while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval:0.003) }
        var buffer:CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil,adapter.pixelBufferPool!,&buffer)
        CVPixelBufferLockBaseAddress(buffer!,[])
        let base=CVPixelBufferGetBaseAddress(buffer!)!.assumingMemoryBound(to:UInt8.self)
        let stride=CVPixelBufferGetBytesPerRow(buffer!)
        let u=Double(frameIndex)/Double(frameCount-1)
        let angle = finalAngle + (1-u)*5
        let slope=tan(angle * .pi/180)
        for y in 0..<height { for x in 0..<width {
            var color:(UInt8,UInt8,UInt8)=(36,49,57)
            let onLine=abs(Double(x)-(135+shift+slope*Double(y-height/2)))<3.5 || abs(Double(x)-(345+shift+slope*Double(y-height/2)))<3.5
            if !blank && onLine && !(occluded && y>=40 && y<=285) { color=(243,244,238) }
            if x>=190 && x<=290 && y>=95 && y<=265 { color = x>=200 && x<=280 && y>=110 && y<=145 ? (32,53,61) : (105,217,186) }
            let index=y*stride+x*4;base[index]=255;base[index+1]=color.0;base[index+2]=color.1;base[index+3]=color.2
        } }
        CVPixelBufferUnlockBaseAddress(buffer!,[])
        guard adapter.append(buffer!,withPresentationTime:CMTime(value:Int64(frameIndex),timescale:fps)) else { fatalError("append failed") }
    }
    input.markAsFinished()
    let semaphore=DispatchSemaphore(value:0)
    writer.finishWriting { semaphore.signal() };semaphore.wait()
    guard writer.status == .completed else { fatalError("video failed: \(String(describing:writer.error))") }
    print(url.path)
}
