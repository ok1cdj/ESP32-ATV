# ESP32-ATV – fáze 0 (POC)

Cílem je terestriální DATV maják na 13 cm (značka **SV0SYH**). Ve fázi 0 ověřujeme, že řetězec

```
XIAO ESP32-C3 (ESP32-DATV-SA, DVB-S2 QPSK 3/4, 2300.500 MHz)
   → vzduch →
XIAO ESP32-S3 Plus (ESP32-DATV-RX firmware: radio dump 16 MS/s → 500 ksps cs4 / 333 ksps cs8, USB-Serial-JTAG)
   → rx_dvbs2.py → leandvb (+ ldpc_tool) → ffplay
```

dává živý obraz testkarty. **Stav a další kroky: [docs/STAV.md](docs/STAV.md).**

| Část | Projekt | Licence |
|---|---|---|
| TX | [barisdinc/ESP32-DATV-SA](https://github.com/barisdinc/ESP32-DATV-SA) (`third_party/`) | PolyForm Noncommercial |
| RX | [barisdinc/ESP32-DATV-RX](https://github.com/barisdinc/ESP32-DATV-RX) (`third_party/`) | BSD-3 / 0BSD, leansdr GPL-3 |

## Forky
Oba projekty v `third_party/` jsou naše forky: větev **`esp32-atv`** s našimi commity, původní repo je remote `upstream`.
- ESP32-DATV-SA je publikovaný jako [ok1cdj/ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA) (remote `origin`,
  URL v `.gitmodules`).
- ESP32-DATV-RX zatím jen lokálně – URL v `.gitmodules` ukazuje na upstream, čerstvý klon naše commity nedostane.
Aktualizace z upstreamu: `git -C third_party/<repo> fetch upstream && git -C third_party/<repo> rebase upstream/main`.

| Fork | Změny |
|---|---|
| ESP32-DATV-SA | maják 40–500 kS/s (tabulka rychlostí, výchozí rychlost z `BEACON_BAUD`, `make_beacon.py`); DC/IQ korekce majáku (`--cal`, příkaz `BDC`) |
| ESP32-DATV-RX | `rx_dvbs2.py --sampler auto/linear/rrc`, `--rrc-rej` (RRC pod 100 kS/s), `--decimate` (experimentální) |

## Jednorázová příprava
```sh
git submodule update --init --recursive
sh third_party/ESP32-DATV-RX/tools/install.sh      # venv (espdr, esptool), leandvb, ldpc_tool
sudo cp third_party/ESP32-DATV-RX/esp32-sdr-trx/udev/70-espdr.rules /etc/udev/rules.d/ \
  && sudo udevadm control --reload && sudo udevadm trigger   # ModemManager nesmí sahat na port (každý bajt zastaví stream)
# ESP-IDF pro C3 (v6.1; SA uvádí 6.2.0, ta zatím nemá release tag)
git clone --depth 1 -b v6.1 --recursive --shallow-submodules https://github.com/espressif/esp-idf ~/esp/esp-idf-v6.1
~/esp/esp-idf-v6.1/install.sh esp32c3
```

## Vysílač – XIAO ESP32-C3
```sh
tx/make_beacons.sh                 # testkarta + SDT se značkou SV0SYH → tx/beacons/sv0syh-333k/
. ~/esp/esp-idf-v6.1/export.sh
tx/flash_tx.sh                     # build v build/tx-firmware (submodul zůstává čistý) + flash /dev/ttyACM0
```
Kontrola: na sériové konzoli C3 příkaz `INFO` → 2300.500 MHz, 333000 Bd.

Nižší rychlosti majáku (náš fork, větev `esp32-atv`):
```sh
tx/make_beacons.sh 66666          # → tx/beacons/sv0syh-67k/   (možné: 40000 50000 66666 100000 125000 250000 333000 400000 500000)
tx/flash_tx.sh tx/beacons/sv0syh-67k
```
Smyčka běží na 8 MS/s s celým počtem 16–232 vzorků/symbol, proto jen tyto rychlosti (nejníž 40 kS/s, S = 200).
Pod 125 kS/s má smyčka 2 PLFRAMEs, aby se tabulky modulátoru (256 B × S) vešly na heap – na HW ověřit, že nehlásí `ERR beacon no memory`.
Alternativa bez reflashe: PC režim `tx/tx_pc.sh 33000 --port /dev/ttyACM0`.

### DC korekce (únik nosné)
C3 má únik nosné (tón uprostřed signálu), který s úrovní neklesá → pod ~−20 dB příjem končí. Kalibrace naším přijímačem:
```sh
AMP=13 tx/make_beacons.sh 40000 && tx/flash_tx.sh tx/beacons/sv0syh-40k   # nízká úroveň: únik je dobře vidět
third_party/ESP32-DATV-RX/.venv/bin/python rx/cal_dc.py --save            # BDC i q → měření → fit; uloží do C3 (NVS) i tx/cal_c3.json
```
`tx/make_beacons.sh` pak `tx/cal_c3.json` zapéká do každého majáku. Ručně: příkaz `BDC [i q | SAVE | RESET]` na USB C3.

## Přijímač – XIAO ESP32-S3 Plus (jeden USB-C port)
1. U.FL anténa. BOOT držet, RESET ťuknout, BOOT pustit.
2. `rx/flash_rx_xiao.sh` – firmware přijímače natrvalo do flash. Po resetu: `lsusb | grep 303a:1001`.
3. Příjem:
   - `rx/rx.sh` – maják 333 kS/s, režim `--wide` (500 ksps, 4 bit)
   - `rx/rx.sh 125000` – 125 kS/s (333 ksps, 8 bit)
   - `rx/rx.sh 66666` / `50000` / `40000` – nízké rychlosti: 8 bit, `rx_dvbs2.py` pod 100 kS/s zapne RRC sampler leandvb (`--sampler`);
     první obraz po ~32 rámcích (66 kS/s ≈ 16 s), s `--ldpc bitflip` hned, ale o ~5 dB méně citlivé
   - doplňky: `--gain 10` (výchozí; při velkém signálu méně), `--ppm X`, `--record x.ts`, `-v`
4. Volitelně spektrum: `rx/spectrum.sh` a SDR++ / SDRangel na `127.0.0.1:1234` (rtl_tcp).

Dvouportová deska upstreamu není nutná: stream jde vždy nativním USB, UART bridge sloužil jen k nahrávání do RAM.
Když firmware běží z flash, `board.ensure()` ho najde a nic nenahrává.

## Test bez hardwaru
```sh
PY=third_party/ESP32-DATV-RX/.venv/bin/python
$PY rx/sim_beacon.py tx/beacons/sv0syh-333k/beacon.bin -o build/beacon_ref.cf32 --loops 20 --sps 3 --baud 333333.3333
$PY third_party/ESP32-DATV-RX/tools/sim_wide.py build/beacon_ref.cf32 --fs-in 1000000 --fs-out 500000 --sr 333333 \
    --bits 4 --esn0 9 --repeat 1 --ldpc-helper third_party/ESP32-DATV-RX/build/LDPC/ldpc_tool
```
Výsledky měření: [docs/phase0-log.md](docs/phase0-log.md).
