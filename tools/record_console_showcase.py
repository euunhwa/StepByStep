"""Drive the real localhost console API and record the app window; never inject app scores."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid


def default_adb():
    executable = 'adb.exe' if os.name == 'nt' else 'adb'
    for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        sdk_root = os.environ.get(variable)
        if sdk_root:
            candidate = Path(sdk_root).expanduser() / 'platform-tools' / executable
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate)
    return shutil.which(executable) or executable


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--profile', required=True)
    p.add_argument('--output', required=True)
    p.add_argument('--window-id', required=True)
    p.add_argument('--recorder', default='/tmp/easycar-window-recorder')
    p.add_argument('--adb', default=default_adb(), help='Defaults to ANDROID_HOME/ANDROID_SDK_ROOT platform-tools, then adb on PATH.')
    args = p.parse_args()
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    profile = json.loads(Path(args.profile).read_text())
    (out / 'console-inputs.json').write_text(json.dumps(profile, ensure_ascii=False))
    adb = [args.adb, '-s', 'emulator-5554']
    runas = ['run-as', 'com.example.easycar', '--user', '10']
    latest = {}
    quit_monitor = threading.Event()
    client_id = str(uuid.uuid4())

    def heartbeat(closing=False):
        url = 'http://127.0.0.1:8088/browser/session?client=' + client_id + ('&closing=1' if closing else '')
        with urllib.request.urlopen(urllib.request.Request(url, method='POST'), timeout=4) as response:
            response.read()

    def keep_console_active():
        while not quit_monitor.wait(.8):
            try:
                heartbeat()
            except OSError:
                pass

    def read_state():
        r = subprocess.run(adb + ['exec-out'] + runas + ['cat', 'files/capture-state.json'], capture_output=True, timeout=5)
        return json.loads(r.stdout)

    def put(path, **values):
        url = 'http://127.0.0.1:8088/vehicle/' + path + '?' + urllib.parse.urlencode(values)
        with urllib.request.urlopen(urllib.request.Request(url, method='PUT'), timeout=4) as response:
            data = response.read()
        return data

    def monitor():
        last_signature = None
        with (out / 'observed-feedback.jsonl').open('w') as log:
            while not quit_monitor.is_set():
                try:
                    state = read_state()
                    latest.clear()
                    latest.update(state)
                    feedback = state.get('feedbacks', [])
                    signature = [(f['resultId'], f.get('score'), f['voiceState']) for f in feedback]
                    if signature != last_signature:
                        log.write(json.dumps({'hostTime': time.time(), 'state': state}, ensure_ascii=False) + '\n')
                        log.flush()
                        print('APP', [(f['type'], f.get('score'), f['voiceState']) for f in feedback], flush=True)
                        last_signature = signature
                except (ValueError, OSError, subprocess.TimeoutExpired):
                    pass
                quit_monitor.wait(.5)

    try:
        old_id = read_state().get('id')
    except (ValueError, OSError, subprocess.TimeoutExpired):
        old_id = None
    heartbeat()
    heartbeat_thread = threading.Thread(target=keep_console_active, daemon=True)
    heartbeat_thread.start()
    deadline = time.monotonic() + 20
    while True:
        with urllib.request.urlopen('http://127.0.0.1:8088/vehicle/state', timeout=4) as response:
            vehicle = json.load(response)
        if vehicle.get('controls', {}).get('dds_ready', {}).get('all') and vehicle.get('gear', {}).get('bridge_ready'):
            break
        if time.monotonic() > deadline:
            raise RuntimeError('8088 vehicle DDS bridge did not become ready')
        time.sleep(.2)
    put('hvac/mode', mode='subscribe_hvac_base')
    put('gear', gear='Park')
    for _ in range(5):
        put('controls', speed=0, accelerator=0, brake=0, steering=0)
        time.sleep(.2)
    device_log = (out / 'instrumentation.log').open('w')
    instrument = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.example.easycar.ConsoleShowcaseCaptureTest',
        'com.example.easycar.test/androidx.test.runner.AndroidJUnitRunner'], stdout=device_log, stderr=subprocess.STDOUT)
    monitor_thread = threading.Thread(target=monitor, daemon=True)
    monitor_thread.start()
    recorder = None
    phase_times = []
    error = None
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if latest.get('ready') and latest.get('id') != old_id:
                break
            if instrument.poll() is not None:
                raise RuntimeError('Observation test ended before becoming ready')
            put('controls', speed=0, accelerator=0, brake=0, steering=0)
            time.sleep(.2)
        else:
            raise RuntimeError('Emulator model did not become ready: ' + json.dumps(latest, ensure_ascii=False))
        print('READY', latest.get('source'), flush=True)
        initial_speed=int(profile.get('initialSpeedKmh',0))
        initial_gear=profile.get('initialGear','P')
        if initial_gear!='P':
            put('gear', gear={'D':'Drive','R':'Reverse','N':'Neutral'}[initial_gear])
            put('controls', speed=initial_speed, accelerator=0, brake=0, steering=0)
        capture_log = (out / 'recorder.log').open('w')
        recorder = subprocess.Popen([args.recorder, '--window-id', args.window_id, '--output', str(out / 'original.mp4'),
            '--duration', '600', '--fps', '30', '--max-width', '1920'], stdout=capture_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 15
        while 'RECORDING_STARTED' not in (out / 'recorder.log').read_text():
            if recorder.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Recorder did not start: ' + (out / 'recorder.log').read_text())
            time.sleep(.1)
        start = time.monotonic()
        (out / 'recording-timing.json').write_text(json.dumps({'hostEpochAtVideoStart':time.time(),
            'observedDeviceMsNearVideoStart':latest.get('observedAtMs')}))
        time.sleep(2)
        current_gear = initial_gear
        phase_by_start = {phase['startFrame']: phase for phase in profile['phases']}
        with (out / 'sent-controls.jsonl').open('w') as trace:
            for i, control in enumerate(profile['controls']):
                step = time.monotonic()
                if i in phase_by_start:
                    phase = phase_by_start[i]
                    phase_times.append({**phase, 'videoStartSeconds': step - start})
                    print('PHASE', phase['name'], round(step - start, 2), flush=True)
                gear = control['gearControl']
                if gear != current_gear:
                    put('gear', gear={'D': 'Drive', 'R': 'Reverse', 'P': 'Park', 'N': 'Neutral'}[gear])
                    current_gear = gear
                # The console's wheel sign is opposite to the SDK's left-positive wheel reading.
                values = {'speed': abs(int(control['speedKmh'])), 'accelerator': 0, 'brake': 0, 'steering': -int(control['steeringDeg'])}
                put('controls', **values)
                trace.write(json.dumps({'frame': i, 'videoSeconds': time.monotonic() - start, **values, 'gear': gear}) + '\n')
                if recorder.poll() is not None:
                    raise RuntimeError('Recorder stopped during input playback')
                time.sleep(max(0, .1 - (time.monotonic() - step)))
        phase_times.append({'name': 'end', 'videoStartSeconds': time.monotonic() - start})
        put('gear', gear='Park')
        put('controls', speed=0, accelerator=0, brake=0, steering=0)
        time.sleep(3)
    except Exception as exc:
        error = str(exc)
        print('ERROR', error, flush=True)
    finally:
        put('gear', gear='Park')
        put('controls', speed=0, accelerator=0, brake=0, steering=0)
        subprocess.run(adb + ['shell'] + runas + ['touch', 'files/capture-stop'], capture_output=True, timeout=5)
        try:
            instrument.wait(timeout=25)
        except subprocess.TimeoutExpired:
            instrument.terminate()
        if recorder and recorder.poll() is None:
            recorder.send_signal(signal.SIGINT)
            recorder.wait(timeout=25)
        quit_monitor.set()
        monitor_thread.join(timeout=6)
        heartbeat_thread.join(timeout=5)
        heartbeat(closing=True)
        (out / 'phase-times.json').write_text(json.dumps(phase_times, ensure_ascii=False, indent=2))
        (out / 'last-observed-state.json').write_text(json.dumps(latest, ensure_ascii=False, indent=2))
        r = subprocess.run(adb + ['exec-out'] + runas + ['cat', 'files/capture-session.json'], capture_output=True, timeout=8)
        try:
            session = json.loads(r.stdout)
            (out / 'session.json').write_text(json.dumps(session, ensure_ascii=False))
            actual = [(r['type'], r.get('score')) for r in session['results']]
            expected = [(phase['type'], phase['targetScore']) for phase in profile['phases']]
            print('ACTUAL', actual, flush=True)
            print('EXPECTED', expected, flush=True)
            (out / 'verification.json').write_text(json.dumps({'actual': actual, 'expected': expected,
                'exactMatch': actual == expected, 'error': error}, ensure_ascii=False, indent=2))
        except (ValueError, KeyError) as exc:
            print('Unable to read final session:', exc, flush=True)
        device_log.close()
    if error:
        raise RuntimeError(error)


if __name__ == '__main__':
    main()
