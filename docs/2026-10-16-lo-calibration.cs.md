# Potlačení úniku nosné C3 (kalibrace LO)

*16. 10. 2026 · [English](2026-10-16-lo-calibration.en.md)*

## Proč

XIAO ESP32-C3 propouští svou nosnou: tón uprostřed signálu, na naší desce asi −34 dBc majáku při AMP 300 (0 dB). S úrovní
neklesá, takže při nízké úrovni omezuje příjem. Firmware přičítá k basebandu DC korekci (i, q) v kódech DAC (`BDC`);
správná tón vynuluje. K jejímu nalezení je potřeba přijímač: XIAO ESP32-S3.

## Nová metoda: únik samotný (`BDC TEST`)

Dosud C3 vysílal maják na nízké úrovni (AMP 13) a tón se měřil proti signálu kolem. Teď firmware vysílá únik samotný:

- `BDC TEST on [f_MHz]`: bez modulace, jen DC korekce (rozložená přes vzorky symbolu, takže se uplatní i zlomky kódu),
  bez PA, na `f_MHz` nebo na kmitočtu majáku. Nikdy se neukládá, po resetu je vypnutý; 120 s po posledním příkazu `BDC`
  vysílání skončí. `BDC i q` mění korekci za běhu: zastavení a nový start vysílání PHY znovu kalibruje, což posouvalo únik
  o desetiny kódu.
- Přijímač streamuje bez přerušení; tón se měří Goertzelovým filtrem (3 biny po 20 Hz, průměr 4 oken po 0,05 s) proti
  šumu. Pozná se tak, že se mění s korekcí: S3 má vlastní čáru přesně na jmenovitém kmitočtu, na kterou se jednou chytil
  starý měřič. Jeho kmitočet se sleduje i s driftem (asi −100 Hz/s první minutu po startu C3).
- Hledání ([rx/dccal/core.py](../rx/dccal/core.py), v aplikaci
  [DcCalibrator.kt](../android/app/src/main/java/com/ok1cdj/atvrx/DcCalibrator.kt)) prokládá všemi měřeními paraboloid
  P = (d − d\*)ᵀ M (d − d\*) + F v dB (Levenberg–Marquardt) a v každém kole měří 7 bodů kolem jeho minima se zmenšujícím se
  krokem. U nuly se měření rozptylují o několik dB; fit v lineárním výkonu sledoval jen nejnižší bod a na desce v polovině
  běhů nulu ztratil.

```sh
PY=third_party/ESP32-DATV-RX/.venv/bin/python
$PY rx/cal_dc.py --mode test                        # ATV_FREQ z boards.sh, 15 s zahřátí, jen výsledek
$PY rx/cal_dc.py --mode test --boots 5 --save       # 5 startů C3, průměr do jeho tabulky a tx/cal_c3.json
$PY rx/cal_dc.py --mode test --freqs 2320,2340,2370 --boots 2 --log sweep.json
$PY rx/cal_dc.py --mode test --eval 0.5 -3.8        # kolik daná korekce ubere
```
Testy bez hardwaru: `python3 -m pytest rx/dccal` a v aplikaci `./gradlew testDebugUnitTest`. Obojí běží nad stejnými
vektory ([rx/dccal/vectors](../rx/dccal/vectors)): syntetické vysílače, vysílače jako na desce (kolísající šum, jitter)
a běhy zaznamenané na desce (`make_vectors.py --log`).

Stará metoda (`rx/cal_dc.py` bez `--mode`) je beze změny. Tentokrát jsme ji na desce nesrovnávali: C3 má ve flash smyčku
s AMP 420 a stará metoda potřebuje maják na AMP 13. 14. 10. dala +23,1 → +2,2 dB proti signálu kolem tónu, což byla mez
toho měření.

## Výsledky (16. 10. 2026, stůl, antény pár cm od sebe, 2340 MHz)

| | |
|---|---|
| tón bez korekce | 25–35 dB nad šumem S3 (v 20 Hz), asi −34 dBc vůči AMP 300 |
| jedna kalibrace | 21–56 měření, **10–25 s** (medián 20 s) plus zahřátí; stará metoda 1,5–2 min |
| v témže startu C3 | **−22 dB** medián (15…27 dB), na asi **−57 dBc**; mez je šum S3 |
| dvakrát v témže startu | 0,1–0,9 kódu od sebe (únik se minuty po startu ještě posouvá) |
| **mezi starty C3** | **nula se posune o 2,1 kódu rms** (7 startů; I −2,9…+0,5, Q −6,9…−2,8) |
| uložená hodnota v jiném startu | průměr ze 7: **2–17 dB, medián 9 dB**; stará uložená 0,576 −3,829: medián 7 dB |
| kmitočet v rámci startu | 2320–2430 MHz: nula do 0,3–0,4 kódu rms; 2310 MHz trochu jinde (měřen první) |

Co z toho plyne:

- **Únik závisí víc na startu C3 než na čemkoli jiném.** PHY při každém startu kalibruje vlastní TX DC a naše korekce jen
  odstraňuje, co po ní zbude. `CONFIG_ESP_PHY_RF_CAL_NONE` (uložená kalibrace PHY, při startu žádná) dal přes 3 starty
  1,0 místo 1,5 kódu rms – na odchylku od výchozího nastavení ESP-IDF to nestačí.
- **Na kmitočtu skoro nezáleží** (v rámci startu, 2320–2430 MHz). Drift kmitočtu i úniku má společnou příčinu, ohřev čipu,
  ale TCXO by únik nezměnil, jen kmitočet.
- Tedy: **kalibrovat těsně před vysíláním** pro plných 20 dB (aplikace to dělá s oběma deskami na hubu); uložená hodnota
  dá asi 9 dB. Tabulka v C3 (`BDC SAVE`, `BDC PUT`, až 16 kmitočtů, interpolace) funguje, ale jedna hodnota je stejně
  dobrá jako tabulka.

## V aplikaci

Menu ⋮ → *Transmitter calibration (S3 + C3)*: obě desky na USB přes hub (napájený: C3 při vysílání bere víc). Aplikace
rozliší dvě desky 303a:1001 podle sériového čísla USB (MAC), jednou zjistí, která je která, a pamatuje si to. C3 se zahřeje
(výchozí 60 s), najde se tón, proběhne hledání (živá úroveň v dB), *Save* uloží výsledek do C3 (`BDC PUT`) i do aplikace
(podle MAC a kmitočtu). Nahrání firmwaru z aplikace tabulku v C3 nechá (NVS nemaže); kdyby přesto zmizela, aplikace ji
vrátí ze své kopie. Obrazovka TX bere hodnotu C3 pro svůj kmitočet (`BDC GET f`).
