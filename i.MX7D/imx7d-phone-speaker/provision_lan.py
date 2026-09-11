#!/usr/bin/env python3
"""Provision a new local-only pairing secret without printing or saving it on the host."""
import re
import secrets
import shlex
import subprocess

PHONE = "9c79ecb0"
BOARD = "000000c4cddac462"

def adb(serial, *args):
    return subprocess.check_output(["adb", "-s", serial, *args], text=True, timeout=30)

def wifi_ip(serial):
    output = adb(serial, "shell", "ip", "-4", "addr", "show", "wlan0")
    match = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/", output)
    if not match:
        raise RuntimeError("Device has no Wi-Fi IPv4 address")
    return match.group(1)

def main():
    phone_ip, board_ip = wifi_ip(PHONE), wifi_ip(BOARD)
    pairing = secrets.token_hex(32)
    for serial, component, fields in [
        (PHONE, "com.zhb.phonespeaker/.MainActivity", {"pair_key": pairing, "board_ip": board_ip, "bind_ip": phone_ip}),
        (BOARD, "com.zhb.petscreen/.PhoneOutputActivity", {"pair_key": pairing, "phone_ip": phone_ip}),
    ]:
        # Restart only the test application so both activities read the new configuration.
        adb(serial, "shell", "am", "force-stop", component.split("/")[0])
        command = ["am", "start", "-n", component]
        for name, value in fields.items():
            command.extend(["--es", name, value])
        result = adb(serial, "shell", shlex.join(command))
        if "Error" in result or "Exception" in result:
            raise RuntimeError("Test application did not start; inspect device UI")
    print(f"Configured phone {phone_ip}:8765 and board {board_ip}; pairing secret not displayed.")

if __name__ == "__main__":
    main()
