#!/usr/bin/env python3
"""Keep Aluo bound to its paired headset; wait/reconnect when unavailable."""
import json
import pathlib
import signal
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
ADDRESS = '00:61:AB:00:38:15'
CARD = 'bluez_card.00_61_AB_00_38_15'
SOURCE = 'bluez_input.00:61:AB:00:38:15'
SINK = 'bluez_output.00_61_AB_00_38_15.1'
PROFILE = 'headset-head-unit-cvsd'

def inspect_graph(objects):
    card = next((o for o in objects if o.get('type') == 'PipeWire:Interface:Device' and o.get('info',{}).get('props',{}).get('device.name') == CARD), None)
    if card is None:
        return None
    info = card['info']
    profiles = info.get('params',{})
    index = next((p['index'] for p in profiles.get('EnumProfile',[]) if p.get('name') == PROFILE and p.get('available') != 'no'), None)
    current = any(p.get('name') == PROFILE for p in profiles.get('Profile',[]))
    nodes = {o.get('info',{}).get('props',{}).get('node.name') for o in objects if o.get('type') == 'PipeWire:Interface:Node'}
    return dict(id=card['id'],index=index,current=current,ready=current and SOURCE in nodes and SINK in nodes)

def run(*args):
    return subprocess.run(args, capture_output=True,text=True,timeout=20,check=True).stdout

def main():
    stopping = False
    child = None
    def signal_stop(*_):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM,signal_stop)
    signal.signal(signal.SIGINT,signal_stop)
    def stop_child():
        nonlocal child
        if child is not None:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
            child = None
    last_attempt = -100
    state = None
    def report(message):
        nonlocal state
        if state != message:
            print(message,flush=True)
            state = message
    try:
        while not stopping:
            try:
                graph = inspect_graph(json.loads(run('pw-dump')))
                if graph and graph['index'] is not None and not graph['current']:
                    stop_child()
                    run('wpctl','set-profile',str(graph['id']),str(graph['index']))
                    report('Selecting AirPods Max CVSD profile')
                elif graph and graph['ready']:
                    if child is None or child.poll() is not None:
                        run('wpctl','set-default',str(next(o['id'] for o in json.loads(run('pw-dump')) if o.get('info',{}).get('props',{}).get('node.name') == SOURCE)))
                        child = subprocess.Popen([sys.executable,'-u',str(ROOT/'aluo.py'),'--airpods','--speak'],cwd=ROOT)
                        report(f'Aluo started pid={child.pid}')
                else:
                    stop_child()
                    report('Waiting for AirPods Max; retry connection every 15 seconds')
                    if time.monotonic()-last_attempt >= 15:
                        last_attempt = time.monotonic()
                        run('bluetoothctl','power','on')
                        run('bluetoothctl','connect',ADDRESS)
            except (subprocess.SubprocessError, OSError, ValueError, StopIteration) as error:
                stop_child()
                report('Audio not ready: '+type(error).__name__)
            for _ in range(25):
                if stopping:
                    break
                time.sleep(.2)
    finally:
        stop_child()

if __name__ == '__main__':
    main()
