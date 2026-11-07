import Foundation
import AppKit
import AVFoundation
import CoreGraphics
import CoreMedia
import QuartzCore
import CoreText
import ImageIO
import UniformTypeIdentifiers
import AudioToolbox

struct MediaError: LocalizedError { let message: String; var errorDescription: String? { message } }
func fail(_ message: String) -> MediaError { MediaError(message: message) }
func time(_ seconds: Double) -> CMTime { CMTime(seconds: seconds, preferredTimescale: 60000) }
func seconds(_ value: CMTime) -> Double { CMTimeGetSeconds(value) }
func json(_ value: Any) throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .prettyPrinted]), as: UTF8.self) }

struct Options {
    let command: String
    var inputs: [String] = []
    var output: String?
    var start: Double = 0
    var duration: Double?
    var frameTime: Double = 0
    var crop: CGRect?
    var title: String?
    var titleDuration: Double = 3
    var fps: Int32 = 30
    var passthrough = false
    var audioStats = false
    var gainDB: Double = 18
    var peakLimit: Double = 0.85
    static let usage = """
    Usage:
      easycar-media-tool probe INPUT.mp4 [--audio-stats]
      easycar-media-tool gain --input INPUT.mp4 --output /absolute/louder.mp4 [--gain-db 18] [--peak-limit 0.85]
      easycar-media-tool frame --input INPUT.mp4 --output /absolute/frame.png [--time 2.5]
      easycar-media-tool edit --input INPUT.mp4 --output /absolute/clip.mp4 [--start 0] [--duration 12] [--crop x,y,width,height] [--title 'Scene title'] [--title-duration 3] [--fps 30] [--passthrough]
      easycar-media-tool concat --output /absolute/joined.mp4 INPUT1.mp4 INPUT2.mp4 ... [--fps 30] [--passthrough]

    All source files remain unchanged. Existing outputs are refused.
    Crop is in oriented video pixels, with origin at top-left. Title is an optional top banner; no app pixels or score values are fabricated.
    Audio content and synchronization are retained. Normal edit/concat may re-encode audio; --passthrough preserves encoded tracks and forbids crop/title.
    --passthrough concat requires every clip to have identical video dimensions and preferred transforms.
    gain applies actual Float32 PCM multiplication, re-encodes AAC audio, and copies the compressed video unchanged. Peak headroom may reduce the requested gain.
    """
    init(_ arguments: [String]) throws {
        guard let first = arguments.first else { throw fail(Self.usage) }
        if first == "--help" || first == "-h" { print(Self.usage); exit(0) }
        command = first
        var index = 1
        func next(_ name: String) throws -> String { index += 1; guard index < arguments.count else { throw fail("Missing value for \(name)") }; return arguments[index] }
        func number(_ name: String) throws -> Double { let text = try next(name); guard let n = Double(text), n.isFinite, n >= 0 else { throw fail("Invalid \(name)") }; return n }
        while index < arguments.count {
            let arg = arguments[index]
            switch arg {
            case "--input": inputs.append(try next(arg))
            case "--output": output = try next(arg)
            case "--start": start = try number(arg)
            case "--duration": duration = try number(arg)
            case "--time": frameTime = try number(arg)
            case "--title": title = try next(arg)
            case "--title-duration": titleDuration = try number(arg)
            case "--fps": let n = try number(arg); guard n >= 1, n <= 60, n.rounded() == n else { throw fail("FPS must be an integer in 1...60") }; fps = Int32(n)
            case "--crop":
                let parts = try next(arg).split(separator: ",").compactMap { Double($0) }
                guard parts.count == 4, parts.allSatisfy({ $0.isFinite }), parts[0] >= 0, parts[1] >= 0, parts[2] >= 2, parts[3] >= 2 else { throw fail("Crop must be x,y,width,height in pixels") }
                crop = CGRect(x: parts[0], y: parts[1], width: parts[2], height: parts[3])
            case "--passthrough": passthrough = true
            case "--audio-stats": audioStats = true
            case "--gain-db":
                let text = try next(arg)
                guard let value = Double(text), value.isFinite, (-60...36).contains(value) else { throw fail("Gain must be -60...36 dB") }
                gainDB = value
            case "--peak-limit":
                let value = try number(arg)
                guard value >= 0.1, value < 0.9 else { throw fail("Peak limit must be at least0.1 and below0.9") }
                peakLimit = value
            default: guard !arg.hasPrefix("--") else { throw fail("Unknown option \(arg)") }; inputs.append(arg)
            }
            index += 1
        }
        guard ["probe", "frame", "edit", "concat", "gain"].contains(command) else { throw fail("Unknown command \(command)") }
    }
    func destination(extension suffix: String) throws -> URL {
        guard let output, output.hasPrefix("/"), output.lowercased().hasSuffix(suffix) else { throw fail("An absolute --output path ending in \(suffix) is required") }
        guard !FileManager.default.fileExists(atPath: output) else { throw fail("Output already exists: \(output)") }
        return URL(fileURLWithPath: output)
    }
}

