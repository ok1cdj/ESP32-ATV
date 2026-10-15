#!/usr/bin/env python3
"""A stand-alone loop for the XIAO ESP32-C3's "video" partition (0x110000) at ANY symbol rate, code rate and frame size:
our fork of ESP32-DATV-SA plays header version 3 (upstream's version 2 is QPSK 3/4 normal frames at 330.579 kBd only).
Our own code (GPL): FFmpeg makes a constant-rate TS at exactly the channel's rate (whole packets per BBFRAME, so the
loop joins up), our DVB-S2 encoder (build/s2enc/s2enc --loop) makes the symbols.

    python3 tx/make_flash_loop.py VIDEO_FILE [--baud 40000] [--rate 3/4] [--frame short] [--no-pilots] [--seconds 20]
        [--start 0] [--width 320] [--fps 5] [--call SV0SYH] -o loop.bin
    esptool --chip esp32c3 write-flash 0x110000 loop.bin          (the firmware stays; the C3 plays it after a reset)

Header (4096 bytes, little endian, as video_hdr_t in the firmware): "DATVVID1", version 3, symbols, baud, data bytes
(symbols / 4 + 64), CRC32 of the data, pilots, MODCOD, flags (bit 0 short frames), 0, service[32], provider[32], frequency
[kHz], DAC amplitude, 0 (the C3 sends the loop there, whatever its panel says). Then the
symbols 4 per byte (first in the low bits, bit 0 = I > 0, bit 1 = Q > 0) and the first 64 bytes again.
The C3's actual symbol rate is 160 MHz / round(160 MHz / baud): give a baud that divides 160 MHz (40000, 50000, 100000,
125000, 250000, 333333) and receive with exactly that.
"""
import argparse
import os
import struct
import subprocess
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
S2ENC = os.path.join(ROOT, "build", "s2enc", "s2enc")
CPU_HZ = 160_000_000
QPSK_RATES = ["1/4", "1/3", "2/5", "1/2", "3/5", "2/3", "3/4", "4/5", "5/6", "8/9", "9/10"]
KBCH = {"normal": dict(zip(QPSK_RATES, [16008, 21408, 25728, 32208, 38688, 43040, 48408, 51648, 53840, 57472, 58192])),
        "short": dict(zip(QPSK_RATES[:-1], [3072, 5232, 6312, 7032, 9552, 10632, 11712, 12432, 13152, 14232]))}
PARTITION = 0x2F0000
HEADER, TAIL = 4096, 64


