---
title: ATV RX/TX – návod (beta)
---

**ATV RX/TX** je přijímač (i vysílač) DVB-S2 pro amatérskou televizi v pásmu 13 cm. Rádiem je **Seeed XIAO ESP32-S3** připojený přes
USB-OTG, **telefon nebo tablet s Androidem** signál demoduluje (leandvb), dekóduje LDPC (ldpc_tool) a ukazuje obraz
spolu s MER, konstelací a údaji o streamu. Protějškem je samostatný maják s ESP32-C3
([ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA)) – a stejná aplikace také **vysílá**: s XIAO ESP32-C3 na
USB-OTG posílá živě testkartu, obraz z kamery nebo obrázek či video z telefonu jako DVB-S2 (přepínač **RX | TX** nahoře).

> ⚠ **Vysílá se v radioamatérském pásmu 13 cm (2,3–2,45 GHz). K vysílání je nutné platné oprávnění (koncese)
> radioamatéra.** Vysílej jen se svou značkou, na kmitočtech a s výkonem, které tvé oprávnění a národní kmitočtový
> plán dovolují (2345 MHz leží v ATV segmentu band plánu IARU 1. oblasti). Část pásma kolem 2,4 GHz sdílí Wi-Fi a další
> služby – bez koncese je vysílání nezákonné a ruší ostatní. Samotný příjem koncesi nevyžaduje.

> **Beta.** Aplikace je ke stažení zdarma pod GNU GPL v3.

![ATV RX/TX na telefonu: obraz majáku, stav příjmu, spektrum a konstelace](img/atvrx-app.png)

## Co potřebuješ

| díl | poznámka |
|---|---|
| **Seeed XIAO ESP32-S3** nebo **S3 Plus** | s externí anténou na U.FL (na 2.3 GHz – anténa z Wi-Fi sady stačí na stůl) |
| **USB-C OTG** kabel nebo redukce | telefon musí umět USB host (OTG); S3 se napájí z telefonu |
| **Android 9+**, procesor **arm64** | testováno: OnePlus 10 Pro (Android 16) |
| pro TX: **Seeed XIAO ESP32-C3** | s anténou na U.FL; firmware nahraje aplikace nebo webový flasher |
| počítač s **Chrome / Edge** | volitelně: firmware do obou desek umí nahrát i aplikace sama |

## 1. Firmware do desek (jednou)

**Z aplikace (bez počítače):** nejdřív nainstaluj aplikaci (kapitola 2). Připoj desku přes OTG, otevři menu **⋮ →
Firmware (S3 / C3)** a klepni na **Write firmware into the connected board**. Aplikace přepne desku do zavaděče v ROM,
ten prozradí čip – **ESP32-S3 → přijímač**, **ESP32-C3 → vysílač**, splést se nedají –, stáhne aktuální firmware z webu
(uloží si ho i pro práci bez sítě), zapíše ho s ukazatelem průběhu, ověří (MD5) a desku restartuje. Android se na
přístup k USB zeptá dvakrát (deska se v zavaděči objeví znovu). Nový firmware na webu nepotřebuje novou aplikaci.
**Check versions on the web** ukáže aktuální verze a které tvoje desky (ty, do kterých aplikace zapisovala nebo s nimi
mluvila) mají starší firmware.

**Z prohlížeče:** [webový flasher](flasher/) v Chrome nebo Edge na počítači – připoj XIAO datovým kabelem, drž **BOOT**,
ťukni **RESET**, pusť BOOT, klikni na tlačítko přijímače (S3) nebo vysílače (C3), vyber port a potvrď. Po dokončení
**RESET**. (Smazání tam smaže i kalibraci nosné v C3, viz kapitola 4.)

**Z příkazové řádky** (tento repozitář, Linux): S3 `third_party/ESP32-DATV-RX/tools/install.sh`, pak
`rx/flash_rx_xiao.sh`; C3 `tx/build_c3_web.sh` nebo `tx/flash_tx.sh` (README).

