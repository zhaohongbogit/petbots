"""Download public pinned model files only. Never reads or uploads recordings."""
from pathlib import Path
import hashlib
import json
import tarfile
import urllib.request

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'native/sherpa-models.json').read_text())
downloads = root / 'build/model-downloads'
downloads.mkdir(parents=True, exist_ok=True)
base = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/'
kws = 'sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20'
asr = 'sherpa-onnx-paraformer-zh-small-2024-03-09'
sources = {
    'kws/encoder.int8.onnx': (f'kws-models/{kws}.tar.bz2', f'{kws}/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx'),
    'kws/decoder.onnx': (f'kws-models/{kws}.tar.bz2', f'{kws}/decoder-epoch-13-avg-2-chunk-16-left-64.onnx'),
    'kws/joiner.int8.onnx': (f'kws-models/{kws}.tar.bz2', f'{kws}/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx'),
    'kws/tokens.txt': (f'kws-models/{kws}.tar.bz2', f'{kws}/tokens.txt'),
    'asr/model.int8.onnx': (f'asr-models/{asr}.tar.bz2', f'{asr}/model.int8.onnx'),
    'asr/tokens.txt': (f'asr-models/{asr}.tar.bz2', f'{asr}/tokens.txt'),
    'vad/silero_vad.onnx': ('asr-models/silero_vad.onnx', None),
}
for name, expected in manifest.items():
    target = root / 'build/sherpa-models' / name
    if target.is_file() and target.stat().st_size == expected['bytes'] and hashlib.sha256(target.read_bytes()).hexdigest() == expected['sha256']:
        continue
    url, entry = sources[name]
    archive = downloads / Path(url).name
    if not archive.is_file():
        part = archive.with_suffix(archive.suffix + '.part')
        urllib.request.urlretrieve(base + url, part)
        part.replace(archive)
    if entry:
        with tarfile.open(archive, 'r:bz2') as tar:
            stream = tar.extractfile(entry)
            if stream is None:
                raise SystemExit(f'Missing archive member: {entry}')
            data = stream.read(expected['bytes'] + 1)
    else:
        data = archive.read_bytes()
    if len(data) != expected['bytes'] or hashlib.sha256(data).hexdigest() != expected['sha256']:
        raise SystemExit(f'Model verification failed: {name}')
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_suffix(target.suffix + '.part')
    part.write_bytes(data)
    part.replace(target)
print(f'{len(manifest)} pinned model files ready; no recording accessed.')
