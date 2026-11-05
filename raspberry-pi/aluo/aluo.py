#!/usr/bin/env python3
"""Linux voice assistant. Raw audio stays local; weather only after consent."""
import argparse
import datetime
import json
import pathlib
import select
import os
import subprocess
import time
import tempfile
import unicodedata
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent

def compact(text):
    return ''.join(c for c in text if not c.isspace() and not unicodedata.category(c).startswith(('P', 'Z')))

def wake(text):
    return compact(text) in {'你好阿洛', '你好啊洛', '你好阿路', '你好啊路'}

def cities():
    return {p[0]: (float(p[1]), float(p[2])) for line in (ROOT/'cities.tsv').read_text().splitlines()
            if line.strip() and not line.startswith('#') for p in [line.split('\t')]}

def intent(raw, catalog):
    text = compact(raw)
    if text.startswith('阿洛'):
        text = text[2:]
    found = [name for name in catalog if name in text]
    if len(found) != 1 or ('今天' in text and '明天' in text):
        return None
    city = found[0]
    text = text.replace(city+'市', '').replace(city, '')
    day = int('明天' in text)
    text = text.replace('今天', '').replace('明天', '')
    if text not in {'天气', '的天气', '天气怎么样', '的天气怎么样', '查天气', '查询天气', '查一下天气', '帮我查天气', '帮我查询天气', '帮我查一下天气'}:
        return None
    return city, day

def weather(target, catalog):
    city, day = target
    lat, lon = catalog[city]
    params = urllib.parse.urlencode(dict(latitude=lat, longitude=lon, daily='weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max', timezone='Asia/Shanghai', forecast_days=2))
    with urllib.request.urlopen('https://api.open-meteo.com/v1/forecast?'+params, timeout=10) as response:
        daily = json.loads(response.read(65536))['daily']
    expected = (datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=day)).isoformat()
    if daily['time'][day] != expected:
        raise ValueError('weather date mismatch')
    code = daily['weather_code'][day]
    condition = ('晴' if code == 0 else '多云或阴天' if code <= 3 else '有雾' if code in (45,48) else '有雷雨' if code >= 95 else '有雪' if 71 <= code <= 77 or code in (85,86) else '有雨' if 51 <= code <= 82 else '请查看天气预报')
    return f'{city}{"明天" if day else "今天"}，{condition}，最低{daily["temperature_2m_min"][day]:.0f}度，最高{daily["temperature_2m_max"][day]:.0f}度，降水概率百分之{daily["precipitation_probability_max"][day]}。'

