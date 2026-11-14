# DATV s ESP32-C5: 13 a 6 cm, jedna deska pro příjem i vysílání (experimentální)

*Listopad 2026 · [English](6cm-c5.en.md)*

> [!WARNING]
> **K vysílání je nutné radioamatérské oprávnění**, na 13 cm (2,3–2,45 GHz) stejně jako na 6 cm (5,65–5,85 GHz). Segment
> ATV IARU R1 na 6 cm je 5700–5720 MHz; těsně pod ním je Wi-Fi (EU kanál 140 končí na 5710 MHz). Vysílej jen pod svou
> značkou a podle národních pravidel.

ESP32-C5 je jediný čip Espressif s rádiem pro 5 GHz a má i pásmo 2,4 GHz. **Waveshare ESP32-C5-Zero** (ESP32-C5HF4, krystal
48 MHz, konektor U.FL) je vysílač i přijímač DVB-S2 pro **13 cm i 6 cm** bez dalšího RF hardwaru. S firmwarem transceiveru
**jedna deska přijímá i vysílá**: telefon přes ni vysílá i přijímá, bez přepojování desek.

```
transceiver ESP32-C5 (tx/c5cw + rx/c5rx v jednom obrazu)
   TX: živě z telefonu nebo uložená smyčka, DVB-S2 QPSK / 8PSK, 2-333 kBd
   RX: IQ estimátor -> halfband -> cs4 250 / 333 / 400 ksps po USB (protokol přijímače S3), do 250 kBd
   2300-2450 MHz (13 cm, např. 2345.000)  a  5650-5900 MHz (6 cm, např. 5715.000)
   -> Android: ATV RX/TX (obrazovka RX i TX, tatáž deska)      nebo      PC: rx/c5_play.sh, tx/tx_s2enc.py
```

**Stav**
- v0.19-beta (11. 11. 2026): přijímač a uložený maják pro 6 cm, oba firmwary z aplikace i webového flasheru.
- v0.20-beta (14. 11. 2026): živé vysílání přes C5, korekce nosné podle kmitočtu, návrat majáku jako u C3.
- Příští verze (větev `trx`): **transceiver** (jedna deska pro RX i TX), **13 cm na C5**, vlastní GPL-3 tabulky modulátoru.
  Vyzkoušeno 14. 11. 2026 ([testovací zpráva](2026-11-14-c5-transceiver.cs.md)): C5 → C5 na 13 i 6 cm při 40–250 kBd a
  spojení s parrotem IZ7PDX jedinou C5.

## Rychlý start (telefon)