struct Clip {
    let path: String
    let asset: AVURLAsset
    let video: AVAssetTrack
    let audio: [AVAssetTrack]
    let duration: CMTime
    let natural: CGSize
    let transform: CGAffineTransform
    let oriented: CGRect
    var normalized: CGAffineTransform { transform.concatenating(CGAffineTransform(translationX: -oriented.minX, y: -oriented.minY)) }
}
func loadClip(_ path: String) async throws -> Clip {
    guard FileManager.default.fileExists(atPath: path) else { throw fail("Source does not exist: \(path)") }
    let asset = AVURLAsset(url: URL(fileURLWithPath: path))
    let duration = try await asset.load(.duration)
    guard seconds(duration).isFinite, seconds(duration) > 0 else { throw fail("Source has no valid duration: \(path)") }
    guard let video = try await asset.loadTracks(withMediaType: .video).first else { throw fail("Source has no video track: \(path)") }
    let natural = try await video.load(.naturalSize)
    let transform = try await video.load(.preferredTransform)
    let oriented = CGRect(origin: .zero, size: natural).applying(transform)
    return Clip(path: path, asset: asset, video: video, audio: try await asset.loadTracks(withMediaType: .audio), duration: duration, natural: natural, transform: transform, oriented: oriented)
}
func fourCC(_ code: FourCharCode) -> String { String(bytes: [UInt8((code >> 24) & 255), UInt8((code >> 16) & 255), UInt8((code >> 8) & 255), UInt8(code & 255)], encoding: .ascii) ?? String(code) }
func transformValues(_ t: CGAffineTransform) -> [Double] { [Double(t.a), Double(t.b), Double(t.c), Double(t.d), Double(t.tx), Double(t.ty)] }

func audioLevels(_ clip: Clip) throws -> [String: Any] {
    guard let audio = clip.audio.first else { return ["audioTrackPresent": false] }
    let reader = try AVAssetReader(asset: clip.asset)
    let output = AVAssetReaderTrackOutput(track: audio, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMIsFloatKey: true, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsNonInterleaved: false])
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else { throw fail("Cannot decode audio") }
    reader.add(output)
    guard reader.startReading() else { throw reader.error ?? fail("Audio reader did not start") }
    var count: Int64 = 0
    var sum = 0.0
    var peak = 0.0
    while let sample = output.copyNextSampleBuffer() {
        guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
        let byteCount = CMBlockBufferGetDataLength(block)
        var values = [Float](repeating: 0, count: byteCount / MemoryLayout<Float>.size)
        let status = values.withUnsafeMutableBytes { buffer in CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: byteCount, destination: buffer.baseAddress!) }
        guard status == kCMBlockBufferNoErr else { throw fail("Could not read decoded audio bytes") }
        for value in values { let n = Double(value); if n.isFinite { count += 1; sum += n*n; peak = max(peak, abs(n)) } }
    }
    if reader.status == .failed { throw reader.error ?? fail("Audio decode failed") }
    let rms = count == 0 ? 0 : sqrt(sum / Double(count))
    return ["audioTrackPresent": true, "decodedSampleValues": count, "peak": peak, "rms": rms, "rmsDBFS": 20*log10(max(rms, 1e-12)), "nonSilent": peak > 0.0001]
}

