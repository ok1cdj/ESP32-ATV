#!/usr/bin/env python3
"""Offline check of the whole chain without hardware: beacon.bin (packed QPSK symbols of the C3 beacon) ->
RRC 0.35 pulse shaping -> frequency offset -> cf32 reference, which ESP32-DATV-RX's tools/sim_wide.py then
resamples to the receiver rate, quantises (cs4 in --wide mode), adds noise to and decodes with leandvb.

    rx/sim_beacon.py tx/beacons/sv0syh-333k/beacon.bin -o build/beacon_ref.cf32
    third_party/ESP32-DATV-RX/.venv/bin/python third_party/ESP32-DATV-RX/tools/sim_wide.py build/beacon_ref.cf32 \
        --fs-in 1332000 --fs-out 500000 --sr 333000 --bits 4 --esn0 12

beacon.bin format (ESP32-DATV-SA host/dvbs2.py Encoder.pack): 4 symbols per byte, first symbol in the low bits,
bit 0 = I level, bit 1 = Q level, 1 = +1.
"""
import argparse
import os
import re

import numpy as np


def rrc(beta, sps, span):
    t = (np.arange(-span * sps, span * sps + 1)) / sps
    h = np.empty_like(t)
    for i, x in enumerate(t):
        if x == 0:
            h[i] = 1 - beta + 4 * beta / np.pi
        elif abs(abs(4 * beta * x) - 1) < 1e-9:
            h[i] = beta / np.sqrt(2) * ((1 + 2 / np.pi) * np.sin(np.pi / (4 * beta)) + (1 - 2 / np.pi) * np.cos(np.pi / (4 * beta)))
        else:
            h[i] = (np.sin(np.pi * x * (1 - beta)) + 4 * beta * x * np.cos(np.pi * x * (1 + beta))) / (np.pi * x * (1 - (4 * beta * x) ** 2))
    return h / np.sqrt(np.sum(h ** 2))


def beacon_symbols(beacon):
    cfg = os.path.join(os.path.dirname(os.path.abspath(beacon)), "beacon_cfg.h")
    m = re.search(r"#define\s+BEACON_SYMBOLS\s+(\d+)", open(cfg).read())
    return int(m.group(1))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("beacon", help="beacon.bin from make_beacon.py")
    ap.add_argument("-o", "--out", required=True, help="cf32 output file")
    ap.add_argument("--sps", type=int, default=4, help="samples per symbol of the output (fs = sps x 333000)")
    ap.add_argument("--loops", type=int, default=10, help="beacon loops (0.4 s each)")
    ap.add_argument("--offset", type=float, default=3000, help="carrier offset [Hz], like the crystal error of the two boards")
    ap.add_argument("--baud", type=float, default=333000)
    ap.add_argument("--symbols", type=int, default=0, help="symbols per loop; 0 = BEACON_SYMBOLS of beacon_cfg.h next to beacon.bin")
    a = ap.parse_args()

    b = np.fromfile(a.beacon, np.uint8)
    s = np.stack([(b >> (2 * k)) & 3 for k in range(4)], axis=1).ravel()
    s = s[:a.symbols or beacon_symbols(a.beacon)]          # beacon.bin is padded; the firmware plays BEACON_SYMBOLS
    sym = ((2.0 * (s & 1) - 1) + 1j * (2.0 * (s >> 1) - 1)) / np.sqrt(2)
    sym = np.tile(sym, a.loops)
    up = np.zeros(len(sym) * a.sps, np.complex128)
    up[::a.sps] = sym
    x = np.convolve(up, rrc(0.35, a.sps, 10), mode="same")
    fs = a.baud * a.sps
    x *= np.exp(2j * np.pi * a.offset * np.arange(len(x)) / fs)
    x.astype(np.complex64).tofile(a.out)
    print(f"{len(sym)} symbols, fs {fs:.0f} Hz, {len(x) / fs:.2f} s -> {a.out}")


if __name__ == "__main__":
    main()
