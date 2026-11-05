#!/usr/bin/env python3
"""Play explicit local test fixtures from a Mac speaker into the connected phone's microphone."""
import argparse
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--command-audio', type=Path, required=True)
    parser.add_argument('--yes-audio', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    run(args)


def run(args):
    fixtures = {('aluo-weather', True): args.command_audio.resolve(), ('yes', False): args.yes_audio.resolve()}
    for path in fixtures.values():
        if not path.is_file():
            raise SystemExit(f'Missing local test fixture: {path}')
    adb = [args.adb, '-s', args.device]
    package = 'cn.aihongbo.voiceweather'

    def request():
        result = subprocess.run(adb + ['exec-out', 'run-as', package, 'cat', 'files/wake-speaker-request.json'], capture_output=True)
        try:
            return json.loads(result.stdout)
        except (ValueError, UnicodeDecodeError):
            return None

    previous = request()
    seen = {previous['nonce']} if previous else set()
    process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'externalSpeaker', 'true', '-e', 'class',
        'cn.aihongbo.voiceweather.WakeCommandDeviceTest#acousticSpeakerCommandAndConfirmation',
        package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline = time.monotonic() + 150
    while process.poll() is None and time.monotonic() < deadline:
        message = request()
        if message and message['nonce'] not in seen:
            nonce = int(message['nonce'])
            seen.add(nonce)
            path = fixtures[(message['name'], message['fixture'])]
            subprocess.run(['/usr/bin/afplay', str(path)], check=True)
            subprocess.run(adb + ['shell', 'run-as', package, 'touch', f'files/wake-speaker-done-{nonce}'], check=True)
            print(f'Played external speaker fixture: {message["name"]}', flush=True)
        time.sleep(0.2)
    if process.poll() is None:
        process.terminate()
    output, _ = process.communicate(timeout=10)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output)
    print(output, flush=True)
    if 'OK (1 test)' not in output:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
