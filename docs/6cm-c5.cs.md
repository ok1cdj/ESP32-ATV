# DATV na 6 cm s ESP32-C5 (experimentální)

*Listopad 2026 · [English](6cm-c5.en.md)*

> [!WARNING]
> **Vysílání na 6 cm (5,65–5,85 GHz) vyžaduje koncesi radioamatéra.** Segment ATV IARU Region 1 je 5700–5720 MHz; těsně
> pod ním je Wi-Fi (EU kanál 140 končí na 5710 MHz). Vysílejte jen se svou značkou a podle národních pravidel.

ESP32-C5 je jediný čip Espressifu s rádiem na 5 GHz. Dvě desky **Waveshare ESP32-C5-Zero** (ESP32-C5HF4, krystal 48 MHz,
konektor U.FL) tvoří vysílač a přijímač DVB-S2 na **5715,000 MHz** bez jakéhokoli dalšího RF hardwaru:

```
ESP32-C5 (tx/c5cw: maják DVB-S2 QPSK 3/4, 125 / 250 kBd, 5715.000 MHz)
   ~ 6 cm ~>
ESP32-C5 (rx/c5rx: IQ estimátor -> halfband -> cs4 250 / 333 / 400 ksps přes USB, protokol přijímače S3)
   -> Android: ATV RX/TX (rozpozná C5 sám)      nebo      PC: rx/c5_play.sh (espdr nb.py, leandvb, ffplay)
```

**Stav:** vydáno ve v0.19-beta (11. 11. 2026): oba firmwary z aplikace i webového flasheru, předvolby v aplikaci; v0.20-beta (14. 11. 2026): živé vysílání přes C5, korekce nosné podle kmitočtu, návrat majáku.
10. 11. 2026: maják z C5 dekódován druhým
C5 na PC i v telefonu (zámek, obraz, MER 11–17 dB). 11. 11. 2026: 250 kBd přes 400 ksps na PC i v telefonu (3 m od sebe,
nalepovací antény z balení: MER 13–17 dB, 319 rámců z 320 bez chyb, žádné ztracené USB pakety).

**Vysílač C5 umí totéž co C3:** uloží si smyčku natočenou v telefonu (záložka TX → *Save as beacon*) a po zapnutí ji sám
vysílá, a **Start TX vysílá živě z telefonu** (kamera, testovací obrazec, soubor; QPSK nebo 8PSK) – novinka ve v0.20-beta,
přijato na vzduchu přijímačem C5 (z PC i z telefonu).

## Vysílač – `tx/c5cw`

