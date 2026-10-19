#!/usr/bin/env python3
"""Tests of our DVB-S2 encoder (android/native/dvbs2enc, GPL) on the PC:

A. bit-exact against ESP32-DATV-SA's make_beacon.py (used only as a test oracle, its code is not in the app): QPSK,
   normal frames 3/4..9/10 (it needs 32 TS packets per frame), short frames at every rate it accepts, pilots on and off;
C. continuous framing (packets across frames, as live transmission) bit-exact against ESP32-DATV-SA's dvbs2.Encoder (the
   PC streaming encoder, also only an oracle): QPSK and 8PSK, every code rate, normal and short frames, pilots on/off;
B. independent decoder: our symbols -> RRC 0.35 -> leandvb + ldpc_tool (as in the receivers, no noise) for QPSK and
   8PSK at every code rate, normal and short frames; the decoded TS must be a run of the input TS, at least one whole
   ldpc_tool batch of 32 frames (40 normal / 56 short frames are sent: the lock and the partial last batch cost some).

    third_party/ESP32-DATV-RX/.venv/bin/python rx/test_dvbs2enc.py [--only A|B|C] [-v]
"""
import argparse
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
SRC = os.path.join(ROOT, "android", "native", "dvbs2enc")
OUT = os.path.join(ROOT, "build", "s2enc")
S2ENC = os.path.join(OUT, "s2enc")
MAKE_BEACON = os.path.join(ROOT, "third_party", "ESP32-DATV-SA", "host", "make_beacon.py")
LEANDVB = os.path.join(ROOT, "third_party", "ESP32-DATV-RX", "build", "leansdr", "src", "apps", "leandvb")
LDPC_TOOL = os.path.join(ROOT, "third_party", "ESP32-DATV-RX", "build", "LDPC", "ldpc_tool")
sys.path.insert(0, HERE)
from sim_beacon import rrc  # noqa: E402

def oracle_python():
    """A Python with numpy and Pillow for make_beacon.py (as tx/make_beacons.sh: the RX venv has no Pillow)."""
    for c in (os.environ.get("PYTHON"), "python3", "/usr/bin/python3", sys.executable):
        if c and subprocess.run([c, "-c", "import numpy, PIL"], capture_output=True).returncode == 0:
            return c
    sys.exit("no python3 with numpy and Pillow for make_beacon.py (set PYTHON=...)")



QPSK_RATES = ["1/4", "1/3", "2/5", "1/2", "3/5", "2/3", "3/4", "4/5", "5/6", "8/9", "9/10"]
PSK8_RATES = ["3/5", "2/3", "3/4", "5/6", "8/9", "9/10"]
MODCOD = {("QPSK", r): 1 + i for i, r in enumerate(QPSK_RATES)} | {("8PSK", r): 12 + i for i, r in enumerate(PSK8_RATES)}


def build():
    os.makedirs(OUT, exist_ok=True)
    subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-Werror", "-std=c11", os.path.join(SRC, "dvbs2enc.c"),
                    os.path.join(SRC, "s2enc_cli.c"), "-o", S2ENC], check=True)


