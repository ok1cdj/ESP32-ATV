# leandvb: falešný první zámek a QPSK 3/5, 5/6 (19. 10. 2026)

[English](2026-10-19-leandvb-acquisition.en.md)

Tři věci, které přijímače (aplikace i `rx_dvbs2.py`, obojí s leandvb z pabr/leansdr `work` 84c59e1) dělaly špatně,
všechny v tom, jak leandvb zachytí signál DVB-S2 (`s2_frame_receiver` v `dvbs2.h`), a oprava:
`tools/leandvb-acquisition.patch` v ESP32-DATV-RX, aplikuje ji jeho `tools/install.sh` (PC) a
`android/native/build-native.sh` (aplikace). Upstream leansdr opravu nemá.

## Co bylo špatně

| Příznak | Příčina |
|---|---|
| Normální rámce při nízkých rychlostech: první zámek obvykle falešný (MER −11 až −15 dB, nosná o kHz vedle), obraz za 30–50 s nebo vůbec | Hledání PLHEADER prohlíželo okno 13050 symbolů, 39 % normálního rámce QPSK (33282): často v něm žádný PLHEADER nebyl a nejlepší korelace ležela někde v datech. Na ní se leandvb zamkl. Krátké rámce (8370 symbolů) se do okna vejdou vždy, proto se u nich chyba neprojevila. |
| QPSK 3/5 a 5/6 s normálními rámci nešly dekódovat ani z čistého signálu (zámek, MER v pořádku, každý rámec vadný) | PLSCODE prvního rámce se dekódoval jen s hrubým kmitočtem z diferenčního korelátoru. Otočení asi o 180° během PLHEADER změní kód na ten, který se liší druhou polovinou: QPSK 3/5 se četlo jako 1/2, 5/6 jako 4/5. Kmitočet dopasovaný na takový špatný PLHEADER pak nechal nosnou mimo o celé otáčky mezi piloty. Piloty a PLHEADERy pak seděly, data mezi nimi rotovala. Po zámku leandvb nosnou už znovu nekontroluje. |
| 8PSK s krátkými rámci v `rx/test_dvbs2enc.py` nikdy nedekódovalo | Nebyla to chyba leandvb: nechává nezpracované dva nejdelší rámce vstupu, takže 40 krátkých rámců 8PSK nenaplnilo jednu 32rámcovou dávku ldpc_tool. Test teď posílá 56. |

Náš kodér byl celou dobu v pořádku: jeho kódová slova projdou všemi kontrolami LDPC i BCH a vlastní vysílač leansdr
(`leandvbtx`) selhal stejně, když jeho symboly prošly stejným kanálem.

## Oprava

1. Když jsou povolené normální rámce, hledání PLHEADER pokryje nejdelší rámec.
2. Na prvním rámci dostane každý povolený PLSCODE vlastní dopasování kmitočtu, fáze a amplitudy; vyhraje nejlepší MER.
   Kandidát, jehož kmitočet vyjde o víc než Rs/16 jinde než u volby LLR dekodéru, se vynechá: bit PILOTS neguje každý
   druhý symbol, což dopasování bere jako posun nosné o Rs/2.
3. Na první PLHEADER o víc než 6 dB pod Es/N0, které jeho MODCOD potřebuje, se nezamyká.

## Výsledky

`rx/sim_lock.py` (leandvb v reálném čase, volby přijímačů), 6 semínek na případ:

| Případ | Předtím | Teď |
|---|---|---|
| 40 kS/s normální, Es/N0 20 dB, nosná 0,3 / 3 kHz vedle, drift 0 / 70 Hz/s | 10 z 12 falešných (15. 10.: 9 z 12) | 24 z 24 správně, jeden zámek, zamčeno za 2,5 s, první TS 28,3 s |
| 40 kS/s krátké, totéž | správně | 24 z 24 správně, zamčeno za 1,9 s, první TS 8,4 s |
| 40 kS/s normální, Es/N0 4,5 dB | 6 z 6 falešných, obraz ve 2 | 6 z 6 správně, obraz ve všech |
| 40 kS/s krátké, Es/N0 4,5 dB | 4 z 6 falešných | 6 z 6 správně |
| 333 kS/s normální i krátké | správně | správně, beze změny (první TS 3,3 / 1,0 s) |

`rx/test_dvbs2enc.py` část B (všechny poměry QPSK a 8PSK, normální i krátké, čistý signál): vše dekóduje, včetně QPSK
3/5 a 5/6 normálních. S normálními rámci trvá první obraz při 40 kS/s dál asi 28 s, ale to je dávka 32 rámců
ldpc_tool, ne zachycení. Krátké rámce zůstávají lepší volbou pro nízké rychlosti.

Zbývá: jednou v testu (QPSK 4/5 krátké, jeden vzor dat, čistý signál) byla nosná po PLHEADER mimo asi o 335 ppm Rs.
To je půl otáčky mezi piloty a zámek se pokazil. Neopravený leandvb dělá totéž, jiná data dekódují. Se šumem se to
neukázalo (72 simulovaných běhů s krátkými rámci).

Jak to získat: PC `third_party/ESP32-DATV-RX/tools/install.sh` (přeloží leandvb znovu), aplikace od tohoto vydání.
