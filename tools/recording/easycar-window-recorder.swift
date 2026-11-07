import Foundation
import AppKit
import ScreenCaptureKit
import AVFoundation
import CoreGraphics
import Darwin

struct Options {
    var list = false
    var requestPermission = false
    var windowID: UInt32?
    var title: String?
    var app: String?
    var output: String?
    var duration: Double = 180
    var fps: Int32 = 30
    var maxWidth = 1920
    var audio = true

    static let usage = """
    Usage:
      easycar-window-recorder --list
      easycar-window-recorder --request-permission
      easycar-window-recorder --window-id ID --output /absolute/movie.mp4 [--duration 180] [--fps 30] [--max-width 1920] [--no-audio]
      easycar-window-recorder --app emulator --title Pleos --output /absolute/movie.mp4 [options]

    Records only the selected window through ScreenCaptureKit, with its associated application audio.
    Capture/list require existing Screen Recording permission. Only --request-permission invokes the macOS permission request.
    SIGINT/SIGTERM stop recording and finalize the MP4. The default limit is 180 seconds; allowed duration is 1...1800 seconds.
    --list returns visible window IDs, titles, owning applications, process IDs, and dimensions as JSON.
    """

    init(_ args: [String]) throws {
        var i = 0
        func next(_ name: String) throws -> String {
            i += 1
            guard i < args.count else { throw RecorderError.message("Missing value for \(name)") }
            return args[i]
        }
        while i < args.count {
            let arg = args[i]
            switch arg {
            case "--list": list = true
            case "--request-permission": requestPermission = true
            case "--window-id":
                let value = try next(arg)
                guard let parsed = UInt32(value) else { throw RecorderError.message("Invalid window ID") }
                windowID = parsed
            case "--title": title = try next(arg)
            case "--app": app = try next(arg)
            case "--output": output = try next(arg)
            case "--duration":
                let value = try next(arg)
                guard let parsed = Double(value), parsed.isFinite, (1...1800).contains(parsed) else { throw RecorderError.message("Duration must be 1...1800 seconds") }
                duration = parsed
            case "--fps":
                let value = try next(arg)
                guard let parsed = Int32(value), (1...60).contains(parsed) else { throw RecorderError.message("FPS must be 1...60") }
                fps = parsed
            case "--max-width":
                let value = try next(arg)
                guard let parsed = Int(value), (320...4096).contains(parsed) else { throw RecorderError.message("Max width must be 320...4096") }
                maxWidth = parsed
            case "--no-audio": audio = false
            case "--help", "-h": print(Self.usage); exit(0)
            default: throw RecorderError.message("Unknown option \(arg)")
            }
            i += 1
        }
    }
}

enum RecorderError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let text): return text }
    }
}

@available(macOS 15.0, *)
final class RecordingObserver: NSObject, SCRecordingOutputDelegate, SCStreamDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var didStart = false
    private var didFinish = false
    private var stopRequested = false
    private var failure: Error?

    func state() -> (started: Bool, finished: Bool, stop: Bool, error: Error?) {
        lock.lock(); defer { lock.unlock() }
        return (didStart, didFinish, stopRequested, failure)
    }
    func requestStop() { lock.lock(); stopRequested = true; lock.unlock() }
    func recordingOutputDidStartRecording(_ recordingOutput: SCRecordingOutput) {
        lock.lock(); didStart = true; lock.unlock()
        print("RECORDING_STARTED")
    }
    func recordingOutputDidFinishRecording(_ recordingOutput: SCRecordingOutput) {
        lock.lock(); didFinish = true; lock.unlock()
    }
    func recordingOutput(_ recordingOutput: SCRecordingOutput, didFailWithError error: Error) {
        lock.lock(); failure = error; didFinish = true; lock.unlock()
    }
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        lock.lock(); failure = error; stopRequested = true; lock.unlock()
    }
}

@main
@MainActor
struct Main {
    static func json(_ value: Any) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    @available(macOS 15.0, *)
    static func windowInfo(_ window: SCWindow) -> [String: Any] {
        ["windowID": window.windowID,
         "title": window.title ?? "",
         "app": window.owningApplication?.applicationName ?? "",
         "bundleID": window.owningApplication?.bundleIdentifier ?? "",
         "pid": window.owningApplication?.processID ?? 0,
         "onScreen": window.isOnScreen,
         "widthPoints": window.frame.width,
         "heightPoints": window.frame.height]
    }

    static func main() async {
        // CLI executables do not receive NSApplicationMain initialization.
        // Establish AppKit/WindowServer on the main actor before ScreenCaptureKit
        // or CoreAnimation creates graphics/recording resources.
        let application = NSApplication.shared
        application.setActivationPolicy(.prohibited)
        application.finishLaunching()
        setbuf(stdout, nil)
        do {
            let options = try Options(Array(CommandLine.arguments.dropFirst()))
            guard #available(macOS 15.0, *) else { throw RecorderError.message("macOS 15 or later is required") }
            try await run(options)
        } catch {
            FileHandle.standardError.write(Data(("ERROR: \(error.localizedDescription)\n").utf8))
            exit(1)
        }
    }

