#!/usr/bin/env python3
"""Null the carrier leakage of the C3 beacon: set its DC correction with "BDC i q" over the transmitter's USB, measure the leakage
tone with the S3 receiver, fit, repeat; then save it in the C3 (BDC SAVE) and in tx/cal_c3.json (used by tx/make_beacons.sh).

    third_party/ESP32-DATV-RX/.venv/bin/python rx/cal_dc.py [--freq 2340.03] [--step 4] [--rounds 3] [--save]

The leakage is a fixed tone at the transmitter's carrier, i.e. in the middle of the signal; its complex amplitude is linear in the
correction, so its power is a paraboloid P = c |d - d*|^2 + floor. Per round: measure at d, d +- step on I and on Q, put the vertex of
each parabola as the new d, halve the step. The receiver is tuned 50 kHz above the carrier so that its own DC lies outside the signal.
Run it with a low beacon level (AMP 13: the leakage stands out) and no rx.sh / espdr-rx holding the receiver.
"""
import argparse
import json
import os
import subprocess
import sys
import time

import numpy as np
import serial

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RX = os.path.join(ROOT, "third_party", "ESP32-DATV-RX")
FS = 1e6 / 3                       # espdr.nb --decim 3
RX_OFFSET = 50e3                   # receiver LO above the carrier


def ports():
    out = subprocess.run(["sh", "-c", '. "$0"; echo "$TX_PORT"; echo "$RX_PORT"; echo "$ATV_FREQ"', os.path.join(ROOT, "boards.sh")],
                         capture_output=True, text=True, check=True).stdout.split()
    return out[0], out[1], float(out[2]) * 1e6


class Beacon:
    def __init__(self, port):
        self.s = serial.Serial()
        self.s.port, self.s.timeout, self.s.dtr, self.s.rts = port, 0.2, False, False
        self.s.open()
        self.cmd("", wait=3)       # opening the port resets the C3: let it boot, a command sent during boot gets "ERR ?"

    def cmd(self, line, wait=0.8):
        self.s.reset_input_buffer()
        self.s.write((line + "\r\n").encode())
        self.s.flush()
        buf, t0 = b"", time.time()
        while time.time() - t0 < wait:
            buf += self.s.read(4096)
        return buf.decode(errors="replace")

    def set_dc(self, i, q):
        r = self.cmd(f"BDC {i:.3f} {q:.3f}")
        if "BDC " not in r or "ERR" in r:
            sys.exit(f"the transmitter did not take BDC (old firmware?):\n{r}")


def capture(rx_port, lo_hz, gain, seconds):
    py = os.path.join(RX, ".venv", "bin", "python")
    p = subprocess.run([py, "-m", "espdr.nb", "-p", rx_port, "run", "--freq", str(round(lo_hz)), "--decim", "3", "--format", "cs8",
                        "--gain", str(gain), "--seconds", str(seconds), "--convert", "cf32", "-o", "-"],
                       capture_output=True, timeout=60)
    if p.returncode:
        sys.exit("espdr.nb failed:\n" + p.stderr.decode(errors="ignore"))
    return np.frombuffer(p.stdout, np.complex64)[int(0.3 * FS):]


def spectrum(x, n=1 << 15):
    seg = len(x) // n
    w = np.hanning(n).astype(np.float32)
    p = np.mean(np.abs(np.fft.fftshift(np.fft.fft(x[:seg * n].reshape(seg, n) * w, axis=1), axes=1)) ** 2, 0)
    return (np.arange(n) - n / 2) * FS / n, p


def tone_db(x, f_carrier):
    """Power of the strongest narrow line within +-3 kHz of the expected carrier, against the signal around it [dB]."""
    f, p = spectrum(x)
    near = np.abs(f - f_carrier) < 3e3
    k = np.flatnonzero(near)[np.argmax(p[near])]
    line = p[k - 2:k + 3].sum()
    around = np.median(p[(np.abs(f - f_carrier) > 4e3) & (np.abs(f - f_carrier) < 12e3)]) * 5
    return 10 * np.log10(line / around), f[k]


