# Vydanie na Google Play (účet VESPAL s. r. o.)

## 1. Vytvor upload kľúč (raz, na počítači s Javou)

```bash
keytool -genkeypair -v -keystore recshot-upload.jks -alias recshot \
  -keyalg RSA -keysize 2048 -validity 10000
```

Súbor `recshot-upload.jks` a heslá si bezpečne odlož. **Nikdy ich nedávaj do repozitára.**

## 2. Pridaj tajomstvá do GitHubu

Repozitár → Settings → Secrets and variables → Actions → New repository secret:

| Názov | Hodnota |
|---|---|
| `KEYSTORE_BASE64` | výstup `base64 -w0 recshot-upload.jks` (na Windows: `certutil -encode` a odstráň hlavičky) |
| `KEYSTORE_PASSWORD` | heslo keystoru |
| `KEY_ALIAS` | `recshot` |
| `KEY_PASSWORD` | heslo kľúča |

Pri ďalšom pushi Actions vytvoria aj `RecShotC8-N.aab`. Nájdeš ho v Releases aj v artefaktoch.

## 3. Play Console

1. **Vytvoriť aplikáciu** → názov „RecShotC8“ (ak je obsadený, zmeň `app_name` v `res/values/strings.xml`), jazyk slovenčina, aplikácia, zadarmo.
2. **Testovanie → Interné testovanie** → nahraj `.aab` → pridaj seba ako testera → nainštaluj cez odkaz. Takto si overíš verziu z Play pred ostrým vydaním.
3. Firemný (organizačný) účet nemusí absolvovať povinný 14-dňový uzavretý test s 12 testermi – ten platí iba pre nové osobné účty.

## 4. Povinné formuláre

**Zásady ochrany súkromia** – URL je povinná (aplikácia používa mikrofón a ukladá médiá).
Text je v [PRIVACY.md](PRIVACY.md). Zverejni ho napríklad na `https://vespal.sk/recshot-privacy`.

**Bezpečnosť údajov (Data safety)**
- Zhromažďuje alebo zdieľa údaje? **Nie** (všetko zostáva v zariadení, nič sa neodosiela)
- Šifrovanie pri prenose: netýka sa, žiadny prenos

**Typy služieb v popredí (Foreground service)** – App content → Foreground service permissions:
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` → *Media projection*: „Používateľ spustí nahrávanie alebo snímku obrazovky. Služba drží zachytávanie obrazovky a zvuku prehrávaného v zariadení (AudioPlaybackCapture), kým používateľ nahrávanie nezastaví.“
- `FOREGROUND_SERVICE_MICROPHONE` → *Microphone*: „Voliteľný záznam komentára z mikrofónu počas nahrávania obrazovky, iba keď ho používateľ zapne.“
- Priložiť treba **video ukážku** (napr. 30 s nahrávka priamo z RecShotC8: štart → notifikácia → stop).

**Hodnotenie obsahu** – dotazník, kategória „Nástroje / Utility“, žiadny citlivý obsah.

**Cieľová skupina** – 13+ alebo 18+ (nie deti).

**Reklamy** – nie.

## 5. Popis do obchodu (návrh)

**Krátky popis (80 znakov):**
Nahrávanie obrazovky bez prekrytia, snímky obrazovky a rýchly strih videa.

**Dlhý popis:**
RecShotC8 nahrá celú obrazovku tvojho telefónu čisto – bez bubliny či panela, ktoré by prekrývali obraz.
Nahrávanie ovládaš z notifikácie, z dlaždice v rýchlych nastaveniach alebo zatrasením telefónu.

• Nahrávanie v 720p až natívnom rozlíšení, do 60 FPS
• Zvuk zo systému (hry, videá) aj komentár z mikrofónu naraz
• Snímky obrazovky jedným ťuknutím, aj počas nahrávania
• Strih videa: orezanie, vystrihnutie úseku, rýchlosť 0,25×–3×, text, hudba
• Úprava fotiek: orezanie, kreslenie, rozmazanie citlivých údajov
• Upravíš aj videá a fotky z fotoaparátu
• Bez reklám, bez registrácie, nič sa neodosiela na internet

**Grafika:** ikona 512×512 PNG, funkčná grafika 1024×500, aspoň 2 snímky obrazovky telefónu (spravíš ich priamo cez RecShotC8).

## 6. Vydanie

Production → Create new release → nahraj `.aab` → poznámky k vydaniu → Review → Rollout.
Prvé posúdenie trvá zvyčajne 1–7 dní.

Každý ďalší push do `main` vytvorí nový build s vyšším `versionCode` (číslo behu GitHub Actions).
