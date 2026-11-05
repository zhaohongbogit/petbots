"""Package checksum-pinned local models. This script never uploads or downloads audio."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import sys
import zipfile

root = Path(__file__).resolve().parent
reuse = '--reuse-model' in sys.argv
assets = root / ('build/sherpa-assets-reuse' if reuse else 'build/sherpa-assets')
shutil.rmtree(assets, ignore_errors=True)
shutil.copytree(root / 'build/voice-assets-reuse', assets)
manifest_file = root / 'native/sherpa-models.json'
manifest = json.loads(manifest_file.read_text())
shutil.copy2(manifest_file, assets / 'sherpa-models.json')
source = Path(os.environ.get('ALUO_MODEL_ROOT', str(root / 'build/sherpa-models')))
if not reuse:
    for name, expected in manifest.items():
        model = source / name
        if not model.is_file() or model.stat().st_size != expected['bytes'] or hashlib.sha256(model.read_bytes()).hexdigest() != expected['sha256']:
            raise SystemExit(f'Missing or mismatched model: {model}; see native/README.md')
        target = assets / 'sherpa-models' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(model, target)
private_login = root / 'build/hbc-private-login.json'
if private_login.is_file():
    shutil.copy2(private_login, assets / 'hbc-test-login.json')
licenses = root.parents[1] / 'demo/android-voice-weather/diary/native/licenses'
shutil.copytree(licenses, assets / 'licenses', dirs_exist_ok=True)
with zipfile.ZipFile(root.parents[1] / 'demo/android-voice-weather/app/libs/sherpa-onnx-1.13.8.aar') as aar:
    for name in ['libonnxruntime.so', 'libsherpa-onnx-c-api.so']:
        (root / 'build/voice-deps/lib/armeabi-v7a' / name).write_bytes(aar.read('jni/armeabi-v7a/' + name))
print('Sherpa 1.13.8 / Silero VAD + one Paraformer Small / ARMv7 prepared')