class Session:
    def __init__(self, say, query=weather, clock=time.monotonic, car=None):
        self.say, self.query, self.clock = say, query, clock
        self.catalog = cities()
        self.state, self.target, self.deadline = 'WAKE', None, 0
        self.recent = {}
        self.car = car
        self.pending_action = None

    def reset(self):
        self.state, self.target = 'WAKE', None
        self.pending_action = None

    def accept(self, raw):
        print(json.dumps({'state': self.state, 'text': raw}, ensure_ascii=False), flush=True)
        from car import command, LABELS
        action = command(compact(raw))
        if action == 'stop':
            if self.car: self.car.stop()
            self.reset()
            self.say('已发出停车指令')
            return
        if self.state != 'WAKE' and self.clock() > self.deadline:
            self.reset()
            self.say('等待超时，请重新说你好阿洛。')
            return
        prefixed = compact(raw).startswith(('你好阿洛','你好阿路','阿洛','阿路'))
        if action and (self.state == 'COMMAND' or self.state == 'WAKE' and prefixed):
            if not self.car or not self.car.enabled:
                self.reset(); self.say('小车硬件尚未启用，需要先确认接线和标定。'); return
            self.pending_action = action
            self.say(('模拟动作，' if self.car.simulated else '') + LABELS[action] + '，是否执行？')
            self.state, self.deadline = 'CONFIRM', self.clock()+8
            return
        text = compact(raw)
        if self.state == 'WAKE':
            if wake(raw):
                self.say('收到')
                self.state, self.deadline = 'COMMAND', self.clock()+20
        elif self.state == 'COMMAND':
            target = intent(raw, self.catalog)
            if target is None:
                self.reset()
                self.say('请重新唤醒，再说天气、前进、后退、转向或跳舞。')
                return
            self.target = target
            self.say(f'你要查询{target[0]}{"明天" if target[1] else "今天"}的天气，对吗？')
            self.state, self.deadline = 'CONFIRM', self.clock()+8
        elif self.state == 'CONFIRM':
            if text.startswith('阿洛'):
                text = text[2:]
            if text in {'不用','不需要','不要','不是的','取消','不用查','不要查'}:
                self.reset()
                self.say('已取消')
            elif text in ({'是的','好的','可以','同意','执行','开始'} if self.pending_action else {'是的','好的','可以','同意','帮我查','请查询','查吧'}):
                target = self.target
                action = self.pending_action
                self.reset()
                if action:
                    self.say('准备执行' if not self.car.simulated else '准备模拟，不会控制实际硬件')
                    try:
                        self.car.start(action)
                    except (RuntimeError, ValueError) as error:
                        self.say(str(error))
                    return
                if self.clock()-self.recent.get(target, -1e9) < 60:
                    self.say('刚刚查询过这个天气，请稍后再试。')
                    return
                try:
                    answer = self.query(target, self.catalog)
                    self.recent[target] = self.clock()
                except Exception as error:
                    print('weather_error:', type(error).__name__, flush=True)
                    answer = '天气查询失败，请稍后重试。'
                self.say(answer)

class Speech:
    def __init__(self, models):
        import sherpa_onnx
        self.api = sherpa_onnx
        self.asr = sherpa_onnx.OfflineRecognizer.from_paraformer(
            paraformer=str(models/'asr/model.int8.onnx'), tokens=str(models/'asr/tokens.txt'),
            num_threads=2, sample_rate=16000, feature_dim=80, decoding_method='greedy_search', debug=False)
        self.models = models

    def decode(self, samples):
        if len(samples) < 1600:
            raise ValueError('audio must contain at least 100ms of samples')
        start = time.monotonic()
        stream = self.asr.create_stream()
        stream.accept_waveform(16000, samples)
        self.asr.decode_stream(stream)
        print(f'asr_seconds={time.monotonic()-start:.3f}', flush=True)
        return stream.result.text

    def listen(self, device, session, pipewire=False):
        import numpy as np
        config = self.api.VadModelConfig()
        config.silero_vad.model = str(self.models/'vad/silero_vad.onnx')
        config.silero_vad.min_silence_duration = .4 if session.state == 'WAKE' else .7
        config.silero_vad.min_speech_duration = .2
        config.silero_vad.max_speech_duration = 10
        config.sample_rate = 16000
        vad = self.api.VoiceActivityDetector(config, buffer_size_in_seconds=30)
        # Capture is closed before ASR/TTS: no playback is fed into the recognizer.
        command = (['pw-record','--raw','--target',device,'--rate','16000','--channels','1','--format','s16','-'] if pipewire else
                   ['arecord','-q','-D',device,'-t','raw','-f','S16_LE','-r','16000','-c','1'])
        process = subprocess.Popen(command, stdout=subprocess.PIPE)
        started = time.monotonic()
        pending = b''
        ready = False
        try:
            while time.monotonic()-started < 15:
                if not select.select([process.stdout], [], [], .2)[0]:
                    if session.state != 'WAKE' and session.clock() > session.deadline:
                        return None
                    continue
                chunk = os.read(process.stdout.fileno(), 1024-len(pending))
                if not chunk:
                    raise RuntimeError('audio capture stopped; check arecord -l/device')
                pending += chunk
                if len(pending) < 1024:
                    continue
                block, pending = pending, b''
                if not ready:
                    print(json.dumps({'event':'input_ready','state':session.state,'device':device}),flush=True)
                    ready = True
                vad.accept_waveform(np.frombuffer(block,dtype='<i2').astype(np.float32)/32768)
                if not vad.empty():
                    samples = vad.front.samples.copy()
                    return samples
                if session.state != 'WAKE' and session.clock() > session.deadline:
                    return None
            return None
        finally:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdout.close()

