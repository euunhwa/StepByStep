#!/usr/bin/env python3
"""Feed a synthetic PCM WAV to an authenticated local Android Emulator microphone.

Requires grpcio and SDK-generated emulator_controller_pb2{,_grpc}. The SDK proto is
used unchanged. No host microphone, user audio, access token or record data is saved.
Run the opt-in GleoVoiceProbe instrumentation fixture first (see integration guide).
"""
import argparse
from pathlib import Path
import subprocess
import time
import wave
import grpc
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as rpc
from google.protobuf.empty_pb2 import Empty


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--discovery',type=Path,required=True)
    parser.add_argument('--wav',type=Path,required=True)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--user',default='10')
    args=parser.parse_args()
    config=dict(line.split('=',1) for line in args.discovery.read_text().splitlines() if '=' in line)
    with wave.open(str(args.wav),'rb') as audio:
        if (audio.getnchannels(),audio.getsampwidth(),audio.getframerate(),audio.getcomptype())!=(1,2,16000,'NONE'):
            raise SystemExit('Use mono signed PCM16 WAV at 16000 Hz.')
        if audio.getnframes()>16000*15:
            raise SystemExit('Limit the synthetic test command to 15 seconds.')
        data=b'\0'*32000+audio.readframes(audio.getnframes())+b'\0'*96000
    metadata=[('authorization','Bearer '+config['grpc.token'])]
    channel=grpc.insecure_channel('localhost:'+config['grpc.port'])
    stub=rpc.EmulatorControllerStub(channel)
    if stub.getMicrophoneState(Empty(),metadata=metadata,timeout=3).realAudioEnabled:
        raise SystemExit('Disable host microphone input before synthetic injection.')
    def packets():
        start=time.monotonic()
        for number,offset in enumerate(range(0,len(data),640)):
            if number==10:
                subprocess.run([args.adb,'-s',args.serial,'shell','am','broadcast','--user',args.user,'-a',
                    'android.intent.action.VOICE_ASSIST','-p','ai.umos.ambientai'],check=True,stdout=subprocess.DEVNULL)
            pause=start+number*.02-time.monotonic()
            if pause>0: time.sleep(pause)
            yield pb.AudioPacket(format=pb.AudioFormat(samplingRate=16000,channels=pb.AudioFormat.Mono,
                format=pb.AudioFormat.AUD_FMT_S16,mode=pb.AudioFormat.MODE_REAL_TIME),
                timestamp=int(time.time()*1000000),audio=data[offset:offset+640])
    try:
        stub.injectAudio(packets(),metadata=metadata,timeout=len(data)/32000+6)
        print('Synthetic microphone stream completed. Check Gleo logs and the probe result for actual recognition/action evidence.')
    except grpc.RpcError as error:
        raise SystemExit('Microphone injection failed: '+error.code().name) from None
    finally:
        channel.close()


if __name__=='__main__': main()
