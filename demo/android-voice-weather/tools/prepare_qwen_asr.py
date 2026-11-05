#!/usr/bin/env python3
"""Pinned sherpa-onnx Android runtime + Qwen3-ASR INT8 conversion, no lexical resources."""
import argparse,hashlib,json,subprocess,tarfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
NAME="sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
ARCHIVE_SHA="393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96"
AAR_SHA="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
ARCHIVE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"+NAME+".tar.bz2"
FILES=["conv_frontend.onnx","encoder.int8.onnx","decoder.int8.onnx","tokenizer/vocab.json","tokenizer/merges.txt","tokenizer/tokenizer_config.json"]

def sha(file):
    with file.open("rb") as source:return hashlib.file_digest(source,"sha256").hexdigest()
def download(url,target,expected):
    target.parent.mkdir(parents=True,exist_ok=True)
    if not target.exists():
        partial=target.with_name(target.name+".partial")
        urllib.request.urlretrieve(url,partial)
        if sha(partial)!=expected:raise RuntimeError("Checksum mismatch: "+target.name)
        partial.rename(target)
    if sha(target)!=expected:raise RuntimeError("Checksum mismatch or incomplete download: "+target.name)
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--runtime-only",action="store_true");parser.add_argument("--device");parser.add_argument("--adb",default="adb");args=parser.parse_args()
    download(AAR_URL,ROOT/"app/libs/sherpa-onnx-1.13.8.aar",AAR_SHA)
    if args.runtime_only:return
    archive=ROOT/"models/qwen3-asr-int8.tar.bz2";download(ARCHIVE_URL,archive,ARCHIVE_SHA)
    folder=ROOT/"models/qwen3-asr-0.6b-int8";folder.mkdir(exist_ok=True)
    if not all((folder/name).exists() for name in FILES):
        import shutil
        wanted={NAME+"/"+name:name for name in FILES}
        with tarfile.open(archive,"r|bz2") as source:
            for member in source:
                if member.name not in wanted:continue
                if not member.isfile():raise RuntimeError("Invalid archive entry")
                target=folder/wanted[member.name];target.parent.mkdir(parents=True,exist_ok=True)
                with source.extractfile(member) as stream,target.open("wb") as output:shutil.copyfileobj(stream,output)
        if not all((folder/name).is_file() for name in FILES):raise RuntimeError("Missing model components")
    manifest={"original_model":"Qwen/Qwen3-ASR-0.6B","conversion":ARCHIVE_URL,"archive_sha256":ARCHIVE_SHA,"runtime_version":"1.13.8","runtime_sha256":AAR_SHA,"files":{name:{"bytes":(folder/name).stat().st_size,"sha256":sha(folder/name)} for name in FILES}}
    (folder/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
    (ROOT/"docs/qwen-asr-manifest-0.6.0.json").write_text(json.dumps(manifest,indent=2)+"\n")
    if args.device:
        prefix=[args.adb,"-s",args.device]
        def run(*cmd):return subprocess.run(prefix+list(cmd),check=True,capture_output=True,text=True).stdout
        # Close the app before replacing model files. Existing models/logs are not removed.
        run("shell","am","force-stop","cn.aihongbo.voiceweather")
        dest="/sdcard/Android/data/cn.aihongbo.voiceweather/files/models/qwen3-asr-0.6b-int8"
        run("shell","mkdir","-p",dest+"/tokenizer")
        for name in FILES:
            run("push",str(folder/name),dest+"/"+name+".partial")
            if run("shell","sha256sum",dest+"/"+name+".partial").split()[0]!=manifest["files"][name]["sha256"]:raise RuntimeError("Device checksum mismatch: "+name)
            run("shell","mv",dest+"/"+name+".partial",dest+"/"+name)
        # Manifest is the commit marker: only published after every model component is verified.
        run("push",str(folder/"manifest.json"),dest+"/manifest.json.partial")
        run("shell","mv",dest+"/manifest.json.partial",dest+"/manifest.json")
    print(json.dumps(manifest,indent=2))
if __name__=="__main__":main()
