#!/usr/bin/env python3
"""Fetch the pinned Apache-2.0 Vosk Chinese model; large assets stay out of Git."""
import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

URL = 'https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip'
SHA256 = '3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba'
ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, help='Use a previously downloaded archive')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as temporary:
        archive = args.archive or Path(temporary) / 'model.zip'
        if not args.archive:
            print('Downloading', URL)
            urllib.request.urlretrieve(URL, archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
            raise SystemExit('Model checksum mismatch; refusing to unpack')
        target = ROOT / 'app/src/main/assets/model-cn'
        target.mkdir(parents=True, exist_ok=True)
        prefix = 'vosk-model-small-cn-0.22/'
        with zipfile.ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                if entry.is_dir():
                    continue
                if not entry.filename.startswith(prefix):
                    raise SystemExit('Unexpected model archive path')
                relative = Path(entry.filename[len(prefix):])
                if relative.is_absolute() or '..' in relative.parts:
                    raise SystemExit('Unsafe model archive path')
                output = target / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(entry) as source, output.open('wb') as dest:
                    shutil.copyfileobj(source, dest)
        (target / 'uuid').write_text('vosk-small-cn-0.22-' + SHA256[:12] + '\n')
        print('Model ready:', target)

if __name__ == '__main__':
    main()
