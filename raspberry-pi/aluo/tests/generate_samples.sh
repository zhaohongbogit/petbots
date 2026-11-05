#!/bin/sh
# Run on macOS; synthesize files only, never play audio.
set -eu
cd "$(dirname "$0")/.."
mkdir -p samples
make_sample() {
    say -v 'Tingting (中文（中国大陆）)' -o "samples/$1.aiff" "$2"
    ffmpeg -v error -y -i "samples/$1.aiff" -ar 16000 -ac 1 -c:a pcm_s16le "samples/$1.wav"
}
make_sample wake '你好阿洛'
make_sample weather '北京明天的天气怎么样'
make_sample yes '是的'
python3 - <<'PY'
import wave
for name in ['wake', 'weather', 'yes']:
    with wave.open('samples/'+name+'.wav') as wav:
        assert wav.getnframes() >= 1600, 'Empty/too-short synthesis: '+name
        print(name, wav.getnframes()/wav.getframerate())
PY
