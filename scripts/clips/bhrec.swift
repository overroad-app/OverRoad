// bhrec <출력.wav> <초> : "BlackHole 2ch" 입력을 CoreAudio(AVAudioEngine)로 그대로 받아 WAV로 저장한다.
// ffmpeg의 avfoundation 입력은 이 환경에서 소리 조각을 빠뜨려(약 12%) 음성이 끊겨 들린다 → 직접 받는다.
import AVFoundation
import CoreAudio

func deviceID(named name: String) -> AudioDeviceID? {
    var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var size: UInt32 = 0
    AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size)
    var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
    AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids)
    for id in ids {
        var nameAddr = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var cf: Unmanaged<CFString>?
        var s = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        if AudioObjectGetPropertyData(id, &nameAddr, 0, nil, &s, &cf) == noErr, let n = cf?.takeRetainedValue() as String?, n == name { return id }
    }
    return nil
}

let args = CommandLine.arguments
guard args.count >= 3, let seconds = Double(args[2]) else { print("usage: bhrec out.wav seconds"); exit(2) }
guard var dev = deviceID(named: "BlackHole 2ch") else { print("BlackHole 2ch 없음"); exit(3) }
let engine = AVAudioEngine()
let input = engine.inputNode
let err = AudioUnitSetProperty(input.audioUnit!, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &dev, UInt32(MemoryLayout<AudioDeviceID>.size))
guard err == noErr else { print("입력 장치 지정 실패 \(err)"); exit(4) }
let format = input.outputFormat(forBus: 0)
let settings: [String: Any] = [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: format.sampleRate, AVNumberOfChannelsKey: format.channelCount,
                               AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false]
let file = try AVAudioFile(forWriting: URL(fileURLWithPath: args[1]), settings: settings)
var started = false
input.installTap(onBus: 0, bufferSize: 4096, format: format) { buf, _ in
    if !started { started = true; print("START \(Date().timeIntervalSince1970)"); fflush(stdout) }   // 첫 소리 조각이 들어온 시각 (영상과 맞출 때 쓴다)
    try? file.write(from: buf)
}
signal(SIGINT, SIG_IGN)
let src = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
src.setEventHandler { engine.stop(); exit(0) }
src.resume()
try engine.start()
DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { engine.stop(); exit(0) }
RunLoop.main.run()
