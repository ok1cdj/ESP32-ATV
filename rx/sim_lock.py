#!/usr/bin/env python3
"""How leandvb acquires the low-rate beacon: true or false first lock, time to the first frame and the first TS.
Built to find out why, on the bench (2026-10-15, 40 kS/s, normal frames), leandvb's first lock was a false one in 5 of
5 runs (MER -11..-15 dB, FREQ near +-Rs/4) and never with short frames. Candidates it can add to the clean beacon:
a carrier offset left after tuning, drift (the C3 warming up), and a tone near the centre of the signal - the C3's
carrier leakage (--tone-at carrier: moves with the signal) or the receiver's own DC left by DcBlock (--tone-at lo: fixed
at 0 Hz, the PC tunes the LO to the signal centre).

    third_party/ESP32-DATV-RX/.venv/bin/python rx/sim_lock.py [--rate 40000] [--frame normal short] [--offset 300]
        [--drift 0 70] [--tone none -40 -20] [--tone-at carrier] [--esn0 20] [--extra - fastlock freq-tol=0.1] [--seeds 1 2 3]
        [--seconds 45] [--jobs 6]

Each run: beacon (tx/make_beacons.sh) -> RRC 0.35 at 5 samples per symbol -> offset + drift (+ tone) -> resampled to the
receiver's 333 ksps -> AWGN -> 8 bit -> leandvb in real time with rx_dvbs2.py's options (RRC sampler below 100 kS/s,
ldpc_tool, --fd-info). One line per run; the info lines of each run go to build/sim-lock/<run>.log.
A lock counts as false when MER stays below 3 dB for the first 2 s after FRAMELOCK 1.
"""
import argparse
import itertools
import os
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RX = os.path.join(ROOT, "third_party", "ESP32-DATV-RX")
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(RX, "tools"))
from sim_beacon import rrc                     # noqa: E402
from sim_frames import beacon                  # noqa: E402
from sim_wide import resample                  # noqa: E402

OUT = os.path.join(ROOT, "build", "sim-lock")
LEANDVB = os.path.join(RX, "build", "leansdr", "src", "apps", "leandvb")
LDPC_TOOL = os.path.join(RX, "build", "LDPC", "ldpc_tool")
FS = 333333                                    # receiver: 16 MS/s / 48, cs8
SPS = 5


def symbols(sr, frame, seconds):
    d = beacon(sr, frame)
    nsym = int(open(os.path.join(d, "beacon_cfg.h")).read().split("BEACON_SYMBOLS")[1].split()[0].rstrip("u"))
    b = np.fromfile(os.path.join(d, "beacon.bin"), np.uint8)
    s = np.stack([(b >> (2 * k)) & 3 for k in range(4)], axis=1).ravel()[:nsym]
    sym = ((2.0 * (s & 1) - 1) + 1j * (2.0 * (s >> 1) - 1)) / np.sqrt(2)
    return np.tile(sym, int(seconds * sr / nsym) + 1)[:int(seconds * sr)]


_cache = {}
_cache_lock = threading.Lock()


def shaped(sr, frame, seconds):
    """Pulse-shaped beacon resampled to the receiver rate (cached: the same for every run of a rate and frame size)."""
    key = (sr, frame, seconds)
    with _cache_lock:
        if key not in _cache:
            sym = symbols(sr, frame, seconds)
            up = np.zeros(len(sym) * SPS, np.complex64)
            up[::SPS] = sym
            x = np.convolve(up, rrc(0.35, SPS, 10).astype(np.float32), mode="same")
            _cache[key] = resample(x.astype(np.complex64), sr * SPS, FS)
        return _cache[key]


