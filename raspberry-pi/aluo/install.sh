#!/bin/sh
set -eu
cd "$(dirname "$0")"
# Explicit system dependency installation; no automatic service or playback.
sudo apt-get update
sudo apt-get install -y python3-venv espeak-ng alsa-utils
python3 -m venv .venv
.venv/bin/pip install --only-binary=:all: -r requirements.txt
.venv/bin/python -m unittest discover -s tests
.venv/bin/python -c 'from aluo import verify, ROOT; verify(ROOT / "models"); print("Model checksums OK")'
