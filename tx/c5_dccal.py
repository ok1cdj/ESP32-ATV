#!/usr/bin/env python3
"""DC trim of the ESP32-C5 transmitter (tx/c5cw) against its carrier leakage, measured by the ESP32-C5 receiver (rx/c5rx).

The transmitter holds a constant DAC word (CW i q): its carrier is the leakage plus (i, q), so its power is a quadratic in
(i, q) with the minimum at the DC trim. The receiver captures 16 ms at 2 Msps (CAP) for each word; the line is found once
(the two crystals differ by up to ~150 kHz) and then measured within a few kHz of it. Parabolas through three points, in i then
in q, with the step halved each round. The trims go to the beacon: BEACON amp S dci dcq (--beacon starts it).

    third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py [--freq 5715] [--beacon] [--amp 150]

Ports: C5_PORT (transmitter) and C5RX_PORT (receiver) from boards.sh, or --tx / --rx.
"""
import argparse
import os
import re
import sys
import time

import numpy as np
import serial

HERE = os.path.dirname(os.path.abspath(__file__))


def boards():
    v = {}
    for line in open(os.path.join(HERE, "..", "boards.sh")):
        m = re.match(r'(\w+)=\$\{\w+:-(.*)\}', line.split("#")[0].strip())
        if m:
            v[m.group(1)] = m.group(2)
    return v


class Port:
    def __init__(self, path):
        self.s = serial.Serial(path, 115200, timeout=0.3)
        self.s.write(b"\n")                       # ends whatever runs (TONE, BEACON, a stream)
        time.sleep(0.5)
        self.s.reset_input_buffer()

    def cmd(self, c, wait=0.3):
        self.s.write(c.encode() + b"\n")
        time.sleep(wait)
        return self.s.read(4096).decode(errors="replace").strip()


def capture(rx):
    """16 ms at 2 Msps from the receiver: (complex samples, rate)."""
    rx.s.reset_input_buffer()
    rx.s.write(b"CAP 32768\n")
    hdr, end = b"", time.time() + 5
    while time.time() < end:
        hdr += rx.s.read(1)
        if hdr.endswith(b"\n"):
            if hdr.startswith(b"CAPD"):
                break
            hdr = b""
    p = hdr.split()
    if len(p) < 3:
        sys.exit("no capture from the receiver (rx/c5rx flashed?)")
    n, rate = int(p[1]), int(p[2])
    data = b""
    while len(data) < 4 * n and time.time() < end + 10:
        data += rx.s.read(4 * n - len(data))
    x = np.frombuffer(data, "<i2").astype(float).reshape(-1, 2)
    return x[:, 0] + 1j * x[:, 1], rate


def spectrum(z, rate):
    S = np.abs(np.fft.fftshift(np.fft.fft((z - z.mean()) * np.hanning(len(z))))) ** 2
    return np.fft.fftshift(np.fft.fftfreq(len(z), 1 / rate)), S


def find_line(rx):
    f, S = spectrum(*capture(rx))
    m = (np.abs(f) < 400e3) & (np.abs(f) > 5e3)       # away from the receiver's own DC
    k = np.argmax(np.where(m, S, 0))
    return f[k]


def line_power(rx, f0, span=3e3):
    """Power of the strongest line within +-span of f0 (it drifts slowly), and its frequency."""
    f, S = spectrum(*capture(rx))
    m = np.abs(f - f0) < span
    k = np.argmax(np.where(m, S, 0))
    return S[max(k - 3, 0):k + 4].sum(), f[k]


def main():
    b = boards()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tx", default=b.get("C5_PORT"))
    ap.add_argument("--rx", default=b.get("C5RX_PORT"))
    ap.add_argument("--freq", type=float, default=float(b.get("ATV6_FREQ", "5715")), help="MHz")
    ap.add_argument("--gain", type=int, default=20, help="receiver gain index")
    ap.add_argument("--step", type=float, default=16, help="first step [DAC codes]")
    ap.add_argument("--rounds", type=int, default=4)
    ap.add_argument("--beacon", action="store_true", help="start the beacon with the trims")
    ap.add_argument("--amp", type=int, default=150)
    a = ap.parse_args()

    tx, rx = Port(a.tx), Port(a.rx)
    print("TX", tx.cmd(f"F {a.freq:.3f}"))
    print("RX", rx.cmd(f"F {a.freq:.3f}"), rx.cmd(f"G {a.gain}"))
    tx.cmd("CW 0 0")
    time.sleep(0.5)
    f0 = find_line(rx)
    p0, f0 = line_power(rx, f0)
    print(f"carrier leakage at {f0 / 1e3:+.1f} kHz from the receiver's LO")

    def measure(i, q):
        nonlocal f0
        tx.cmd(f"CW {int(round(i))} {int(round(q))}", 0.05)
        p, f0 = line_power(rx, f0)
        return p

    i = q = 0.0
    step = a.step
    for r in range(a.rounds):
        for axis in (0, 1):
            pts = [-step, 0.0, step]
            p = [measure(i + d, q) if axis == 0 else measure(i, q + d) for d in pts]
            den = p[0] - 2 * p[1] + p[2]
            d = 0.5 * step * (p[0] - p[2]) / den if den > 0 else 0.0
            d = max(-2 * step, min(2 * step, d))
            if axis == 0:
                i += d
            else:
                q += d
        print(f"round {r + 1}: dc {i:+.1f} {q:+.1f}, carrier {10 * np.log10(measure(i, q) / p0):+.1f} dB re untrimmed")
        step = max(step / 2, 2)
    tx.cmd("OFF")
    print(f"DC trim: {i:.1f} {q:.1f}  ->  BEACON {a.amp} 16 {i:.1f} {q:.1f}")
    if a.beacon:
        print("TX", tx.cmd(f"BEACON {a.amp} 16 {i:.1f} {q:.1f}", 0.5))


if __name__ == "__main__":
    main()