def verify(models):
    import hashlib
    for name, meta in json.loads((ROOT/'models.json').read_text()).items():
        file = models/name
        if file.stat().st_size != meta['bytes'] or hashlib.sha256(file.read_bytes()).hexdigest() != meta['sha256']:
            raise ValueError(f'model checksum mismatch: {name}')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--car-config', type=pathlib.Path, default=ROOT/'car.json')
    parser.add_argument('--simulate-car', action='store_true', help='synthetic car only; never opens GPIO')
    parser.add_argument('--models', type=pathlib.Path, default=ROOT/'models')
    parser.add_argument('--wav', type=pathlib.Path, help='offline ASR only, no business/network/playback')
    parser.add_argument('--text', action='store_true', help='text state-machine mode')
    parser.add_argument('--tts', choices=['piper','espeak'], default='piper')
    parser.add_argument('--tts-models', type=pathlib.Path, default=ROOT/'tts')
    parser.add_argument('--airpods', action='store_true', help='use paired AirPods Max PipeWire input/output')
    parser.add_argument('--speak', action='store_true', help='explicitly enable playback')
    parser.add_argument('--device', default='plughw:CARD=Device,DEV=0')
    parser.add_argument('--output', default='plughw:CARD=Headphones,DEV=0')
    args = parser.parse_args()
    if args.airpods:
        args.device = 'bluez_input.00:61:AB:00:38:15'
        args.output = 'bluez_output.00_61_AB_00_38_15.1'
    neural = None
    if args.speak and args.tts == 'piper':
        from voice_output import NeuralVoice
        neural = NeuralVoice(args.tts_models)
    def say(text):
        print('阿洛:', text, flush=True)
        if args.speak:
            if neural is not None and args.airpods:
                neural.play(text,args.output)
                return
            # Capture has stopped before synthesis/playback.
            with tempfile.NamedTemporaryFile(suffix='.wav', prefix='aluo-tts-') as wav:
                if neural is not None:
                    neural.synthesize(text, wav.name)
                else:
                    subprocess.run(['espeak-ng','-v','cmn','-a','40','-w',wav.name,text],check=True)
                command = (['pw-play','--target',args.output,wav.name] if args.airpods else
                           ['aplay','-q','-D',args.output,wav.name])
                subprocess.run(command,check=True)
    import atexit, signal
    from car import load
    car = load(None if args.wav else args.car_config, simulate=args.simulate_car and not args.wav)
    atexit.register(car.close)
    def shutdown(*_):
        car.stop()
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, shutdown)
    session = Session(say, car=car)
    if args.text:
        for line in __import__('sys').stdin:
            session.accept(line.strip())
        return
    verify(args.models)
    speech = Speech(args.models)
    if args.wav:
        import wave
        import numpy as np
        with wave.open(str(args.wav)) as wav:
            if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (16000,1,2):
                raise ValueError('WAV must be 16kHz mono PCM16')
            samples = np.frombuffer(wav.readframes(wav.getnframes()),dtype='<i2').astype(np.float32)/32768
        print(speech.decode(samples))
        return
    while True:
        samples = speech.listen(args.device, session, pipewire=args.airpods)
        if samples is not None:
            session.accept(speech.decode(samples))
        elif session.state != 'WAKE' and session.clock() > session.deadline:
            session.reset()
            say('等待超时，请重新说你好阿洛。')

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