func amplifiedPCMBuffer(_ source: CMSampleBuffer, multiplier: Double, peakLimit: Double) throws -> CMSampleBuffer {
    guard let sourceBlock = CMSampleBufferGetDataBuffer(source), let format = CMSampleBufferGetFormatDescription(source),
          let basic = CMAudioFormatDescriptionGetStreamBasicDescription(format) else { throw fail("PCM buffer has no audio format/data") }
    let asbd = basic.pointee
    guard asbd.mFormatID == kAudioFormatLinearPCM, asbd.mBitsPerChannel == 32,
          asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0,
          asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0 else { throw fail("Expected interleaved Float32 PCM") }
    let bytes = CMBlockBufferGetDataLength(sourceBlock)
    let count = CMSampleBufferGetNumSamples(source)
    guard bytes == count * Int(asbd.mBytesPerFrame), bytes % MemoryLayout<Float>.size == 0 else { throw fail("Unexpected PCM buffer size") }
    var values = [Float](repeating: 0, count: bytes / MemoryLayout<Float>.size)
    let readStatus = values.withUnsafeMutableBytes { memory in CMBlockBufferCopyDataBytes(sourceBlock, atOffset: 0, dataLength: bytes, destination: memory.baseAddress!) }
    guard readStatus == kCMBlockBufferNoErr else { throw fail("Cannot read PCM sample data") }
    for index in values.indices {
        let scaled = Double(values[index]) * multiplier
        values[index] = scaled.isFinite ? Float(max(-peakLimit, min(peakLimit, scaled))) : 0
    }
    var block: CMBlockBuffer?
    let allocateStatus = CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: bytes,
        blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0, dataLength: bytes,
        flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block)
    guard allocateStatus == kCMBlockBufferNoErr, let block else { throw fail("Cannot allocate amplified PCM buffer") }
    let writeStatus = values.withUnsafeBytes { memory in CMBlockBufferReplaceDataBytes(with: memory.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: bytes) }
    guard writeStatus == kCMBlockBufferNoErr else { throw fail("Cannot write amplified PCM samples") }
    var timing = CMSampleTimingInfo()
    guard CMSampleBufferGetSampleTimingInfo(source, at: 0, timingInfoOut: &timing) == noErr else { throw fail("Cannot copy PCM timing") }
    var size = Int(asbd.mBytesPerFrame)
    var result: CMSampleBuffer?
    let createStatus = CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
        sampleCount: count, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
        sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &result)
    guard createStatus == noErr, let result else { throw fail("Cannot create amplified sample buffer: \(createStatus)") }
    return result
}

