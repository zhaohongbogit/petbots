#!/usr/bin/env python3
"""Reuse hbc-agent proxy config without embedding credentials in code or APK."""
import argparse
from pathlib import Path
import shlex
import subprocess

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", default="/Users/hongbozhao/Source/hbc-agent/.env")
    parser.add_argument("--serial", default="000000c4cddac462")
    speech = parser.add_mutually_exclusive_group()
    speech.add_argument("--with-speech", action="store_true", help="Reuse the proxy address and token for ASR")
    speech.add_argument("--clear-speech", action="store_true", help="Disable ASR without changing chat configuration")
    args = parser.parse_args()
    config = {}
    for line in Path(args.env_file).read_text().splitlines():
        if "=" in line and line.strip().startswith("LLM_"):
            name, value = line.split("=", 1)
            config[name.strip()] = value.strip().strip("\"'")
    expected_base = "http://101.200.126.74/llm-proxy/v1"
    expected_model = "Qwen/Qwen3.6-35B-A3B"
    if config.get("LLM_BASE_URL") != expected_base or config.get("LLM_MODEL") != expected_model:
        raise SystemExit("Reference endpoint/model differs from approved configuration; not provisioned.")
    if not config.get("LLM_API_KEY") or config.get("LLM_PROVIDER") != "openai_compatible":
        raise SystemExit("Missing key or incompatible provider")
    adb = ["adb", "-s", args.serial]
    subprocess.run(adb + ["shell", "am", "force-stop", "com.zhb.petscreen"], check=True, capture_output=True)
    command = ["am", "start", "-n", "com.zhb.petscreen/.ChatActivity"]
    values = {"llm_base": expected_base, "llm_model": expected_model, "llm_key": config["LLM_API_KEY"]}
    if args.with_speech:
        values.update({
            "speech_base": expected_base,
            "speech_model": "FunAudioLLM/SenseVoiceSmall",
            "speech_key": config["LLM_API_KEY"],
        })
    elif args.clear_speech:
        values.update({"speech_base": "__clear__", "speech_key": "__clear__"})
    for name, value in values.items():
        command.extend(["--es", name, value])
    result = subprocess.run(adb + ["shell", shlex.join(command)], capture_output=True, text=True, timeout=30)
    if result.returncode or "Error" in result.stdout:
        raise SystemExit("Provisioning failed; inspect device. Output withheld to protect credentials.")
    state = "chat and speech" if args.with_speech else "chat"
    if args.clear_speech:
        state = "chat; speech disabled"
    print(f"Private {state} proxy configuration saved on iMX7; secret not displayed or embedded in APK.")

if __name__ == "__main__":
    main()