Cesta podle [fedaykinofdune/esp32-C5-sdr-tx](https://github.com/fedaykinofdune/esp32-C5-sdr-tx) (GPL-3): vendor CW
(`esp_phy_wifi_tx_tone`) naladí libovolné MHz a zapne vysílací řetězec, `phy_stop_tx_tone(1)` zastaví jeho tón a DAC pak
bere hodnotu z registru TX DC (0x600a0c08), do kterého CPU zapisuje vzorky I/Q (15 cyklů na zápis) podle čítače cyklů
s maskovanými přerušeními. Maják používá tabulky modulátoru QPSK s RRC 0,35 z ESP32-DATV-SA (`qpsk_lut.h`).

**Maják z telefonu** (jako u C3): nahrajte firmware vysílače (aplikace: menu → Firmware, *6 cm transmitter*; nebo
`tx/c5cw/flash.sh none`), připojte C5 do telefonu, na záložce TX zadejte 5715.000 MHz, symbolovou rychlost (do 250 kS/s pro
přijímač C5) a značku a dejte *Save as beacon*. Smyčka (DATVVID1 jako u C3, až ~3 MB flash) se hned spustí a pak po každém
zapnutí; libovolný bajt na jejím USB ji zastaví a stejně jako u C3 se po 5 s bez příkazu spustí znovu (po platném příkazu –
např. z aplikace – ji vrátí jen `PLAY`, `BDC`, nová smyčka nebo reset). AMP z C3 se na C5 přepočítá jako AMP × 150 / 300
(AMP 300 = úroveň použitá při těchto testech; výstupní výkon nebyl měřen). 11. 11. 2026: smyčka uložená z telefonu (250 kS/s, short rámce) přijatá
přijímačem C5 v telefonu, MER ~17 dB, 1084 rámců bez chyby z 1088.

**Živé vysílání** (Start na záložce TX, nebo na PC `third_party/ESP32-DATV-SA/host/tx_dvbs.py`): protokol `QPSKT` / `PSK8T`
jako u C3, telefon kóduje DVB-S2 a posílá surové symboly. QPSK s 16 vzorky na symbol při libovolné rychlosti 2–333 kBd
(stůl, vždy 8 s: 40k, 125k, 250k bez jediného pozdního vzorku; 333k 145 pozdních z 2,8 M symbolů); 8PSK s nejméně 100 cykly
CPU na vzorek (S 60 při 40k, 19 při 125k, 9 při 250k, nejvýš 300 kBd), na stole bez pozdních vzorků. Přijímač C5 bere do 250 kS/s.
13. 11. 2026: symboly z kodéru aplikace (`tx/tx_s2enc.py`, short rámce) poslané živě a přijaté přijímačem C5 na PC
(`rx/c5_play.sh`): QPSK 3/4 při 40k, 125k a 250k a 8PSK 2/3 při 125k a 250k dekódováno, služba OK1CDJ, H.264 640×360.
14. 11. 2026 z telefonu (Start TX): 40k a 66,7k QPSK se zvukem, 250k 8PSK 2/3 s kamerou a mikrofonem (3 min se zhasnutým
displejem: 56127 TS paketů, žádné přerušení kontinuity); smyčka 40k uložená přes *Save as beacon* se spustí i z nabíječky.

**Maják zabudovaný ve firmwaru** (PC, pro vývoj):

```sh
. ~/esp/esp-idf-v6.1/export.sh                       # ESP-IDF v6.1 s podporou esp32c5 (install.sh esp32c5)
CALL=YOURCALL BAND=6cm tx/make_beacons.sh 125000     # -> tx/beacons/yourcall-125k-6cm (ATV6_FREQ z boards.sh)
tx/c5cw/flash.sh tx/beacons/yourcall-125k-6cm        # C5_PORT z boards.sh; "-" jen přeloží; "none": bez vestavěného majáku
```

Příkazy na USB konzoli C5 (jeden na řádek):

| Příkaz | Význam |
|---|---|
| `BEACON [amp] [S] [dci dcq]` | maják na kmitočtu `F`: amp = nejvyšší \|I\|, \|Q\| v kódech DAC (výchozí 150), S vzorků na symbol (výchozí 16: 2 MS/s při 125 kBd, 4 MS/s při 250 kBd), dci dcq = korekce úniku nosné; libovolný bajt ho zastaví |
| `PLAY` | uložená smyčka, jinak vestavěný maják, jako po zapnutí |
| `VIDW …`, `INFO` | uložení smyčky, identifikace desky: jako u C3 (*Save as beacon* v aplikaci) |
| `QPSKT` / `PSK8T f_MHz baud sps …` | živé vysílání jako u C3 (surové symboly po USB, hlášení zaplnění `B7`); sps si volí C5 |
| `BDC …` | korekce úniku nosné podle kmitočtu jako u C3: `GET [f]`, `i q`, `SAVE`, `PUT f i q`, `LIST`, `RESET`, `TEST on [f]` / `off` |
| `DC [i q]` | jediná korekce úniku nosné, použije se tam, kde tabulka `BDC` nemá záznam; uložená ve flash |
| `F 5715.000` | kmitočet v MHz (2300–5900) |
| `CW [i q]` / `TONE kHz [amp]` | nosná / jeden tón pro spektrální analyzátor |
| `SCALE n`, `ANT 1\|0`, `OFF`, `ST` | měřítko DAC (výchozí 15), U.FL / čipová anténa, vypnout, stav |

**Únik nosné** se mění s každým startem C5 a přijímač cs4 ho odstraní jen tehdy, když leží do ~±0,5 kHz od svého LO.
Korekci najde `tx/c5_dccal.py` (vysílač vysílá `CW i q`, přijímač měří; potřebuje obě C5 na PC):

```sh
third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py --beacon      # najde dci dcq a spustí BEACON 150 16 dci dcq
third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py --save        # ... a uloží je pro ten kmitočet (BDC PUT)
```

Uložená smyčka, vestavěný maják i živé vysílání berou korekci pro svůj kmitočet z tabulky `BDC`. Z telefonu C5 zatím
kalibrovat nejde (kalibrace v aplikaci je jen pro C3 a S3 na 13 cm).

**Výstupní výkon nebyl měřen:** vysílač nikdy nebyl připojen ke spektrálnímu analyzátoru kabelem. Přes antény (tinySA
Ultra s anténou u desky, úrovně jen hrubě orientační, nejde o výkon vysílače): nosná asi −8 dBm na vstupu analyzátoru na
5710 MHz; spolehlivější jsou relativní údaje: únik LO 39 dBc, zrcadlo 42 dBc,
obrazy vzorkování (S=16) ~34 dBc. **Krystal se vysíláním ohřívá:** od studeného startu ~+100 kHz (19 ppm) během několika
minut. Napájejte vysílač přímo z počítače, ne přes hub nebo powerbanku (u C3 to kazilo MER).

## Přijímač – `rx/c5rx`

C5 nemá dump cestu jako S3. Firmware čte **IQ estimátor** (průběžné součty 10bitového ADC na 80 MHz): rozdíl dvou čtení je
boxcar přes vzorky mezi nimi. Čte se v pevném taktu na dvojnásobku výstupní rychlosti, halfband FIR decimuje 2× a výstup jde
jako **cs4** (4 bity I/Q se ziskem v hlavičce paketu) v paketech přijímače S3 přes USB Serial/JTAG. Umí i binární řídicí
protokol S3, takže ho bez úprav ovládá `espdr nb.py` i aplikace.

| Výstup | Čtení | Cyklů na čtení | Symbolová rychlost |
|---|---|---|---|
| 250 ksps | 500 k/s | 480 | do 148 kBd |
| 333 ksps | 667 k/s | 360 | do 197 kBd |
| 400 ksps (`NB_SET_DECIM 25`) | 800 k/s | 300 | do 250 kBd |

Pro 400 ksps čte firmware estimátor jen dvakrát (I, pak Q; Q se zpozdí o 75 ns, zrcadlo −28 dB na kraji pásma)
a použije kratší halfband (19 tapů, −40 dB). 500 ksps nestíhá CPU a cs8 nestíhá USB.

```sh
rx/c5rx/flash.sh                       # C5RX_PORT z boards.sh
rx/c5_play.sh 125000                   # PC: SR (do 250000), volitelně kmitočet v MHz; GAIN=0..89, CR=3/4, FRAMES=short
```

Krystaly dvou C5 se na 5,7 GHz liší až o ~150 kHz, proto `rx/c5_play.sh` nejdřív zachytí 16 ms při 2 MS/s a naladí se na
střed nejsilnějšího signálu v ±500 kHz.

C5 **nemá výstup pro PA** (`PA 0` odpoví jako C3, `PA 1` odmítne – PA enable C3 je GPIO3).

**LED** na C5-Zero: tlumeně modrá = firmware běží a čeká, zelená = příjem, červená = vysílání, zhasnutá = ROM bootloader
(resetujte).

## Android

Oba firmwary jde nahrát z telefonu: menu → Firmware se u ESP32-C5 zeptá, jestli bude přijímač, nebo vysílač pro 6 cm (nebo
webový flasher, který má tlačítko pro každý).

Aplikace přijímač C5 pozná podle příznaků v `CTL_INFO 3` a zapamatuje si ho: vždy cs4, 250 ksps do 148 kS/s, 333 ksps
do 197 kS/s a 400 ksps do 250 kS/s (s firmwarem, který to umí). Hledání signálu sahá do ±300 kHz. Kmitočet zadejte
5715.000 MHz.

## Známé problémy

- **Na krátkou vzdálenost horší příjem než na delší:** přebuzení přijímače (pevný zisk, 0 dB v aplikaci = index 30 z 0–89).
  Zatím pomůže snížit zisk; AGC se chystá.
- Obě C5 po připojení k telefonu občas skončí v ROM bootloaderu (stačí reset).
- Korekce nosné C5 se nastavuje na PC (`tx/c5_dccal.py --save`), ne z telefonu.
- Po mnoha bězích bez restartu jednou únik nosné vysílače znemožnil jakékoli dekódování (i uložené smyčky); pomohl restart
  C5. Když příjem selže při čistém spektru, nastavte korekci `tx/c5_dccal.py --save`.
- Drift krystalů: aplikace a `rx/c5_play.sh` ho dohledají, při ohřevu vysílače ale může signál ujet z okna.
