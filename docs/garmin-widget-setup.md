# ClimbPro Browse Widget — Setup gids

Dit document legt stap voor stap uit hoe je de ClimbPro Browse widget bouwt, op je Garmin FR255 Music zet, en koppelt aan de Android companion app.

---

## Wat je nodig hebt

- **Garmin Connect IQ SDK** — download via [developer.garmin.com/connect-iq/sdk](https://developer.garmin.com/connect-iq/sdk/)
- **Developer key** — eenmalig aanmaken in Garmin's developer portal (zie stap 1)
- **Android companion app** geïnstalleerd op je telefoon
- **Garmin FR255 Music** verbonden met de Garmin Connect app

---

## Stap 1 — Developer key aanmaken (eenmalig)

1. Ga naar [apps.garmin.com/en-US/developer](https://apps.garmin.com/en-US/developer) en log in met je Garmin account.
2. Klik op **Generate Key**.
3. Sla het `.der`-bestand op, bijvoorbeeld op `C:\garmin\developer_key.der`.

Je hebt dit bestand nodig bij elke build.

---

## Stap 2 — Connect IQ SDK installeren

1. Download de SDK installer van [developer.garmin.com/connect-iq/sdk](https://developer.garmin.com/connect-iq/sdk/).
2. Installeer de SDK (standaard in `C:\Users\<jouw naam>\AppData\Roaming\Garmin\ConnectIQ\Sdks\`).
3. Zorg dat `monkeyc` beschikbaar is in je PATH, of gebruik het volledige pad:
   ```
   C:\Users\<naam>\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-<versie>\bin\monkeyc.exe
   ```
4. Download ook het **device profile** voor de FR255 Music als de installer dat niet automatisch doet (via de SDK Manager in VS Code / Eclipse plugin).

---

## Stap 3 — Widget bouwen

Open een terminal in de projectmap en voer uit:

```powershell
monkeyc `
  -o garmin-widget\ClimbBrowse.prg `
  -f garmin-widget\monkey.jungle `
  -y C:\garmin\developer_key.der `
  -d fr255m `
  -r
```

Vlaggen:
| Vlag | Betekenis |
|------|-----------|
| `-o` | Uitvoerbestand (het `.prg` dat op de watch gaat) |
| `-f` | Jungle build-bestand |
| `-y` | Pad naar jouw developer key |
| `-d` | Doeldevice — `fr255m` voor Forerunner 255 Music |
| `-r` | Release build (kleiner, geen debug output) |

Als de build slaagt staat `garmin-widget\ClimbBrowse.prg` klaar.

> **Tip:** Je kunt ook de bestaande datafield opnieuw bouwen op dezelfde manier met `-f garmin\monkey.jungle -o garmin\ClimbPro.prg`.

---

## Stap 4 — Widget op de watch zetten (sideloaden)

### Optie A — via USB (snelst)

1. Verbind de Garmin met je pc via USB.
2. In Windows Verkenner verschijnt de watch als USB-schijf.
3. Kopieer `garmin-widget\ClimbBrowse.prg` naar:
   ```
   <GARMIN_DRIVE>\GARMIN\APPS\
   ```
4. Verwijder de USB-verbinding veilig.
5. De widget verschijnt nu in de widget-loop (swipe omhoog/omlaag op het watch face).

### Optie B — via Connect IQ simulator (testen zonder watch)

```powershell
monkeyc `
  -o garmin-widget\ClimbBrowse.prg `
  -f garmin-widget\monkey.jungle `
  -y C:\garmin\developer_key.der `
  -d fr255m
```
Start dan de simulator vanuit de SDK en laad het `.prg`-bestand via **File → Run**.

---

## Stap 5 — Android app: sturen naar beide UUIDs

De widget heeft een eigen app-UUID (`fedcba9876543210fedcba9876543210`), los van de datafield (`0123456789abcdef0123456789abcdef`). De Android companion app moet data naar **beide** sturen.

Zoek in de Android-broncode de klasse die via de Connect IQ Mobile SDK berichten verstuurt (waarschijnlijk `GarminSyncManager.java` of vergelijkbaar). Daar staat iets als:

```java
connectIQ.sendMessage(device, app, payload, new ConnectIQ.IQSendMessageListener() { ... });
```

Voeg een tweede `sendMessage`-aanroep toe met de widget-UUID:

```java
// Bestaand: stuur naar datafield
IQApp datafieldApp = new IQApp("0123456789abcdef0123456789abcdef");
connectIQ.sendMessage(device, datafieldApp, payload, listener);

// Nieuw: stuur ook naar de widget
IQApp widgetApp = new IQApp("fedcba9876543210fedcba9876543210");
connectIQ.sendMessage(device, widgetApp, payload, listener);
```

Zolang de widget op de watch geïnstalleerd is, wordt het bericht automatisch ontvangen zodra de Garmin Connect app verbinding heeft.

---

## Stap 6 — Testen op de watch

1. Open de Garmin Connect app op je telefoon en wacht tot de watch verbinding heeft.
2. Trigger een sync vanuit de companion app (bijv. via de "Sync now" knop).
3. Swipe op je watch naar de widget-loop en open **ClimbPro Browse**.
4. Je ziet:
   - **Scherm 1:** Routenaam + aantal klimmetjes
   - Druk **SELECT (middelste knop)** → klimlijst
   - Scroll met **UP/DOWN** door de klimmetjes
   - Druk **SELECT** → segmentprofiel met gekleurde balkjes + stats onderaan
   - Druk **BACK** om terug te gaan

---

## Knopindeling FR255 Music

```
         ▲ UP
    ┌────────────┐
    │            │
◄ BACK          SELECT ►
    │            │
    └────────────┘
         ▼ DOWN
```

| Knop | Actie |
|------|-------|
| SELECT | Bevestigen / dieper navigeren |
| BACK | Terug naar vorig scherm |
| UP | Scroll omhoog in klimlijst |
| DOWN | Scroll omlaag in klimlijst |

---

## Problemen oplossen

| Probleem | Oplossing |
|----------|-----------|
| `monkeyc: command not found` | Voeg de SDK `bin/`-map toe aan PATH of gebruik het volledige pad |
| Widget verschijnt niet op watch | Controleer of het `.prg`-bestand in `GARMIN\APPS\` staat; herstart de watch |
| "No data" in widget | App heeft nog geen sync ontvangen — open Garmin Connect en wacht op verbinding, of trigger handmatig sync |
| Bericht komt niet aan | Controleer in de Android app of beide UUIDs aanwezig zijn; de widget-UUID moet exact overeenkomen met die in `manifest.xml` |
| Build error op `Ui.DataField` | Zorg dat `ClimbProView.mc` **niet** in de widget-sourcepath zit — de widget gebruikt alleen de bestanden in `garmin-widget/source/` |
