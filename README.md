# TV Minimal

Osobní minimalistický launcher pro Android TV / Google TV. Zobrazuje jednoduchou mřížku nainstalovaných aplikací bez reklam a doporučení. Pod mřížkou běží živé vysílání **ČT24**. Volitelná dlaždice **Action Reel** pouští veřejný stream Red Bull TV.

> **Stav:** osobní projekt jednoho autora. Je vyzkoušený jen na jedné televizi s Google TV (Android 14, API 34).
> Neexistuje k němu žádná oficiální podpora. Projekt nemá vazbu na Českou televizi, Red Bull ani Google. Zveřejněný je **jen zdrojový kód**: žádné hotové APK ani releasy. APK si sestavíte a podepíšete sami.

## Co umí

- **Mřížka aplikací:** 6 sloupců se všemi aplikacemi, které mají launcher pro TV (`LEANBACK_LAUNCHER`). Aplikace jde přesunout nebo skrýt. Nově nainstalované aplikace mají štítek *NOVÉ*.
  - Výchozí pořadí při prvním spuštění odpovídá autorově TV (seznam `PREFERRED` v `AppOrder.java`). Ostatní aplikace následují abecedně, Obchod Play a Nastavení jsou na konci. Seznam si upravte podle sebe.
- **ČT24 jako výchozí zdroj:** první dlaždice a video na pozadí. Adresu streamu zjišťuje aplikace z veřejného endpointu ČT `stream-data` (stejného, jaký volá webový přehrávač ČT24). Před vypršením tokenu ji sama obnoví.
  - Pokud ČT stream zablokuje (`isBlocked`, HTTP 451) nebo ho nabízí jen s DRM, aplikace to **respektuje**. Zobrazí hlášku a nabídne *Otevřít iVysílání*. DRM ani žádné přihlášení neobchází.
- **Action Reel (volitelné):** pevná veřejná HLS adresa Red Bull TV (bez tokenu a bez DRM). Spouští se **jen ručně**, nikdy automaticky.
  - Když Red Bull stream změní nebo vypne, dlaždice prostě přestane fungovat. Aplikace pak nepřepne na jiný zdroj.
  - Dlaždici jde skrýt nebo přesunout jako aplikaci.
- **Automatické ČT24:** spouští vždy jen ČT24, nikdy Action Reel. Režim se nastavuje v dlaždici *Launcher*:
  - vypnuto,
  - jen po zapnutí,
  - po zapnutí + po probuzení jen z launcheru,
  - po zapnutí i každém probuzení (výchozí). Po rozsvícení obrazovky v tomto režimu přenese launcher do popředí i přes aplikaci, která byla otevřená.

  Po zapnutí TV se video spustí nejvýš jednou za start systému. Stisk Home ani návrat z aplikace přehrávání nespouští.
- **Dlaždice *Launcher*:** režim automatického ČT24, skryté aplikace, obnovení výchozího pořadí, systémové nastavení a informace o aplikaci.

## Ovládání dálkovým ovladačem

| Tlačítko | Akce |
|---|---|
| Šipky | pohyb v mřížce |
| OK | otevře aplikaci nebo pustí ČT24 / Action Reel. Když už vybraný zdroj hraje, jen skryje mřížku. |
| Dlouhé OK nebo Menu | menu dlaždice: *Přesunout*, *Skrýt*, *Informace o aplikaci* |
| Přesun dlaždice | šipkami; OK nebo Zpět přesun ukončí |
| Zpět | během přehrávání skryje mřížku. Launcher se nikdy neukončí. |
| Jakákoli klávesa, když je mřížka skrytá | jen znovu ukáže mřížku |
| Home | otevře TV Minimal (potřebuje službu přístupnosti, viz níže). Běžící přehrávání se nerestartuje. |
| Hlasitost, vypínač, Nastavení, Vstup | projdou do systému beze změny |

Během přehrávání se mřížka po 10 s bez stisku klávesy sama skryje.

## Proč služba přístupnosti (a co dělá)

Na Google TV má systémová plocha (`com.google.android.apps.tv.launcherx`) při výběru Home přednost před launcherem třetí strany. Nastavení výchozí aplikace pro Home proto nestačí. Na testované TV ani přidělení role HOME nezměnilo, co otevře tlačítko Home. Tlačítko Home proto přesměrovává služba přístupnosti **„TV Minimal – tlačítko Home“** (`HomeKeyService`):

- Z kláves zachytí **jen Home**. Ostatní klávesy hned propustí a nikam je neukládá.
- Sleduje jen událost „zobrazilo se okno“ a jen od balíčku Google TV Home. Když se zobrazí jeho plocha (po startu, probuzení, Zpět do plochy), přenese do popředí TV Minimal.
- Má `canRetrieveWindowContent="false"`, takže **nečte obsah obrazovky**. Stisky kláves neukládá a nic neodesílá. Lokálně si pamatuje jen identitu posledního startu systému, aby po startu přesměrovala jen jednou (viz `app/res/xml/home_key_service.xml` a `HomeKeyService.java`).

