# Calendar

Kalendář pro Android s přehledným zobrazením celého měsíce (podobně jako Samsung Kalendář) a chytrými upozorněními.

Zobrazuje kalendáře, které už má telefon synchronizované, tedy i všechny Google kalendáře. Nepotřebuje žádné přihlašování, změny se přes telefon synchronizují zpět do Googlu.

## Co umí

- měsíc na celou obrazovku, události přímo ve dnech, vícedenní jako jeden pruh
- listování měsíců tažením do stran, tlačítko „Dnes“, klepnutím na den jeho přehled
- vlastní editor: název, celý den, začátek a konec, kalendář, opakování, priorita, místo, poznámka, účastníci
- u opakované události změna nebo smazání jen jednoho výskytu, nebo celé série
- widget na plochu s celým měsícem
- kategorie kalendářů a chytrá upozornění (viz níže)
- import událostí ze Samsung Kalendáře přes soubor .ics

## Kategorie kalendářů

V nastavení (ozubené kolo) má každý kalendář kategorii:

- **Osobní** (výchozí): upozorní na všechny události
- **Pracovní**: upozorní na události, které pořádáš ty, a na ty, kde je mezi účastníky některý z tvých e-mailů (a událost jsi neodmítl), nebo které obsahují zadané slovo (např. `testProjekt`) v názvu, poznámce či místě
- **Tichý**: jen zobrazit, neupozorňovat

U každé události jde v editoru kategorii přebít volbou **Upozornění**:

- **Podle kalendáře** (výchozí): editor rovnou ukáže, jestli upozornění přijde
- **Vždy (sledovat)**: třeba cizí opakovaná porada nebo událost, kde tě nikdo neoznačil
- **Nikdy**: třeba týdenní věc v osobním kalendáři, na kterou upozorňovat nepotřebuješ

U opakované události volba platí pro celou sérii. Vypnout upozorňování jde i tlačítkem **Neupozorňovat** přímo v oznámení.

## Kdy přijde upozornění

| Událost | Upozornění |
| --- | --- |
| vícedenní | 3 dny předem a den předem v 18:00 |
| celodenní | den předem v 18:00 |
| ráno (do 9:00) | večer předem ve 20:00 a 30 min předem |
| dopoledne (do 12:00) | týž den v 7:30 a 30 min předem |
| ostatní | 30 min předem |
| opakovaná | jen nejbližší z výše uvedených |

Priorita události (nastavuje se v editoru) to upraví:

- **Nízká**: jen nejbližší upozornění, potichu
- **Normální**: podle tabulky
- **Vysoká**: navíc 2 h a večer předem (u celodenních i ráno v 8:00), vyskakovací upozornění; v kalendáři má před názvem „!“

Priorita a volba upozornění se ukládají jen v této aplikaci, do Googlu se nepřenášejí.

Aby upozornění nechodila dvakrát, vypni je v aplikaci Google Kalendář (v nastavení je na to tlačítko).

## Samsung Kalendář

Samsung kalendář se na telefonech jiných značek synchronizovat nedá. Na telefonu Samsung události vyexportuj nebo sdílej jako soubor `.ics`, pošli si ho do telefonu a v nastavení → „Import ze Samsung Kalendáře“ ho vyber. Jde vybrat i víc souborů naráz, události, které už v kalendáři jsou, se přeskočí.

## Data a baterie

- **Mobilní data: žádná.** Aplikace nemá oprávnění k internetu. Synchronizaci s Googlem dělá systém stejně jako bez ní.
- **Na pozadí nic neběží pravidelně.** Telefon probudí jen budík konkrétního upozornění.
- O půlnoci se posune „dnes“ ve widgetu, ale až když se telefon probudí sám.
- Po změně v kalendáři se upozornění a widget přepočítají jednou. Změny ze synchronizace se slučují (30 s až 2 min).
- Každý přepočet je jedno čtení kalendáře na 7 dní dopředu, trvá zlomek sekundy.

## Instalace na telefon

1. Na GitHubu otevři **Actions** → poslední běh **Build APK**.
2. Dole v **Artifacts** stáhni `kalendar-apk` a rozbal ho.
3. Soubor `app-debug.apk` pošli do telefonu a otevři ho.
4. Povol instalaci z neznámých zdrojů, když se telefon zeptá.
5. Při prvním spuštění povol přístup ke kalendářům a upozorněním.

Pokud nějaký Google kalendář chybí, zapni u něj synchronizaci v aplikaci Google Kalendář → Nastavení → tvůj účet.

## Widget

Dlouze podrž prázdné místo na ploše → Widgety → Kalendář → přetáhni na plochu. Velikost jde měnit.

## Testy

Testy běží na počítači bez telefonu (Robolectric simuluje Android i kalendář):

- pravidla upozornění, kategorie a sledování událostí
- ukládání, úpravy jednoho výskytu, účastníci, import .ics
- celé postupy klikáním: vytvoření, smazání, nastavení, panel dne, widget, restart telefonu

```
./gradlew testDebugUnitTest koverHtmlReportDebug
```

Test `ScreenshotTour` uloží snímky všech obrazovek s ukázkovými daty do `app/build/shots/`.

Výsledky a pokrytí kódu jsou v `app/build/reports/`, na GitHubu v Actions → Artifacts → `testy-a-pokryti`.

## Vlastní sestavení

Otevři složku v Android Studiu a spusť, nebo v terminálu:

```
./gradlew testDebugUnitTest assembleDebug
```
