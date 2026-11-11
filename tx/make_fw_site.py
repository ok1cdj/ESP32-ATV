#!/usr/bin/env python3
"""The firmware part of the GitHub Pages site: ESP Web Tools manifests (also read by the app's FirmwareStore) with our
sha256 and version per part, for the ESP32-S3 receiver, the ESP32-C3 transmitter and the ESP32-C5 receiver and transmitter (6 cm).

    python3 tx/make_fw_site.py OUT_DIR [--c3 build/c3-web/out] [--c5 build/c5-web/out] [--c5tx build/c5tx-web/out]
    -> OUT_DIR/manifest.json + firmware/ (S3, from third_party/ESP32-DATV-RX/firmware)
       OUT_DIR/c3/manifest.json + firmware/ (C3, from tx/build_c3_web.sh's output; skipped when absent)
       OUT_DIR/c5/manifest.json + firmware/ (C5, from rx/c5rx/build_web.sh's output; skipped when absent)
       OUT_DIR/c5tx/manifest.json + firmware/ (C5 transmitter, from tx/c5cw/build_web.sh's output; skipped when absent)
The pages workflow runs it into public/flasher; for a test on the phone: serve OUT_DIR's parent and
`adb reverse tcp:8000 tcp:8000`, then http://127.0.0.1:8000/flasher in the app's firmware screen.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")


def write(out, name, version, chip, parts):
    os.makedirs(os.path.join(out, "firmware"), exist_ok=True)
    plist = []
    for src, offset in parts:
        dst = os.path.join("firmware", os.path.basename(src))
        shutil.copy(src, os.path.join(out, dst))
        plist.append({"path": dst, "offset": offset, "sha256": hashlib.sha256(open(src, "rb").read()).hexdigest()})
    m = {"name": name, "version": version, "new_install_prompt_erase": True, "builds": [{"chipFamily": chip, "parts": plist}]}
    json.dump(m, open(os.path.join(out, "manifest.json"), "w"), indent=2)
    print(f"{out}/manifest.json: {chip} {version}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out")
    ap.add_argument("--c3", default=os.path.join(ROOT, "build", "c3-web", "out"))
    ap.add_argument("--c5", default=os.path.join(ROOT, "build", "c5-web", "out"))
    ap.add_argument("--c5tx", default=os.path.join(ROOT, "build", "c5tx-web", "out"))
    a = ap.parse_args()
    fw = os.path.join(ROOT, "third_party", "ESP32-DATV-RX", "firmware")
    rx_ver = subprocess.run(["git", "-C", os.path.join(ROOT, "third_party", "ESP32-DATV-RX"), "describe", "--tags", "--always"],
                            capture_output=True, text=True).stdout.strip()
    write(a.out, "ATV RX receiver firmware for the ESP32-S3 (esp32-sdr-trx 1.0.0-rc4 + cs4, ESP32-DATV-RX)",
          f"1.0.0-rc4+cs4 ({rx_ver})", "ESP32-S3",
          [(os.path.join(fw, "bootloader.bin"), 0), (os.path.join(fw, "partition-table.bin"), 0x8000), (os.path.join(fw, "rx.bin"), 0x10000)])
    if os.path.exists(os.path.join(a.c3, "tx.bin")):
        ver = open(os.path.join(a.c3, "version.txt")).read().strip()
        write(os.path.join(a.out, "c3"), "ESP32-ATV transmitter firmware for the XIAO ESP32-C3 (fork of ESP32-DATV-SA, PolyForm Noncommercial)",
              ver, "ESP32-C3",
              [(os.path.join(a.c3, "bootloader.bin"), 0), (os.path.join(a.c3, "partition-table.bin"), 0x8000), (os.path.join(a.c3, "tx.bin"), 0x10000)])
    if os.path.exists(os.path.join(a.c5, "c5rx.bin")):
        ver = open(os.path.join(a.c5, "version.txt")).read().strip()
        write(os.path.join(a.out, "c5"), "ESP32-ATV 6 cm receiver firmware for the ESP32-C5 (rx/c5rx)", ver, "ESP32-C5",
              [(os.path.join(a.c5, "bootloader.bin"), 0x2000), (os.path.join(a.c5, "partition-table.bin"), 0x8000),
               (os.path.join(a.c5, "c5rx.bin"), 0x10000)])
    if os.path.exists(os.path.join(a.c5tx, "c5tx.bin")):
        ver = open(os.path.join(a.c5tx, "version.txt")).read().strip()
        write(os.path.join(a.out, "c5tx"), "ESP32-ATV 6 cm transmitter firmware for the ESP32-C5 (tx/c5cw, stored beacon only)", ver, "ESP32-C5",
              [(os.path.join(a.c5tx, "bootloader.bin"), 0x2000), (os.path.join(a.c5tx, "partition-table.bin"), 0x8000),
               (os.path.join(a.c5tx, "c5tx.bin"), 0x10000)])


if __name__ == "__main__":
    main()
