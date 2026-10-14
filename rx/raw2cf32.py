#!/usr/bin/env python3
"""Raw packets recorded by the Android app (phase F1: E5 5D header + cs8 / cs4 payload, as the ESP32-S3 sends them)
-> complex float32 I/Q, and optionally straight into leandvb + ldpc_tool to check that the recording is intact.

    third_party/ESP32-DATV-RX/.venv/bin/python rx/raw2cf32.py f1-cs8-333k-....raw [-o x.cf32]
    third_party/ESP32-DATV-RX/.venv/bin/python rx/raw2cf32.py f1-cs8-333k-....raw --decode 40000 [--ts out.ts]

The packet parser and the cs4 unpacking come from espdr (nb.py); the I/Q is conjugated as espdr --convert cf32 does.
Sample rate: 1 Msps / decim (cs8 333k: 333333, cs4 500k: 500000, cs8 250k: 250000), taken from the file name or --fs.
"""
import argparse
import os
import re
import struct
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RX = os.path.join(HERE, "..", "third_party", "ESP32-DATV-RX")
sys.path.insert(0, os.path.join(RX, "esp32-sdr-trx", "src"))
from espdr.nb import BYTES_PER_SAMPLE, NB_HEADER, NB_MAGIC, unpack_cs4  # noqa: E402


def packets(data):
    """Yields (format, shift word, first, payload); skips anything that is not a valid packet."""
    i, n, skipped = 0, len(data), 0
    while i + NB_HEADER <= n:
        if data[i:i + 2] != NB_MAGIC:
            i += 1
            skipped += 1
            continue
        words = struct.unpack_from("<10H", data, i)
        if sum(words) & 0xFFFF != 0xFFFF:
            i += 1
            skipped += 1
            continue
        fmt, count = words[1] & 0xFF, words[2]
        size = NB_HEADER + count * BYTES_PER_SAMPLE.get(fmt, 4)
        if i + size > n:
            break
        yield fmt, words[8], words[6] | words[7] << 16, data[i + NB_HEADER:i + size]
        i += size
    if skipped:
        print(f"{skipped} stray bytes skipped", file=sys.stderr)


def convert(data):
    out, lost, expected, fmt0 = [], 0, None, None
    for fmt, shift, first, payload in packets(data):
        fmt0 = fmt
        count = len(payload) // BYTES_PER_SAMPLE[fmt]
        if expected is not None and first != expected:
            lost += (first - expected) & 0xFFFFFFFF
        expected = (first + count) & 0xFFFFFFFF
        if fmt == 2:
            a = unpack_cs4(payload, shift, np) / 32768.0
        elif fmt == 1:
            a = np.frombuffer(payload, dtype=np.int8).astype(np.float32) / 128.0
        else:
            a = np.frombuffer(payload, dtype="<i2").astype(np.float32) / 32768.0
        a[1::2] *= -1.0
        out.append(a.astype("<f4"))
    return (np.concatenate(out) if out else np.zeros(0, "<f4")), lost, fmt0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("raw")
    ap.add_argument("-o", "--out", help="cf32 output (default: next to the input)")
    ap.add_argument("--fs", type=float, help="sample rate [S/s] (default from the file name)")
    ap.add_argument("--decode", type=float, metavar="SR", help="run leandvb + ldpc_tool, DVB-S2 QPSK 3/4 at this symbol rate")
    ap.add_argument("--cr", default="3/4")
    ap.add_argument("--ts", help="decoded TS output (default: next to the input)")
    a = ap.parse_args()

    iq, lost, fmt = convert(open(a.raw, "rb").read())
    fs = a.fs or {"333k": 1e6 / 3, "500k": 5e5, "250k": 2.5e5}.get((re.search(r"(333k|500k|250k)", a.raw) or [None, None])[1])
    if not fs:
        sys.exit("sample rate unknown: --fs")
    print(f"{len(iq) // 2} samples = {len(iq) / 2 / fs:.1f} s at {fs / 1e3:.1f} ksps, {lost} samples lost", file=sys.stderr)
    out = a.out or os.path.splitext(a.raw)[0] + ".cf32"
    iq.tofile(out)
    print(f"-> {out}", file=sys.stderr)
    if not a.decode:
        return
    leandvb = os.path.join(RX, "build", "leansdr", "src", "apps", "leandvb")
    ldpc = os.path.join(RX, "build", "LDPC", "ldpc_tool")
    modcod = {"1/4": 1, "1/3": 2, "2/5": 3, "1/2": 4, "3/5": 5, "2/3": 6, "3/4": 7, "4/5": 8, "5/6": 9, "8/9": 10,
              "9/10": 11}[a.cr]  # DVB-S2 MODCODs of QPSK
    cmd = [leandvb, "--f32", "--float-scale", "512" if fmt == 2 else "128", "-f", str(fs), "--sr", str(int(a.decode)),
           "--standard", "DVB-S2", "--const", "QPSK", "--cr", a.cr, "--fastdrift", "-d", "--modcods", str(1 << modcod),
           "--framesizes", "1", "--ldpc-helper", ldpc] + (["--sampler", "rrc", "--rrc-rej", "50"] if a.decode < 100e3 else [])
    ts = a.ts or os.path.splitext(a.raw)[0] + ".ts"
    with open(out, "rb") as fin, open(ts, "wb") as fts:
        p = subprocess.run(cmd, stdin=fin, stdout=fts, stderr=subprocess.PIPE)
    err = p.stderr.decode(errors="ignore")
    # leandvb -d: '_' clean frame, '!' failed (':' with ldpc_tool); '.' (corrected by BCH) also appears in other lines
    print(f"frames ok {err.count('_')}, with errors {err.count(':') + err.count('!')}, "
          f"locks {err.count('LOCKED')}, TS packets {os.path.getsize(ts) // 188} -> {ts}", file=sys.stderr)


if __name__ == "__main__":
    main()
