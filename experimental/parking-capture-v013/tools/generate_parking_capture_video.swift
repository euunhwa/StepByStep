// Deterministic synthetic AVM-like flow fixtures. No camera footage or scores.
import Foundation
import AVFoundation
import CoreVideo
let output=CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath:output,withIntermediateDirectories:true)
let width=480,height=360,fps:Int32=15,frames=98
for (name,angle,shift,blank) in [("aligned",0.0,0.0,false),("intrusion",0.0,80.0,false),("blank",0.0,0.0,true),("tilted",10.0,0.0,false)] {
 let url=URL(fileURLWithPath:output).appendingPathComponent(name+".mp4")
 if FileManager.default.fileExists(atPath:url.path) {try FileManager.default.removeItem(at:url)}
 let writer=try AVAssetWriter(outputURL:url,fileType:.mp4)
 let input=AVAssetWriterInput(mediaType:.video,outputSettings:[AVVideoCodecKey:AVVideoCodecType.h264,AVVideoWidthKey:width,AVVideoHeightKey:height])
 let adapter=AVAssetWriterInputPixelBufferAdaptor(assetWriterInput:input,sourcePixelBufferAttributes:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32ARGB,kCVPixelBufferWidthKey as String:width,kCVPixelBufferHeightKey as String:height])
 writer.add(input);writer.startWriting();writer.startSession(atSourceTime:.zero)
 for i in 0..<frames {
  while !input.isReadyForMoreMediaData {Thread.sleep(forTimeInterval:0.002)}
  var buffer:CVPixelBuffer?;CVPixelBufferPoolCreatePixelBuffer(nil,adapter.pixelBufferPool!,&buffer)
  CVPixelBufferLockBaseAddress(buffer!,[]);let bytes=CVPixelBufferGetBaseAddress(buffer!)!.assumingMemoryBound(to:UInt8.self);let stride=CVPixelBufferGetBytesPerRow(buffer!)
  let time=Double(i)/Double(fps);var distance=0.0
  for j in 0..<5 {let duration=max(0,min(0.8,time-Double(j)*0.8));distance += duration * (j%2==0 ? 0.5 : -0.5)}
  let shiftY=(distance-0.4)*100;let a=angle * .pi/180
  for y in 0..<height {for x in 0..<width {
   let dx=Double(x)-240-shift,dy=Double(y)-180-shiftY
   let u=dx*cos(a)+dy*sin(a),v = -dx*sin(a)+dy*cos(a)
   let border=(abs(abs(u)-105)<3.5 && abs(v)<=138.5)||(abs(abs(v)-135)<3.5 && abs(u)<=108.5)
   var color:(UInt8,UInt8,UInt8)=(36,49,57)
   if !blank && border {color=(243,244,238)}
   if x>=190 && x<=290 && y>=95 && y<=265 {color=(105,217,186)}
   let k=y*stride+x*4;bytes[k]=255;bytes[k+1]=color.0;bytes[k+2]=color.1;bytes[k+3]=color.2
  }}
  CVPixelBufferUnlockBaseAddress(buffer!,[])
  guard adapter.append(buffer!,withPresentationTime:CMTime(value:Int64(i),timescale:fps)) else {fatalError("fixture append failed")}
 }
 input.markAsFinished();let sem=DispatchSemaphore(value:0);writer.finishWriting {sem.signal()};sem.wait()
 guard writer.status == .completed else {fatalError("fixture encoding failed")};print(name)
}