Přesto jde o **citlivé oprávnění**. Android před jeho zapnutím varuje, že služba s filtrem kláves technicky dostává stisky kláves. Zapínejte ji jen u APK, které jste sami sestavili ze zdrojového kódu, kterému věříte.

Bez této služby launcher normálně funguje, když ho otevřete ručně, ale Home vede do Google TV. Aplikace pak ukáže panel s tlačítkem *Otevřít Přístupnost*.

**Google TV Home zůstává nainstalovaná i zapnutá.** TV Minimal nic nevypíná a jen se dává do popředí. Při startu TV se proto může nakrátko ukázat původní plocha.

## Architektura

- **Java** (zdroj/cíl 8) a klasické **Android Views**: `GridView`, `SurfaceView`, dialogy. Bez Compose, bez AppCompat a bez Gradle.
- **Media3 ExoPlayer 1.11.1 (HLS):** přehrávač se vytváří při spuštění a úplně uvolňuje při zastavení nebo otevření jiné aplikace. Má omezené buffery a audio focus.
- Vlastní build skript `app/build.sh`: `aapt2 → javac → R8 (bez obfuskace) → zipalign → apksigner`.
- Logika bez závislosti na Androidu je v `app/src/core` a testuje se JUnitem na běžném JVM:
  - stavový automat přehrávání,
  - autoplay po startu a probuzení,
  - pořadí a skrývání dlaždic,
  - parser `stream-data`,
  - automatické skrytí mřížky,
  - povolené intent extras.
- Oprávnění: `INTERNET` a `ACCESS_NETWORK_STATE`. `allowBackup="false"`, nešifrovaný HTTP je zakázaný. Podepsané URL streamu se nelogují (`Redact`).

```
app/
  AndroidManifest.xml, proguard.pro, res/
  src/core/   čistá logika (cz.tvminimal.core)
  src/main/   Activity, přehrávač, resolver ČT24, služba přístupnosti (cz.tvminimal.home)
  test/java/  JUnit testy pro src/core
  build.sh, test.sh, fetch_deps.sh, deps.lock, tools/
```

## Sestavení

### Požadavky

- Linux s `bash`, `curl`, `sha256sum`, `unzip` a `/usr/bin/python3` (vyzkoušeno na Linuxu).
- **JDK 17** (proměnná `JAVA_HOME`).
- **Android SDK** s `build-tools;34.0.0` a `platforms;android-34` (proměnná `ANDROID_SDK`). Pro instalaci na TV i `platform-tools` (adb):
  ```bash
  sdkmanager --sdk_root="$ANDROID_SDK" "build-tools;34.0.0" "platforms;android-34" "platform-tools"
  ```

### Závislosti a testy

Knihovny třetích stran nejsou součástí repozitáře. `fetch_deps.sh` je stáhne z Google Maven a Maven Central do `app/libs/` (je v `.gitignore`) a ověří jejich SHA-256 podle `app/deps.lock`.

`app/tools/resolve_deps.py` je jen pomocník pro údržbu, kterým se dohledá strom závislostí při změně verzí. Hashe nekontroluje. Po jeho použití je potřeba ručně aktualizovat `deps.lock`.

```bash
export JAVA_HOME=/cesta/k/jdk17 ANDROID_SDK=/cesta/k/android-sdk
cd app
./fetch_deps.sh
./test.sh            # v době zveřejnění: OK (73 tests)
```

### Vlastní podpisový klíč

Repozitář **neobsahuje žádný klíč**. Vytvořte si vlastní mimo repozitář a zálohujte ho. Bez stejného klíče nejde aplikaci aktualizovat bez odinstalace (tou se ztratí nastavení).

```bash
KEYDIR=~/.config/tv-minimal-signing
mkdir -p "$KEYDIR" && chmod 700 "$KEYDIR"
( umask 077; head -c 24 /dev/urandom | base64 > "$KEYDIR/password" )
"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYDIR/release.jks" \
  -storepass:file "$KEYDIR/password" -keypass:file "$KEYDIR/password" \
  -alias tvminimal -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=TV Minimal"
```

### Build APK

```bash
cd app
TVM_KEYSTORE=~/.config/tv-minimal-signing/release.jks \
TVM_KEYSTORE_PASS_FILE=~/.config/tv-minimal-signing/password \
TVM_KEY_ALIAS=tvminimal \
VERSION_NAME=1.3.0 VERSION_CODE=7 ./build.sh
# výsledek: app/build/out/tv-minimal-home-1.3.0-release-signed.apk
```