def vertex(xm, x0, xp, pm, p0, pp):
    """Vertex of the parabola through (xm, pm), (x0, p0), (xp, pp) with xm = x0 - h, xp = x0 + h; None if it opens downwards."""
    h = xp - x0
    curv = pp + pm - 2 * p0
    if curv <= 0:
        return None
    return x0 + h * (pm - pp) / (2 * curv)


def null(measure, d, step, rounds):
    """Coordinate search with parabolic steps; measure(i, q) returns the leakage in dB."""
    d = np.array(d, float)
    for r in range(rounds):
        print(f"round {r + 1}, step {step:g}")
        p0 = measure(*d)
        for axis in (0, 1):
            e = np.eye(2)[axis] * step
            pm, pp = measure(*(d - e)), measure(*(d + e))
            v = vertex(d[axis] - step, d[axis], d[axis] + step, 10 ** (pm / 10), 10 ** (p0 / 10), 10 ** (pp / 10))
            if v is None:                   # no minimum in sight: go to the lower side
                v = d[axis] - step if pm < pp else d[axis] + step
            d[axis] = float(np.clip(v, d[axis] - 2 * step, d[axis] + 2 * step))
            p0 = measure(*d)
        step /= 2
    return d


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--freq", type=float, help="carrier [MHz] (default: the strongest line near ATV_FREQ of boards.sh)")
    ap.add_argument("--start", type=float, nargs=2, default=(0.0, 0.0), metavar=("I", "Q"), help="starting correction [codes]")
    ap.add_argument("--step", type=float, default=4.0, help="first step [DAC codes] (default 4)")
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--gain", type=int, default=60, help="espdr gain selector (default 60)")
    ap.add_argument("--seconds", type=int, default=2)
    ap.add_argument("--save", action="store_true", help="BDC SAVE in the C3 and write tx/cal_c3.json")
    a = ap.parse_args()

    tx_port, rx_port, freq0 = ports()
    tx = Beacon(tx_port)
    d = np.array(a.start, float)
    tx.set_dc(*d)
    time.sleep(0.5)
    if a.freq:
        carrier = a.freq * 1e6
    else:                                   # find it: the leakage is the strongest narrow line near ATV_FREQ
        x = capture(rx_port, freq0 + 30e3, a.gain, a.seconds)
        f, p = spectrum(x)
        ok = np.abs(f) > 2e3                # not the receiver's own DC
        carrier = freq0 + 30e3 + f[ok][np.argmax(p[ok])]
    lo = carrier + RX_OFFSET

    def measure(i, q):
        tx.set_dc(i, q)
        time.sleep(0.3)
        db, fk = tone_db(capture(rx_port, lo, a.gain, a.seconds), -RX_OFFSET)
        print(f"  BDC {i:+8.3f} {q:+8.3f}: leakage {db:+6.1f} dB (line at {(lo + fk) / 1e6:.5f} MHz)", flush=True)
        return db

    print(f"carrier {carrier / 1e6:.5f} MHz, receiver at {lo / 1e6:.5f} MHz")
    first = measure(*d)
    d = null(measure, d, a.step, a.rounds)
    last = measure(*d)
    print(f"result: BDC {d[0]:.3f} {d[1]:.3f}, leakage {first:+.1f} dB -> {last:+.1f} dB")
    if a.save:
        r = tx.cmd("BDC SAVE")
        print(r.strip())
        path = os.path.join(ROOT, "tx", "cal_c3.json")
        cal = json.load(open(path)) if os.path.exists(path) else {"iq_gain": 1.0, "iq_phase_deg": 0.0}
        cal.update(note=f"Carrier leakage of our XIAO ESP32-C3, nulled with rx/cal_dc.py on {time.strftime('%Y-%m-%d')} "
                        f"({first:+.1f} -> {last:+.1f} dB against the signal around it).", dc=[round(d[0], 3), round(d[1], 3)])
        json.dump(cal, open(path, "w"), indent=2)
        print(f"-> {path}")


if __name__ == "__main__":
    main()
