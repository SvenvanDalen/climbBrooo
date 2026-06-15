# Telefoon ⇄ Garmin verbinding

Uitleg van hoe de Android-companion app verbindt en data uitwisselt met de
ClimbPro-app op de Forerunner 255 Music, via de **Connect IQ Communications
API**. Dit document beschrijft de werkende implementatie (Android-zijde:
`nl.paree.climbpro.connectiq`).

> Wil je het alleen aan de praat krijgen? Spring naar [Verifiëren op de watch](#verifiëren-op-de-watch).

---

## 1. De onderdelen

```
┌─────────────────────────┐     Bluetooth / Garmin Connect Mobile     ┌────────────────────────┐
│  Android-telefoon       │  ◄──────────────────────────────────────► │  Forerunner 255 Music  │
│                         │                                            │                        │
│  ClimbProApplication    │                                            │  ClimbWidgetApp        │
│   └─ ConnectIqClient ───┼──► Garmin Connect IQ Mobile SDK (.aar) ────┼──► CommListener        │
│        └─ WatchRequest- │        (com.garmin.android.connectiq)      │       PhoneMessage-    │
│           Handler       │                                            │       Callback         │
│  RouteSyncWorker        │                                            │  Application.Storage   │
└─────────────────────────┘                                            └────────────────────────┘
```

De verbinding loopt **niet** rechtstreeks over Bluetooth vanuit onze app. De
**Garmin Connect Mobile** app (GCM) op de telefoon is de tussenlaag: onze app
praat met de Connect IQ SDK, de SDK bindt aan een service in GCM, en GCM doet
de daadwerkelijke Bluetooth-communicatie met de watch. Daarom moet Garmin
Connect geïnstalleerd zijn en de watch daarin gekoppeld.

| Laag | Bestand | Rol |
|---|---|---|
| App-lifecycle | `android/.../ClimbProApplication.java` | Maakt één app-brede `ConnectIqClient`, roept `connect()` aan bij opstart |
| SDK-wrapper | `android/.../connectiq/ConnectIqClient.java` | Init, device discovery, zenden, ontvangen, statusbeheer |
| App-UUID | `android/.../connectiq/ConnectIqAppId.java` | De UUID van de watch-app waarmee we praten |
| Wire-format | `android/.../connectiq/PayloadCodec.java` | JSON-bytes → `Map` (zodat de watch een Dictionary krijgt) |
| Inkomende requests | `android/.../connectiq/WatchRequestHandler.java` | Beantwoordt `LIST_ROUTES` / `LOAD_ROUTE` van de watch |
| Achtergrond-sync | `android/.../service/RouteSyncWorker.java` | WorkManager-job die routes naar de watch stuurt |
| Watch-ontvanger | `garmin-widget/source/CommListener.mc` | `PhoneMessageCallback.onMessage()` verwerkt inkomende Dictionaries |

---

## 2. Met wélke watch-app praat de telefoon?

Er zijn **twee** Connect IQ apps op de watch:

- **Datafield** `ClimbProApp` — `garmin/manifest.xml`, id `0123…`
- **Widget** `ClimbWidgetApp` — `garmin-widget/manifest.xml`, id `fedcba9876543210fedcba9876543210`

De sync-tegenhanger van de telefoon is de **widget** (zie
`docs/superpowers/specs/2026-06-08-garmin-widget-sync-and-storage-design.md`).
De widget vraagt routes op, slaat ze op in `Application.Storage`, en de datafield
leest tijdens een activiteit uit die opslag.

> **UUID-regel:** de telefoon (`ConnectIqAppId.VALUE`) en de watch
> (`garmin-widget/manifest.xml` → `id`) moeten **exact dezelfde** string zijn.
> Connect IQ schrijft app-id's als **32 hex-tekens zonder streepjes** — dus
> géén `xxxxxxxx-xxxx-…`-formaat. Wijzig je de één, wijzig dan de ander mee.

---

## 3. De verbindingslevenscyclus (Android-zijde)

`connect()` is **asynchroon**. Je krijgt niet meteen een verbonden watch terug —
je registreert callbacks en de status komt later binnen via `LiveData<ConnectIqState>`.

```
connect()
  │  state = CONNECTING
  ▼
ConnectIQ.getInstance(ctx, WIRELESS).initialize(ctx, autoUI=true, listener)
  │
  ├─► onInitializeError(status)        → state = ERROR   (bv. GCM_NOT_INSTALLED)
  │
  └─► onSdkReady()                      → handleSdkReady()
         │
         ├─ getConnectedDevices()  leeg? → state = ERROR, nieuwe poging na 10 s
         │
         ├─ device = devices.get(0)
         ├─ registerForDeviceEvents(device, …)   → onDeviceStatusChanged → state = CONNECTED / DISCONNECTED
         └─ registerForAppEvents(device, iqApp, …) → onMessageReceived   → WatchRequestHandler
                                                   → state = CONNECTED
```

Statuswaarden (`ConnectIqState`): `DISCONNECTED → CONNECTING → CONNECTED →
SENDING → …`, en `ERROR` bij een initialisatie- of verbindingsfout.

`connect()` is **idempotent**: een tweede aanroep terwijl we al verbinden of
verbonden zijn doet niets, zodat de SDK nooit dubbel wordt geïnitialiseerd.

De client is **app-breed** (`ClimbProApplication.connectIqClient()`). Maak nooit
je eigen `new ConnectIqClient(...)` aan in een Activity of Worker — er is één
SDK-singleton en één verbinding.

> **Telefoon-only update:** `ClimbProApplication.onCreate()` roept bij opstart
> `forceRebind()` aan (niet het kale `connect()`). Dat doet eerst een
> `ConnectIQ.shutdown()` en daarna opnieuw `initialize()`, zodat een stale
> Connect-IQ-binding in Garmin Connect Mobile — die na een *alleen-telefoon*
> app-update naar het oude, dode proces blijft wijzen — wordt opgeruimd en het
> nieuwe proces schoon herbindt. Zodra de verbinding `CONNECTED` is, stuurt de
> telefoon éénmalig een `HELLO`; de widget vraagt daarop opnieuw `LIST_ROUTES`.

---

## 4. Het wire-format: waarom een `Map`, geen bytes

De watch accepteert **alleen een Dictionary**. In `PhoneMessageCallback.onMessage`
staat letterlijk de guard:

```monkeyc
if (msg == null || !(msg instanceof Toybox.Lang.Dictionary)) { … return; }
```

De Garmin SDK serialiseert wat je meegeeft aan `sendMessage(device, app, obj, …)`:
- een Java **`Map`** → een Monkey C **`Dictionary`** ✅
- een Java **`Integer`/`Long`** → een **`Toybox.Lang.Number`** ✅
- rauwe **`byte[]`** → een **`ByteArray`** ❌ (door de watch geweigerd)

Daarom stuurt de telefoon altijd een `Map`:

- `sendMessage(Map)` — stuurt de Map rechtstreeks (bv. de `ROUTE_LIST`-respons).
- `sendPayload(byte[])` — de bestaande `ClimbPayloadBuilder` levert JSON-bytes;
  `PayloadCodec.decode(byte[])` zet die eerst terug naar een `Map<String,Object>`
  (met behoud van integer-typen) en stuurt díe. Zo blijft het v3-formaat
  ongewijzigd maar komt het toch als Dictionary aan.

Maximale berichtgrootte: **4096 bytes** (`PayloadBudget.MAX_BYTES`).

### Berichttypen (protocol)

| Richting | Bericht | Inhoud |
|---|---|---|
| watch → telefoon | `{ "type": "LIST_ROUTES" }` | widget vraagt de routelijst |
| watch → telefoon | `{ "type": "LOAD_ROUTE", "id": "…" }` | widget vraagt één route |
| telefoon → watch | `{ "type": "ROUTE_LIST", "routes": [{id,name,climbCount}] }` | antwoord op LIST_ROUTES |
| telefoon → watch | `{ "type": "HELLO" }` | telefoon meldt zich na (her)verbinden; widget vraagt opnieuw `LIST_ROUTES` |
| telefoon → watch | v3 route-payload (`{v:3, mode, routeId, name, climbs:[…]}`) | antwoord op LOAD_ROUTE / periodieke sync |

Inkomende berichten komen binnen op `registerForAppEvents` →
`onMessageReceived(…, List<Object> data, …)`. We pakken `data.get(0)`, en als dat
een `Map` is geven we het door aan `WatchRequestHandler.handleMessage(Map)`, die
op `type` dispatcht.

---

## 5. Zenden vanuit de achtergrond (sync)

`RouteSyncWorker` is een WorkManager-job (periodiek + handmatige knop). Omdat de
verbinding async is, gebruikt de worker:

1. de **app-brede** client (`((ClimbProApplication) ctx).connectIqClient()`),
   niet een eigen exemplaar;
2. een korte **wachtlus** (max ~5 s) tot `isConnected()` true is — anders
   `Result.retry()`;
3. een **blokkerende** verzending `sendPayloadBlocking(payload, 10_000)` die pas
   `true` teruggeeft als de watch `SUCCESS` bevestigt binnen 10 s. Zo betekent
   `Result.success()` echt "afgeleverd" en `Result.retry()` "nog niet gelukt".

---

## 6. Belangrijke randvoorwaarden

- **Garmin Connect Mobile moet geïnstalleerd zijn** en de FR255 daarin gekoppeld.
  Onze app praat via GCM, niet rechtstreeks.
- **Android 11+ package-visibility:** `AndroidManifest.xml` bevat een `<queries>`
  voor `com.garmin.android.apps.connectmobile`. Zonder dit kan de SDK niet aan de
  GCM-service binden en krijg je `GCM_NOT_INSTALLED`, ook al ís Garmin Connect
  geïnstalleerd.
- **Permissies:** `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, locatie, internet
  (staan in het manifest).
- **De SDK is een binaire `.aar`** onder `android/app/libs/ciq-mobile-sdk.aar`
  (gelinkt in `android/app/build.gradle`). Garmin distribueert deze los van Maven.

---

## 7. Verifiëren op de watch

Voorwaarden: FR255 gekoppeld in Garmin Connect, de **widget** (`ClimbWidgetApp`)
gesideload op de watch, minstens één route op de telefoon.

```bash
cd android
./gradlew :app:installDebug
adb logcat -s ConnectIqClient WatchRequestHandler RouteSyncWorker
```

Verwachte volgorde:

1. `Using device: <naam>` en status bereikt `CONNECTED` — **geen**
   `CIQ initialize error: GCM_NOT_INSTALLED`.
2. Widget openen → `Sent ROUTE_LIST with N routes`; de "Telefoon"-sectie vult.
3. Route kiezen op de watch → `Sent route payload … (bytes)`; de watch tekent de
   klim (bewijst dat de Dictionary-decode klopte — bytes waren stil gedropt).
4. Handmatige sync → de worker logt een geslaagde, bevestigde verzending.

### Probleemoplossing

| Symptoom | Oorzaak / fix |
|---|---|
| `GCM_NOT_INSTALLED` terwijl Garmin Connect er is | `<queries>`-blok ontbreekt in het manifest, of GCM niet ingelogd/gekoppeld |
| Status blijft `ERROR`, "No connected Garmin devices found" | Watch niet gekoppeld in Garmin Connect, of niet binnen Bluetooth-bereik. De client probeert het elke 10 s opnieuw zodra de watch weer verbonden is |
| Widget toont "Geen verbinding" | UUID-mismatch: `ConnectIqAppId.VALUE` ≠ `garmin-widget/manifest.xml` id |
| "Telefoon"-lijst leeg ná alleen-telefoon update | Stale GCM-binding. Sinds `forceRebind()` bij opstart zou dit vanzelf moeten herstellen; lukt het niet, herstart Garmin Connect Mobile + ClimbPro. Géén UUID-probleem. |
| Watch ontvangt niets / dropt bericht | Er werd `byte[]` i.p.v. een `Map` gestuurd, of payload > 4096 bytes |
| Worker blijft `retry()` | Watch niet verbonden bij sync-moment (verbinding is async) |

---

## 8. Bekende beperkingen

- `handleSdkReady` kiest `getConnectedDevices().get(0)`. Met meerdere gekoppelde
  Garmin-toestellen is dat niet per se de FR255 — een device-keuze-UI is een
  mogelijke vervolgstap.
- Geregistreerde event-listeners worden niet expliciet afgemeld; bij een
  reconnect-flow zonder `shutdown()` zouden ze kunnen opstapelen. Met de huidige
  app-brede single-connect speelt dit niet.

---

*Gerelateerd: `Documentation/ARCHITECTURE.md` (totaalplaatje),
`docs/superpowers/specs/2026-06-08-garmin-widget-sync-and-storage-design.md`
(sync/opslag-ontwerp), `docs/superpowers/plans/2026-06-08-android-connectiq-sdk-connection.md`
(implementatieplan).*