def signal(c, seed):
    x = shaped(c["rate"], c["frame"], c["seconds"]).astype(np.complex128)
    t = np.arange(len(x)) / FS
    ph = 2 * np.pi * (c["offset"] * t + 0.5 * c["drift"] * t * t)        # carrier: offset + linear drift [Hz/s]
    x *= np.exp(1j * ph)
    ps = np.mean(np.abs(x) ** 2)
    if c["tone"] is not None:
        a = np.sqrt(ps * 10 ** (c["tone"] / 10))
        x += a * (np.exp(1j * ph) if c["tone_at"] == "carrier" else 1.0)  # leakage rides on the carrier; DC stays at 0 Hz
    rng = np.random.default_rng(seed)
    sigma = np.sqrt(ps / c["rate"] / 10 ** (c["esn0"] / 10) * FS / 2)
    x += sigma * (rng.standard_normal(len(x)) + 1j * rng.standard_normal(len(x)))
    s = 20 / np.sqrt(np.mean(np.abs(x) ** 2) / 2)                          # 8 bit, RMS 20 LSB per rail (as sim_frames)
    q = lambda v: np.clip(np.round(v * s), -128, 127)
    return (q(x.real) + 1j * q(x.imag)).astype(np.complex64)


def run(c, seed):
    x = signal(c, seed)
    data = (x / np.sqrt(np.mean(np.abs(x) ** 2)) * 0.25).astype(np.complex64).tobytes()
    info_r, info_w = os.pipe()
    cmd = [LEANDVB, "--f32", "--float-scale", "1", "-f", str(FS), "--sr", str(c["rate"]), "--standard", "DVB-S2",
           "--const", "QPSK", "--cr", "3/4", "--fastdrift", "-d", "--modcods", str(1 << 7),
           "--framesizes", {"normal": "1", "short": "2"}[c["frame"]], "--ldpc-helper", LDPC_TOOL,
           "--fd-info", str(info_w)]
    if c["rate"] < 100e3:
        cmd += ["--sampler", "rrc", "--rrc-rej", "50"]
    if FS > 3 * c["rate"]:
        cmd += ["--cnr"]
    cmd += c["extra"].split()
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(info_w,))
    os.close(info_w)
    t0 = time.monotonic()
    info, marks, first_ts = [], [], []

    def read_info():
        with os.fdopen(info_r, "r", errors="ignore") as f:
            for line in f:
                k, _, v = line.strip().partition(" ")
                try:
                    info.append((time.monotonic() - t0, k, float(v)))
                except ValueError:
                    pass

    def read_err():
        # leandvb -d: '_' clean frame, '!' failed, without newlines, mixed with messages (as the app reads them)
        in_marks = True
        while ch := p.stderr.read1(4096):
            now = time.monotonic() - t0
            for b in ch.decode(errors="ignore"):
                if b == "\n":
                    in_marks = True
                elif in_marks and b in "_.!:C":
                    if b in "_!":
                        marks.append((now, b))
                else:
                    in_marks = False

    def read_ts():
        while d := p.stdout.read1(65536):
            if not first_ts:
                first_ts.append(time.monotonic() - t0)

    th = [threading.Thread(target=f, daemon=True) for f in (read_info, read_err, read_ts)]
    for t in th:
        t.start()
    chunk = int(FS * 0.05) * 8
    for i in range(0, len(data), chunk):
        try:
            p.stdin.write(data[i:i + chunk]); p.stdin.flush()
        except BrokenPipeError:
            break
        lag = t0 + (i + chunk) / 8 / FS - time.monotonic()
        if lag > 0:
            time.sleep(lag)
    time.sleep(3)
    p.stdin.close()
    try:
        p.wait(20)
    except subprocess.TimeoutExpired:
        p.kill()
    for t in th:
        t.join(5)

    name = ("{rate}-{frame}-off{offset:g}-dr{drift:g}-t{tone}-{tone_at}-e{esn0:g}".format(**c)
            + (c["extra"].replace("--", "-").replace(" ", "") if c["extra"] else "") + f"-s{seed}")
    with open(os.path.join(OUT, name + ".log"), "w") as f:
        for t, k, v in info:
            f.write(f"{t:7.2f} {k} {v:g}\n")
        f.write("marks " + " ".join(f"{t:.1f}{m}" for t, m in marks) + "\n")
    # first lock: first FRAMELOCK 1; false if MER stays below 3 dB in the 2 s after it (MER of a rotating constellation)
    lock_t = next((t for t, k, v in info if k == "FRAMELOCK" and v == 1), None)
    false_lock, freq0, mer0 = None, None, None
    if lock_t is not None:
        win = [(k, v) for t, k, v in info if lock_t <= t < lock_t + 2]
        mers = [v for k, v in win if k == "MER"]
        freqs = [v for k, v in win if k == "FREQ"]
        mer0 = float(np.median(mers)) if mers else None
        freq0 = float(np.median(freqs)) if freqs else None
        false_lock = None if mer0 is None else mer0 < 3          # None: no MER in that window, undecided
    first_frame = next((t for t, m in marks if m == "_"), None)
    ok = sum(m == "_" for _, m in marks)
    return dict(name=name, lock=lock_t, false=false_lock, freq0=freq0, mer0=mer0, first_frame=first_frame,
                first_ts=first_ts[0] if first_ts else None, ok=ok, bad=sum(m == "!" for _, m in marks),
                locks=locks(info))