func amplifyAudio(_ clip: Clip, to output: URL, gainDB: Double, peakLimit: Double) async throws -> [String: Any] {
    guard clip.audio.count == 1, let audioTrack = clip.audio.first else { throw fail("Gain requires exactly one source audio track") }
    let before = try audioLevels(clip)
    let sourcePeak = before["peak"] as? Double ?? 0
    let requested = pow(10, gainDB / 20)
    let multiplier = sourcePeak > 0 ? min(requested, peakLimit / sourcePeak) : requested
    let reader = try AVAssetReader(asset: clip.asset)
    let videoOutput = AVAssetReaderTrackOutput(track: clip.video, outputSettings: nil)
    videoOutput.alwaysCopiesSampleData = false
    let audioOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM,
        AVSampleRateKey: 48000, AVNumberOfChannelsKey: 2,
        AVLinearPCMIsFloatKey: true, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsNonInterleaved: false])
    audioOutput.alwaysCopiesSampleData = false
    guard reader.canAdd(videoOutput), reader.canAdd(audioOutput) else { throw fail("Cannot configure gain reader") }
    reader.add(videoOutput); reader.add(audioOutput)
    let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
    let formats = try await clip.video.load(.formatDescriptions)
    guard let videoFormat = formats.first else { throw fail("Source video has no format description") }
    let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: videoFormat)
    videoInput.transform = clip.transform
    videoInput.expectsMediaDataInRealTime = false
    let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: 48000, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 192000])
    audioInput.expectsMediaDataInRealTime = false
    guard writer.canAdd(videoInput), writer.canAdd(audioInput) else { throw fail("Cannot configure gain writer") }
    writer.add(videoInput); writer.add(audioInput)
    writer.shouldOptimizeForNetworkUse = true
    guard reader.startReading() else { throw reader.error ?? fail("Gain reader did not start") }
    guard writer.startWriting() else { throw writer.error ?? fail("Gain writer did not start") }
    writer.startSession(atSourceTime: .zero)
    var videoSample = videoOutput.copyNextSampleBuffer()
    var audioSample = audioOutput.copyNextSampleBuffer()
    var videoFinished = false
    var audioFinished = false
    var processedAudioFrames: Int64 = 0
    while videoSample != nil || audioSample != nil {
        if reader.status == .failed { throw reader.error ?? fail("Gain reading failed") }
        if writer.status == .failed { throw writer.error ?? fail("Gain writing failed") }
        let videoReady = videoSample != nil && videoInput.isReadyForMoreMediaData
        let audioReady = audioSample != nil && audioInput.isReadyForMoreMediaData
        let videoEarlier: Bool
        if let videoSample, let audioSample {
            videoEarlier = CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(videoSample), CMSampleBufferGetPresentationTimeStamp(audioSample)) <= 0
        } else { videoEarlier = videoSample != nil }
        if videoReady && (!audioReady || videoEarlier), let sample = videoSample {
            guard videoInput.append(sample) else { throw writer.error ?? fail("Cannot append original compressed video") }
            videoSample = videoOutput.copyNextSampleBuffer()
        } else if audioReady, let sample = audioSample {
            let amplified = try amplifiedPCMBuffer(sample, multiplier: multiplier, peakLimit: peakLimit)
            guard audioInput.append(amplified) else { throw writer.error ?? fail("Cannot append amplified audio") }
            processedAudioFrames += Int64(CMSampleBufferGetNumSamples(sample))
            audioSample = audioOutput.copyNextSampleBuffer()
        } else {
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        if videoSample == nil && !videoFinished { videoInput.markAsFinished(); videoFinished = true }
        if audioSample == nil && !audioFinished { audioInput.markAsFinished(); audioFinished = true }
    }
    if !videoFinished { videoInput.markAsFinished() }
    if !audioFinished { audioInput.markAsFinished() }
    writer.endSession(atSourceTime: clip.duration)
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in writer.finishWriting { continuation.resume() } }
    guard writer.status == .completed else { throw writer.error ?? fail("Gain output did not finalize") }
    let result = try await loadClip(output.path)
    let after = try audioLevels(result)
    guard (after["peak"] as? Double ?? 0) < 0.9 else { throw fail("Encoded audio peak reached0.9; retry with lower gain or peak-limit") }
    return ["output": output.path, "sourceUnchanged": clip.path, "requestedGainDB": gainDB,
        "effectiveGainDB": 20*log10(multiplier), "multiplier": multiplier, "peakLimit": peakLimit,
        "videoCopiedWithoutReencoding": true, "processedAudioFrames": processedAudioFrames,
        "sourceDurationSeconds": seconds(clip.duration), "outputDurationSeconds": seconds(result.duration),
        "before": before, "after": after]
}

func exportAsset(_ asset: AVAsset, to output: URL, passthrough: Bool, composition: AVVideoComposition? = nil, range: CMTimeRange? = nil) async throws {
    guard let exporter = AVAssetExportSession(asset: asset, presetName: passthrough ? AVAssetExportPresetPassthrough : AVAssetExportPresetHighestQuality) else { throw fail("Cannot create exporter") }
    exporter.outputURL = output
    exporter.outputFileType = .mp4
    exporter.shouldOptimizeForNetworkUse = true
    exporter.metadata = []
    exporter.videoComposition = composition
    if let range { exporter.timeRange = range }
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        exporter.exportAsynchronously {
            if exporter.status == .completed { continuation.resume(returning: ()) }
            else { continuation.resume(throwing: exporter.error ?? fail("Export did not complete, status \(exporter.status.rawValue)")) }
        }
    }
}

