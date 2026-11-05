"""Device-only synthetic WAV replay; no playback, recording or network query."""
import json
import pathlib
import sys
import time
import wave
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
import numpy as np
from aluo import Speech, Session, verify, ROOT
verify(ROOT/'models')
t = time.monotonic()
speech = Speech(ROOT/'models')
print('model_load_seconds=',round(time.monotonic()-t,3),flush=True)
results=[]
queries=[]
session=Session(lambda x: print('reply:',x,flush=True),lambda target,_: queries.append(target) or '模拟天气，不是真实预报')
for name in ['wake','weather','yes']:
    with wave.open(str(ROOT/'samples'/f'{name}.wav')) as wav:
        audio=np.frombuffer(wav.readframes(wav.getnframes()),dtype='<i2').astype(np.float32)/32768
    start=time.monotonic()
    text=speech.decode(audio)
    results.append(dict(sample=name,text=text,seconds=round(time.monotonic()-start,3)))
    session.accept(text)
# Verify VAD binding with actual speech + trailing silence.
config=speech.api.VadModelConfig()
config.silero_vad.model=str(ROOT/'models/vad/silero_vad.onnx')
config.silero_vad.min_silence_duration=.4
config.sample_rate=16000
vad=speech.api.VoiceActivityDetector(config,buffer_size_in_seconds=30)
with wave.open(str(ROOT/'samples/weather.wav')) as wav:
    audio=np.frombuffer(wav.readframes(wav.getnframes()),dtype='<i2').astype(np.float32)/32768
full=np.concatenate([np.zeros(8000,dtype=np.float32),audio,np.zeros(16000,dtype=np.float32)])
for i in range(0,len(full)-512,512): vad.accept_waveform(full[i:i+512])
segments=0
while not vad.empty():
    assert len(vad.front.samples)>0
    segments+=1
    vad.pop()
print(json.dumps(dict(samples=results,simulated_queries=queries,vad_segments=segments),ensure_ascii=False),flush=True)
assert segments>0
assert results[1]['text'] == '北京明天的天气怎么样'
assert results[2]['text'] == '是的'
# Wake output is reported verbatim, never patched to make the session pass.
