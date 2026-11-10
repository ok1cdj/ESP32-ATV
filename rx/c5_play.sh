#!/bin/sh
# Receive DVB-S2 on 6 cm with the ESP32-C5 receiver (rx/c5rx): C5 -> espdr nb.py (cs4) -> leandvb (+ ldpc_tool) -> ffplay.
#   rx/c5_play.sh [SR [FREQ_MHz]]        defaults 125000 and ATV6_FREQ (boards.sh, the tx/c5cw beacon)
# Environment: GAIN (C5 RX gain index 0..89, default 30), CR (3/4), FRAMES (normal | short), SECONDS (0: until the window closes),
# SEARCH=0 to tune to FREQ_MHz exactly, PORT (C5RX_PORT from boards.sh).
# The two boards' 48 MHz crystals differ by up to about 150 kHz at 5.7 GHz (the transmitter warms up by ~100 kHz), more than
# the 250 / 333 ksps stream leaves: the script first captures 16 ms at 2 Msps (text command CAP) and tunes to the centre of the
# strongest signal within +-500 kHz of FREQ_MHz.
# The C5 streams cs4 at 250 or 333 ksps only (500 ksps does not keep up), so SR is at most about 197 kS/s.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RX="$ROOT/third_party/ESP32-DATV-RX"
PY="$RX/.venv/bin/python"
. "$ROOT/boards.sh"
SR=${1:-125000}
FREQ=${2:-$ATV6_FREQ}
PORT=${PORT:-$C5RX_PORT}
GAIN=${GAIN:-30}
CR=${CR:-3/4}
FRAMES=${FRAMES:-normal}
SECONDS_=${SECONDS:-0}
# 250 ksps passes +-100 kHz, 333 ksps +-133 kHz (rx/c5rx/tools/design_taps.py); the signal is SR * 1.35 wide
if [ "$SR" -le 148000 ]; then DECIM=4; FS=250000
elif [ "$SR" -le 197000 ]; then DECIM=3; FS=333333
else echo "SR $SR: the C5 receiver takes up to 197000 (cs4 at 333 ksps)"; exit 1; fi
case "$FRAMES" in normal) FS_MASK=1 ;; short) FS_MASK=2 ;; *) echo "FRAMES: normal or short"; exit 1 ;; esac
MODCOD=$(case "$CR" in 1/4) echo 1;; 1/3) echo 2;; 2/5) echo 3;; 1/2) echo 4;; 3/5) echo 5;; 2/3) echo 6;; 3/4) echo 7;;
         4/5) echo 8;; 5/6) echo 9;; 8/9) echo 10;; 9/10) echo 11;; *) echo 0;; esac)
[ "$MODCOD" -gt 0 ] || { echo "CR $CR: not a DVB-S2 QPSK code rate"; exit 1; }

OFF=0
if [ "${SEARCH:-1}" != 0 ]; then
    OFF=$("$PY" - "$PORT" "$FREQ" "$GAIN" <<'EOF'
import sys, time
import numpy as np
import serial
port, freq, gain = sys.argv[1], sys.argv[2], sys.argv[3]
s = serial.Serial(port, 115200, timeout=0.3)
s.write(b"\n")                                     # ends a run left streaming
time.sleep(0.3)
s.reset_input_buffer()
for c in (f"F {freq}", f"G {gain}"):
    s.write(c.encode() + b"\n")
    time.sleep(0.3)
s.reset_input_buffer()
s.write(b"CAP 32768\n")
hdr, end = b"", time.time() + 5
while time.time() < end:                           # skip what is left of the answers to F and G
    hdr += s.read(1)
    if hdr.endswith(b"\n"):
        if hdr.startswith(b"CAPD"):
            break
        hdr = b""
p = hdr.split()
if len(p) < 3 or p[0] != b"CAPD":
    sys.exit("no capture from the C5 (is rx/c5rx flashed?)")
n, rate = int(p[1]), int(p[2])
data = b""
while len(data) < 4 * n and time.time() < end + 10:
    data += s.read(4 * n - len(data))
x = np.frombuffer(data, "<i2").astype(float).reshape(-1, 2)
z = x[:, 0] + 1j * x[:, 1]
z -= z.mean()
S = np.abs(np.fft.fftshift(np.fft.fft(z * np.hanning(len(z))))) ** 2
f = np.fft.fftshift(np.fft.fftfreq(len(z), 1 / rate))
S = np.convolve(S, np.ones(256) / 256, "same")   # ~16 kHz smoothing: the shape, not the carrier leakage
m = np.abs(f) < 500e3
P = 10 * np.log10(S[m] / S[m].max())
if np.median(P) > -10:                            # nothing above the noise: no correction
    print(0)
    print("no signal found: tuning to the frequency given", file=sys.stderr)
else:
    k = np.argmax(P)                               # the -6 dB band around the strongest point
    a = k
    while a > 0 and P[a - 1] > -6: a -= 1
    b = k
    while b < len(P) - 1 and P[b + 1] > -6: b += 1
    fc = (f[m][a] + f[m][b]) / 2
    print(int(round(fc)))
    print(f"signal {f[m][b] - f[m][a]:.0f} Hz wide (-6 dB), centre {fc / 1e3:+.1f} kHz from {freq} MHz", file=sys.stderr)
EOF
)
fi
FHZ=$("$PY" -c "print(int(round($FREQ * 1e6 + $OFF)))")
echo "C5 $PORT: LO $(echo "$FHZ" | awk '{printf "%.3f", $1 / 1e6}') MHz, SR $SR, $FS sps cs4, gain $GAIN" >&2
SAMPLER=
[ "$SR" -lt 100000 ] && SAMPLER="--sampler rrc --rrc-rej 50"   # as rx/raw2cf32.py
(cd "$RX/esp32-sdr-trx/src" && exec "$PY" -W ignore -m espdr.nb -p "$PORT" run --freq "$FHZ" --decim "$DECIM" --format cs4 \
    --gain "$GAIN" --seconds "$SECONDS_" --convert cf32 -o -) |
    "$RX/build/leansdr/src/apps/leandvb" --f32 --float-scale 512 -f "$FS" --sr "$SR" --standard DVB-S2 --const QPSK --cr "$CR" \
        --fastdrift --modcods $((1 << MODCOD)) --framesizes "$FS_MASK" --ldpc-helper "$RX/build/LDPC/ldpc_tool" $SAMPLER |
    ffplay -autoexit -hide_banner -loglevel error -window_title "$(echo "$FHZ" | awk '{printf "%.3f", $1 / 1e6}') MHz, ESP32-C5 receiver" \
        -fflags nobuffer -i -
