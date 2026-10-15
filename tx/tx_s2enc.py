#!/usr/bin/env python3
"""Streams a transport stream through the XIAO ESP32-C3 (ESP32-DATV(-SA) firmware, PC mode) with OUR DVB-S2 encoder
(android/native/dvbs2enc via build/s2enc/s2enc --stream): the same steps as the app's TX tab (C3Link.kt / Transmitter.kt),
for checking them on a PC. QPSK only.

    third_party/ESP32-DATV-RX/.venv/bin/python tx/tx_s2enc.py TS_FILE [--baud 50000] [--rate 3/4] [--frame short]
        [--pilots] [--amp 300] [--seconds 40] [--freq 2340.000] [--port /dev/...]

The TS file is looped until --seconds (it should be a constant-rate stream at about the channel rate, e.g. a beacon.ts).
"""
import argparse
import os
import subprocess
import sys
import time

import serial

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
S2ENC = os.path.join(ROOT, "build", "s2enc", "s2enc")
CPU_HZ = 160_000_000


def qpsk_sps(baud):
    if baud == 1_000_000:
        return 8
    best = None
    for period in (20, 24):
        sps = round(CPU_HZ / (period * baud))
        if 16 <= sps <= 232:
            err = abs(CPU_HZ / (period * sps) / baud - 1)
            if err <= 0.01 and (best is None or err < best[0] - 1e-9):
                best = (err, sps)
    return best[1] if best else 16 if baud * 16 <= 4_000_000 else 8 if baud * 8 <= 4_000_000 else 4


class Link:
    def __init__(self, port):
        self.s = serial.Serial(port, 115200, timeout=0)
        time.sleep(1.2)                                      # opening the port may reset the C3
        self.buf, self.text, self.fill, self.under, self.sent_since, self.reports = b"", b"", 0, 0, 0, 0

    def poll(self):
        self.buf += self.s.read(65536)
        b, i = self.buf, 0
        while i < len(b):
            if b[i] == 0xB7:
                if i + 4 > len(b):
                    break
                self.fill, self.under = b[i + 1] | b[i + 2] << 8, b[i + 3]
                self.sent_since, self.reports = 0, self.reports + 1
                i += 4
            else:
                self.text += b[i:i + 1]
                i += 1
        self.buf = b[i:]

    def wait(self, prefix, timeout):
        end = time.time() + timeout
        while time.time() < end:
            self.poll()
            for ln in self.text.decode(errors="replace").splitlines():
                if ln.startswith(prefix) or ln.startswith("ERR"):
                    self.text = b""
                    return ln
            time.sleep(0.01)
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ts")
    ap.add_argument("--baud", type=int, default=50000)
    ap.add_argument("--rate", default="3/4")
    ap.add_argument("--frame", default="short", choices=("normal", "short"))
    ap.add_argument("--pilots", action="store_true")
    ap.add_argument("--amp", type=int, default=300)
    ap.add_argument("--seconds", type=float, default=40)
    ap.add_argument("--freq", type=float, default=2340.000)
    ap.add_argument("--port")
    ap.add_argument("--target", type=int, default=3000)
    a = ap.parse_args()
    port = a.port or os.environ.get("TX_PORT")
    if not port:
        sys.exit("--port or TX_PORT (boards.sh)")

    # symbols for the whole run: loop the TS enough times, encode with s2enc --stream --format qpsk
    ts = open(a.ts, "rb").read()
    need = int(a.baud * a.seconds / 4) + 100000                # bytes of packed symbols, with a margin
    loops = max(1, int(need * 4 * 1.3 / (len(ts) / 188 * 1504 / 1.4)) + 1)
    tmp_ts, tmp_sym = "/tmp/tx_s2enc.ts", "/tmp/tx_s2enc.sym"
    open(tmp_ts, "wb").write(ts * loops)
    r = subprocess.run([S2ENC, "--rate", a.rate, "--frame", a.frame, "--stream", "--format", "qpsk"]
                       + (["--pilots"] if a.pilots else []) + [tmp_ts, tmp_sym], capture_output=True, text=True)
    if r.returncode:
        sys.exit(r.stderr)
    print(r.stderr.strip())
    sym = open(tmp_sym, "rb").read()

    link = Link(port)
    link.s.write(b"\nINFO\n")
    print(link.wait("ESP32DATV", 1.5))
    link.s.write(b"BDC\n")
    bdc = link.wait("BDC ", 1.0)
    print(bdc)
    dc = [float(v) for v in bdc.split()[1:3]] if bdc and bdc.startswith("BDC ") else [0.0, 0.0]
    link.s.write(b"PA 0\n")
    print(link.wait("OK PA", 1.0))
    sps = qpsk_sps(a.baud)
    cmd = f"QPSKT {a.freq:.3f} {a.baud} {sps} {a.amp} 86400 0 {a.target} {round(16 * dc[0])} {round(16 * dc[1])} 10000 0"
    print(">", cmd)
    link.s.write((cmd + "\n").encode())
    print(link.wait("OK ", 8.0))
    pos, t0 = 0, time.time()
    while time.time() - t0 < a.seconds and pos < len(sym):
        link.poll()
        todo = a.target - (link.fill + link.sent_since)
        if todo >= 32:
            nb = 2 * min(todo, 1024)
            link.s.write(sym[pos:pos + nb])
            pos += nb
            link.sent_since += nb // 2
        else:
            time.sleep(0.0005)
    link.s.write(b"\xA5\xFF")
    print(link.wait("TX END", 3.0), f"sent {pos} bytes in {time.time() - t0:.1f} s, fill {link.fill}, underruns {link.under}")


if __name__ == "__main__":
    main()