Desky se hlásí jako `303a:1001` a po každém zapnutí spustí svůj firmware.

## 2. Aplikace

1. Stáhni APK z [Releases](https://github.com/ok1cdj/ESP32-ATV/releases) (soubor `atvrx-….apk`, kontrolní součet
   v `SHA256SUMS`).
2. Otevři ho v telefonu a povol instalaci z tohoto zdroje (*Instalovat neznámé aplikace*).
3. Při prvním spuštění povol **oznámení** – příjem běží ve službě na pozadí a v oznámení je jeho stav a tlačítko Stop.
4. Připoj S3 přes OTG. Android nabídne otevření ATV RX/TX a zeptá se na **přístup k USB zařízení** – povol.

**Aktualizace:** jednou denně se aplikace podívá na GitHub po nové verzi a po novějším firmwaru pro desky, které zná,
a ohlásí to dialogem (*Download* otevře vydání; *Skip this version* ji přeskočí). Menu ⋮ → *Check for updates*
zkontroluje hned, *Daily update check: on/off* kontrolu vypne. Nové APK se nainstaluje přes staré (stejný podpis),
nastavení zůstane. Automatické aktualizace bez dialogu: [Obtainium](https://github.com/ImranR98/Obtainium) s adresou
`https://github.com/ok1cdj/ESP32-ATV` (zapnout *include pre-releases*).

## 3. Příjem

1. Nahoře zvol **Preset…** – např. *Beacon 50k QPSK 3/4* (kmitočet 2345.000 MHz, 50 kS/s). Předvolba nastaví kmitočet,
   symbolovou rychlost, modulaci, kódový poměr a velikost rámců; vlastní nastavení ulož tlačítkem **Save**. Vyhni se kmitočtům na
   násobcích 20 MHz (2320, 2340, 2360 …): S3 má přesně tam vlastní parazitní čáru, uvnitř signálu.
2. **Gain**: na stole 10 dB, na vzdálenost 30 dB a víc. Při silném signálu a velkém zisku se přijímač zahltí (MER klesá,
   ztrácí zámek).
3. **Start.** Aplikace nejdřív sekundu hledá signál (FFT), naladí se na jeho střed a spustí demodulátor.
4. **První obraz** přijde, až ldpc_tool dekóduje první dávku 32 rámců: při 333 kS/s asi za 3 s, při 50 kS/s asi
   za 20 s, při 40 kS/s asi za 27 s (první neúplná dávka se často ztratí, takže v praxi i dvojnásobek). Pak už obraz
   běží plynule (zpožděný o tu dávku). Maják s **krátkými rámci** (`FRAME=short`) a předvolba *Beacon … short* zkrátí
   čekání čtyřikrát, na 5–8 s, při stejné citlivosti ([krátké rámce](https://github.com/ok1cdj/ESP32-ATV/blob/master/docs/2026-10-15-short-frames.cs.md)).

**Zvuk** hraje s obrazem: AAC (i HE-AAC, které posílá TX této aplikace) a MPEG audio (MP2 – posílá ho řada DATV
vysílačů i převodník videa ESP32-DATV-SA – a MP3). Obraz se řídí hodinami zvuku, takže jdou spolu i ve chvíli, kdy
ldpc_tool vydává rámce po dávkách. **⋮ → Sound off / on** zvuk ztlumí. AAC v LATM a AC-3 podporované nejsou (stav to
napíše); bez zvuku se obraz ukazuje, jak přichází, jako dřív.

Obrazovku můžeš zhasnout nebo přepnout do jiné aplikace – příjem běží dál ve službě (i zvuk). Zastavíš ho tlačítkem
**Stop** v aplikaci nebo v oznámení.

**Nahrávání:** **● REC** vedle Start (během příjmu) zapisuje přijatý transportní proud do souboru přesně tak, jak
přišel, nic se nepřekódovává – obraz, zvuk i název služby jako při vysílání, podle rychlosti asi 20–300 MB za hodinu.
Začne u nejbližšího klíčového snímku obrazu (s tabulkami proudu před ním), takže soubor hraje čistě od začátku; výpadek signálu je v souboru mezera. Vedle něj
je **.srt** (titulky) se stejným jménem: každou sekundu čas, MER, CNR, odchylka nosné a rámce – přehrávač je ukáže přes
obraz, hodí se na testy dosahu. Ukončí ho **■** (s časem nahrávání) nebo *Stop REC* v oznámení; skončí i se zastavením
příjmu a sám, když dochází místo. Kam: **Download/ATV RX/** (Android 10+, např.
`ATV_2026-10-18_153012_2345.000MHz_50k_SV0SYH.ts`); Android 9: `Android/data/com.ok1cdj.atvrx/files/Movies/ATV RX/`.
Přehraješ ho ve **VLC** nebo mpv (telefon i PC; VLC si .srt najde sám; VLC na Linuxu potřebuje `vlc-plugin-dvb` a `vlc-plugin-ffmpeg`, při černém obrazu vypni *Hardwarově akcelerované dekódování*) – galerie telefonu `.ts` většinou neumí.

### Co znamenají údaje

| řádek | význam |
|---|---|
| `carrier` | odchylka nosné od naladěného kmitočtu; `NCO` = softwarové doladění, `res` = zbytek, který hlásí demodulátor |
| `drift … Hz/s` | jak rychle aplikace dolaďuje drift oscilátorů (ESP32 nemají TCXO; C3 se při zahřívání posouvá o kHz, až asi 260 Hz/s) |
| `re-search` | kolikrát se signál ztratil a hledal znovu (10 s – u nízkých rychlostí 2,5 dávky LDPC – bez dobrého rámce) |
| `LOCK`, `FRAME` | zámek demodulátoru a rámcové synchronizace DVB-S2 (✓ / ✗) |
| `MER` | modulation error ratio v dB – hlavní míra kvality; QPSK 3/4 potřebuje zhruba > 5 dB, 8PSK 3/5 > 10 dB |
| `CNR` | poměr nosná/šum (jen když vzorkování > 3× symbolová rychlost, tj. do 100 kS/s) |
| `frames ok / fixed / bad` | rámce bez chyb / opravené kódem BCH / nedekódované |
| `TS`, `TEI`, `CC` | tok transport streamu (průměr 10 s), pakety s chybou, nespojitosti čítače |
| `service`, `video` | název služby a poskytovatele z SDT, kodek a rozlišení; `A/V sync` = obraz se řídí zvukem, `late … ms` = obraz přichází později než jeho zvuk |
| `audio` | zvuk: kodek, vzorkování, kanály (`(off)` = ztlumeno); `… not supported` u LATM / AC-3 |
| `USB lost`, `ESP drop`, `queue` | ztráty na USB, v ESP32-S3 a ve frontě k demodulátoru – mají být 0 |
| konstelace (čtverec) | body symbolů: QPSK 4 shluky, 8PSK 8 shluků; prstenec = málo signálu nebo fázový šum |

## 4. Samostatný maják (ESP32-C3)

Firmware vysílače z webu (kapitola 1, fork [ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA) od ESP32-ATV,
licence PolyForm Noncommercial) **sám nic nevysílá**, dokud do něj neuložíš maják se svou značkou:

1. Připoj C3 přes OTG, přepni na **TX** a nastav vše jako pro živé vysílání (kapitola 5): kmitočet, symbolovou rychlost
   (do 333 kS/s), kódový poměr QPSK, velikost rámců (při nízkých rychlostech nejlépe **short**), piloty, **značku**,
   **AMP**, obraz (testkarta, kamera, nebo vlastní obrázek či video – **File…**), **zvuk** (CW ID: značka v Morseovce
   jednou za smyčku; *from the video*; při 40–50 kS/s zvol kb/s, *auto* tam zvuk neposílá) a délku (10–60 s, nebo
   **video**: celá délka videa, nic se neusekne – do toho, co se při dané rychlosti vejde do flash).
2. Klepni na **Save as beacon in the C3**. Telefon po tu dobu nahrává obraz, zakóduje ho jako smyčku a uloží do flash C3
   (ukazatel průběhu: nahrávání, kódování, ukládání – pro 20 s asi půl minuty).
3. Odpoj C3 a napájej ho čímkoli – powerbankou, nabíječkou. Po každém zapnutí vysílá smyčku sám, na uloženém kmitočtu
   a úrovni, bez telefonu. Nový maják ten starý nahradí.

Poznámky:

- **AMP** je úroveň buzení DAC v C3, ne změřený výkon (tabulka v kapitole 5); smyčka si nese tu, se kterou byla uložena.
- Maják se přijímá jako každý jiný, např. *Beacon 50k QPSK 3/4 short* v aplikaci. Skutečná symbolová rychlost C3 je
  160 MHz dělených celým číslem (333 kS/s → 333 333; 40, 50, 100, 125, 250 kS/s přesně).
- **Kalibrace nosné:** C3 propouští svou nosnou (tón uprostřed signálu). Korekce je uložená v C3 (`BDC`, měří se
  `rx/cal_dc.py --save` s S3 na PC) a platí i pro maják; zápis firmwaru z aplikace ji zachová, smazání ve webovém
  flasheru ji smaže. Platí pro jeden kmitočet.
- Smyčky z [převodníku videa](https://barisdinc.github.io/ESP32-DATV-SA/converter/) původního projektu (330,58 kS/s,
  QPSK 3/4) tento firmware přehraje také (zápis esptoolem na 0x110000).
- Na PC: `tx/make_flash_loop.py VIDEO --baud 40000 --frame short --call ZNACKA -o loop.bin` udělá smyčku z libovolného
  videa, `tx/c3_vidw.py loop.bin` ji uloží přes USB, `tx/c3_vidw.py --erase` ji smaže. Vývojáři mohou dál stavět firmware
  s vestavěnou testkartou (`tx/make_beacons.sh`, `tx/flash_tx.sh`, README).

## 5. Vysílání z telefonu (TX, beta)

![TX: testkarta z telefonu, stav C3 a streamu](img/atvrx-tx.png)

Telefon sám zakóduje obraz (H.264), transport stream i DVB-S2 a posílá symboly do C3, který jen moduluje (jeho režim
s PC). C3 potřebuje firmware vysílače (kapitola 1); příkaz z USB zastaví běžící maják a spustí živý režim.

1. Připoj **XIAO ESP32-C3** přes OTG místo S3 (jeden port: RX a TX se střídají). C3 s anténou dej **dál od telefonu** –
   položený vedle něj dával signál o 20 dB slabší a nedekódoval se.
2. Nahoře přepni na **TX**, povol přístup k USB (a ke kameře, když ji použiješ).
3. Nastav kmitočet (2300–2450 MHz, jinak ho firmware odmítne), symbolovou rychlost, **QPSK / 8PSK**, kódový poměr,
   velikost rámců (**short** dá při nízkých rychlostech první obraz 4× dřív), piloty, **značku**, **AMP** (300 = 0 dB,
   pár mW; 13 = −27 dB; 8PSK až 420), **obraz** (testkarta, zadní nebo přední kamera, nebo **soubor s obrázkem či
   videem**) a **zvuk**: vypnutý, mikrofon, CW ID (tvoje značka v Morseovce, 20 WPM, 700 Hz, každých 30 s), mikrofon
   + CW ID nebo *from the video*, s jeho datovým tokem.
4. **Start TX.** Aplikace si z C3 přečte jeho DC kalibraci (`BDC`) a spustí ho; ve stavu je zaplnění bufferu C3 (má
   se držet kolem 3000), rychlost TS a videa. TX běží na pozadí i se zhasnutým displejem; zastavíš ho **Stop TX**
   nebo v oznámení.

- **AMP** je špičková amplituda, kterou C3 budí modulátor, v kódech DAC (1–480) – digitální úroveň buzení, ne změřený
  výkon. Mění napětí: úroveň = 20·log₁₀(AMP / 300) dB.

  | AMP | úroveň | použití |
  |---|---|---|
  | 480 | +4 dB | maximum; čekej zkreslení (rozšíření spektra, nižší MER) |
  | 420 | +3 dB | výchozí pro 8PSK v PC nástrojích ESP32-DATV; hlídej MER na přijímači |
  | **300** | **0 dB** | výchozí pro QPSK, plná úroveň (pár mW, neměřeno) |
  | 95 / 30 | −10 / −20 dB | na stole s anténami blízko sebe (300 může přijímač zahltit) |
  | 13 | −27 dB | výchozí hodnota původního majáku; zkoušky citlivosti |

- Obraz z kamery se otáčí podle toho, jak telefon držíš (naležato vyplní celý rámec), a dole nese pruh se značkou
  a módem.
- **Obrázek / video ze souboru:** **File…** otevře systémový výběr souborů (obrázky a videa; bez oprávnění k úložišti,
  aplikace si volbu pamatuje). Obrázek se vysílá celý (černé pruhy, když nemá tvar 16:9), video běží dokola svým
  tempem, otočené podle značky rotace (videa natočená na výšku). **callsign** přes něj dá stejný pruh se značkou jako
  u kamery (výchozí zapnuto kvůli identifikaci stanice; vypni, pokud ji obrázek obsahuje). Zvuk *from the video* hraje
  zvukovou stopu souběžně se smyčkou (do 10 minut); video bez zvuku nebo obrázek zvuk neposílá. Náhled ukazuje soubor
  i při zastaveném TX. Rozlišení nevadí: aplikace ho zmenší na velikost videa (nejvýš 640×360).
- **Zvuk** je AAC: do 32 kb/s HE-AAC, nad tím AAC-LC. *auto* **pod 80 kb/s TS zvuk neposílá** (40–50 kS/s: obraz
  potřebuje každý bit; zvol 8–16 kb/s, když ho chceš i tak), do 200 kb/s 24 kb/s, nad tím 64 kb/s; bity zvuku se
  berou videu. Obraz i zvuk nesou časové značky z okamžiku zachycení, takže jdou spolu (změřeno do 20 ms; na stole
  rty v pořádku při 333 kS/s). Na mikrofon se aplikace zeptá při prvním Startu s ním; zvuk jde i se zhasnutým displejem.
- Bitrate a snímková frekvence videa jsou automatické (asi 70 % rychlosti TS; 320×180 5 fps při 40–66 kS/s,
  640×360 15 fps při 333 kS/s), nebo je vybereš ze seznamu (*auto* nebo 50–400 kb/s, 2–30 fps).
- Přijímat můžeš druhým telefonem, PC (`rx/rx.sh 333000 --frame short`) nebo jakýmkoli přijímačem DVB-S2. 8PSK:
  pro přijímače s leandvb použij normální rámce (krátké 8PSK nedekódují).
- Vyzkoušeno na stole: QPSK 3/4 50 kS/s short MER 17 dB, 333 kS/s MER 15 dB; 8PSK 2/3 333 kS/s MER 16 dB. Se
  zvukem: 333 kS/s kamera + mikrofon (AAC 64 kb/s), 66,7 kS/s CW ID (HE-AAC 24 kb/s), 40 kS/s mikrofon + CW ID při
  12 kb/s, uložený maják s CW ID; PC (`rx/rx.sh`) přehrává zvuk taky.

### Kalibrace vysílače (S3 + C3 na hubu)

C3 propouští svou nosnou: tón uprostřed signálu (asi −34 dBc), který při nízké úrovni omezuje příjem. Aplikace ho
vynuluje s S3 jako měřicím přijímačem: připoj **obě desky přes USB hub** (lépe napájený) a zvol menu ⋮ → *Transmitter
calibration (S3 + C3)*. Poprvé se zeptá na přístup k oběma a zjistí, která je která (podle MAC v sériovém čísle USB;
pamatuje si to). C3 vysílá jen svůj únik na nastaveném kmitočtu (bez modulace, bez PA – i tak je to vysílání: jen
s koncesí), antény měj kus od sebe, aby se S3 nepřebudil. Po zahřátí (60 s: kmitočet C3 po startu ujíždí) trvá hledání
asi 20 s a typicky ubere 20 dB; *Save* ho uloží do C3 (pro ten kmitočet) i do aplikace. Únik se při každém startu C3
posune (asi o 2 kódy DAC), takže uložená hodnota dá méně, asi 9 dB: nejlepší je kalibrovat těsně před vysíláním a C3
mezitím neodpojovat (aplikace ho neresetuje, kalibrace tedy platí, dokud C3 nepřijde o napájení). Nahrání firmwaru z aplikace uložené hodnoty zachová. Podrobnosti a měření:
[kalibrace LO](https://github.com/ok1cdj/ESP32-ATV/blob/master/docs/2026-10-16-lo-calibration.cs.md).

## Řešení problémů

| příznak | co zkusit |
|---|---|
| S3 na USB hubu: MER skáče, mnoho zámků | hub ztrácí vzorky (hlavně sdílený se sluchátky): S3 přímo do telefonu / počítače |
| *ESP32-S3 not connected* | kabel s daty (ne jen nabíjecí), OTG zapnuté v telefonu, firmware nahraný (deska = `303a:1001`) |
| *searching: no signal* | zapnutý vysílač, správný kmitočet, anténa na obou deskách; vyšší gain |
| zámek je, ale `bad` přibývá | málo signálu (MER nízké) – vyšší úroveň vysílače / gain, lepší antény; u 8PSK spíš QPSK |
| MER klesá při silném signálu | přijímač zahlcen – snížit gain |
| `USB lost` / `queue` > 0 | jiný OTG kabel / redukce; nepouštět náročné aplikace souběžně |
| obraz nejde, rámce ano | špatný kódový poměr nebo modulace v předvolbě (maják = QPSK 3/4) |
| TX: *no ESP32-DATV answer* | připojená je S3, ne C3 (obě jsou `303a:1001`), nebo má C3 jiný firmware |
| TX: příjem slabý, nízké MER | dej C3 s anténou dál od telefonu |
| TX: při 40–50 kS/s není zvuk | *auto* pod 80 kb/s TS zvuk neposílá – vedle *sound* zvol kb/s (12) |
| RX: `audio … not supported` | AAC v LATM nebo AC-3: obraz jde, zvuk ne (AAC v ADTS, MP2 a MP3 hrají) |
| C3 sám nic nevysílá | ještě v něm není maják: TX → Save as beacon in the C3 |
| *this C3 firmware cannot store a loop* | nahraj aktuální firmware: menu ⋮ → Firmware |
| firmware: *no network and no saved firmware* | první zápis potřebuje jednou internet, pak funguje uložený i bez sítě |
| TX log: *C3 firmware …, on the web …* | na webu je novější firmware vysílače: menu ⋮ → Firmware |

Hlášení chyb a nápady: [issues](https://github.com/ok1cdj/ESP32-ATV/issues). Když něco nejde, pošli **menu ⋮ → Report
a problem…** (i v *About*): krátce napiš, co se stalo (RX / TX, hub nebo přímo), a zvol *E-mail…* nebo *GitHub issue*.
Aplikace přiloží svůj log, model telefonu, připojená USB zařízení (MAC desek) a nastavení – nic dalšího. Desky nech
zapojené tak, jak byly, když se to stalo. U *GitHub issue* je zip v **Download/ATV RX/**, přilož ho k issue.

## Licence

ATV RX/TX: GNU GPL v3. Části: leansdr / leandvb (GPL-3), LDPC / ldpc_tool (Ahmet Inan, 0BSD-style),
ESP32-DATV-RX (BSD-3-Clause), esp32-sdr-trx (0BSD, firmware S3), minimp3 (CC0, zvuk MP2 / MP3).
