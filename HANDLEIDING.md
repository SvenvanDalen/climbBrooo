# ClimbPro — Handleiding & functie-uitleg

Volledige uitleg van **alle functies** van het ClimbPro-systeem voor de Garmin
Forerunner 255 Music, plus een **complete installatiehandleiding** van nul tot een
werkende opstelling (telefoon + watch).

> Engels overzicht + ontwerpdocumenten: [README.md](README.md),
> [Documentation/ARCHITECTURE.md](Documentation/ARCHITECTURE.md),
> [Documentation/CONNECTION.md](Documentation/CONNECTION.md).

**Inhoud**

1. [Wat is ClimbPro?](#1-wat-is-climbpro)
2. [De vier onderdelen](#2-de-vier-onderdelen)
3. [Functie-uitleg — telefoon-app](#3-functie-uitleg--telefoon-app)
4. [Functie-uitleg — de drie watch-apps](#4-functie-uitleg--de-drie-watch-apps)
5. [Functie-uitleg — domeinlogica (hoe een klim ontstaat)](#5-functie-uitleg--domeinlogica-hoe-een-klim-ontstaat)
6. [Functie-uitleg — het protocol (wat over de lijn gaat)](#6-functie-uitleg--het-protocol-wat-over-de-lijn-gaat)
7. [Complete installatiehandleiding](#7-complete-installatiehandleiding)
8. [Eerste gebruik & verifiëren](#8-eerste-gebruik--verifiëren)
9. [Bediening op de watch](#9-bediening-op-de-watch)
10. [Probleemoplossing](#10-probleemoplossing)

---

## 1. Wat is ClimbPro?

ClimbPro is een zelfgebouwde, ClimbPro-achtige beleving voor de Forerunner 255 Music.
Het kernidee: **de telefoon doet al het zware rekenwerk**, de **watch toont alleen
compacte, voorberekende data** en matcht GPS op de route. Tijdens het fietsen heb je
**geen telefoon en geen netwerk** nodig — synchroniseren gebeurt opportunistisch.

Een **klim** is gedefinieerd als een stuk van **≥ 800 m** lang **én** met **≥ 3 %**
gemiddeld stijgingspercentage. Beide voorwaarden moeten gelden.

---

## 2. De vier onderdelen

| Onderdeel | Map | Techniek | Rol |
|---|---|---|---|
| **Android companion-app** | `android/` | Java, MVVM, WorkManager | Routes parsen, klimmen detecteren/segmenteren, Strava, sync-orkestratie, alle telefoon-analyses |
| **ClimbPro browse-app** (watch) | `garmin-widget/` | Monkey C (`watch-app` + glance) | Routes/klimmen bekijken, opslaan, er één **actief** maken |
| **ClimbPro datafield** (watch) | `garmin/` | Monkey C (`datafield`) | Actieve klim tonen tijdens de rit, GPS matchen, startalarm — volledig offline |
| **Ondergrond datafield** (watch) | `garmin-surface/` | Monkey C (`datafield`) | Toont het door jou gemarkeerde ondergrond-stuk + het volgende |
| **Gedeeld protocol** | `protocol/` | JSON Schema (canoniek) | Enige bron voor het wire-format en de domeinconstanten |

> De drie watch-apps hebben **gescheiden opslag**. Een route "actief" maken vanuit de
> browse-app gaat daarom **via de telefoon** naar de datafields. Bij het *kiezen* moet de
> telefoon bereikbaar zijn; de **rit zelf niet**.

### Connect IQ app-UUID's (moeten exact kloppen)

| App | UUID |
|---|---|
| Browse-app (`garmin-widget`) — sync-tegenhanger van de telefoon | `fedcba9876543210fedcba9876543210` |
| Klim-datafield (`garmin`) | `0123456789abcdef0123456789abcdef` |
| Ondergrond-datafield (`garmin-surface`) | `00112233445566770011223344556677` |

Deze staan in elk `manifest.xml` én in
`android/.../connectiq/ConnectIqAppId.java`. **Voor een publieke release: genereer alle
drie nieuwe UUID's** en pas manifest + `ConnectIqAppId` samen aan.

---

## 3. Functie-uitleg — telefoon-app

De Android-app (`nl.paree.climbpro`, MVVM + Repository) is de hub. Hieronder elke
gebruikersfunctie en waar die leeft.

### 3.1 Routes binnenhalen

- **Strava-import.** Koppel je Strava-account (OAuth) en de app haalt je routes binnen.
  `data/strava/` (`StravaAuthRepository`, `StravaRoutesRepository`, `StravaApiClient`).
  De eerste sync haalt ook 12 maanden activiteiten op voor het logboek (zie 3.9).
- **GPX-import.** Importeer een losse route/klim uit een GPX-bestand
  (`domain/route/GpxParser.java`). Robuust tegen ontbrekende/0-hoogtesamples en corrupte
  bestanden (gooit `GpxParseException` i.p.v. te crashen).
- **Opslag.** Routes worden als **JSON-bestanden** in app-privé opslag bewaard
  (`getFilesDir()/routes/<id>.json` + `catalog.json`-index). Geen Room/SQLite. Schrijven
  is atomisch (temp + rename). `data/route/RouteRepository.java`.

### 3.2 Een route kiezen om te volgen (route-modus)

Kies een gesynchroniseerde route uit de lijst; die wordt de **actieve** route op de
watch. De telefoon bouwt een compacte payload met de klimmen, anchors langs de route, en
(indien beschikbaar) per-segment doeltijden. `ui/routes/RouteListActivity`,
`service/ClimbPayloadBuilder`.

### 3.3 Radius-modus

Geen vaste route: de watch houdt een kleine index bij van klimmen **dichtbij**, elk
verankerd op een **startcoördinaat** (lat/lon), en checkt of je GPS-positie binnen een
instelbare radius van een bekende klimstart valt. Architectonisch los van route-modus.
`service/RadiusModeAssembler.java`. De telefoon kapt af op de payload-budget (closest-first)
en waarschuwt; de watch weet niets van die afkapping.

### 3.4 Routes en klimmen hernoemen

Door jou ingevoerde namen worden **apart** van de brondata bewaard en **overleven een
resync** (worden niet overschreven bij her-import). `RouteRepository` matcht her-import op
`startDistance`. Geldt ook voor losse flat-segmenten en ondergrond-stukken.

### 3.5 Eigen metadata op routes

Vrije notities/tags voor persoonlijke context. **Blijven op de telefoon** en gaan nooit
de wire over. Alleen de **route-naam** en **klim-naam** kunnen (indien ze in het
byte-budget passen) meegestuurd worden.

### 3.6 Eigen ondergrond-stukken (surface sections)

Markeer een **willekeurig stuk** van een route met een ondergrondtype (asfalt, grind,
aarde, kasseien, gemengd) en een optionele naam, vanuit het routedetail-scherm
(`RouteDetailActivity` → "Ondergrond-stukken"). Opgeslagen in
`StoredRoute.surfaceSections`. Deze worden naar de **Ondergrond-datafield** gestuurd via
het `surfSec`-veld; ook door jou benoemde/getypte flat-segmenten worden meegenomen.
Onaangeraakte flat-segmenten worden overgeslagen.

### 3.7 Geschatte klimtijd (alleen telefoon)

Schatting van hoe lang elke klim duurt, op basis van je **rider-profiel** (FTP in watt,
lichaamsgewicht, fietsgewicht — in Instellingen). Het model leeft in `domain/power`:

- `PowerSpeedSolver` — lost de steady-state power-balansvergelijking op naar snelheid
  (`P = m·g·(sinθ + Crr·cosθ)·v + ½·ρ·CdA·v³`).
- `SurfaceRollingResistance` — per ondergrond een eigen rolweerstand `Crr` (ruwer = trager).
- `PowerDurationModel` — Critical-Power-curve `P(t) = FTP + W'/t`.
- `ClimbTimeEstimator` — koppelt dit met fixed-point iteratie tot totale + per-segment tijd.

**Vermoeidheidsbewust:** `RouteAwareClimbEstimator` + `RouteEffortProfileBuilder`
modelleren de hele route met één gedeelde W'-balans; klimmen later in een zware route
worden trager geschat. De tussenstukken rijd je op een instelbare intensiteit (% FTP) waar
W' herstelt. **Niet** in de payload — de watch ziet dit nooit.

### 3.8 Pacing-paspoort & live ghost

Het routedetail-scherm toont een **pacing-paspoort** (aantal klimmen, hoogtemeters,
zwaarste klim, geschatte totaaltijd, doeltijd per klim). De per-segment doeltijden worden
**wél** gesynchroniseerd (route-modus, optioneel `tsec`-array). Op de watch toont de
klim-datafield live of je vóór/achter ligt (`+/−s`) plus een korte samenvatting na elke
top. `service/RoutePacingPlanner`, `ui/routes/RoutePassport`.

### 3.9 Klimlogboek (alleen telefoon)

Per-klim historie en PR's, afgeleid uit je Strava-ritten (eerste sync: 12 maanden, daarna
incrementeel). Bereikbaar via het overflow-menu van de routelijst ("Logboek"); elk
klimdetail toont een "Historie"-blok met eerdere pogingen en delta-tot-PR. Elke klim
heeft een route-onafhankelijke `ClimbIdentity` (gebucketeerde startcoördinaat + lengte),
zodat dezelfde fysieke klim over routes heen wordt gegroepeerd. `domain/matching/ClimbAttemptMatcher`,
`data/route/ClimbAttemptRepository`, `domain/climb/LogbookCalculator`. **Niet** synct naar
de watch.

### 3.10 Navigatie starten

De app deelt het GPX-bestand naar **Garmin Connect** (intent); Garmin Connect pusht de
course naar de watch en jij start de navigatie vanaf de watch. De app pusht zelf geen
courses.

> **Tip — nauwkeurigere afstand:** navigeer de gekozen route als Garmin-course. Het
> ClimbPro-datafield gebruikt dan de course-afstand (`rtl − distanceToDestination`) i.p.v.
> de ruwe activiteits-odometer, met een kalibratie-controle die terugvalt op de odometer
> als je een andere of geen course navigeert. Sla je per ongeluk een klim over, dan gaat
> het datafield na ±1 km automatisch door met de volgende klim.

### 3.11 Routelijst sorteren & auto-refresh

Sorteer op importtijd (oudste/nieuwste) of naam A–Z via het "Sorteer"-menu; de keuze
wordt bewaard in `SharedPreferences`. De lijst ververst automatisch zodra een Strava-sync
klaar is. `ui/routes/RouteSorting`, `RouteListViewModel`.

### 3.12 Synchronisatie (offline-first)

`RouteSyncWorker` (WorkManager, periodiek + handmatige "Sync nu"-knop) is dunne lijm; de
logica zit in de pure, geteste `service/SyncOrchestrator`. Eén sync-ronde:

1. **Strava-pull eerst** — routes downloaden en opslaan, **onafhankelijk** van de
   watch-verbinding (offline-first-garantie).
2. **Voortgang melden** — UI ververst meteen.
3. **Opportunistisch naar de watch sturen** — faalt/wordt overgeslagen zonder de sync te
   laten falen. `retry()` alleen bij echte transiente fouten, nooit louter omdat de watch
   afwezig is.

Incrementeel (alleen gewijzigde routes, op bron-hash), met retry (exponentiële backoff) en
hervatbaarheid. Re-sync ook wanneer je rider-profiel verandert (voor pacing).

---

## 4. Functie-uitleg — de drie watch-apps

### 4.1 ClimbPro browse-app (`garmin-widget/`, type `watch-app`)

Een device-app met een **glance** in de up/down-loop van de FR255. Functies:

- Bladeren door **gesynchroniseerde routes** (leest een lichte `saved_route_meta`-index;
  volledige klimdata pas bij openen).
- Een route of losse klim **opslaan** op de watch.
- Een route/klim **actief** maken. Omdat apps gescheiden opslag hebben, stuurt de
  browse-app `SET_ACTIVE_ROUTE {id}` of `SET_ACTIVE_CLIMB {id, climbIdx}` naar de
  telefoon; `WatchRequestHandler` bouwt de payload en pusht die naar de datafield-apps;
  de telefoon bevestigt met `ACTIVE_SET {ok, name}`.

Schermen/knoppen: zie [§9 Bediening](#9-bediening-op-de-watch).

### 4.2 ClimbPro datafield (`garmin/`, type `datafield`)

Rendert tijdens een rit de **actieve klim**. Volledig **offline**: elke ontvangen payload
wordt onder Storage-sleutel `active_payload` bewaard en bij `onStart` hersteld.

- **Huidige klim**: profiel met gekleurde segmenten, live positie/voortgang, resterende
  afstand en hoogtemeters.
- **Volgende klim**: afstand tot start, lengte, gemiddeld percentage, hoogtewinst,
  mini-profiel.
- **GPS-matching**: nearest-point-zoek met **hysterese** (20 m) zodat voortgang niet
  terugspringt bij GPS-drift of haarspeldbochten; tolerant voor van-route-af en
  route-omkering.
- **Startalarm**: trilling + toon, **één keer per klim**, binnen **50 m** van de start;
  idempotent (drift terug over de grens triggert niet opnieuw), gereset bij routewissel.
- **Live ghost**: bij aanwezige `tsec` toont het veld `+/−s` t.o.v. plan + samenvatting na
  de top.

### 4.3 Ondergrond-datafield (`garmin-surface/`, type `datafield`)

Toont het **ondergrond-stuk** waar je in zit (naam als titel, type eronder, resterende
meters) en het **volgende** stuk. Ontvangt een lean payload met `surfSec`. Op de watch:

- `elapsedDistance` is de primaire matchas; een gesmoothde `distanceOffset` snapt naar het
  dichtstbijzijnde checkpoint binnen 40 m (`correctElapsed`) zodat GPS drift corrigeert
  zonder afstandsmatching te vervangen.
- Het huidige stuk wordt lokaal in **sub-stukjes van 8 %** van zijn lengte opgedeeld
  (`SUBPIECE_FRACTION_PCT`, ~13 stuks, gecapt op `MAX_SUBPIECES`); getoond als gevulde
  segmentbalk met inline "deel X/Y"-teller.
- **Eén-shot trilling + `TONE_LAP`** binnen 50 m van elke sectiestart, idempotent per
  sectie, gereset bij routewissel — hetzelfde patroon als het klim-startalarm.

---

## 5. Functie-uitleg — domeinlogica (hoe een klim ontstaat)

De pipeline op de telefoon, allemaal **pure** (testbare) functies in `domain/`:

1. **Parsen** — GPX/FIT → punten met lat/lon/hoogte (`domain/route/GpxParser`).
2. **Cumulatieve afstand** — afstand-langs-route per punt (`CumulativeDistance`).
3. **Hoogte gladstrijken** — moving average (`ElevationSmoother`), tegen GPS-hoogteruis.
4. **Geometrie vereenvoudigen** — Douglas-Peucker (`RouteSimplifier`), voor geheugen.
5. **Klimmen detecteren** — `domain/climb/ClimbDetector` met de regels: ≥ 800 m én ≥ 3 %.
6. **Vals plat trimmen** — `ClimbTrimmer` haalt voor-/na-stukken < 2 % over ≥ 200 m weg,
   maar nooit zo dat de klim onder 800 m zakt. Start/eind + startcoördinaat verschuiven mee.
7. **Segmenteren** — `domain/segment/Segmenter` splitst elke klim in een vast aantal
   segmenten (`ClimbConstants.SEGMENT_COUNT`). Per segment: gemiddeld percentage,
   hoogtewinst, afstand, **kleurindex**.
8. **Kleur** — `GradientColor` mapt percentage → kleurindex volgens de gedeelde tabel.

**De domeinconstanten** (`domain/climb/ClimbConstants.java`):

| Constante | Waarde | Betekenis |
|---|---|---|
| `MIN_CLIMB_LENGTH_M` | `800` | Minimale klimlengte |
| `MIN_AVG_GRADIENT` | `0.03` | Minimaal gemiddeld percentage (3 %) |
| `FALSE_FLAT_MAX_GRADIENT` | `0.02` | Drempel "vals plat" (< 2 %) |
| `FALSE_FLAT_MIN_LENGTH_M` | `200` | Minimale lengte vals plat voor trimmen |
| `SEGMENT_COUNT` | `16` | Aantal segmenten per klim |
| `ALERT_RADIUS_M` | `50` | Startalarm binnen 50 m |
| `ROUTE_MATCHING_HYSTERESIS_M` | `20` | Hysterese tegen terugspringen |
| `CALIBRATION_MIN_DISTANCE_M` | `200` | Min. afstand tussen kalibratiepunten |

**Kleurmapping** (één plek — `protocol/colors.md` + gegenereerde tabel):
0–2 % licht geel · 2–4 % geel · 4–6 % donkergeel · 6–8 % oranje · 8–10 % donkeroranje ·
10 %+ rood.

---

## 6. Functie-uitleg — het protocol (wat over de lijn gaat)

`protocol/schema.json` is **canoniek** (JSON Schema, draft-07). Java-POJO's worden eruit
**gegenereerd** (`generateProtocolPojos`, jsonschema2pojo); de Monkey C-parsers zijn
**handgeschreven**. `ProtocolRoundTripTest` valideert zowel `protocol/examples/*.json` als
de **live** builder-output tegen het schema — dus Java-zijde drift faalt CI; de Monkey
C-zijde blijft review-only.

- **v3 packed format**: korte sleutels + gepakte int-arrays (de watch is geheugenkrap).
- **Fixed-point percentages**: integer = `percentage × 10` (7,2 % = `72`).
- **Kleur als index** i.p.v. naam (bytes besparen, één gedeelde tabel).
- **`segs`** = `[afstand, hoogtewinst, percentage×10, kleurindex, …]` (4 ints/segment).
- **`calib`** = `[afstandVanafKlimStart, latInt, lonInt, …]` (3 ints/punt; latInt/lonInt =
  graden × 100000). Route-modus, optioneel.
- **`surf`** = één ondergrondcode (0–5) per segment, parallel aan `segs`.
- **`tsec`** = per-segment doeltijd in hele seconden (route-modus, optioneel).
- **`surfSec`** = array `{s, e, t, n?, cp}` voor de Ondergrond-datafield; `cp` = gepakt
  checkpoint-array.
- **Max berichtgrootte**: `4096 bytes` (`PayloadBudget.MAX_BYTES`). De watch accepteert
  alleen een **Dictionary** (Map), nooit rauwe bytes — zie `connectiq/PayloadCodec`.

> **Bij elke wijziging van het wire-format** pas je samen aan: `schema.json`,
> `protocol/examples/`, `ClimbPayloadBuilder`, én de Monkey C-parsers
> (`CommListener.mc` / `SurfaceData.mc`). Bewerk gegenereerde Java nooit met de hand.

---

## 7. Complete installatiehandleiding

Van nul naar een werkende opstelling. Doelapparaat: **Forerunner 255 Music** (`fr255m`).
Voorbeelden zijn Windows/PowerShell (zoals deze repo).

### 7.0 Benodigdheden (overzicht)

- Een **Android-telefoon** (Android 8.0 / API 26 of hoger) met **Garmin Connect Mobile**
  geïnstalleerd en je FR255 daarin gekoppeld.
- **Android Studio** (Hedgehog 2023.1+) — bundelt JDK + Android SDK.
- **Connect IQ SDK** + een **developer key**.
- Een **Strava-account** + geregistreerde API-app (voor route/activiteit-import).
- USB-kabel voor de watch (sideloaden) of de Connect IQ simulator.

---

### 7.1 Android companion-app

**Stap 1 — Android Studio installeren.** Download en installeer Android Studio. Dit levert
de JDK en de Android SDK mee.

**Stap 2 — Project openen.** Open de map `android/` in Android Studio. Het stelt voor om:
- ontbrekende SDK-platforms te downloaden (`compileSdk = 34`, `minSdk = 26`),
- de Gradle-wrapper te installeren (AGP 8.5.2),
- het project te synchroniseren (maakt `local.properties` met `sdk.dir`).

De app gebruikt **Java 17** (`sourceCompatibility/targetCompatibility = 17`).

**Stap 3 — Strava-API registreren.**
1. Ga (ingelogd) naar <https://www.strava.com/settings/api>.
2. Maak een applicatie aan:
   - **Authorization Callback Domain**: `localhost` (voor ontwikkeling).
   - **Application Name**: ClimbPro (of naar keuze).
3. Noteer **Client ID** en **Client Secret**.
4. Strava-limieten: 100 requests / 15 min, 1000 / dag — de sync cachet om eronder te
   blijven.

**Stap 4 — `local.properties` invullen.** Kopieer
`android/local.properties.example` → `android/local.properties` en vul in:

```properties
sdk.dir=C:\\Users\\<jij>\\AppData\\Local\\Android\\Sdk
strava.client.id=<jouw-client-id>
strava.client.secret=<jouw-client-secret>
```

De OAuth-redirect gebruikt het scheme `climbpro` (`appAuthRedirectScheme`,
`net.openid:appauth`). De keys worden via `BuildConfig.STRAVA_CLIENT_ID/SECRET` ingelezen
— **commit `local.properties` niet** (staat in `.gitignore`).

> **Garmin Connect IQ Mobile SDK** zit al als binaire `app/libs/ciq-mobile-sdk.aar` in de
> repo (Garmin distribueert die los van Maven). Niets te doen.

**Stap 5 — Bouwen & installeren.**

```powershell
cd android
.\gradlew.bat assembleDebug          # APK in app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat test                   # JVM unit tests (incl. ProtocolRoundTripTest)
.\gradlew.bat :app:installDebug      # installeer op een via USB verbonden telefoon
```

`generateProtocolPojos` draait automatisch mee en genereert de protocol-POJO's uit
`protocol/schema.json`.

---

### 7.2 De drie watch-apps (Connect IQ)

**Stap 1 — Connect IQ SDK installeren.** Download via
<https://developer.garmin.com/connect-iq/sdk/>. Standaardpad op Windows:
`C:\Users\<naam>\AppData\Roaming\Garmin\ConnectIQ\Sdks\...`. Zorg dat `monkeyc` in je
PATH staat (of gebruik het volledige pad). Installeer via de SDK-manager het **device
profile** voor de FR255 Music. De makkelijkste route is de **VS Code "Monkey C"-extensie**.

**Stap 2 — Developer key aanmaken (eenmalig).** Twee opties:

- Via de developer-portal (<https://apps.garmin.com/en-US/developer> → **Generate Key**),
  sla het `.der`-bestand op (bv. `C:\garmin\developer_key.der`); of
- Via OpenSSL:
  ```powershell
  openssl genrsa -out developer_key.pem 4096
  openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem -out developer_key -nocrypt
  ```
  (Het `developer_key`-bestand is gitignored.)

**Stap 3 — Alle drie de apps bouwen.** Elke watch-app heeft een eigen `monkey.jungle` en
`manifest.xml`. Vervang `<key>` door je keypad:

```powershell
# Klim-datafield
monkeyc -o garmin\ClimbPro.prg          -f garmin\monkey.jungle          -y <key> -d fr255m -r
# Browse-app (+ glance)
monkeyc -o garmin-widget\ClimbBrowse.prg -f garmin-widget\monkey.jungle  -y <key> -d fr255m -r
# Ondergrond-datafield
monkeyc -o garmin-surface\Surface.prg    -f garmin-surface\monkey.jungle -y <key> -d fr255m -r
```

Vlaggen: `-o` uitvoer-`.prg`, `-f` jungle-build, `-y` developer key, `-d fr255m`
doeldevice, `-r` release (kleiner, geen debug). Laat `-r` weg om in de **simulator** te
testen (SDK → Connect IQ Simulator → File → Run → het `.prg` laden, FR255 Music-profiel).

**Stap 4 — Sideloaden op de watch (USB).**
1. Sluit de FR255 via USB aan; hij verschijnt als USB-schijf.
2. Kopieer de `.prg`-bestanden naar `<GARMIN_DRIVE>\GARMIN\APPS\`.
3. Werp de schijf veilig uit.
4. De **browse-app** verschijnt in de up/down-loop; de **datafields** kies je bij het
   opbouwen van een **activiteit-scherm** (data fields) op de watch.

> **UUID-regel:** de UUID's in de drie `manifest.xml`-bestanden moeten exact gelijk zijn
> aan die in `ConnectIqAppId.java` (zie [§2](#connect-iq-app-uuids-moeten-exact-kloppen)).
> Wijzig je er één, wijzig de andere mee.

---

### 7.3 Telefoon ⇄ watch koppelen

De verbinding loopt **niet** rechtstreeks over Bluetooth maar **via Garmin Connect Mobile
(GCM)**: onze app → Connect IQ SDK → GCM-service → Bluetooth → watch. Daarom:

1. **Garmin Connect Mobile** moet geïnstalleerd zijn en de **FR255 daarin gekoppeld**.
2. Android 11+ vereist het `<queries>`-blok voor `com.garmin.android.apps.connectmobile`
   in `AndroidManifest.xml` (al aanwezig) — zonder dat krijg je `GCM_NOT_INSTALLED`.
3. Permissies `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, locatie en internet staan in het
   manifest; verleen ze bij eerste start.
4. Bij opstart doet de app `forceRebind()` (shutdown + opnieuw initialize) om een stale
   GCM-binding na een telefoon-only update op te ruimen, en stuurt één `HELLO`.

Volledige uitleg: [Documentation/CONNECTION.md](Documentation/CONNECTION.md).

---

## 8. Eerste gebruik & verifiëren

Voorwaarden: FR255 gekoppeld in Garmin Connect, browse-app + datafields gesideload,
minstens één route op de telefoon.

```powershell
cd android
.\gradlew.bat :app:installDebug
adb logcat -s ConnectIqClient WatchRequestHandler RouteSyncWorker
```

Verwachte volgorde:

1. `Using device: <naam>`, status `CONNECTED` — **geen** `GCM_NOT_INSTALLED`.
2. Browse-app openen → `Sent ROUTE_LIST with N routes`; de "Telefoon"-sectie vult.
3. Route kiezen op de watch → `Sent route payload … (bytes)`; de watch tekent de klim.
4. Handmatige sync → de worker logt een bevestigde verzending.

**Eerste flow in de app:**
1. Open de app → ga naar Instellingen → vul je **rider-profiel** (FTP, lichaamsgewicht,
   fietsgewicht, ride-intensiteit) voor klimtijd/pacing.
2. Koppel **Strava** (Strava-scherm) of importeer een **GPX**.
3. Druk **Sync nu** of wacht op de periodieke WorkManager-sync.
4. Open een route → bekijk klimmen, segmenten, ondergrond-stukken, pacing-paspoort.
5. Maak een route/klim **actief** vanaf de browse-app op de watch.
6. Voeg de **ClimbPro**- en **Ondergrond**-datafields toe aan een fiets-activiteitscherm
   en rijd.

---

## 9. Bediening op de watch

Knoppen FR255 Music:

```
         ▲ UP
    ┌────────────┐
◄ BACK          SELECT ►
    └────────────┘
         ▼ DOWN
```

| Knop | Actie (browse-app) |
|---|---|
| SELECT | Bevestigen / dieper navigeren |
| BACK | Terug naar vorig scherm |
| UP / DOWN | Scrollen door de klimlijst |

Browse-flow: **Scherm 1** routenaam + aantal klimmen → SELECT → **klimlijst** → SELECT →
**segmentprofiel** met gekleurde balkjes + stats → BACK om terug.

De datafields tonen automatisch data zodra een route/klim actief is en je rijdt; geen
bediening nodig.

---

## 10. Probleemoplossing

| Symptoom | Oorzaak / oplossing |
|---|---|
| `GCM_NOT_INSTALLED` terwijl Garmin Connect er is | `<queries>`-blok ontbreekt, of GCM niet ingelogd/gekoppeld |
| Status blijft `ERROR`, "No connected Garmin devices found" | Watch niet gekoppeld of buiten Bluetooth-bereik; client probeert elke 10 s opnieuw |
| Browse-app toont "Geen verbinding" | UUID-mismatch tussen `ConnectIqAppId.VALUE` en `garmin-widget/manifest.xml` |
| "Telefoon"-lijst leeg ná telefoon-only update | Stale GCM-binding; `forceRebind()` zou dit moeten herstellen. Anders GCM + ClimbPro herstarten. Géén UUID-probleem |
| Watch ontvangt niets / dropt bericht | Er werd `byte[]` i.p.v. een `Map` gestuurd, of payload > 4096 bytes |
| Worker blijft `retry()` | Watch niet verbonden op sync-moment (verbinding is async) |
| `monkeyc: command not found` | SDK `bin/`-map aan PATH toevoegen of volledig pad gebruiken |
| Datafield/widget verschijnt niet op de watch | `.prg` staat niet in `GARMIN\APPS\`; herstart de watch |
| "No data" in datafield | Nog geen actieve route/sync ontvangen — kies een actieve route met telefoon bereikbaar |
| Build error `Ui.DataField` in widget | Datafield-broncode hoort niet in de widget-sourcepath; widget gebruikt alleen `garmin-widget/source/` |
| Strava werkt niet | `strava.client.id/secret` ontbreken in `local.properties`, of callback-domain ≠ `localhost` |
| Java POJO's ontbreken bij compile | `generateProtocolPojos` faalde — controleer `protocol/schema.json` |

---

*Gerelateerd: [README.md](README.md) · [Documentation/ARCHITECTURE.md](Documentation/ARCHITECTURE.md)
· [Documentation/CONNECTION.md](Documentation/CONNECTION.md) · [Documentation/SETUP.md](Documentation/SETUP.md)
· [docs/garmin-widget-setup.md](docs/garmin-widget-setup.md) · [protocol/schema.md](protocol/schema.md).*
