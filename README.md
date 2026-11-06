# Calendar

Jednoduchý kalendář pro Android s přehledným zobrazením celého měsíce (podobně jako Samsung Kalendář).

Zobrazuje kalendáře, které už má telefon synchronizované, tedy i všechny Google kalendáře. Nepotřebuje žádné přihlašování.

## Co umí

- měsíc na celou obrazovku, názvy událostí přímo ve dnech
- listování měsíců tažením do stran, tlačítko „Dnes“
- klepnutí na den ukáže seznam událostí
- výběr, které kalendáře se zobrazí (☰ vpravo nahoře)
- přidání a úprava události (zatím přes systémový editor, tedy Google Kalendář)

## Instalace na telefon

1. Na GitHubu otevři **Actions** → poslední běh **Build APK**.
2. Dole v **Artifacts** stáhni `kalendar-apk` a rozbal ho.
3. Soubor `app-debug.apk` pošli do telefonu a otevři ho.
4. Povol instalaci z neznámých zdrojů, když se telefon zeptá.
5. Při prvním spuštění povol přístup ke kalendářům.

Pokud nějaký Google kalendář chybí, zapni u něj synchronizaci v aplikaci Google Kalendář → Nastavení → tvůj účet.

## Vlastní sestavení

Otevři složku v Android Studiu a spusť, nebo v terminálu:

```
./gradlew assembleDebug
```
