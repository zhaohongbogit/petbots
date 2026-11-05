"""Offline neural voice; no network, no recording or device-volume changes."""
import hashlib
import json
import time
import wave
from pathlib import Path

class NeuralVoice:
    def __init__(self, root):
        from piper import PiperVoice, SynthesisConfig
        root = Path(root)
        for name, meta in json.loads((root/'manifest.json').read_text()).items():
            file = root/name
            if file.stat().st_size != meta['bytes'] or hashlib.sha256(file.read_bytes()).hexdigest() != meta['sha256']:
                raise ValueError(f'TTS model checksum mismatch: {name}')
        self.voice = PiperVoice.load(root/'zh_CN-huayan-medium.onnx')
        self.config = SynthesisConfig(volume=.4, length_scale=1.0)
        self.received = [chunk.audio_int16_bytes for chunk in self.voice.synthesize('收到', syn_config=self.config)]

    def synthesize(self, text, path):
        start = time.monotonic()
        with wave.open(str(path), 'wb') as wav:
            self.voice.synthesize_wav(text, wav, syn_config=self.config)
        with wave.open(str(path)) as wav:
            if wav.getnframes() == 0:
                raise RuntimeError('TTS generated empty audio')
            duration = wav.getnframes()/wav.getframerate()
        print(json.dumps({'event':'tts_generated','seconds':round(time.monotonic()-start,3),'audio_seconds':round(duration,3)}),flush=True)

    def play(self, text, target):
        """Bounded synthesis producer overlaps playback; no microphone is open."""
        import re
        import queue
        import threading
        import subprocess
        from concurrent.futures import ThreadPoolExecutor
        # Keep clauses together for intonation, but don't wait for the entire reply.
        parts, pending = [], ''
        for clause in re.findall(r'[^，。！？]+[，。！？]?', text):
            pending += clause
            if len(pending) >= 10:
                parts.append(pending)
                pending = ''
        if pending:
            parts.append(pending)
        audio = queue.Queue(maxsize=2)
        stopped = threading.Event()
        def put(item):
            while not stopped.is_set():
                try:
                    audio.put(item, timeout=.1)
                    return
                except queue.Full:
                    pass
        def produce():
            try:
                if text == '收到':
                    for block in self.received:
                        put(block)
                    put(None)
                    return
                for part in parts:
                    if stopped.is_set():
                        break
                    for chunk in self.voice.synthesize(part, syn_config=self.config):
                        if stopped.is_set():
                            break
                        put(chunk.audio_int16_bytes)
                put(None)
            except Exception as error:
                put(error)
        start = time.monotonic()
        process = subprocess.Popen(['pw-play','--raw','--rate',str(self.voice.config.sample_rate),'--channels','1','--format','s16','--target',target,'-'],stdin=subprocess.PIPE)
        try:
            with ThreadPoolExecutor(max_workers=1) as executor:
                future = executor.submit(produce)
                try:
                    first = True
                    while True:
                        block = audio.get(timeout=60)
                        if isinstance(block, Exception):
                            raise block
                        if block is None:
                            break
                        if first:
                            print(json.dumps({'event':'tts_first_audio','seconds':round(time.monotonic()-start,3)}),flush=True)
                            first = False
                        process.stdin.write(block)
                    process.stdin.close()
                    if process.wait(timeout=30):
                        raise RuntimeError('neural stream playback failed')
                    future.result()
                finally:
                    stopped.set()
        finally:
            if not process.stdin.closed:
                process.stdin.close()
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=3)
        print(json.dumps({'event':'tts_playback_complete','seconds':round(time.monotonic()-start,3)}),flush=True)