1. **Firmware:** připoj C5 přes OTG, menu ⋮ → **Firmware** → *Write firmware…*, zvol *transceiver (RX + TX)* (nebo
   *receiver* pro desku, která jen přijímá). Nebo [webový flasher](https://ok1cdj.github.io/ESP32-ATV/flasher/), nebo na PC
   `tx/c5cw/flash.sh none` (transceiver) / `rx/c5rx/flash.sh` (přijímač).
2. **Příjem:** **RX**, předvolba nebo vlastní nastavení – 2345.000 MHz na 13 cm, 5715.000 MHz na 6 cm, do 250 kS/s –,
   **Start**. Aplikace C5 rozpozná sama.
3. **Živé vysílání:** **TX**, kmitočet, symbolová rychlost, QPSK nebo 8PSK, **značka**, obraz a zvuk, **Start TX**.
4. **Uložený maják:** na obrazovce TX *Save as beacon*: C5 si smyčku uloží a vysílá ji po každém zapnutí (i z nabíječky).
5. Mezi **RX** a **TX** přepínej se stejnou připojenou deskou. AMP 300 = úroveň našich testů; výstupní výkon změřený není.

## Jedna deska pro RX i TX – transceiver

`tx/c5cw` přeloží do svého obrazu i přijímač (`rx/c5rx/main/main.c` s `C5_TRX`). Jeden USB port odpovídá na textové
příkazy vysílače (jako C3) i na binární protokol přijímače (jako S3: 0xB4 na začátku řádku), takže obrazovku RX i TX
aplikace obslouží tatáž deska (INFO `ESP32DATV 1 C5 RXTX`; na hubu ji aplikace najde pro obě role). Fungují na ní i `G`,
`CAP`, `STREAM` přijímače; `F` a `ANT` nastaví obojí. Po vysílání přijímač před dalším během znovu nastaví rádio. Požadavek
přijímače drží uložený maják vypnutý.

Vyzkoušeno 14. 11. 2026 s oběma C5 s obrazem transceiveru (z PC): deska A vysílá, deska B přijímá a obráceně, na 2345
i 5715 MHz, QPSK 125k / 250k a 8PSK 250k, každá deska přepínala TX → RX i RX → TX bez resetu – vše dekódováno bez chyby TS.
Samotnou jednou C5 se podařilo spojení s parrotem IZ7PDX (OK1KCI, 2345 MHz) při 40–250 kBd z PC a při 250 kBd z telefonu
(Start TX, pak obrazovka RX): výzva odvysílána, odpověď přijata na téže desce ([testovací zpráva](2026-11-14-c5-transceiver.cs.md)).

## Vysílač

Cesta podle [fedaykinofdune/esp32-C5-sdr-tx](https://github.com/fedaykinofdune/esp32-C5-sdr-tx) (GPL-3): vendor CW
(`esp_phy_wifi_tx_tone`) naladí libovolné MHz a zapne vysílací řetězec, `phy_stop_tx_tone(1)` zastaví jeho tón a DAC pak
bere hodnotu z registru TX DC (0x600a0c08), do kterého CPU zapisuje vzorky I/Q (15 cyklů na zápis) podle čítače cyklů
s maskovanými přerušeními. Tabulky modulátoru RRC 0,35 a zlomkové hodiny vzorků jsou vlastní (`tx/common/rrc_tables.h`,
`tx/common/frac_clock.h`, GPL-3); firmware C5 nebere nic z ESP32-DATV-SA.

**Živé vysílání** (Start na obrazovce TX; na PC `tx/tx_s2enc.py` s kodérem aplikace): protokol `QPSKT` / `PSK8T` jako u C3,
telefon kóduje DVB-S2 a posílá surové symboly. QPSK s 16 vzorky na symbol při libovolné rychlosti 2–333 kBd; 8PSK s nejméně
100 cykly CPU na vzorek (S 60 při 40k, 19 při 125k, 9 při 250k), nejvýš 300 kBd. Na stole bez pozdního vzorku do 250 kBd
(QPSK 333k: 145 pozdních z 2,8 M symbolů). Z telefonu (14. 11. 2026): 40k a 66,7k QPSK se zvukem, 250k 8PSK s kamerou
a mikrofonem, 3 min se zhasnutým displejem bez chyby TS.

**Uložená smyčka** (TX → *Save as beacon*, jako u C3): smyčka DATVVID1 až ~3 MB flash, QPSK, libovolná rychlost do 333 kBd,
short i normal rámce. Spustí se hned a po každém zapnutí; libovolný bajt na USB ji zastaví a po 5 s bez příkazu se spustí
znovu (po platném příkazu – např. z aplikace – ji vrátí jen `PLAY`, `BDC`, nová smyčka nebo reset). AMP z C3 se na C5
přepočítá jako AMP × 150 / 300.

**Maják zabudovaný ve firmwaru** (PC, pro vývoj):

```sh
. ~/esp/esp-idf-v6.1/export.sh                       # ESP-IDF v6.1 s esp32c5 (install.sh esp32c5)
CALL=YOURCALL BAND=6cm tx/make_beacons.sh 125000     # -> tx/beacons/yourcall-125k-6cm (ATV6_FREQ); bez BAND: 13 cm (ATV_FREQ)
tx/c5cw/flash.sh tx/beacons/yourcall-125k-6cm        # C5_PORT z boards.sh; "-" jen přeloží; "none": bez vestavěného majáku (jako web)
```

Příkazy na USB konzoli C5 (jeden na řádek):

| Příkaz | Význam |
|---|---|
| `QPSKT` / `PSK8T f_MHz baud sps …` | živé vysílání jako u C3 (surové symboly po USB, hlášení zaplnění `B7`); sps si volí C5 |
| `PLAY` | uložená smyčka, jinak vestavěný maják, jako po zapnutí |
| `VIDW …`, `INFO` | uložení smyčky, identifikace desky: jako u C3 (*Save as beacon* v aplikaci) |
| `BEACON [amp] [S] [dci dcq]` | vestavěný maják na kmitočtu `F`: amp = nejvyšší \|I\|, \|Q\| v kódech DAC (výchozí 150), S vzorků na symbol (výchozí 16); libovolný bajt ho zastaví |
| `BDC …` | korekce úniku nosné podle kmitočtu jako u C3: `GET [f]`, `i q`, `SAVE`, `PUT f i q`, `LIST`, `RESET`, `TEST on [f]` / `off` |
| `DC [i q]` | jediná korekce úniku nosné, použije se tam, kde tabulka `BDC` nemá záznam; uložená ve flash |
| `F 2345.000` | kmitočet v MHz (2300–5900), pro vysílač i přijímač |
| `G n`, `CAP n`, `STREAM …` | přijímač: zisk 0–89, záznam při 2 MS/s, stream (transceiver; jako u `rx/c5rx`) |
| `CW [i q]` / `TONE kHz [amp]` | nosná / jeden tón pro spektrální analyzátor |
| `SCALE n`, `ANT 1\|0`, `OFF`, `ST` | měřítko DAC (výchozí 15), U.FL / čipová anténa, vypnout, stav |

**Únik nosné** se mění s každým startem C5 a přijímač cs4 ho odstraní jen tehdy, když leží do ~±0,5 kHz od svého LO. Je asi
1,5 kódu DAC (≈ −40 dB pod špičkou majáku) na obou pásmech. Korekci najde `tx/c5_dccal.py` se dvěma C5 na PC (vysílač
vysílá `CW i q`, přijímač měří):

```sh
third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py --freq 2345 --save   # 13 cm; --freq 5715 pro 6 cm; --beacon spustí maják
```

Uložená smyčka, vestavěný maják i živé vysílání berou korekci pro svůj kmitočet z tabulky `BDC`. Z telefonu C5 zatím
kalibrovat nejde.

**Výstupní výkon změřený není:** vysílač nikdy nebyl kabelem připojen ke spektrálnímu analyzátoru. Vzduchem na 5710 MHz
(tinySA Ultra s anténou u desky, jen orientačně): nosná asi −8 dBm na analyzátoru, únik LO 39 dBc, zrcadlo 42 dBc, obrazy
vzorkování (S=16) ~34 dBc. **Krystal se při vysílání ohřívá:** na 6 cm asi +100 kHz (19 ppm) během pár minut od studeného
startu. Desku napájej přímo z počítače nebo telefonu, ne přes hub nebo powerbanku (u C3 to kazilo MER).

## Přijímač

C5 nemá cestu pro záznam jako S3. Firmware čte **IQ estimátor** (průběžné součty 10bitového ADC při 80 MHz): rozdíl dvou
čtení je průměr vzorků mezi nimi. Čte ho v pevném taktu na dvojnásobku výstupní rychlosti, halfband FIR decimuje 2× a výstup
jde jako **cs4** (4bitové I/Q se ziskem v hlavičce paketu) v paketech přijímače S3 po USB Serial/JTAG. Mluví binárním
protokolem S3, takže `espdr nb.py` i aplikace ho ovládají beze změn. Stejný přijímač je v obou firmwarech (samotný `rx/c5rx`
i v transceiveru).

| Výstup | Čtení | Cyklů na čtení | Symbolová rychlost |
|---|---|---|---|
| 250 ksps | 500 k/s | 480 | do 148 kBd |
| 333 ksps | 667 k/s | 360 | do 197 kBd |
| 400 ksps (`NB_SET_DECIM 25`) | 800 k/s | 300 | do 250 kBd |

Pro 400 ksps čte firmware estimátor jen dvakrát (I, pak Q; Q o 75 ns později, zrcadlo −28 dB na okraji pásma) a používá
kratší halfband (19 koeficientů, −40 dB). 500 ksps CPU nestíhá, cs8 nestíhá USB.

```sh
rx/c5_play.sh 125000 2345.000          # PC: SR (do 250000), kmitočet v MHz (výchozí ATV6_FREQ); GAIN=0..89, CR=3/4, FRAMES=short
```

Krystaly dvou C5 se na 5,7 GHz liší až o ~150 kHz (na 2,3 GHz naměřeno +13…+29 kHz), proto `rx/c5_play.sh` nejdřív zachytí
signál při 2 MS/s a naladí se na střed nejsilnějšího signálu v ±500 kHz; aplikace hledá v ±300 kHz.

## Naměřeno (14. 11. 2026, z PC, QPSK 3/4 short rámce, pokud není uvedeno jinak)

| Test | Výsledek |
|---|---|
| C5 → C5, 2345 MHz, 40k / 50k / 66,7k / 100k / 125k / 250k QPSK, 250k 8PSK 2/3 | vše dekódováno, 0 chyb TS, žádný pozdní vzorek TX |
| C5 → C5, 5715 MHz, 40k / 125k / 250k QPSK, 125k / 250k 8PSK | vše dekódováno, 0 chyb TS |
| jeden transceiver: TX → RX a RX → TX bez resetu (2345 / 5715 MHz) | vše dekódováno |
| spojení s parrotem IZ7PDX (OK1KCI, 2345 MHz), jedna C5 | 40k–250k: výzvu parrot dekódoval (MER 15–21 dB), odpověď přijata bez chyby TS; 125k jen se short rámci; 333k jen směrem k parrotu |

Podrobnosti: [testovací zpráva 14. 11. 2026](2026-11-14-c5-transceiver.cs.md).

## Android

Aplikace rozpozná přijímač C5 podle příznaků `CTL_INFO 3` a transceiver podle jeho řádku INFO a zapamatuje si je: vždy cs4,
250 ksps do 148 kS/s, 333 ksps do 197 kS/s a 400 ksps do 250 kS/s. C5 bere na 2300–2450 i 5650–5900 MHz (S3 jen na 13 cm).
Předvolby TX *… 6 cm … (C5)* jsou pro 5715 MHz; na 13 cm C5 bere předvolby pro 2345 MHz stejně jako C3.

**LED** na C5-Zero: tlumeně modrá = firmware běží a čeká, zelená = příjem, červená = vysílání, zhasnutá = ROM bootloader
(reset). C5 **nemá výstup pro PA** (`PA 0` odpoví jako C3, `PA 1` odmítne – PA enable C3 je GPIO3).

## Známé problémy

- **Na krátkou vzdálenost horší příjem než na delší:** přebuzení přijímače (pevný zisk, 0 dB v aplikaci = index 30 z 0–89).
  Zatím pomůže snížit zisk; AGC se chystá.
- C5 po připojení k telefonu občas skončí v ROM bootloaderu (stačí reset).
- Korekce nosné C5 se nastavuje na PC (`tx/c5_dccal.py --save`); kalibrace vysílače v aplikaci je jen pro C3 a S3.
- Po mnoha bězích bez restartu jednou únik nosné vysílače znemožnil jakékoli dekódování (i uložené smyčky); pomohl restart
  C5. Když příjem selže při čistém spektru, nastav korekci `tx/c5_dccal.py --save`.
- Drift krystalů: aplikace a `rx/c5_play.sh` ho dohledají, při ohřevu vysílače ale může signál ujet z okna.
- Přijímač C5 bere nejvýš 250 kS/s: odpověď na 333k (např. od parrotu IZ7PDX) přijmout nejde.