func insertAudio(_ tracks: [AVAssetTrack], sourceRange: CMTimeRange, into composition: AVMutableComposition, at insertion: CMTime, reusing existing: [AVMutableCompositionTrack] = []) async throws -> [AVMutableCompositionTrack] {
    var destinations = existing
    for (index, source) in tracks.enumerated() {
        let available = try await source.load(.timeRange)
        let range = CMTimeRangeGetIntersection(sourceRange, otherRange: available)
        if !range.isEmpty && seconds(range.duration) > 0 {
            while destinations.count <= index {
                guard let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw fail("Cannot create audio track") }
                destinations.append(track)
            }
            let destination = destinations[index]
            let offset = CMTimeSubtract(range.start, sourceRange.start)
            try destination.insertTimeRange(range, of: source, at: CMTimeAdd(insertion, offset))
        }
    }
    return destinations
}

func rasterizedTitle(_ title: String, size: CGSize, fontSize: CGFloat) throws -> CGImage {
    let scale: CGFloat = 2
    let width = max(1, Int(ceil(size.width * scale)))
    let height = max(1, Int(ceil(size.height * scale)))
    guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
          let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
              bytesPerRow: width * 4, space: colorSpace,
              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
        throw fail("Cannot allocate raster title image")
    }
    context.clear(CGRect(x: 0, y: 0, width: width, height: height))
    context.scaleBy(x: scale, y: scale)
    context.setShouldAntialias(true)
    context.setShouldSmoothFonts(true)
    context.textMatrix = .identity
    context.setTextDrawingMode(.fill)
    let font = CTFontCreateWithName("AppleSDGothicNeo-SemiBold" as CFString, fontSize, nil)
    let attributes: [NSAttributedString.Key: Any] = [
        NSAttributedString.Key(kCTFontAttributeName as String): font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: 1, alpha: 1)
    ]
    let attributed = NSAttributedString(string: title, attributes: attributes)
    let line = CTLineCreateWithAttributedString(attributed as CFAttributedString)
    var ascent: CGFloat = 0
    var descent: CGFloat = 0
    var leading: CGFloat = 0
    let lineWidth = CGFloat(CTLineGetTypographicBounds(line, &ascent, &descent, &leading))
    context.textPosition = CGPoint(x: max(0, (size.width - lineWidth) / 2),
        y: max(descent, (size.height - ascent - descent) / 2 + descent))
    CTLineDraw(line, context)
    guard let image = context.makeImage() else { throw fail("Cannot finalize raster title image") }
    return image
}

func addTitle(_ title: String, duration: Double, renderSize: CGSize, to composition: AVMutableVideoComposition) throws {
    let root = CALayer(); root.frame = CGRect(origin: .zero, size: renderSize)
    let video = CALayer(); video.frame = root.bounds; root.addSublayer(video)
    let bannerHeight = max(58, renderSize.height * 0.10)
    let inset = max(12, renderSize.width * 0.02)
    let banner = CALayer()
    banner.frame = CGRect(x: inset, y: renderSize.height - bannerHeight - inset, width: renderSize.width - 2*inset, height: bannerHeight)
    banner.backgroundColor = CGColor(gray: 0, alpha: 0.76); banner.cornerRadius = 10
    let text = CALayer(); text.frame = banner.bounds.insetBy(dx: 14, dy: 10)
    // Rasterize glyphs eagerly: CATextLayer may never draw in a headless export.
    text.contents = try rasterizedTitle(title, size: text.bounds.size, fontSize: max(20, renderSize.height * 0.034))
    text.contentsScale = 2
    text.contentsGravity = .resizeAspect
    banner.addSublayer(text); root.addSublayer(banner)
    if duration > 0 {
        banner.opacity = 0
        let visible = CAKeyframeAnimation(keyPath: "opacity")
        visible.values = [1, 1, 0]; visible.keyTimes = [0, 0.9, 1]
        visible.beginTime = AVCoreAnimationBeginTimeAtZero; visible.duration = duration
        visible.isRemovedOnCompletion = false; visible.fillMode = .forwards
        banner.add(visible, forKey: "titleVisibility")
    }
    composition.animationTool = AVVideoCompositionCoreAnimationTool(postProcessingAsVideoLayer: video, in: root)
}