    @available(macOS 15.0, *)
    static func run(_ options: Options) async throws {
        if options.requestPermission {
            let allowed = CGRequestScreenCaptureAccess()
            print(try json(["screenRecordingAllowed": allowed, "requestInvoked": true]))
            return
        }
        guard CGPreflightScreenCaptureAccess() else {
            throw RecorderError.message("Screen Recording permission is unavailable. No permission request was made. Grant permission in System Settings before running this tool.")
        }
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
        let windows = content.windows.filter { $0.windowLayer == 0 && $0.frame.width >= 100 && $0.frame.height >= 100 }
        if options.list {
            print(try json(windows.map(windowInfo)))
            return
        }
        guard let path = options.output, path.hasPrefix("/"), path.lowercased().hasSuffix(".mp4") else {
            throw RecorderError.message("An absolute --output path ending in .mp4 is required")
        }
        guard !FileManager.default.fileExists(atPath: path) else {
            throw RecorderError.message("Output already exists: \(path)")
        }
        let matches = windows.filter { window in
            if let id = options.windowID, window.windowID != id { return false }
            if let title = options.title, !(window.title ?? "").localizedCaseInsensitiveContains(title) { return false }
            if let app = options.app {
                let owner = window.owningApplication
                if !(owner?.applicationName.localizedCaseInsensitiveContains(app) ?? false) && !(owner?.bundleIdentifier.localizedCaseInsensitiveContains(app) ?? false) { return false }
            }
            return true
        }
        guard options.windowID != nil || options.title != nil || options.app != nil else {
            throw RecorderError.message("Select an explicit --window-id, --app, or --title; use --list to inspect windows")
        }
        guard matches.count == 1, let selected = matches.first else {
            throw RecorderError.message("Selection matched \(matches.count) windows; narrow the selection. Candidates: \(try json(matches.map(windowInfo)))")
        }
        let filter = SCContentFilter(desktopIndependentWindow: selected)
        let filterScale = Double(filter.pointPixelScale)
        // A desktop-independent window filter can report nominal 1x sizing.
        // Retain the backing density of the display containing most of the window.
        let targetDisplay = content.displays.max { a, b in
            let aRect = a.frame.intersection(selected.frame)
            let bRect = b.frame.intersection(selected.frame)
            let aArea = aRect.isNull ? 0 : aRect.width * aRect.height
            let bArea = bRect.isNull ? 0 : bRect.width * bRect.height
            return aArea < bArea
        }
        let targetScreen = NSScreen.screens.first { screen in
            guard let displayID = (screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value else { return false }
            return displayID == targetDisplay?.displayID
        }
        let displayScale = Double(targetScreen?.backingScaleFactor ?? 1)
        let scale = max(1, max(filterScale, displayScale))
        let nativeWidth = Double(filter.contentRect.width) * scale
        let nativeHeight = Double(filter.contentRect.height) * scale
        guard nativeWidth > 0, nativeHeight > 0 else { throw RecorderError.message("Selected window has no capturable dimensions") }
        let outputScale = min(1, Double(options.maxWidth) / nativeWidth)
        let width = max(2, Int(nativeWidth * outputScale) / 2 * 2)
        let height = max(2, Int((nativeHeight * outputScale / 2).rounded()) * 2)
        let config = SCStreamConfiguration()
        config.width = width
        config.height = height
        config.minimumFrameInterval = CMTime(value: 1, timescale: options.fps)
        config.queueDepth = 4
        config.showsCursor = false
        config.scalesToFit = true
        config.preservesAspectRatio = true
        config.ignoreShadowsSingleWindow = true
        config.captureResolution = .best
        config.capturesAudio = options.audio
        config.captureMicrophone = false
        config.sampleRate = 48000
        config.channelCount = 2
        config.excludesCurrentProcessAudio = true
        let recordingConfig = SCRecordingOutputConfiguration()
        recordingConfig.outputURL = URL(fileURLWithPath: path)
        recordingConfig.videoCodecType = .h264
        recordingConfig.outputFileType = .mp4
        let observer = RecordingObserver()
        let recording = SCRecordingOutput(configuration: recordingConfig, delegate: observer)
        let stream = SCStream(filter: filter, configuration: config, delegate: observer)
        try stream.addRecordingOutput(recording)
        signal(SIGINT, SIG_IGN)
        signal(SIGTERM, SIG_IGN)
        let interrupt = DispatchSource.makeSignalSource(signal: SIGINT, queue: .global())
        let terminate = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .global())
        interrupt.setEventHandler { observer.requestStop() }
        terminate.setEventHandler { observer.requestStop() }
        interrupt.resume(); terminate.resume()
        defer { interrupt.cancel(); terminate.cancel() }
        print("SELECTED " + (try json(["window": windowInfo(selected), "width": width, "height": height, "filterPointPixelScale": filterScale, "displayBackingScale": displayScale, "effectivePixelScale": scale, "contentWidthPoints": Double(filter.contentRect.width), "contentHeightPoints": Double(filter.contentRect.height), "nativeWidthPixels": nativeWidth, "nativeHeightPixels": nativeHeight, "audio": options.audio, "output": path, "durationLimitSeconds": options.duration])))
        try await stream.startCapture()
        let startupDeadline = Date().addingTimeInterval(15)
        while !observer.state().started && observer.state().error == nil && !observer.state().stop && Date() < startupDeadline {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        if let error = observer.state().error { try? await stream.stopCapture(); throw error }
        guard observer.state().started else { try? await stream.stopCapture(); throw RecorderError.message("Recording did not report startup") }
        let deadline = Date().addingTimeInterval(options.duration)
        while Date() < deadline && !observer.state().stop && observer.state().error == nil {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        try await stream.stopCapture()
        let finishDeadline = Date().addingTimeInterval(15)
        while !observer.state().finished && Date() < finishDeadline {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        if let error = observer.state().error { throw error }
        guard observer.state().finished else { throw RecorderError.message("Recording did not report finalization; verify the MP4 before use") }
        print("RECORDING_FINISHED " + (try json(["output": path, "durationSeconds": CMTimeGetSeconds(recording.recordedDuration), "bytes": recording.recordedFileSize])))
    }
}
