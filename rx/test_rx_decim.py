#!/usr/bin/env python3
"""Tests of the Decimator in our ESP32-DATV-RX fork (rx_dvbs2.py), without hardware.

    third_party/ESP32-DATV-RX/.venv/bin/python rx/test_rx_decim.py unit
    third_party/ESP32-DATV-RX/.venv/bin/python rx/test_rx_decim.py chain tx/beacons/sv0syh-67k/beacon.bin 66666.6667 --esn0 6

unit:  streaming in odd-sized chunks gives the same output as one block; passband flat, stopband down.
chain: beacon -> RRC 0.35 at 1 MS/s -> AWGN -> 333 ksps -> cu8 (as espdr-rx sends it) -> rx_dvbs2.Decimator in random
       chunks -> leandvb with the options rx_dvbs2.py uses below 100 kS/s (f32, RRC sampler, ldpc_tool) -> frame counts.
"""
import argparse
import os
import subprocess
import sys
import threading

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RX = os.path.join(HERE, "..", "third_party", "ESP32-DATV-RX")
sys.path.insert(0, RX)
sys.path.insert(0, os.path.join(RX, "tools"))
import rx_dvbs2  # noqa: E402

FS = rx_dvbs2.FS


def unit():
    rng = np.random.default_rng(0)
    for sr in (66666, 50000, 40000):
        d = int(FS // (2 * sr))
        b = rng.integers(0, 256, 2 * 200_000, dtype=np.uint8).astype(np.float32) - 128.0
        data = (b[0::2] + 1j * b[1::2]).astype(np.complex64)      # as rx_dvbs2.DcBlock delivers it
        ref = np.frombuffer(rx_dvbs2.Decimator(FS, sr, d).process(data), np.complex64)
        dec, out, i = rx_dvbs2.Decimator(FS, sr, d), [], 0
        while i < len(data):
            n = int(rng.integers(1, 4500))
            out.append(dec.process(data[i:i + n]))
            i += n
        got = np.frombuffer(b"".join(out), np.complex64)
        assert len(got) == len(ref) and np.allclose(got, ref, atol=1e-3), (sr, len(got), len(ref))
        h = rx_dvbs2.Decimator(FS, sr, d).h
        f = np.fft.rfftfreq(65536, 1 / FS)
        a = 20 * np.log10(np.abs(np.fft.rfft(h, 65536)) + 1e-12)
        ripple = np.ptp(a[f <= 0.675 * sr])
        stop = a[f >= FS / d - 0.675 * sr].max()
        print(f"{sr / 1e3:5.1f} kS/s: D {d}, {len(h)} taps, {FS / d / sr:.2f} sps, passband ripple {ripple:.3f} dB, "
              f"stopband {stop:.0f} dB, streaming == one block: ok")
        assert ripple < 0.1 and stop < -60


def chain(a):
    from sim_wide import add_noise, resample
    sys.path.insert(0, HERE)
    from sim_beacon import beacon_symbols, rrc

    b = np.fromfile(a.beacon, np.uint8)
    s = np.stack([(b >> (2 * k)) & 3 for k in range(4)], axis=1).ravel()[:beacon_symbols(a.beacon)]
    sym = np.tile(((2.0 * (s & 1) - 1) + 1j * (2.0 * (s >> 1) - 1)) / np.sqrt(2), a.loops)
    sps = round(1e6 / a.baud)
    up = np.zeros(len(sym) * sps, np.complex64)
    up[::sps] = sym
    x = np.convolve(up, rrc(0.35, sps, 10).astype(np.float32), "same")
    x *= np.exp(2j * np.pi * a.offset * np.arange(len(x)) / 1e6).astype(np.complex64)
    x = add_noise(x, 1e6, a.baud, a.esn0, np.random.default_rng(a.seed))
    x = resample(x, 1e6, FS)
    x *= a.rms / np.sqrt(np.mean(np.abs(x) ** 2) / 2)                    # per-component RMS in u8 steps, like the gain sets it
    x += a.dc * (1 + 0.5j)                                                 # the receiver's own DC (cs8 keeps it)
    u8 = np.clip(np.round(np.stack((x.real, x.imag), 1).ravel() + 127.5), 0, 255).astype(np.uint8).tobytes()

    sr = int(a.baud)
    d = int(FS // (2 * sr))
    dec = rx_dvbs2.Decimator(FS, sr, d)
    src = rx_dvbs2.NarrowSource(None)
    src.dec = None if a.no_decim else dec
    modcod = rx_dvbs2.MODCODS[("QPSK", "3/4")]
    cmd = ([os.path.join(RX, "build", "leansdr", "src", "apps", "leandvb")]
           + (["--u8", "-f", str(FS)] if a.raw_u8 else src.leandvb_input())
           + ["--sr", str(sr), "--standard", "DVB-S2", "--const", "QPSK", "--cr", "3/4", "--fastdrift", "-d",
              "--modcods", str(1 << modcod), "--framesizes", "1",
              "--ldpc-helper", os.path.join(RX, "build", "LDPC", "ldpc_tool"), "--sampler", "rrc", "--rrc-rej", "50"])
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    res = {}
    readers = [threading.Thread(target=lambda f=f, k=k: res.__setitem__(k, f.read()), daemon=True)
               for f, k in ((p.stdout, "ts"), (p.stderr, "err"))]
    for t in readers:
        t.start()
    rng, i = np.random.default_rng(1), 0
    dc = rx_dvbs2.DcBlock()
    while i < len(u8):                                                      # chunks like socket reads (odd sizes too)
        n = int(rng.integers(1000, 65537))
        y = dc.process(u8[i:i + n]) if not a.raw_u8 else None              # as NarrowSource.start does
        p.stdin.write(u8[i:i + n] if a.raw_u8 else y.tobytes() if a.no_decim else dec.process(y))
        i += n
    p.stdin.close()
    p.wait(600)
    for t in readers:
        t.join(10)
    err = res["err"].decode(errors="ignore")
    clip = np.mean((np.frombuffer(u8, np.uint8) == 0) | (np.frombuffer(u8, np.uint8) == 255))
    print(f"{a.baud / 1e3:.1f} kS/s Es/N0 {a.esn0} dB seed {a.seed}: "
          f"{'no decimation' if a.no_decim else f'D {d} -> {FS / d / 1e3:.1f} ksps'}, cu8 rms {a.rms} "
          f" (clip {100 * clip:.2f}%): frames ok {err.count('_')} bad {err.count('!')} locks {err.count('LOCKED')} "
          f"TS {len(res['ts']) // 188}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    sp.add_parser("unit")
    c = sp.add_parser("chain")
    c.add_argument("beacon")
    c.add_argument("baud", type=float)
    c.add_argument("--esn0", type=float, default=9)
    c.add_argument("--seed", type=int, default=1)
    c.add_argument("--loops", type=int, default=40)
    c.add_argument("--offset", type=float, default=4000, help="carrier offset left after tuning [Hz] (default 4000)")
    c.add_argument("--no-decim", action="store_true", help="feed the 333 ksps cu8 stream as it is (rx_dvbs2.py --decimate off)")
    c.add_argument("--rms", type=float, default=20, help="per-component RMS of the cu8 stream in steps (default 20)")
    c.add_argument("--dc", type=float, default=0, help="the receiver's DC added to the cu8 stream [steps] (default 0)")
    c.add_argument("--raw-u8", action="store_true", help="feed cu8 to leandvb --u8 as before (no DC removal, no decimation)")
    a = ap.parse_args()
    unit() if a.cmd == "unit" else chain(a)


if __name__ == "__main__":
    main()
