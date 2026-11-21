# RecShotC8

Natívna Android aplikácia (Kotlin + Jetpack Compose + Media3) na nahrávanie obrazovky, snímky obrazovky
a úpravu videí a fotiek. Vydavateľ: **VESPAL s. r. o.**

## Funkcie (verzia 1.1)

**Nahrávanie**
- celá obrazovka, rozlíšenie 720p / 1080p / 1440p / natívne, 24 / 30 / 60 FPS
- **čistý záznam** – na obrazovke nie je žiadna bublina ani panel, takže nič neprekrýva obraz
- ovládanie cez notifikáciu (Pauza, Snímka, Stop), dlaždice v rýchlych nastaveniach, zatrasenie = stop
- **zvuk zo systému** (hry, videá, hudba – Android 10+), mikrofón, alebo oboje zmixované naraz
- odpočet 0 / 3 / 5 s
- snímka obrazovky aj počas nahrávania

**Snímky obrazovky**
- z aplikácie, z dlaždice „Snímka obrazovky“ alebo z notifikácie počas nahrávania

**Editor videa** – aj pre videá z fotoaparátu a galérie telefónu
- orezanie začiatku a konca, vystrihnutie úseku zo stredu
- rýchlosť 0,25× – 3× (spomalenie aj zrýchlenie, zvuk bez zmeny výšky tónu)
- text do videa (hore / stred / dole, 5 farieb)
- hudba pod video (z telefónu, opakuje sa do konca), odstránenie pôvodného zvuku
- export do MP4 (Movies/RecShotC8)

**Editor fotiek** – aj pre fotky z telefónu
- orezanie, kreslenie (6 farieb), rozmazanie citlivých údajov, krok späť, zdieľanie

**Import** – systémový výber fotiek a videí (Photo Picker), netreba povolenie na prístup k celému úložisku.
Aplikácia sa ponúka aj v menu „Zdieľať / Upraviť“ pri videách a fotkách.

## Inštalácia do telefónu (bez Android Studia)

1. Nahraj projekt do GitHub repozitára (vetva `main`).
2. GitHub Actions automaticky zostaví APK (záložka **Actions**, trvá to asi 5–8 minút).
3. V telefóne otvor `https://github.com/<tvoj-účet>/RecShotC8/releases` → stiahni `RecShotC8-N-debug.apk`.
4. Povoľ inštaláciu z prehliadača („Neznáme zdroje“) a nainštaluj.
5. Dlaždice pridáš cez: potiahni lištu dole → ceruzka (upraviť) → presuň „Nahrať obrazovku“ a „Snímka obrazovky“.

Debug verzia má ID `sk.vespal.recshotc8.debug`, takže môže byť v telefóne naraz s verziou z Google Play.

## Zostavenie pre Google Play

Pozri [PLAY_STORE.md](PLAY_STORE.md).

## Štruktúra

```
app/src/main/java/sk/vespal/recshot/
  MainActivity.kt         navigácia, žiadosť o zachytenie obrazovky
  RecorderService.kt      služba v popredí: MediaProjection, snímky, ovládanie
  ScreenEncoder.kt        MediaCodec H.264 + AAC, zvuk zo systému (AudioPlaybackCapture) + mikrofón, MediaMuxer
  Tiles.kt                dlaždice v rýchlych nastaveniach
  ShotTrampolineActivity  snímka z notifikácie (zatvorí lištu notifikácií)
  ShakeDetector.kt        stop zatrasením
  MediaStoreUtil.kt       ukladanie do Movies/RecShotC8 a Pictures/RecShotC8
  Prefs.kt                nastavenia
  ui/                     Compose obrazovky (Domov, Galéria, editor videa, editor fotiek)
```

## Plán ďalších verzií

- hlasitosť hudby vs. pôvodného zvuku, export GIF
- kamera v rohu (facecam), zobrazenie dotykov

## Poznámka k zvuku zo systému

Android dovoľuje nahrať zvuk iba z aplikácií, ktoré to povoľujú (väčšina hier, YouTube, prehrávače).
Telefonické hovory, niektoré aplikácie s ochranou (Netflix a pod.) a zvuky systému sa nahrať nedajú – to je obmedzenie Androidu.
