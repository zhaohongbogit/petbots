#!/usr/bin/env python3
"""Transfer the verified model atomically; never expose a partially copied GGUF to inference."""
import argparse,hashlib,subprocess,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--device",required=True);parser.add_argument("--adb",default="adb");args=parser.parse_args()
    model=ROOT/"models/Qwen3-0.6B-Q4_K_M.gguf"
    expected="1f06f85468d17be76c1212d612c15f9d7d70f54fc553839458d5a8f5ccd4c8fd"
    with model.open("rb") as source: actual=hashlib.file_digest(source,"sha256").hexdigest()
    if actual!=expected: raise RuntimeError("Unexpected model hash; run prepare_qwen.py with pinned sources")
    prefix=[args.adb,"-s",args.device]
    folder="/sdcard/Android/data/cn.aihongbo.voiceweather/files/models"
    target=folder+"/"+model.name
    def run(*cmd): return subprocess.run(prefix+list(cmd),check=True,capture_output=True,text=True).stdout
    run("shell","mkdir","-p",folder)
    run("push",str(model),target+".partial")
    copied=run("shell","sha256sum",target+".partial").split()[0]
    if copied!=expected: raise RuntimeError("Device file checksum mismatch; original model preserved")
    run("shell","mv",target+".partial",target)
    print(json.dumps({"device":args.device,"model":model.name,"bytes":model.stat().st_size,"sha256":copied}))
if __name__=="__main__": main()
