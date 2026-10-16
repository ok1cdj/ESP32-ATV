#!/usr/bin/env python3
"""Null the carrier leakage of the C3 beacon: set its DC correction with "BDC i q" over the transmitter's USB, measure the leakage
tone with the S3 receiver, fit, repeat; then save it in the C3 (BDC SAVE) and in tx/cal_c3.json (used by tx/make_beacons.sh).

    third_party/ESP32-DATV-RX/.venv/bin/python rx/cal_dc.py [--freq 2340.03] [--step 4] [--rounds 3] [--save]
    third_party/ESP32-DATV-RX/.venv/bin/python rx/cal_dc.py --mode test [--freq 2340] [--save] [--log run.json]
    third_party/ESP32-DATV-RX/.venv/bin/python rx/cal_dc.py --mode test --freq-sweep 2300:2450:5 --log sweep.json
    third_party/ESP32-DATV-RX/.venv/bin/python rx/cal_dc.py --mode test --eval I Q      # the leakage of a correction

--mode test (needs the C3 firmware with BDC TEST): the C3 sends the leakage alone (BDC TEST on f: no modulation, the PA off), the
receiver streams without a break (one espdr.nb for the whole run), the tone is found by FFT and measured by a Goertzel filter at
its frequency against the noise floor, and the search is rx/dccal/core.py (a quadratic fitted to every measurement). The
frequency is --freq (default ATV_FREQ of boards.sh), set by the command, so --freq-sweep calibrates a series of them. --save
keeps the result in the C3's table for that frequency (BDC SAVE, up to 16 frequencies) and in tx/cal_c3.json. Afterwards the
transmitter is off (BDC TEST off, INFO).

--mode beacon (the default, any firmware with BDC):

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
import threading
import time

import numpy as np
import serial

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dccal.core import Calibrator, bowl_db  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RX = os.path.join(ROOT, "third_party", "ESP32-DATV-RX")
FS = 1e6 / 3                       # espdr.nb --decim 3
RX_OFFSET = 50e3                   # receiver LO above the carrier
SETTLE = 0.25                      # test mode: wait after a BDC command [s] (the pipeline of the receiver)


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

    def line(self, cmd, prefix, timeout=2.0):
        """Sends cmd and returns the first line starting with prefix (an ERR line ends the program)."""
        self.s.reset_input_buffer()
        self.s.write((cmd + "\r\n").encode())
        self.s.flush()
        buf, t0 = "", time.time()
        while time.time() - t0 < timeout:
            buf += self.s.read(max(1, self.s.in_waiting)).decode(errors="replace")
            for ln in buf.splitlines():
                if ln.startswith("ERR"):
                    sys.exit(f"{cmd}: {ln}")
                if ln.startswith(prefix) and "\n" in buf[buf.index(ln):]:
                    return ln.strip()
        sys.exit(f"{cmd}: no answer from the transmitter")

    def bdc(self, cmd="BDC"):
        """BDC ... -> (i, q, source, f_kHz, test) from "BDC i q (src) F f TEST t"; needs the firmware with BDC TEST."""
        p = self.line(cmd, "BDC ").replace("(", "").replace(")", "").split()
        if len(p) < 8 or p[4] != "F":
            sys.exit(f"the transmitter's firmware has no BDC TEST (answer: {' '.join(p)}); flash the current one")
        return float(p[1]), float(p[2]), p[3], int(p[5]), p[7] == "1"


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


class Stream:
    """espdr.nb running for the whole calibration; a thread keeps the newest samples."""

    def __init__(self, rx_port, lo_hz, gain):
        py = os.path.join(RX, ".venv", "bin", "python")
        self.p = subprocess.Popen([py, "-m", "espdr.nb", "-p", rx_port, "run", "--freq", str(round(lo_hz)), "--decim", "3",
                                   "--format", "cs8", "--gain", str(gain), "--seconds", "0", "--convert", "cf32", "-o", "-"],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.lock = threading.Lock()
        self.buf = []
        self.n = 0
        threading.Thread(target=self._read, daemon=True).start()
        self.take(int(0.5 * FS), settle=0.5)          # the start of the stream

    def _read(self):
        while True:
            b = self.p.stdout.read(65536)
            if not b:
                return
            x = np.frombuffer(b[:len(b) // 8 * 8], np.complex64)
            with self.lock:
                self.buf.append(x)
                self.n += len(x)

    def take(self, n, settle=0.15):
        """n samples received after now + settle s (the transmitter's change has passed the pipeline by then)."""
        time.sleep(settle)
        with self.lock:
            self.buf, self.n = [], 0
        t0 = time.time()
        while True:
            with self.lock:
                if self.n >= n:
                    return np.concatenate(self.buf)[:n]
            if self.p.poll() is not None:
                sys.exit("espdr.nb stopped:\n" + self.p.stderr.read().decode(errors="ignore"))
            if time.time() - t0 > 10:
                sys.exit("no samples from the receiver")
            time.sleep(0.02)

    def close(self):
        self.p.terminate()
        self.p.wait(5)


class ToneMeter:
    """The leakage tone in a block of N samples: the power spectrum averaged over 4 windows of N / 4 (20 Hz bins), the tone's
    power the sum of the 3 bins around its frequency, against the mean noise per bin around it [dB; noise alone: about 0].
    Its frequency: first the strongest line anywhere but the receiver's own DC; then, from a line at least STRONG dB above the
    noise, the strongest bin within +-TRACK Hz of the prediction. Weaker lines do not move it (near the null the strongest bin is
    noise): the tone is taken where it should be by now, as the crystals drift (about 10 Hz/s while warming up), with the drift
    rate from the last strong lines."""
    N = 1 << 16                     # 0.2 s at 333 ksps
    SUB = 4
    TRACK = 400.0                   # once found (about 0.7 s per measurement at up to 100 Hz/s)
    STRONG = 12.0
    LOCATE = 3.0

    def __init__(self):
        self.w = np.hanning(self.N // self.SUB).astype(np.float32)
        self.f = None               # the last strong line [Hz] and when it was seen
        self.t = 0.0
        self.rate = 0.0             # drift [Hz/s]
        self.hist = []

    def predict(self, now=None):
        if self.f is None:
            return None
        return self.f + self.rate * ((time.time() if now is None else now) - self.t)

    def _spectrum(self, x):
        m = self.N // self.SUB
        x = x[:self.N] - np.mean(x[:self.N])                 # the receiver's own DC out
        p = np.mean(np.abs(np.fft.fftshift(np.fft.fft(x.reshape(self.SUB, m) * self.w, axis=1), axes=1)) ** 2, 0)
        return (np.arange(m) - m / 2) * FS / m, p

    def locate(self, blocks):
        """The leakage tone among other lines (the receiver has one of its own at exactly the nominal frequency): spectra at
        different corrections, the line whose level changes most between them (at least LOCATE dB) and is strong in one;
        its frequency is then the tracked one (the drift learned afresh). False if there is none."""
        ps = [self._spectrum(x)[1] for x in blocks]
        f = self._spectrum(blocks[0])[0]
        hi, lo = np.max(ps, 0), np.min(ps, 0)
        ring = (np.abs(f) > 3e3)
        noise = np.median(hi[ring]) / 0.84
        score = np.where(ring & (hi > noise * 10 ** (self.STRONG / 10)), hi / np.maximum(lo, 1e-30), 0)
        k = int(np.argmax(score))
        if score[k] < 10 ** (self.LOCATE / 10):
            return False
        self.f, self.t, self.rate, self.hist = float(f[k]), time.time(), 0.0, []
        return True

    def gap(self, x):
        """Did the transmitter stop within x? The tone's level in the 4 windows differs by more than 10 dB (a strong tone only;
        near the null the windows are noise)."""
        fr = self.predict()
        if fr is None:
            return False
        m = self.N // self.SUB
        f = (np.arange(m) - m / 2) * FS / m
        k = int(np.argmin(np.abs(f - fr)))
        lv = []
        for j in range(self.SUB):
            p = np.abs(np.fft.fftshift(np.fft.fft((x[j * m:(j + 1) * m] - np.mean(x[j * m:(j + 1) * m])) * self.w))) ** 2
            lv.append(10 * np.log10(p[k - 1:k + 2].sum() / np.median(p) + 1e-30))
        return max(lv) - min(lv) > 10 and max(lv) > 25

    def measure(self, x):
        clip = float(np.mean((np.abs(x.real) >= 0.99) | (np.abs(x.imag) >= 0.99)))
        now = time.time()
        f, p = self._spectrum(x)
        df = f[1] - f[0]
        fr = self.predict(now)
        look = (np.abs(f) > 3e3) if fr is None else (np.abs(f - fr) < self.TRACK) & (np.abs(f) > 3e3)
        if not look.any():
            look = np.abs(f) > 3e3
        k = int(np.flatnonzero(look)[np.argmax(p[look])])

        def level(k):
            ring = (np.abs(f - f[k]) > 3e3) & (np.abs(f - f[k]) < 20e3) & (np.abs(f) > 3e3)
            noise = np.mean(np.sort(p[ring])[:int(0.9 * ring.sum())]) / 0.84   # the lowest 90 %: no stray lines
            return 10 * np.log10(p[k - 1:k + 2].sum() / (3 * noise))

        db = level(k)
        if db >= self.STRONG:
            a, b, c = np.log(p[k - 1:k + 2])
            fk = f[k] + (0.5 * (a - c) / (a - 2 * b + c) if a - 2 * b + c < 0 else 0.0) * df
            self.hist = [h for h in self.hist if now - h[0] < 5] + [(now, fk)]
            if len(self.hist) >= 2 and self.hist[-1][0] - self.hist[0][0] > 1.0:
                tt, ff = np.array(self.hist).T
                self.rate = float(np.clip(np.polyfit(tt - tt[-1], ff, 1)[0], -300, 300))
            self.f, self.t = fk, now
        elif fr is not None:
            k = int(np.argmin(np.abs(f - fr)))
            fk = fr
            db = level(k)
        else:
            fk = f[k]
        return db, fk, clip


def signal_rms(amp, S=200, beta=0.35, span=8):
    """RMS of the beacon's complex baseband at level amp [DAC codes], with the scaling of the firmware's tables (the worst sample
    sum of |pulse| = amp): the yardstick for dBc."""
    t = np.arange(span * S) / S - span / 2
    h = np.empty_like(t)
    for k, x in enumerate(t):
        if x == 0:
            h[k] = 1 - beta + 4 * beta / np.pi
        elif abs(abs(4 * beta * x) - 1) < 1e-9:
            h[k] = beta / np.sqrt(2) * ((1 + 2 / np.pi) * np.sin(np.pi / (4 * beta)) + (1 - 2 / np.pi) * np.cos(np.pi / (4 * beta)))
        else:
            h[k] = (np.sin(np.pi * x * (1 - beta)) + 4 * beta * x * np.cos(np.pi * x * (1 + beta))) / (np.pi * x * (1 - (4 * beta * x) ** 2))
    h = h.reshape(span, S)                          # h[l, j]: the symbol of age l at sample j
    sc = amp / np.max(np.sum(np.abs(h), 0))
    return float(np.sqrt(2 * np.mean(np.sum((sc * h) ** 2, 0))))


def test_mode(a, tx_port, rx_port, freq0):
    """--boots sessions (each one opens the port: a reset of the C3), then per frequency the mean of the converged results;
    --save stores the means in the C3's table and in tx/cal_c3.json."""
    if a.freqs:
        freqs = [float(v) for v in a.freqs.split(",")]
    elif a.freq_sweep:
        f1, f2, st = (float(v) for v in a.freq_sweep.split(":"))
        freqs = [round(f1 + k * st, 3) for k in range(int(round((f2 - f1) / st)) + 1)]
    else:
        freqs = [a.freq if a.freq is not None else freq0 / 1e6]
    if a.save and len(freqs) > 16:
        sys.exit("the C3's table holds 16 frequencies")
    runs = []
    for boot in range(a.boots):
        if a.boots > 1:
            print(f"=== boot {boot + 1} of {a.boots}")
        for r in session(a, freqs, tx_port, rx_port):
            r["boot"] = boot
            runs.append(r)
        if a.log:                                               # after every boot: a long run keeps what it has
            json.dump({"note": f"rx/cal_dc.py --mode test on {time.strftime('%Y-%m-%d %H:%M')}", "runs": runs},
                      open(a.log, "w"), indent=1)
    if a.log:
        print(f"-> {a.log}")
    if a.eval is not None:
        return
    table = []
    for f_mhz in freqs:
        ok = [r for r in runs if r["freq_mhz"] == f_mhz and r.get("converged")]
        if not ok:
            print(f"{f_mhz:.3f} MHz: no converged result")
            continue
        mi = float(np.mean([r["i"] for r in ok]))
        mq = float(np.mean([r["q"] for r in ok]))
        sd = float(np.sqrt(np.sum([(r["i"] - mi) ** 2 + (r["q"] - mq) ** 2 for r in ok]) / (len(ok) - 1))) if len(ok) > 1 else None
        sup = float(np.median([r["suppression_db"] for r in ok]))
        table.append((f_mhz, mi, mq, sd, sup, len(ok)))
        print(f"{f_mhz:.3f} MHz: BDC {mi:.3f} {mq:.3f} from {len(ok)} result(s)"
              + (f", spread {sd:.2f} codes rms" if sd is not None else "") + f", in-boot suppression {sup:.1f} dB (median)")
    if a.save and table:
        tx = Beacon(tx_port)
        try:
            for f_mhz, mi, mq, sd, sup, n in table:
                tx.bdc(f"BDC TEST on {f_mhz:.3f}")
                tx.bdc(f"BDC {mi:.3f} {mq:.3f}")
                tx.bdc("BDC SAVE")
                save_json(f_mhz, mi, mq, sup, sd, n)
            print(tx.cmd("BDC LIST", wait=0.8).strip())
        finally:
            tx.cmd("BDC TEST off", wait=0.5)
            tx.cmd("INFO", wait=0.5)
            tx.s.close()


def session(a, freqs, tx_port, rx_port):
    """One boot of the C3: warm-up, then each frequency --repeat times; the results."""
    tx = Beacon(tx_port)
    meter = ToneMeter()
    runs = []
    start = tuple(a.start) if a.start is not None else None
    stream = None
    try:
        for f_mhz in [f for f in freqs for _ in range(a.repeat)]:
            i0, q0, src, fk, _ = tx.bdc(f"BDC TEST on {f_mhz:.3f}")
            st = start if start is not None else (i0, q0)
            # the receiver 50 kHz above the frequency asked for (its DC outside); the crystals are tens of kHz off
            if stream is None or abs(stream_lo - (f_mhz * 1e6 + RX_OFFSET)) > 1:
                if stream:
                    stream.close()
                stream_lo = f_mhz * 1e6 + RX_OFFSET
                stream = Stream(rx_port, stream_lo, a.gain)
                meter.f = None
            log = []
            t0 = [time.time()]

            def measure(i, q, blocks=a.blocks):
                tx.bdc(f"BDC {i:.3f} {q:.3f}")
                dbs = []
                for b in range(blocks):
                    # BDC restarts the transmitter: about 0.25 s without a carrier after the answer (the PLL is set again)
                    for _ in range(3):
                        x = stream.take(ToneMeter.N, settle=SETTLE if b == 0 else 0.0)
                        if not meter.gap(x):
                            break
                        print("  (a gap in the carrier: again)", flush=True)
                    db, ftone, clip = meter.measure(x)
                    if clip > 1e-3:
                        print(f"  receiver overloaded ({clip:.1%} of the samples at full scale): lower --gain", flush=True)
                    dbs.append(db)
                db = 10 * np.log10(np.mean(10 ** (np.array(dbs) / 10)))
                log.append({"i": i, "q": q, "db": float(db), "f": float(stream_lo + ftone), "t": round(time.time() - t0[0], 3)})
                print(f"  BDC {i:+8.3f} {q:+8.3f}: tone {db:6.1f} dB above the noise, at {(stream_lo + ftone) / 1e6:.5f} MHz",
                      flush=True)
                return float(db)

            print(f"{f_mhz:.3f} MHz: BDC TEST, start {st[0]:+.3f} {st[1]:+.3f} ({src})")
            def locate():
                xs = []
                for di, dq in ((8.0, 0.0), (0.0, 0.0), (0.0, 8.0)):
                    tx.bdc(f"BDC {st[0] + di:.3f} {st[1] + dq:.3f}")
                    xs.append(stream.take(ToneMeter.N, settle=SETTLE))
                if not meter.locate(xs):
                    sys.exit("no leakage tone found (no line follows the correction): is the C3 transmitting (BDC TEST), "
                             "the receiver's antenna on, --gain enough?")
                print(f"  the leakage tone at {(stream_lo + meter.f) / 1e6:.5f} MHz", flush=True)

            locate()
            if a.warmup > 0 and not runs:
                # opening the port resets the C3: its frequency runs away for the first seconds (about -100 Hz/s)
                # (8 codes off: a strong tone, so that the meter finds it and learns its drift)
                t_end = time.time() + a.warmup
                while time.time() < t_end:
                    tx.bdc(f"BDC {st[0] + 8:.3f} {st[1]:.3f}")     # also keeps BDC TEST alive
                    x = stream.take(ToneMeter.N, settle=SETTLE)
                    db, ftone, _ = meter.measure(x)
                    print(f"  warming up {t_end - time.time():3.0f} s: tone {db:5.1f} dB at {(stream_lo + ftone) / 1e6:.5f} MHz, "
                          f"drift {meter.rate:+.0f} Hz/s", flush=True)
                    time.sleep(0.5)
                locate()                                        # again: it drifted
            t0[0] = time.time()                                 # the calibration's own time, without the warm-up
            if a.eval is not None:
                ref = measure(0.0, 0.0, 4 * a.blocks)
                got = measure(*a.eval, 4 * a.blocks)
                print(f"BDC 0 0: {ref:.1f} dB, BDC {a.eval[0]} {a.eval[1]}: {got:.1f} dB -> {ref - got:.1f} dB less leakage")
                runs.append({"freq_mhz": f_mhz, "eval": list(a.eval), "measurements": log})
                continue
            cal = Calibrator(start=st, step=a.step, tol=0.1, min_step=a.min_step)
            while not cal.done:
                for i, q in cal.next_points():
                    cal.add(i, q, measure(i, q))
            r = cal.result()
            secs = time.time() - t0[0]
            ref = measure(0.0, 0.0, 4 * a.blocks)              # without a correction: the yardstick
            last = measure(r["i"], r["q"], 4 * a.blocks)
            res = {"freq_mhz": f_mhz, "start": list(st), "step": a.step, "i": r["i"], "q": r["q"], "converged": r["converged"],
                   "measurements_n": r["measurements"], "seconds": round(secs, 1), "uncorrected_db": ref, "final_db": last,
                   "suppression_db": ref - last, "measurements": log}
            # dBc against the beacon at AMP 300: the leakage at BDC 0 0 in codes from the fit (sqrt((P(0) - P(null)) / mean
            # curvature)), the residual by the measured ratio
            if cal.fit:
                x = cal.fit
                p0, pn = 10 ** (bowl_db(x, 0.0, 0.0)[0] / 10), 10 ** (bowl_db(x, x[0], x[1])[0] / 10)
                leak = np.sqrt(max(p0 - pn, 0.0) / ((x[2] ** 2 + x[3] ** 2 + x[4] ** 2) / 2))
                rms = signal_rms(300)
                p_ref, p_last = 10 ** (ref / 10) - 1, max(10 ** (last / 10) - 1, 1e-3)
                res["leak_codes"] = float(leak)
                res["leak_dbc"] = 20 * np.log10(leak / rms)
                res["residual_dbc"] = res["leak_dbc"] - 10 * np.log10(p_ref / p_last)
            runs.append(res)
            print(f"result {f_mhz:.3f} MHz: BDC {r['i']:.3f} {r['q']:.3f}{'' if r['converged'] else ' (NOT converged)'}, "
                  f"{r['measurements']} measurements in {secs:.0f} s; leakage {ref:.1f} -> {last:.1f} dB above the noise "
                  f"({ref - last:.1f} dB less)" + (f", {res['leak_dbc']:.1f} -> {res['residual_dbc']:.1f} dBc of AMP 300"
                                                    if "leak_dbc" in res else ""))
            if len(freqs) > 1 and a.repeat == 1:                  # the next frequency starts from this one
                start = (r["i"], r["q"])
    finally:
        if stream:
            stream.close()
        try:
            tx.cmd("BDC TEST off", wait=0.5)
            tx.cmd("INFO", wait=0.5)                            # the transmitter off (until a command or a reset)
            tx.s.close()
        except serial.SerialException:
            pass
    return runs


def save_json(f_mhz, i, q, gain_db, spread=None, n=1):
    path = os.path.join(ROOT, "tx", "cal_c3.json")
    cal = json.load(open(path)) if os.path.exists(path) else {"iq_gain": 1.0, "iq_phase_deg": 0.0}
    table = {round(e["freq_mhz"], 3): e for e in cal.get("table", [])}
    table[round(f_mhz, 3)] = {"freq_mhz": round(f_mhz, 3), "dc": [round(i, 3), round(q, 3)], "date": time.strftime("%Y-%m-%d"),
                             "suppression_db": round(gain_db, 1), "boots": n,
                             "spread_codes": None if spread is None else round(spread, 2)}
    cal["table"] = [table[k] for k in sorted(table)]
    _, _, f0 = ports()
    if abs(f_mhz * 1e6 - f0) < 1e3:                     # make_beacons.sh bakes "dc" into the beacons of ATV_FREQ
        cal.update(note=f"Carrier leakage of our XIAO ESP32-C3, nulled with rx/cal_dc.py --mode test on {time.strftime('%Y-%m-%d')} "
                        f"at {f_mhz:.3f} MHz ({gain_db:.1f} dB less leakage).", dc=[round(i, 3), round(q, 3)])
    json.dump(cal, open(path, "w"), indent=2)
    print(f"-> {path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=("beacon", "test"), default="beacon",
                    help="beacon: the old way (beacon at a low level); test: BDC TEST (the leakage alone)")
    ap.add_argument("--freq", type=float, help="carrier [MHz] (default: the strongest line near ATV_FREQ of boards.sh; "
                                               "test: the frequency to transmit on, default ATV_FREQ)")
    ap.add_argument("--freq-sweep", metavar="F1:F2:STEP", help="test: calibrate F1, F1+STEP ... F2 [MHz]")
    ap.add_argument("--eval", type=float, nargs=2, metavar=("I", "Q"), help="test: only measure the leakage at BDC 0 0 and I Q")
    ap.add_argument("--freqs", metavar="F1,F2,...", help="test: calibrate these frequencies [MHz]")
    ap.add_argument("--boots", type=int, default=1, help="test: N sessions, each after a reset of the C3; the mean is the result "
                                                        "(the leakage moves by about 1.5 codes rms from boot to boot)")
    ap.add_argument("--repeat", type=int, default=1, help="test: calibrate each frequency N times (in one boot of the C3)")
    ap.add_argument("--blocks", type=int, default=1, help="test: 0.2 s blocks averaged per measurement (default 1)")
    ap.add_argument("--warmup", type=float, default=15.0, help="test: seconds of transmitting before the first measurement "
                                                                 "(the frequency settles; default 15)")
    ap.add_argument("--min-step", type=float, default=0.5, help="test: smallest step of the search [codes] (default 0.5: "
                                                                "smaller steps drown in the noise at about 30 dB of tone)")
    ap.add_argument("--log", help="write the measurements as JSON (for rx/dccal/make_vectors.py --log)")
    ap.add_argument("--start", type=float, nargs=2, default=None, metavar=("I", "Q"),
                    help="starting correction [codes] (default 0 0; test: the value the C3 holds for the frequency)")
    ap.add_argument("--step", type=float, default=4.0, help="first step [DAC codes] (default 4)")
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--gain", type=int, default=60, help="espdr gain selector (default 60)")
    ap.add_argument("--seconds", type=int, default=2)
    ap.add_argument("--save", action="store_true", help="BDC SAVE in the C3 and write tx/cal_c3.json")
    a = ap.parse_args()

    tx_port, rx_port, freq0 = ports()
    if a.mode == "test":
        return test_mode(a, tx_port, rx_port, freq0)
    if a.freq_sweep or a.eval:
        sys.exit("--freq-sweep and --eval need --mode test")
    tx = Beacon(tx_port)
    d = np.array(a.start if a.start is not None else (0.0, 0.0), float)
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

    log = []
    t0 = time.time()

    def measure(i, q):
        tx.set_dc(i, q)
        time.sleep(0.3)
        db, fk = tone_db(capture(rx_port, lo, a.gain, a.seconds), -RX_OFFSET)
        print(f"  BDC {i:+8.3f} {q:+8.3f}: leakage {db:+6.1f} dB (line at {(lo + fk) / 1e6:.5f} MHz)", flush=True)
        log.append({"i": float(i), "q": float(q), "db": float(db), "t": round(time.time() - t0, 3)})
        return db

    print(f"carrier {carrier / 1e6:.5f} MHz, receiver at {lo / 1e6:.5f} MHz")
    first = measure(*d)
    d = null(measure, d, a.step, a.rounds)
    last = measure(*d)
    print(f"result: BDC {d[0]:.3f} {d[1]:.3f}, leakage {first:+.1f} dB -> {last:+.1f} dB, {len(log)} measurements in "
          f"{time.time() - t0:.0f} s")
    if a.log:
        json.dump({"note": f"rx/cal_dc.py --mode beacon on {time.strftime('%Y-%m-%d %H:%M')}",
                   "runs": [{"freq_mhz": carrier / 1e6, "start": [float(v) for v in a.start or (0, 0)], "step": a.step,
                             "i": float(d[0]), "q": float(d[1]), "first_db": first, "final_db": last,
                             "seconds": round(time.time() - t0, 1), "measurements": log}]}, open(a.log, "w"), indent=1)
        print(f"-> {a.log}")
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
