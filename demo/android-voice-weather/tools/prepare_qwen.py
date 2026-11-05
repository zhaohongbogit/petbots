#!/usr/bin/env python3
"""Pinned official llama.cpp + official Qwen GGUF, locally requantized to Q4_K_M.
No credentials, no runtime server. Network is used only during preparation.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
COMMIT="7170cad4d02f7907517613931840cbb2367399a3"
MODEL_SHA="9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
MODEL_URL="https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf"
SOURCE=ROOT/"third_party/llama.cpp"
MODELS=ROOT/"models"

def sha(path):
    digest=hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda:stream.read(4*1024*1024),b""): digest.update(block)
    return digest.hexdigest()

def run(*args): subprocess.run([str(arg) for arg in args],check=True)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--source-only",action="store_true")
    parser.add_argument("--cmake",default=shutil.which("cmake") or "/Users/hongbozhao/Library/Android/sdk/cmake/3.22.1/bin/cmake")
    args=parser.parse_args()
    SOURCE.parent.mkdir(parents=True,exist_ok=True)
    if not (SOURCE/".git").exists(): run("git","clone","--filter=blob:none","--no-checkout","--depth","1","https://github.com/ggml-org/llama.cpp.git",SOURCE)
    if not (SOURCE/"CMakeLists.txt").exists(): run("git","-C",SOURCE,"sparse-checkout","set","cmake","include","src","ggml","common","vendor","tools")
    head=subprocess.check_output(["git","-C",str(SOURCE),"rev-parse","HEAD"],text=True).strip()
    if head!=COMMIT:
        run("git","-C",SOURCE,"fetch","--depth","1","origin",COMMIT)
        run("git","-C",SOURCE,"checkout","--detach",COMMIT)
    elif not (SOURCE/"CMakeLists.txt").exists(): run("git","-C",SOURCE,"checkout","--detach",COMMIT)
    if args.source_only: return
    MODELS.mkdir(exist_ok=True)
    original=MODELS/"Qwen3-0.6B-Q8_0.gguf"
    if not original.exists():
        partial=original.with_suffix(".gguf.partial")
        urllib.request.urlretrieve(MODEL_URL,partial)
        if sha(partial)!=MODEL_SHA: raise RuntimeError("Official model SHA-256 mismatch")
        partial.rename(original)
    if sha(original)!=MODEL_SHA: raise RuntimeError("Official model SHA-256 mismatch")
    build=ROOT/"build/llama-host"
    run(args.cmake,"-S",SOURCE,"-B",build,"-DCMAKE_BUILD_TYPE=Release","-DGGML_METAL=OFF","-DGGML_OPENMP=OFF","-DLLAMA_BUILD_APP=OFF","-DLLAMA_BUILD_TESTS=OFF","-DLLAMA_BUILD_EXAMPLES=OFF","-DLLAMA_BUILD_SERVER=OFF","-DLLAMA_CURL=OFF","-DLLAMA_OPENSSL=OFF")
    run(args.cmake,"--build",build,"--target","llama-quantize","-j","6")
    target=MODELS/"Qwen3-0.6B-Q4_K_M.gguf"
    if not target.exists(): run(build/"bin/llama-quantize","--allow-requantize",original,target,"Q4_K_M","4")
    manifest={"llama_commit":COMMIT,"upstream":MODEL_URL,"upstream_sha256":MODEL_SHA,"quantization":"Q8_0 -> Q4_K_M (--allow-requantize)","model_file":target.name,"size_bytes":target.stat().st_size,"sha256":sha(target)}
    (MODELS/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
    print(json.dumps(manifest,indent=2))
if __name__=="__main__": main()
