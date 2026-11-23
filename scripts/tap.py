#!/usr/bin/env python3
"""Dev helper: tap a UI element on the device by visible text. usage: tap.py <serial> <text>"""
import subprocess, sys, re
ser, text = sys.argv[1], sys.argv[2]
adb = ["adb", "-s", ser]
subprocess.run(adb + ["shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
xml = subprocess.run(adb + ["shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1).lower() == text.lower():
        x = (int(m.group(2)) + int(m.group(4))) // 2; y = (int(m.group(3)) + int(m.group(5))) // 2
        subprocess.run(adb + ["shell", "input", "tap", str(x), str(y)]); print("tapped", text, x, y); break
else:
    print("not found:", text); sys.exit(1)
