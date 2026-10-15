# Krátké rámce DVB-S2 pro maják s nízkou rychlostí

*15. 10. 2026 · [English](2026-10-15-short-frames.en.md)*

## Proč

Přijímač dekóduje LDPC programem [ldpc_tool](https://github.com/Blockstream/LDPC), který pracuje po dávkách 32 rámců.
Při nízké symbolové rychlosti se dávka normálních rámců (64800 bitů) plní dlouho, a první obraz proto přijde pozdě:

| Symbolová rychlost | Normální PLFRAME | Dávka 32 | Krátký PLFRAME | Dávka 32 |
|---|---|---|---|---|
| 40 kS/s | 0,83 s | 27 s | 0,21 s | 6,7 s |
| 50 kS/s | 0,67 s | 21 s | 0,17 s | 5,4 s |
| 66,7 kS/s | 0,50 s | 16 s | 0,13 s | 4,0 s |

(QPSK s piloty: 33282 symbolů na normální PLFRAME, 8370 na krátký.)

Krátké rámce (16200 bitů) jsou 4× kratší, takže se dávka naplní 4× dřív. Totéž platí pro vše, co čeká na rámec:
zachycení rámcové synchronizace po úniku, hlídání ztráty signálu, údaj MER. Cenou je nižší užitečná rychlost – maják
dává do rámce jen celé TS pakety, při QPSK 3/4 7 do krátkého proti 32 do normálního, tedy o 13 % méně při stejné
symbolové rychlosti (asi 4 %, kdyby pakety přecházely přes hranici rámců) – a teoreticky pár desetin dB kódového zisku.

## Použití

Vysílač (XIAO ESP32-C3, fork [ok1cdj/ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA)):
```sh
FRAME=short tx/make_beacons.sh 40000          # -> tx/beacons/<značka>-40k-short/  (také 50000, 66666, …)
tx/flash_tx.sh tx/beacons/<značka>-40k-short
```
Testkarta píše „DVB-S2 QPSK 3/4 short 40 kS/s“, přední panel „S2 QPSK 3/4 SF“; příkaz `INFO` na C3 ukáže velikost
rámců. Smyčka drží 8 krátkých rámců pod 66 kS/s, 12 pod 100 kS/s a 16 nad tím (8 krátkých zabere RAM jako 2 normální).

Přijímač:
- Aplikace pro Android: předvolby *Beacon 40k / 50k / 66.7k QPSK 3/4 short*, případně velikost rámců *short* /
  *normal+short*.
- PC: `rx/rx.sh 40000 --frame short` (`--frame auto` bere obojí).
- Citlivost na stole: `FRAME=short rx/level_sweep.sh 40000 120 300 95 30 13`.

## Simulace

`rx/sim_frames.py` projde celý řetězec bez hardwaru: maják z `tx/make_beacons.sh` → `rx/sim_beacon.py` (RRC 0,35,
offset nosné) → `tools/sim_wide.py` z ESP32-DATV-RX (AWGN pro dané Es/N0, převzorkování na 333 ksps přijímače, 8 bitů)
→ leandvb (RRC sampler) + ldpc_tool. Signál dlouhý 96 normálních rámců (80 s při 40 kS/s, 48 s při 66,7 kS/s), stejný
čas pro krátké rámce; 3 semínka šumu.

Podíl dekódovaných = TS pakety při daném Es/N0 / TS pakety téhož signálu bez šumu:

| Es/N0 (dB) | 40k normal | 40k short | 66,7k normal | 66,7k short |
|---|---|---|---|---|
| 4,0 | 4,6 % | 20,8 % | 9,5 % | 24,7 % |
| 4,5 | 100 % | 99,1 % | 98,9 % | 96,7 % |
| 5,0 | 81,8 % | 99,7 % | 33,9 % | 93,8 % |
| 5,5 | 83,3 % | 99,9 % | 100 % | 96,9 % |
| 6,0 | 100 % | 99,7 % | 101 % | 100 % |
| 6,5 | 65,1 % | 100 % | 101 % | 100 % |
| 7,0 | 66,1 % | 100 % | 83,1 % | 100 % |

| | 40k normal | 40k short | 66,7k normal | 66,7k short |
|---|---|---|---|---|
| první TS (reálný čas) | 59 s při 8 dB | 8,4 s při 5,5 i 8 dB | 40 s při 8 dB | 8,4 s při 5,5 dB, 5,2 s při 8 dB |
| iterace LDPC při 4,5 dB | – | 16,3 | – | 14,0 |

Závěry:
- **Práh** kolem Es/N0 4,5 dB pro obě velikosti rámců (v kroku 0,5 dB bez rozdílu).
- **Nad prahem jsou krátké rámce stabilní** (≥ 94 %), normální kolísají: ztráta jedné dávky 32 rámců znamená při
  40 kS/s 27 s signálu, a při 2–3 dávkách na běh jedna ztráta posune podíl o třetinu. Pro férové číslo u normálních
  rámců by bylo potřeba víc běhů; samotné kolísání je ale přesně to, co divák vidí.
- **První obraz** za 5–8 s s krátkými rámci proti 40–59 s s normálními. To je víc než doba dávky (27 / 16 s), protože
  první neúplná dávka se obvykle ztratí.

Zopakování:
```sh
PY=third_party/ESP32-DATV-RX/.venv/bin/python
$PY rx/sim_frames.py --build-iter --rates 40000 66666 --esn0 4 7 0.5 --seeds 1 2 3  # výsledky v build/sim-frames/
```

## Zbývá

Ověřit na stole a na vzdálenost: dobu do prvního obrazu a počty rámců se skutečným hardwarem, a jestli drift a úniky
desek bez TCXO nezvýhodní krátké rámce ještě víc.
