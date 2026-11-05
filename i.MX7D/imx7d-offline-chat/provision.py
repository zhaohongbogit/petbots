#!/usr/bin/env python3
"""Configure only the two explicit test apps; never print or persist a host copy of the pair key."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import os

ROOT = Path(__file__).resolve().parent
ADB = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
BOARD = '000000c4cddac462'
PHONE = '9c79ecb0'
BP = 'com.zhb.offlineassistant'
PP = 'com.zhb.offlinechat'

def adb(serial, *args, payload=None, source=None, check=True):
    result = subprocess.run([ADB, '-s', serial, *args], input=payload, stdin=source, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        raise RuntimeError('ADB command failed; device or app unavailable (credentials suppressed)')
    return result

def private_write(serial, package, path, payload=None, source=None):
    # These paths are constants controlled by this program, not user input.
    size = len(payload) if payload is not None else os.fstat(source.fileno()).st_size
    command = shlex.join(['run-as', package, 'sh', '-c', 'umask 077; head -c '+str(size)+' > '+path])
    adb(serial, 'shell', '-T', command, payload=payload, source=source)

def board_ip():
    raw = adb(BOARD, 'shell', 'ip', '-4', 'addr', 'show', 'wlan0').stdout.decode()
    match = re.search(r'inet (\d+\.\d+\.\d+\.\d+)/', raw)
    if not match or not ipaddress.ip_address(match[1]).is_private:
        raise RuntimeError('NXP WLAN private IPv4 unavailable')
    return match[1]

def configure(model, board_only=False, skip_install=False):
    if model is not None and not model.is_file():
        raise RuntimeError('Prepared mixed Q2_K model file is required')
    if model is not None and model.name != 'Qwen3-0.6B-Q2_K.gguf':
        raise RuntimeError('Only the mixed Q2_K model is supported; pure Q2_K failed validation')
    address = board_ip()
    targets = [(BOARD, BP, 'board.apk')] + ([] if board_only else [(PHONE, PP, 'phone.apk')])
    for serial, package, apk in targets:
        if not (ROOT / 'build' / apk).is_file():
            raise RuntimeError('Build both APKs first')
        if not skip_install:
            adb(serial, 'install', '--no-incremental', '-r', str(ROOT / 'build' / apk))
        adb(serial, 'shell', 'am', 'force-stop', package)
    saved = adb(BOARD, 'shell', '-T', 'run-as', BP, 'cat', 'files/lan.json', check=False)
    try:
        key = json.loads(saved.stdout)['key']
        if not isinstance(key, str) or len(key) < 32:
            raise ValueError()
    except Exception:
        key = secrets.token_hex(32)
    configs = [(BOARD, BP, dict(key=key))] + ([] if board_only else [(PHONE, PP, dict(key=key, host=address))])
    for serial, package, config in configs:
        adb(serial, 'shell', 'run-as', package, 'mkdir', '-p', 'files')
        private_write(serial, package, 'files/lan.json', payload=json.dumps(config).encode())
        stored = json.loads(adb(serial, 'shell', '-T', 'run-as', package, 'cat', 'files/lan.json').stdout)
        if stored != config:
            raise RuntimeError('Private pairing write verification failed')
    if model is not None:
        adb(BOARD, 'shell', 'run-as', BP, 'mkdir', '-p', 'files/models')
        digest = hashlib.file_digest(model.open('rb'), 'sha256').hexdigest()
        target = 'files/models/Qwen3-0.6B-Q2_K.gguf'
        old = adb(BOARD, 'shell', 'run-as', BP, 'sha256sum', target, check=False).stdout.decode().split()
        if not old or old[0] != digest:
            with model.open('rb') as stream:
                private_write(BOARD, BP, target, source=stream)
        actual = adb(BOARD, 'shell', 'run-as', BP, 'sha256sum', target).stdout.decode().split()[0]
        if actual != digest:
            raise RuntimeError('On-device model digest mismatch')
    adb(BOARD, 'shell', 'am', 'start', '-n', BP+'/com.zhb.petscreen.OfflineSetupActivity')
    if not board_only:
        adb(PHONE, 'shell', 'am', 'start', '-n', PP+'/.MainActivity')
    print('Configured '+('NXP service' if board_only else 'separate test apps')+' via WLAN '+address+'. No chat submitted. Model verification: '+('SHA-256 matched' if model else 'not provisioned')+'')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', type=Path)
    parser.add_argument('--board-only', action='store_true')
    parser.add_argument('--skip-install', action='store_true')
    args = parser.parse_args()
    configure(args.model.resolve() if args.model else None, args.board_only, args.skip_install)
