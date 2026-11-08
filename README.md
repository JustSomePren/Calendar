# Calendar

Kalendář pro Android s přehledným zobrazením celého měsíce (podobně jako Samsung Kalendář) a chytrými upozorněními.

Zobrazuje kalendáře, které už má telefon synchronizované, tedy i všechny Google kalendáře. Nepotřebuje žádné přihlašování, změny se přes telefon synchronizují zpět do Googlu.

## Co umí

- měsíc na celou obrazovku, události přímo ve dnech, vícedenní jako jeden pruh
- listování měsíců tažením do stran, tlačítko „Dnes“, klepnutím na den jeho přehled
- levé menu (☰): připojené účty a všechny jejich kalendáře se zaškrtnutím, co vidíš; kalendář, který telefon zatím nesynchronizuje (např. pracovní přidaný v Google Kalendáři na webu), se zaškrtnutím začne synchronizovat
- „Nový kalendář v telefonu“: kalendář bez účtu, zůstává jen v telefonu (nezálohuje se)
- klepnutím na den detail se všemi jeho událostmi, seřazenými: osobní, pracovní, které se tě týkají, ostatní pracovní, zbytek; tažením do stran další dny
- „Přidat účet“ nabídne účty Google, které už v telefonu jsou (bez nového přihlašování), aplikace u vybraného zapne a spustí synchronizaci kalendáře; jde přidat i úplně nový účet
- přidržení dne a tažení přes další dny založí celodenní událost přes vybrané dny (samotné přidržení založí událost na ten den)
- vlastní editor: název, celý den, začátek a konec, kalendář, opakování, priorita, místo, poznámka, účastníci
- u opakované události změna nebo smazání jen jednoho výskytu, nebo celé série
- widget na plochu s celým měsícem
- kategorie kalendářů a chytrá upozornění (viz níže)
- import událostí ze Samsung Kalendáře přes soubor .ics

## Kategorie kalendářů

V nastavení (ozubené kolo) má každý kalendář kategorii:

- **Osobní** (výchozí): upozorní na všechny události
- **Pracovní**: upozorní na události, které pořádáš ty nebo jsi je vytvořil v této aplikaci, a na ty, kde je mezi účastníky některý z tvých e-mailů (a událost jsi neodmítl), nebo které obsahují zadané slovo (např. `testProjekt`) v názvu, poznámce či místě
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

Samsung kalendář se na telefonech jiných značek synchronizovat nedá. Na starém telefonu Samsung ho ale tahle aplikace umí zkopírovat do Google kalendáře:

1. Na telefon Samsung nainstaluj tuto aplikaci (stejné APK, stačí Android 8 a novější) a povol jí přístup ke kalendářům.
2. Ověř, že je na Samsungu přihlášený tvůj Google účet a synchronizuje kalendář.
3. ☰ → Nastavení → **Zkopírovat kalendář**: „Z kalendáře“ vyber Samsung kalendář (nebo „Můj kalendář“), „Do kalendáře“ svůj Google kalendář, klepni na Zkopírovat.
4. Google události pošle do nového telefonu. Kopírovat jde i opakovaně, co už v cíli je, se přeskočí.

Zkopírují se i opakované události s vynechanými a změněnými výskyty. Účastníci se nekopírují, aby nikomu znovu nepřišla pozvánka.

Jednotlivé sdílené události (.ics/.vcs) jde naimportovat v Nastavení → Import ze souboru.

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

Testy běží i pro Android 9; Robolectric si tu verzi stahuje sám, přes zrcadlo s `-ProbolectricRepo=<url>`.

Test `ScreenshotTour` uloží snímky všech obrazovek s ukázkovými daty do `app/build/shots/`.

Výsledky a pokrytí kódu jsou v `app/build/reports/`, na GitHubu v Actions → Artifacts → `testy-a-pokryti`.

## Vlastní sestavení

Otevři složku v Android Studiu a spusť, nebo v terminálu:

```
./gradlew testDebugUnitTest assembleDebug
```
