"""Exercise user-approved screen recording with actual 8088 -> vehicle-service inputs.

Requires a built/installed debug app and test APK, one booted Pleos emulator,
and the localhost virtual console. It changes only simulated gear/speed,
returns them to P/0, and restores application settings in the device test.
No scores, maneuver labels, vehicle frames or camera frames are injected into EasyCar.
"""
import argparse
import json
from pathlib import Path
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default='/Users/genius/Library/Android/sdk/platform-tools/adb')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-s', 'emulator-5554']
    runas = ['run-as', 'com.example.easycar', '--user', '10']
    client = str(uuid.uuid4())
    stopped = threading.Event()
    requests = []

    def request(path, method, **values):
        url = 'http://127.0.0.1:8088/' + path + '?' + urllib.parse.urlencode(values)
        with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=4) as response:
            return response.read()

    def heartbeat():
        while not stopped.is_set():
            try:
                request('browser/session', 'POST', client=client)
            except OSError:
                pass
            stopped.wait(.8)

    def gear(name, speed, seconds):
        request('vehicle/gear', 'PUT', gear=name)
        requests.append({'hostMonotonic': time.monotonic(), 'gear': name, 'speedKph': speed, 'durationSeconds': seconds})
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            request('vehicle/controls', 'PUT', speed=speed, accelerator=0, brake=0, steering=0)
            time.sleep(.15)

    process = None
    threading.Thread(target=heartbeat, daemon=True).start()
    try:
        deadline = time.monotonic() + 20
        while True:
            data = json.loads(request('vehicle/state', 'GET'))
            if data.get('controls', {}).get('dds_ready', {}).get('all') and data.get('gear', {}).get('bridge_ready'):
                break
            if time.monotonic() > deadline:
                raise RuntimeError('8088 DDS bridge unavailable')
            time.sleep(.2)
        gear('Park', 0, 2)
        subprocess.run(adb + ['shell'] + runas + ['rm', '-f', 'files/parking-capture-console-ready.json'], check=True)
        with (output / 'device-parking-console-v013.log').open('w') as log:
            process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '--user', '10', '-w',
                '-e', 'class', 'com.example.easycar.ParkingProjectionCaptureTest',
                '-e', 'cameraDisplayProbe', 'true', '-e', 'externalConsoleFlow', 'true',
                'com.example.easycar.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 35
            while True:
                state = subprocess.run(adb + ['exec-out'] + runas + ['cat', 'files/parking-capture-console-ready.json'], capture_output=True)
                try:
                    ready = state.returncode == 0 and json.loads(state.stdout).get('ready')
                except (ValueError, UnicodeDecodeError):
                    ready = False  # Missing file or a concurrent write has not completed yet.
                if ready:
                    break
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Capture did not become ready; see device log')
                time.sleep(.2)
            # Stable R -> D twice, then a full stop before P. Values are integer console units.
            for name, speed, seconds in [('Drive', 2, 2), ('Reverse', 2, 2), ('Drive', 2, 2),
                                         ('Reverse', 2, 2), ('Drive', 2, 2), ('Drive', 0, 2), ('Park', 0, 2)]:
                gear(name, speed, seconds)
            process.wait(timeout=30)
        log = (output / 'device-parking-console-v013.log').read_text()
        if 'OK (1 test)' not in log:
            raise RuntimeError('Device assertions failed; see device log')
        for name in ['parking-console-v013.json', 'parking-console-record-v013.json', 'parking-console-frame-v013.png']:
            data = subprocess.run(adb + ['exec-out'] + runas + ['cat', 'files/validation-' + name], check=True, capture_output=True).stdout
            (output / name).write_bytes(data)
        print('PASS: real screen MP4 + actual vehicle-service R→D count 2 + automatic P completion; top-view score held')
    finally:
        try:
            gear('Park', 0, 1)
            request('browser/session', 'POST', client=client, closing=1)
        finally:
            stopped.set()
            (output / 'parking-console-sent-inputs-v013.json').write_text(json.dumps(requests, ensure_ascii=False, indent=2) + '\n')
            if process is not None and process.poll() is None:
                process.terminate()


if __name__ == '__main__':
    main()