def locks(info):
    """Number of FRAMELOCK 0 -> 1 transitions."""
    n, prev = 0, 0
    for _, k, v in info:
        if k == "FRAMELOCK":
            n += v == 1 and prev == 0
            prev = v
    return n


def options(word):
    """'fastlock+freq-tol=0.1' -> '--fastlock --freq-tol 0.1'; '-' -> ''."""
    if word == "-":
        return ""
    return " ".join("--" + o.replace("=", " ") for o in word.split("+"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rate", type=int, default=40000)
    ap.add_argument("--frame", nargs="+", default=["normal", "short"])
    ap.add_argument("--offset", type=float, nargs="+", default=[300], help="carrier offset left after tuning [Hz]")
    ap.add_argument("--drift", type=float, nargs="+", default=[0], help="carrier drift [Hz/s] (bench: about 70)")
    ap.add_argument("--tone", nargs="+", default=["none"], help="tone level [dBc] or none")
    ap.add_argument("--tone-at", nargs="+", default=["carrier"], choices=("carrier", "lo"))
    ap.add_argument("--esn0", type=float, nargs="+", default=[20])
    ap.add_argument("--seeds", type=int, nargs="+", default=[1, 2, 3])
    ap.add_argument("--extra", nargs="+", default=["-"],
                    help="leandvb option sets to compare: '-' none, 'fastlock', 'fastlock+freq-tol=0.1' (= --fastlock --freq-tol 0.1)")
    ap.add_argument("--seconds", type=float, default=45)
    ap.add_argument("--jobs", type=int, default=6)
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    configs = []
    for fr, off, dr, tone, at, e, ex in itertools.product(a.frame, a.offset, a.drift, a.tone, a.tone_at, a.esn0, a.extra):
        tone = None if tone == "none" else float(tone)
        if tone is None and at != a.tone_at[0]:
            continue
        configs.append(dict(rate=a.rate, frame=fr, offset=off, drift=dr, tone=tone, tone_at=at if tone is not None else "-",
                            esn0=e, seconds=a.seconds, extra=options(ex)))
    for c in configs:                              # build the shaped signals once, before the threads
        shaped(c["rate"], c["frame"], c["seconds"])
    jobs = [(c, s) for c in configs for s in a.seeds]
    print(f"{len(jobs)} runs of {a.seconds:g} s, {a.jobs} at a time: about {len(jobs) / a.jobs * (a.seconds + 5) / 60:.0f} min",
          flush=True)
    f = lambda v, fmt: "-" if v is None else format(v, fmt)
    with ThreadPoolExecutor(a.jobs) as ex:
        for r in ex.map(lambda j: run(*j), jobs):
            print(f"{r['name']:60s} lock {f(r['lock'], '5.1f')} s  {'FALSE' if r['false'] else 'true ' if r['false'] is not None else '?    ' if r['lock'] is not None else '-    '}"
                  f" FREQ {f(r['freq0'], '7.0f')} MER {f(r['mer0'], '5.1f')}  first frame {f(r['first_frame'], '5.1f')} s"
                  f"  first TS {f(r['first_ts'], '5.1f')} s  frames ok {r['ok']:3d} bad {r['bad']:2d}  locks {r['locks']}",
                  flush=True)


if __name__ == "__main__":
    main()
