#!/usr/bin/env python3
"""A recording for the app's "Replay recording" made from a transport stream, without any radio: TS -> our DVB-S2
encoder (build/s2enc/s2enc --stream, QPSK, as the TX tab) -> RRC 0.35 -> carrier offset -> noise -> cs8 at the S3's
sample rate -> packets as the ESP32-S3 sends them (E5 5D header, I/Q conjugated as espdr does). For checking the RX
chain (e.g. sound) on the phone:

    third_party/ESP32-DATV-RX/.venv/bin/python rx/make_replay.py IN.ts --baud 66666 [--frame short] [--seconds 30] \
        [--offset 5000] [--esn0 15] -o build/replay/x.raw
    adb push build/replay/x.raw /sdcard/Android/data/com.ok1cdj.atvrx/files/    (Replay plays the newest .raw)

The TS should run at about the channel's TS rate (tx/mux_selftest.sh makes such). Sample rate: 1 Msps / 3 below
200 kS/s, else 1 Msps / 2 (cs8 both: a replay takes format and decimation from its packets).
"""
import argparse
import os
import struct
from fractions import Fraction
import subprocess
import sys

import numpy as np
from scipy.signal import resample_poly

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from sim_beacon import rrc  # noqa: E402

S2ENC = os.path.join(HERE, "..", "build", "s2enc", "s2enc")
FMT_CS8 = 1
PACKET = 2048                                   # samples per packet


def packets(iq, decim):
    """complex samples -> raw packets (cs8, conjugated)"""
    a = np.empty(2 * len(iq), np.int8)
    a[0::2] = np.clip(np.round(iq.real), -127, 127)
    a[1::2] = np.clip(np.round(-iq.imag), -127, 127)
    out = bytearray()
    for k, first in enumerate(range(0, len(iq), PACKET)):
        n = min(PACKET, len(iq) - first)
        words = [0x5DE5, FMT_CS8 | decim << 8, n, 0, k & 0xFFFF, k >> 16, first & 0xFFFF, (first >> 16) & 0xFFFF, 0]
        words.append((0xFFFF - sum(words)) & 0xFFFF)
        out += struct.pack("<10H", *words) + a[2 * first:2 * (first + n)].tobytes()
    return bytes(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ts")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--baud", type=float, required=True)
    ap.add_argument("--rate", default="3/4")
    ap.add_argument("--frame", choices=("normal", "short"), default="normal")
    ap.add_argument("--no-pilots", action="store_true")
    ap.add_argument("--seconds", type=float, default=30)
    ap.add_argument("--offset", type=float, default=5000, help="carrier offset [Hz]")
    ap.add_argument("--esn0", type=float, default=15, help="Es/N0 [dB]")
    ap.add_argument("--rms", type=float, default=30, help="cs8 level of the signal + noise")
    a = ap.parse_args()

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    sym_file = a.out + ".sym"
    cmd = [S2ENC, "--stream", "--format", "phase", "--rate", a.rate, "--frame", a.frame] + ([] if a.no_pilots else ["--pilots"])
    subprocess.run(cmd + [a.ts, sym_file], check=True)
    k = np.fromfile(sym_file, np.uint8)
    os.remove(sym_file)
    need = int(a.seconds * a.baud)
    if len(k) < need:
        print(f"the TS gives {len(k) / a.baud:.1f} s of symbols", file=sys.stderr)
    k = k[:need]
    sym = np.exp(1j * np.pi * k / 4)

    decim = 3 if a.baud < 200_000 else 2
    fs = 1e6 / decim
    # pulse shaping at an integer number of samples per symbol, then to fs
    sps = max(4, int(np.ceil(fs / a.baud)))
    ratio = Fraction(1_000_000, decim * round(a.baud * sps))           # exact: fs / (baud x sps)
    up = np.zeros(len(sym) * sps, np.complex128)
    up[::sps] = sym
    x = np.convolve(up, rrc(0.35, sps, 10), mode="same") * np.sqrt(sps)
    x = resample_poly(x, ratio.numerator, ratio.denominator)
    t = np.arange(len(x)) / fs
    x *= np.exp(2j * np.pi * a.offset * t)
    # noise for Es/N0: a symbol's energy is fs / baud samples of the signal power, N0 the noise variance per sample
    n0 = np.mean(np.abs(x) ** 2) * fs / a.baud / 10 ** (a.esn0 / 10)
    rng = np.random.default_rng(1)
    x = x + np.sqrt(n0 / 2) * (rng.standard_normal(len(x)) + 1j * rng.standard_normal(len(x)))
    x *= a.rms / np.sqrt(np.mean(np.abs(x) ** 2))
    with open(a.out, "wb") as f:
        f.write(packets(x, decim))
    print(f"{len(sym)} symbols at {a.baud:.0f} Bd = {len(sym) / a.baud:.1f} s; {len(x)} samples at {fs:.0f} S/s cs8 "
          f"(sps {sps} x {ratio}); offset {a.offset:+.0f} Hz, Es/N0 {a.esn0} dB -> {a.out}")


if __name__ == "__main__":
    main()
