"""Package pinned ARMv7 dependencies already resolved by the board Gradle project."""
from pathlib import Path
import shutil
import zipfile
import sys

root = Path(__file__).resolve().parent
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
out = root / 'build/voice-deps'
out.mkdir(parents=True, exist_ok=True)
for group, name, version in [('com.alphacephei', 'vosk-android', '0.3.75'), ('net.java.dev.jna', 'jna', '5.18.1')]:
    matches = list((cache / group / name / version).glob(f'*/{name}-{version}.aar'))
    if len(matches) != 1:
        raise SystemExit('先在 demo/android-voice-weather 执行 ./gradlew :board:assembleDebug 以准备固定依赖')
    with zipfile.ZipFile(matches[0]) as aar:
        (out / f'{name}.jar').write_bytes(aar.read('classes.jar'))
        for entry in aar.namelist():
            if entry.startswith('jni/armeabi-v7a/') and entry.endswith('.so'):
                target = out / 'lib/armeabi-v7a' / Path(entry).name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(aar.read(entry))
reuse = '--reuse-model' in sys.argv
assets = root / ('build/voice-assets-reuse' if reuse else 'build/voice-assets')
source = root.parents[1] / 'demo/android-voice-weather/app/src/main/assets'
if reuse:
    (assets / 'model-cn').mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / 'model-cn/uuid', assets / 'model-cn/uuid')
    (assets / 'aluo-model-reuse-only').write_text('Requires the previously extracted exact small-cn-0.22 model.\n')
else:
    shutil.copytree(source / 'model-cn', assets / 'model-cn', dirs_exist_ok=True)
shutil.copy2(source / 'cities.tsv', assets / 'cities.tsv')
(assets / 'samples').mkdir(exist_ok=True)
shutil.copy2(source / 'samples/zhengzhou.pcm', assets / 'samples/zhengzhou.pcm')
shutil.copy2(source.parents[2] / 'src/androidTest/assets/wake/aluo.pcm', assets / 'samples/aluo.pcm')
for name in ['README', 'README.md']:
    if (source / 'model-cn' / name).is_file():
        shutil.copy2(source / 'model-cn' / name, assets / ('VOSK-' + name))
print('Vosk 0.3.75 / JNA 5.18.1 / ARMv7 / small-cn-0.22 prepared')