@main
@MainActor
struct Main {
    static func main() async {
        // CLI executables do not receive NSApplicationMain initialization.
        // Establish AppKit/WindowServer on the main actor before ScreenCaptureKit
        // or CoreAnimation creates graphics/recording resources.
        let application = NSApplication.shared
        application.setActivationPolicy(.prohibited)
        application.finishLaunching()
        setbuf(stdout, nil)
        do { try await run(Options(Array(CommandLine.arguments.dropFirst()))) }
        catch { FileHandle.standardError.write(Data(("ERROR: \(error.localizedDescription)\n").utf8)); exit(1) }
    }
    static func run(_ options: Options) async throws {
        if options.command == "concat" { try await concat(options); return }
        guard options.inputs.count == 1, let input = options.inputs.first else { throw fail("Exactly one input is required") }
        let clip = try await loadClip(input)
        switch options.command {
        case "probe":
            var info: [String: Any] = ["path": input, "durationSeconds": seconds(clip.duration), "naturalWidth": Double(clip.natural.width), "naturalHeight": Double(clip.natural.height), "orientedWidth": Double(clip.oriented.width), "orientedHeight": Double(clip.oriented.height), "preferredTransform": transformValues(clip.transform), "fps": Double(try await clip.video.load(.nominalFrameRate)), "audioTracks": clip.audio.count]
            let formats = try await clip.video.load(.formatDescriptions)
            if let format = formats.first { info["videoCodec"] = fourCC(CMFormatDescriptionGetMediaSubType(format)) }
            if options.audioStats { info["audioLevels"] = try audioLevels(clip) }
            print(try json(info))
        case "frame":
            let output = try options.destination(extension: ".png")
            guard options.frameTime < seconds(clip.duration) else { throw fail("Frame time is outside the source") }
            let generator = AVAssetImageGenerator(asset: clip.asset)
            generator.appliesPreferredTrackTransform = true
            generator.requestedTimeToleranceBefore = .zero; generator.requestedTimeToleranceAfter = .zero
            let (image, actual) = try await generator.image(at: time(options.frameTime))
            guard let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil) else { throw fail("Cannot open PNG destination") }
            CGImageDestinationAddImage(destination, image, nil)
            guard CGImageDestinationFinalize(destination) else { throw fail("Cannot write PNG") }
            print(try json(["output": output.path, "timeSeconds": seconds(actual), "width": image.width, "height": image.height]))
        case "edit": try await edit(clip, options)
        case "gain": try await gain(clip, options)
        default: throw fail("Unknown command")
        }
    }

    static func gain(_ clip: Clip, _ options: Options) async throws {
        let output = try options.destination(extension: ".mp4")
        let stats = try await amplifyAudio(clip, to: output, gainDB: options.gainDB, peakLimit: options.peakLimit)
        print(try json(stats))
    }

    static func edit(_ clip: Clip, _ options: Options) async throws {
        let output = try options.destination(extension: ".mp4")
        let available = seconds(clip.duration) - options.start
        guard available > 0 else { throw fail("Start time is outside the source") }
        let length = min(options.duration ?? available, available)
        guard length > 0 else { throw fail("Duration must be greater than zero") }
        let range = CMTimeRange(start: time(options.start), duration: time(length))
        if options.passthrough {
            guard options.crop == nil && options.title == nil else { throw fail("Passthrough cannot crop or draw titles") }
            try await exportAsset(clip.asset, to: output, passthrough: true, range: range)
        } else {
            let rect = options.crop ?? CGRect(origin: .zero, size: clip.oriented.size)
            guard rect.maxX <= clip.oriented.width + 0.01, rect.maxY <= clip.oriented.height + 0.01 else { throw fail("Crop exceeds oriented source dimensions") }
            let size = CGSize(width: Int(rect.width) / 2 * 2, height: Int(rect.height) / 2 * 2)
            let composition = AVMutableComposition()
            guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw fail("Cannot create video track") }
            try video.insertTimeRange(range, of: clip.video, at: .zero)
            _ = try await insertAudio(clip.audio, sourceRange: range, into: composition, at: .zero)
            let instructions = AVMutableVideoCompositionInstruction(); instructions.timeRange = CMTimeRange(start: .zero, duration: range.duration)
            let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: video)
            layer.setTransform(clip.normalized.concatenating(CGAffineTransform(translationX: -rect.minX, y: -rect.minY)), at: .zero)
            instructions.layerInstructions = [layer]
            let processing = AVMutableVideoComposition(); processing.renderSize = size; processing.frameDuration = CMTime(value: 1, timescale: options.fps); processing.instructions = [instructions]
            if let title = options.title { try addTitle(title, duration: options.titleDuration, renderSize: size, to: processing) }
            try await exportAsset(composition, to: output, passthrough: false, composition: processing)
        }
        print(try json(["output": output.path, "durationSeconds": length, "sourceUnchanged": clip.path, "passthrough": options.passthrough]))
    }
    static func concat(_ options: Options) async throws {
        guard !options.inputs.isEmpty else { throw fail("Concat needs at least one input") }
        guard options.crop == nil && options.title == nil else { throw fail("Apply crop/title to individual clips with edit before concat") }
        let output = try options.destination(extension: ".mp4")
        var clips: [Clip] = []
        for path in options.inputs { clips.append(try await loadClip(path)) }
        let first = clips[0]
        if options.passthrough {
            guard clips.allSatisfy({ $0.natural == first.natural && transformValues($0.transform) == transformValues(first.transform) }) else { throw fail("Passthrough concat requires identical dimensions and transforms") }
        }
        let composition = AVMutableComposition()
        guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw fail("Cannot create video track") }
        video.preferredTransform = options.passthrough ? first.transform : .identity
        let size = CGSize(width: Int(first.oriented.width) / 2 * 2, height: Int(first.oriented.height) / 2 * 2)
        var cursor = CMTime.zero
        var instructions: [AVMutableVideoCompositionInstruction] = []
        var audioTracks: [AVMutableCompositionTrack] = []
        for clip in clips {
            let range = CMTimeRange(start: .zero, duration: clip.duration)
            try video.insertTimeRange(range, of: clip.video, at: cursor)
            audioTracks = try await insertAudio(clip.audio, sourceRange: range, into: composition, at: cursor, reusing: audioTracks)
            let instruction = AVMutableVideoCompositionInstruction(); instruction.timeRange = CMTimeRange(start: cursor, duration: clip.duration)
            let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: video)
            let fit = min(size.width / clip.oriented.width, size.height / clip.oriented.height)
            let offsetX = (size.width - clip.oriented.width * fit) / 2
            let offsetY = (size.height - clip.oriented.height * fit) / 2
            layer.setTransform(clip.normalized.concatenating(CGAffineTransform(scaleX: fit, y: fit)).concatenating(CGAffineTransform(translationX: offsetX, y: offsetY)), at: cursor)
            instruction.layerInstructions = [layer]; instructions.append(instruction)
            cursor = CMTimeAdd(cursor, clip.duration)
        }
        let processing = AVMutableVideoComposition(); processing.renderSize = size; processing.frameDuration = CMTime(value: 1, timescale: options.fps); processing.instructions = instructions
        try await exportAsset(composition, to: output, passthrough: options.passthrough, composition: options.passthrough ? nil : processing)
        print(try json(["output": output.path, "durationSeconds": seconds(cursor), "clips": options.inputs.count, "sourcesUnchanged": options.inputs, "passthrough": options.passthrough]))
    }
}
