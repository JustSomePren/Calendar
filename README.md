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

## Propojení s Galerií

Když je v telefonu nainstalovaná i aplikace **Galerie**, propojí se samy, nic se nenastavuje:

- **Fotky u události**: v editoru proběhlé události jsou náhledy fotek, které vznikly během ní
  (u událostí s časem 15 min předem až 30 min po konci, u celodenních celé dny).
  Klepnutím se otevřou v Galerii. Snímky obrazovky se nepočítají.
- **Dovolená / výlet** (jen u kalendářů s kategorií Osobní): přepínač v editoru.
  Galerie pak z fotek těch dní udělá album (název = název události, nebo vlastní)
  a událost ukáže ve svém kalendáři jako barevný pruh.
- **Posílat do Google Fotek**: u dovolené vybereš Google účet (z účtů přidaných v Galerii).
  Galerie pak fotky z těch dní sama pošle do toho účtu a alba, jen na Wi-Fi.
- **Místo podle fotek**: pokud mají fotky GPS polohu, editor ukáže, kde vznikly
  (tlačítko „Na mapě“, a když událost nemá místo, i „Použít“). Bez polohy ve fotkách se řádek neukáže.

Dovolená se ukládá jen v této aplikaci (jako priorita), do Googlu se nepřenáší.
Aplikace si navzájem povolí přístup jen tehdy, když je ta druhá podepsaná správným klíčem.

## Samsung Kalendář

Samsung kalendář se na telefonech jiných značek synchronizovat nedá. Na telefonu Samsung události vyexportuj nebo sdílej jako soubor `.ics`, pošli si ho do telefonu a v nastavení → „Import ze Samsung Kalendáře“ ho vyber. Jde vybrat i víc souborů naráz, události, které už v kalendáři jsou, se přeskočí.

## Data a baterie

- **Mobilní data: žádná.** Aplikace nemá oprávnění k internetu. Synchronizaci s Googlem dělá systém stejně jako bez ní.
- **Na pozadí nic neběží pravidelně.** Telefon probudí jen budík konkrétního upozornění.
- O půlnoci se posune „dnes“ ve widgetu, ale až když se telefon probudí sám.
- Po změně v kalendáři se upozornění a widget přepočítají jednou. Změny ze synchronizace se slučují (30 s až 2 min).
- Každý přepočet je jedno čtení kalendáře na 7 dní dopředu, trvá zlomek sekundy.

## Podpisový klíč (jednou, před první instalací)

Klíč není v repozitáři (je veřejný). Obě aplikace, Kalendář i Galerie, mají **stejný** klíč,
díky tomu se navzájem poznají a nová verze se vždy nainstaluje přes starou.

1. Na GitHubu otevři repozitář → **Settings** → **Secrets and variables** → **Actions**.
2. **New repository secret**, název `SIGNING_KEY`, hodnota = celý obsah souboru `SIGNING_KEY.txt`.
3. **New repository secret**, název `SIGNING_KEY_PASSWORD`, hodnota = obsah `SIGNING_KEY_PASSWORD.txt`.
4. Totéž udělej v druhém repozitáři (Calendar i Photo_Gallery).
5. V **Actions** spusť znovu poslední běh **Build APK** (bez klíče skončí chybou „Chybí secret SIGNING_KEY“).
   Hotové APK se objeví v **Releases** pod odkazem z části Instalace.

Soubor `podpisovy-klic.p12` a heslo si **schovej** (správce hesel nebo soukromý Disk).
Bez nich nepůjde vydat novou verzi, která se nainstaluje přes starou, a Google záloha se nevrátí.
Do repozitáře je nikdy nedávej.

Vlastní sestavení s tímto klíčem: nastav proměnné `SIGNING_KEY_FILE` (cesta k `.p12`) a `SIGNING_KEY_PASSWORD`.
Bez nich se APK podepíše vývojovým klíčem počítače a přes verzi z GitHubu se nenainstaluje.

## Záloha a nová instalace

Všechno, co si aplikace pamatuje (kategorie, skryté kalendáře, pracovní filtr, priority,
upozornění, dovolené), se **samo zálohuje do Google účtu** a po nové instalaci se vrátí:

- Telefon musí mít zapnuté *Nastavení → Google → Zálohování*.
- Záloha běží zhruba jednou denně (v noci při nabíjení na Wi-Fi), takže dnešní změny v ní ještě být nemusí.
- Funguje jen se stejným podpisovým klíčem (viz výše).

Před odinstalací proto pro jistotu: *Nastavení → Záloha → Uložit do souboru*. Po nové instalaci
*Načíst ze souboru*. Samotné události jsou v Google Kalendáři, ty se vrátí vždy.

Funguje to i na **novém telefonu**. Android tam kalendáře a události očísluje znovu, aplikace je ale
pozná podle Google ID (kalendář podle účtu a jeho Google ID, událost podle Google ID události;
nesynchronizovanou podle názvu a začátku). Dokud se nový telefon s Googlem nesynchronizuje,
nastavení počká a přiřadí se samo, jakmile události dorazí.

## Instalace na telefon

1. V telefonu otevři odkaz https://github.com/JustSomePren/Calendar/releases/download/nejnovejsi/kalendar.apk
2. Stažený soubor otevři (v oznámení o stažení) a klepni na **Instalovat**.
3. Povol instalaci z neznámých zdrojů, když se telefon zeptá (jen poprvé).
4. Při prvním spuštění povol přístup ke kalendářům a upozorněním.

Odkaz vede vždy na nejnovější verzi, aktualizace je stejná: stáhnout a nainstalovat přes starou.

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

Výsledky a pokrytí kódu jsou v `app/build/reports/`, na GitHubu v Actions → Artifacts → `testy-a-pokryti`.

## Vlastní sestavení

Otevři složku v Android Studiu a spusť, nebo v terminálu:

```
./gradlew testDebugUnitTest assembleDebug
```