def frame_syms(frame, pilots):
    slots = 360 if frame == "normal" else 90
    return 90 + slots * 90 + (36 * ((slots - 1) // 16) if pilots else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video")
    ap.add_argument("--baud", type=int, default=40000)
    ap.add_argument("--rate", default="3/4")
    ap.add_argument("--frame", choices=("normal", "short"), default="short")
    ap.add_argument("--no-pilots", action="store_true")
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--start", type=float, default=0)
    ap.add_argument("--width", type=int, default=0, help="picture width (default: 320 below 100 kb/s of video, else 480)")
    ap.add_argument("--fps", type=int, default=0, help="frames per second (default: 5 below 100 kb/s of video, else 15)")
    ap.add_argument("--call", default="SV0SYH", help="service name; provider '<call> ESP32-ATV'")
    ap.add_argument("--freq", type=float, default=2340.000, help="MHz the C3 sends the loop on (2300..2450)")
    ap.add_argument("--amp", type=int, default=300, help="DAC amplitude 1..480 (300 = 0 dB, 13 = -27 dB)")
    ap.add_argument("-o", "--out", required=True)
    a = ap.parse_args()
    pilots = not a.no_pilots
    if a.rate not in KBCH[a.frame]:
        sys.exit(f"no QPSK {a.rate} with {a.frame} frames")
    sper = round(CPU_HZ / a.baud)
    if (sper - 4) // 20 < 16:
        sys.exit("symbol rate too high for the flash loop (about 333 kBd at most)")
    baud = CPU_HZ / sper                                      # what the C3 sends
    fs = frame_syms(a.frame, pilots)
    ppf = (KBCH[a.frame][a.rate] - 80) // 1504
    ts_rate = ppf * 1504 * baud / fs                          # bit/s of TS, whole packets per frame
    nframes = int(a.seconds * baud / fs) // 2 * 2            # the firmware wants an even number of frames
    nsym = nframes * fs
    if HEADER + nsym // 4 + TAIL > PARTITION:
        sys.exit(f"{nsym // 4} bytes do not fit the partition: fewer --seconds")
    video_bps = int(ts_rate * 0.70)
    width = a.width or (320 if video_bps < 100_000 else 480)
    fps = a.fps or (5 if video_bps < 100_000 else 15)
    npk = nframes * ppf
    print(f"{baud:.1f} Bd, QPSK {a.rate} {a.frame}{' pilots' if pilots else ''}: {fs} symbols and {ppf} packets per frame, "
          f"TS {ts_rate / 1e3:.1f} kb/s, video {video_bps / 1e3:.0f} kb/s {width} px {fps} fps, {nframes} frames = {nsym / baud:.1f} s")

    # the TS: H.264 constant rate, no B frames, muxed at exactly the channel rate, then cut / padded to npk packets
    tmp = a.out + ".ts"
    vf = f"scale={width}:-2,fps={fps},drawtext=text='{a.call}':x=8:y=h-th-8:fontsize=h/9:fontcolor=white:box=1:boxcolor=black@0.6"
    cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-ss", str(a.start), "-t", str(nsym / baud + 2),
           "-i", a.video, "-map", "0:v:0", "-an", "-vf", vf, "-c:v", "libx264", "-preset", "slow", "-profile:v", "main", "-bf", "0",
           "-g", str(2 * fps), "-b:v", str(video_bps), "-maxrate", str(video_bps), "-bufsize", str(video_bps // 2),
           "-x264-params", "nal-hrd=cbr:force-cfr=1", "-f", "mpegts", "-muxrate", str(int(ts_rate)), "-pcr_period", "200",
           "-pat_period", "1", "-sdt_period", "2",           # FFmpeg's 0.1 s tables would take ~45 kb/s, most of a 40 kS/s channel
           "-mpegts_flags", "+resend_headers", "-metadata", f"service_name={a.call}",
           "-metadata", f"service_provider={a.call} ESP32-ATV", tmp]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode and "drawtext" in r.stderr:                # an FFmpeg without drawtext (no libfreetype): no callsign overlay
        cmd[cmd.index(vf)] = f"scale={width}:-2,fps={fps}"
        r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit(r.stderr)
    ts = open(tmp, "rb").read()
    got = len(ts) // 188
    null = b"\x47\x1f\xff\x10" + b"\xff" * 184
    ts = ts[:npk * 188] + null * max(0, npk - got)
    open(tmp, "wb").write(ts)

    sym = a.out + ".sym"
    r = subprocess.run([S2ENC, "--rate", a.rate, "--frame", a.frame, "--loop", "--frames", str(nframes), "--format", "qpsk"]
                       + (["--pilots"] if pilots else []) + [tmp, sym], capture_output=True, text=True)
    if r.returncode:
        sys.exit(r.stderr)
    data = open(sym, "rb").read()[:nsym // 4]
    data += data[:TAIL]
    modcod = 1 + QPSK_RATES.index(a.rate)
    name = a.call.encode()[:31]
    prov = f"{a.call} ESP32-ATV".encode()[:31]
    if not 2300 <= a.freq <= 2450 or not 1 <= a.amp <= 480:
        sys.exit("--freq 2300..2450 MHz, --amp 1..480")
    hdr = struct.pack("<8s5I4B32s32sIHH", b"DATVVID1", 3, nsym, round(baud), len(data), zlib.crc32(data) & 0xFFFFFFFF,
                      int(pilots), modcod, 1 if a.frame == "short" else 0, 0, name, prov, round(a.freq * 1000), a.amp, 0)
    open(a.out, "wb").write(hdr.ljust(HEADER, b"\0") + data)
    os.remove(sym)
    if got < npk * 0.9 or got > npk * 1.25:
        print(f"warning: FFmpeg made {got} packets for {npk}: the TS rate is off (the picture will run fast or slow)")
    print(f"{a.out}: {HEADER + len(data)} bytes ({100 * (HEADER + len(data)) / PARTITION:.0f} % of the partition); "
          f"{got} TS packets from FFmpeg, {npk} used. Write it at 0x110000.")


if __name__ == "__main__":
    main()