Každá další verze musí mít vyšší `VERSION_CODE`.

## Instalace na TV

1. Na TV zapněte *Možnosti pro vývojáře* a *Ladění přes síť / USB*.
2. Nainstalujte APK. `<IP_TV>` nahraďte adresou vaší televize:
   ```bash
   export TV=<IP_TV>:5555
   adb connect "$TV"
   adb -s "$TV" install -r app/build/out/tv-minimal-home-<VERZE>-release-signed.apk
   ```
3. Na TV zapněte službu: *Nastavení → Systém → Přístupnost → TV Minimal – tlačítko Home*. Přesný název menu se může lišit podle TV.
4. Stiskněte Home. Mělo by se otevřít TV Minimal.

Aktualizace se dělá stejným `adb install -r` s vyšším `VERSION_CODE` a stejným klíčem. Nastavení a pořadí dlaždic zůstanou zachované.

## Návrat zpět (bez mazání dat)

- **Vypnout přesměrování Home:** vypněte službu *TV Minimal – tlačítko Home* v Přístupnosti. Home pak zase otevře Google TV. TV Minimal zůstane nainstalovaná i s nastavením a jde dál otevřít ze seznamu aplikací.
- **Starší verze:** produkční TV obvykle odmítne `adb install -d` na nižší `VERSION_CODE`. **Neodinstalovávejte**, odinstalace smaže pořadí, skryté aplikace i zapnutí služby. Sestavte starší zdrojový kód s **vyšším** `VERSION_CODE` a stejným klíčem a nainstalujte ho přes `adb install -r`.
- **Úplné odstranění:** *Nastavení → Aplikace → TV Minimal → Odinstalovat* nebo `adb -s "$TV" uninstall cz.tvminimal.home`. Smaže jen data TV Minimal. Google TV Home se nemění.

## Testovací háčky

`HomeActivity` je kvůli roli Home exportovaná. Release build proto přijímá jen neškodné extras:
- `tvm_play`: spustí ČT24, jen když nic nehraje,
- `tvm_stop`,
- `tvm_via`.

Příklad:

```bash
adb -s "$TV" shell am start -n cz.tvminimal.home/.HomeActivity --ez tvm_play true
```

Extras `tvm_test_*` (vynucené chyby a obnovy) fungují jen v debuggable buildu. `build.sh` vytváří release build.

## Soukromí

Aplikace nemá reklamní ani analytické SDK. Loguje jen do lokálního logcatu a podepsané URL v logu zkracuje. V logcatu a ve výpisu `dumpsys activity` jsou ale i názvy spouštěných a nainstalovaných balíčků, skryté aplikace a identita startu systému, takže tyto výpisy nesdílejte bez kontroly. Text „bez telemetrie“ v dialogu *O aplikaci* platí jen pro tuto aplikaci. Síťově komunikuje jen s API České televize a s CDN, kterou toto API vrátí, a s Red Bull TV, ale jen při ručním spuštění Action Reel. Skript `fetch_deps.sh` stahuje při buildu z Maven repozitářů.

**Z televize to ale nedělá zařízení bez telemetrie.** Google TV, systém výrobce i ostatní aplikace dál sbírají a odesílají data podle svých pravidel.

## Omezení

- Vyzkoušené na jediném zařízení. Přesměrování Home počítá s balíčkem Google TV Home `com.google.android.apps.tv.launcherx`. Na jiných TV nebo verzích systému se to může chovat jinak.
- Chování v reálném provozu je ověřené jen omezeně: start TV, probuzení po noci a dlouhé přehrávání. Detekce probuzení stojí na systémových událostech zapnutí a vypnutí obrazovky.
- Unit testy pokrývají jen `app/src/core`. UI, přehrávač a služba přístupnosti testy nemají. Testy na zařízení se dělaly soukromě a jejich skripty nejsou součástí repozitáře.
- Dostupnost streamů je zcela v rukou jejich provozovatelů. Mohou je kdykoli omezit, zablokovat nebo změnit. Podmínky jejich užití dodržujte sami. Projekt je určený k osobnímu použití.
- Repozitář je zdrojový kód, **ne kompletní záloha instalace**. Neobsahuje podpisový klíč, hotové APK ani nastavení konkrétní TV.

## Licence

Licence zatím **není určená**. Dokud autor žádnou nezvolí, neuděluje se tím žádná licence ke kódu. Zveřejnění slouží ke čtení a inspiraci.

Knihovny třetích stran (AndroidX/Media3, Guava, Kotlin stdlib, JUnit, Hamcrest, org.json) se stahují zvlášť a platí pro ně jejich vlastní licence. Názvy ČT24, iVysílání, Red Bull TV a Google TV patří jejich vlastníkům. Repozitář neobsahuje jejich loga ani jiné materiály.
