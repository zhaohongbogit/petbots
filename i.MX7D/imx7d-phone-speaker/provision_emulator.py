#!/usr/bin/env python3
"""Add an emulator text-chat pairing without replacing the physical phone pairing."""
import re
import secrets
import subprocess
import sys

BOARD = "000000c4cddac462"
EMULATOR = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"


def adb(serial, *args):
    return subprocess.check_output(["adb", "-s", serial, *args], text=True, timeout=30)


def ipv4(serial, interface):
    output = adb(serial, "shell", "ip", "-4", "addr", "show", interface)
    match = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/", output)
    if not match:
        raise RuntimeError(f"{serial} has no IPv4 on {interface}")
    return match.group(1)


def start_with_extras(serial, component, fields):
    command = ["shell", "am", "start", "-n", component]
    for name, value in fields.items():
        command.extend(["--es", name, value])
    result = adb(serial, *command)
    if "Error" in result or "Exception" in result:
        raise RuntimeError(f"Could not configure {component}")


def main():
    board_ip = ipv4(BOARD, "wlan0")
    emulator_ip = ipv4(EMULATOR, "wlan0")
    pairing = secrets.token_hex(32)

    start_with_extras(EMULATOR, "com.zhb.phonespeaker/.MainActivity", {
        "pair_key": pairing,
        "board_ip": board_ip,
        "bind_ip": emulator_ip,
    })
    start_with_extras(BOARD, "com.zhb.petscreen/.ChatActivity", {
        "debug_pair_key": pairing,
    })
    start_with_extras(EMULATOR, "com.zhb.phonespeaker/.TextChatActivity", {})
    print(f"Configured emulator text chat to board {board_ip}; physical phone pairing preserved.")


if __name__ == "__main__":
    main()
