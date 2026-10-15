#!/usr/bin/env python3
"""Stores a stand-alone loop (tx/make_flash_loop.py, or upstream's make_video.py) in the XIAO ESP32-C3's "video" partition over
USB with our fork's VIDW command - no esptool, the firmware stays and keeps running (the same protocol as the app's TX screen):

    third_party/ESP32-DATV-RX/.venv/bin/python tx/c3_vidw.py LOOP.bin [--port /dev/...]     (default TX_PORT of boards.sh)
    third_party/ESP32-DATV-RX/.venv/bin/python tx/c3_vidw.py --erase                         back to the test card

VIDW BEGIN bytes crc32 -> OK VIDW BEGIN; VIDW DATA offset n crc32 + n bytes -> OK VIDW DATA offset (n <= 4096);
VIDW END -> VIDEO ... and OK VIDW END crc (or ERR ...); the C3 then plays the loop.
"""
import argparse
import os
import sys
import time
import zlib

import serial

CHUNK = 4096


def wait(s, prefixes, timeout):
    end = time.time() + timeout
    buf = b""
    while time.time() < end:
        buf += s.read(4096)
        lines = buf.decode(errors="replace").splitlines()
        for ln in lines:
            if any(ln.startswith(p) for p in prefixes) or ln.startswith("ERR"):
                return ln, lines
    return None, buf.decode(errors="replace").splitlines()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("loop", nargs="?")
    ap.add_argument("--erase", action="store_true")
    ap.add_argument("--port", default=os.environ.get("TX_PORT"))
    a = ap.parse_args()
    if not a.port:
        sys.exit("--port or TX_PORT")
    s = serial.Serial(a.port, 115200, timeout=0.1)
    time.sleep(1.2)                                          # opening the port may reset the C3
    s.reset_input_buffer()
    s.write(b"\nINFO\n")
    ln, lines = wait(s, ["VIDW 1"], 2)
    if not ln or not ln.startswith("VIDW 1"):
        sys.exit("this firmware has no VIDW command (flash ESP32-ATV's fork of ESP32-DATV-SA): " + " | ".join(lines[-3:]))
    print("before:", ln)
    if a.erase:
        s.write(b"VIDW ERASE\n")
        print(wait(s, ["OK VIDW ERASE"], 5)[0])
        return
    data = open(a.loop, "rb").read()
    t0 = time.time()
    s.write(f"VIDW BEGIN {len(data)} {zlib.crc32(data):08x}\n".encode())
    ln, _ = wait(s, ["OK VIDW BEGIN"], 60)                  # erasing 3 MB takes a while
    if not ln or not ln.startswith("OK"):
        sys.exit(f"BEGIN: {ln}")
    for off in range(0, len(data), CHUNK):
        part = data[off:off + CHUNK]
        s.write(f"VIDW DATA {off} {len(part)} {zlib.crc32(part):08x}\n".encode() + part)
        ln, _ = wait(s, [f"OK VIDW DATA {off}"], 5)
        if not ln or not ln.startswith("OK"):
            sys.exit(f"DATA {off}: {ln}")
        print(f"\r{off + len(part)} / {len(data)} bytes", end="", flush=True)
    print()
    s.write(b"VIDW END\n")
    ln, lines = wait(s, ["OK VIDW END"], 30)
    print("\n".join(l for l in lines if l.startswith("VIDEO")))
    print(ln, f"({len(data)} bytes in {time.time() - t0:.1f} s)")
    if not ln or not ln.startswith("OK"):
        sys.exit(1)


if __name__ == "__main__":
    main()