def s2enc(ts, out, const="qpsk", rate="3/4", frame="normal", pilots=True, frames=None, loop=False, fmt="phase", packets=None,
          stream=False):
    cmd = [S2ENC, "--const", const, "--rate", rate, "--frame", frame, "--format", fmt]
    cmd += ["--pilots"] if pilots else []
    cmd += ["--frames", str(frames)] if frames else []
    cmd += ["--loop"] if loop else []
    cmd += ["--packets", str(packets)] if packets else []
    cmd += ["--stream"] if stream else []
    r = subprocess.run(cmd + [ts, out], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(r.stderr.strip())
    return r.stderr


def cfg_value(d, key):
    return open(os.path.join(d, "beacon_cfg.h")).read().split(key)[1].split()[0].rstrip("u")


def part_a(verbose):
    fails = runs = 0
    cases = [("normal", r, p) for r in ["3/4", "4/5", "5/6", "8/9", "9/10"] for p in (True, False)]
    cases += [("short", r, p) for r in QPSK_RATES[:-1] for p in (True, False)]
    for frame, rate, pilots in cases:
        d = os.path.join(OUT, "a", f"{frame}-{rate.replace('/', '')}-{'p' if pilots else 'np'}")
        os.makedirs(d, exist_ok=True)
        cmd = [oracle_python(), MAKE_BEACON, "--fec", rate, "--frame", frame, "--frames", "2" if frame == "normal" else "8",
               "--baud", "333000", "--out", d, "--ts", os.path.join(d, "beacon.ts")] + ([] if pilots else ["--no-pilots"])
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode:
            if verbose:
                print(f"A {frame:6s} {rate:4s} pilots {int(pilots)}: oracle refuses ({r.stderr.strip().splitlines()[-1]})")
            continue
        n, frames = int(cfg_value(d, "BEACON_SYMBOLS")), int(cfg_value(d, "BEACON_PLFRAMES"))
        mine = os.path.join(d, "mine.bin")
        s2enc(os.path.join(d, "beacon.ts"), mine, rate=rate, frame=frame, pilots=pilots, frames=frames, loop=True, fmt="qpsk",
              packets=32 if frame == "normal" else None)            # the beacon puts 32 packets in every normal frame
        a = np.fromfile(os.path.join(d, "beacon.bin"), np.uint8)
        b = np.fromfile(mine, np.uint8)
        sa = np.stack([(a >> (2 * k)) & 3 for k in range(4)], axis=1).ravel()[:n]
        sb = np.stack([(b >> (2 * k)) & 3 for k in range(4)], axis=1).ravel()[:n]
        bad = int(np.count_nonzero(sa != sb)) if len(sb) == n else n
        runs += 1
        fails += bad > 0
        print(f"A {frame:6s} QPSK {rate:4s} pilots {int(pilots)}: {n} symbols, {frames} frames: "
              + ("bit-exact" if not bad else f"{bad} symbols differ  FAIL"))
    return runs, fails


def test_ts(npk, seed):
    """npk TS packets on PID 0x100 with a running continuity counter and random payload."""
    rng = np.random.default_rng(seed)
    p = rng.integers(0, 256, (npk, 188), dtype=np.uint8)
    p[:, 0], p[:, 1], p[:, 2] = 0x47, 0x01, 0x00
    p[:, 3] = 0x10 | (np.arange(npk) & 15)
    return p


def decode(k, lead, const, rate, frame, sps, sr, h, sent):
    """Phase indices -> RRC -> leandvb + ldpc_tool. Returns (packets back, whether they are a run of the sent ones,
    clean frames)."""
    sym = np.exp(1j * np.pi / 4 * k.astype(np.float64))
    sym = np.concatenate([np.zeros(lead), sym, np.zeros(500)])
    up = np.zeros(len(sym) * sps, np.complex128)
    up[::sps] = sym
    x = np.convolve(up, h, mode="same")
    data = (x / np.sqrt(np.mean(np.abs(x) ** 2)) * 0.25).astype(np.complex64).tobytes()
    cmd = [LEANDVB, "--f32", "--float-scale", "1", "-f", str(sps * sr), "--sr", str(sr), "--standard", "DVB-S2",
           "--const", const, "--cr", rate, "-d", "--modcods", str(1 << MODCOD[(const, rate)]),
           "--framesizes", "1" if frame == "normal" else "2", "--ldpc-helper", LDPC_TOOL]
    r = subprocess.run(cmd, input=data, capture_output=True, timeout=300)
    got = np.frombuffer(r.stdout, np.uint8)
    npk = len(got) // 188
    ok = False
    if npk:
        g = got[:npk * 188].reshape(npk, 188)
        start = next((i for i in range(len(sent)) if np.array_equal(sent[i], g[0])), None)
        ok = start is not None and start + npk <= len(sent) and np.array_equal(sent[start:start + npk], g)
    return npk, ok, r.stderr.decode(errors="ignore").count("_")


def part_b(verbose):
    fails = runs = 0
    sps, sr = 4, 250000
    h = rrc(0.35, sps, 16)
    cases = [("QPSK", r, f) for f in ("normal", "short") for r in QPSK_RATES if not (f == "short" and r == "9/10")]
    cases += [("8PSK", r, f) for f in ("normal", "short") for r in PSK8_RATES if not (f == "short" and r == "9/10")]
    cases += [("QPSK", "1/2", "normal", False), ("8PSK", "2/3", "short", False)]
    for case in cases:
        const, rate, frame = case[:3]
        pilots = case[3] if len(case) > 3 else True
        d = os.path.join(OUT, "b")
        os.makedirs(d, exist_ok=True)
        tag = f"{const}-{rate.replace('/', '')}-{frame}-{'p' if pilots else 'np'}"
        # leandvb leaves the last two longest frames' worth of input (2 x 33282 symbols) unprocessed: 40 short 8PSK
        # frames would not fill a 32-frame ldpc_tool batch
        frames = 40 if frame == "normal" else 56
        # up to 3 tries with other TS data in case the acquisition goes wrong (see rx/sim_lock.py); an encoder error
        # fails all
        tries = []
        for seed in (1, 2, 3):
            ts_in = os.path.join(d, f"test{seed}.ts")
            if not os.path.exists(ts_in):
                test_ts(4000, seed).tofile(ts_in)
            info = s2enc(ts_in, os.path.join(d, tag + ".sym"), const=const.lower(), rate=rate, frame=frame,
                         pilots=pilots, frames=frames)
            ppf = int(info.split(",")[1].split()[0])
            k = np.fromfile(os.path.join(d, tag + ".sym"), np.uint8)
            sent = np.fromfile(ts_in, np.uint8)[:frames * ppf * 188].reshape(-1, 188)
            npk, ok, clean = decode(k, 500, const, rate, frame, sps, sr, h, sent)
            tries.append(npk)
            good = ok and npk >= 32 * ppf - 1          # one whole ldpc_tool batch; leandvb drops the very first packet
            if good:
                break
        runs += 1
        fails += not good
        print(f"B {frame:6s} {const} {rate:4s} pilots {int(pilots)}: {npk:4d} of {frames * ppf} packets back "
              f"({clean} clean frames{', tries ' + str(tries) if len(tries) > 1 else ''}) " + ("OK" if good else "FAIL"))
    return runs, fails


ORACLE_C = """
import sys, numpy as np
sys.path.insert(0, sys.argv[1])
import dvbs2
mod, fec, frame, pilots, ts, out, nframes = sys.argv[2:9]
e = dvbs2.Encoder(fec=fec, frame=frame, pilots=pilots == "1", mod=mod)
data = open(ts, "rb").read()
res, pk = [], 0
while len(res) < int(nframes):
    e.push(data[188 * pk:188 * (pk + 1)]); pk += 1
    while e.ready() and len(res) < int(nframes):
        bb = e.bbframe()
        if mod == "8psk":
            res.append(e.plframe8(bb))
        else:
            si, sq = e.plframe(bb)
            res.append(np.array([{(1, 1): 1, (-1, 1): 3, (-1, -1): 5, (1, -1): 7}[(int(i), int(q))] for i, q in zip(si, sq)], np.uint8))
np.concatenate(res).astype(np.uint8).tofile(out)
"""


def part_c(verbose):
    fails = runs = 0
    d = os.path.join(OUT, "c")
    os.makedirs(d, exist_ok=True)
    ts = os.path.join(d, "test.ts")
    test_ts(3000, 7).tofile(ts)
    script = os.path.join(d, "oracle_c.py")
    open(script, "w").write(ORACLE_C)
    host = os.path.dirname(MAKE_BEACON)
    cases = [("qpsk", r, f, p) for f in ("normal", "short") for r in QPSK_RATES for p in (True, False)
             if not (f == "short" and r == "9/10")]
    cases += [("8psk", r, f, p) for f in ("normal", "short") for r in PSK8_RATES for p in (True, False)
              if not (f == "short" and r == "9/10")]
    for mod, rate, frame, pilots in cases:
        nf = 3 if frame == "normal" else 6
        ref, mine = os.path.join(d, "ref.k"), os.path.join(d, "mine.k")
        r = subprocess.run([oracle_python(), script, host, mod, rate, frame, "1" if pilots else "0", ts, ref, str(nf)],
                           capture_output=True, text=True)
        if r.returncode:
            if verbose:
                print(f"C {frame:6s} {mod} {rate:4s} pilots {int(pilots)}: oracle fails ({r.stderr.strip().splitlines()[-1]})")
            continue
        s2enc(ts, mine, const=mod, rate=rate, frame=frame, pilots=pilots, frames=nf, fmt="phase", stream=True)
        a, b = np.fromfile(ref, np.uint8), np.fromfile(mine, np.uint8)
        bad = int(np.count_nonzero(a != b)) if len(a) == len(b) else -1
        runs += 1
        fails += bad != 0
        print(f"C {frame:6s} {mod.upper()} {rate:4s} pilots {int(pilots)}: {len(a)} symbols, {nf} frames: "
              + ("bit-exact" if bad == 0 else f"{'length ' + str(len(b)) + ' vs ' + str(len(a)) if bad < 0 else str(bad) + ' symbols differ'}  FAIL"))
    return runs, fails


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", choices=("A", "B", "C"))
    ap.add_argument("-v", action="store_true")
    a = ap.parse_args()
    build()
    total = fails = 0
    for name, part in (("A", part_a), ("C", part_c), ("B", part_b)):
        if a.only and a.only != name:
            continue
        r, f = part(a.v)
        total += r
        fails += f
    print(f"{total - fails} of {total} passed")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
