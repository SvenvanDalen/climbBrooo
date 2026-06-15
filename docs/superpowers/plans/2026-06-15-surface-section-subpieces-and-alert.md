# Ondergrond-stukken: tussentijdse deel-stuk-checks + entry-alert — Implementatieplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Geef de ondergrond-datafield op het horloge dezelfde tussentijdse voortgangscheck als de klimmen: deel elk ondergrond-stuk in deel-stukken van 8% van de stuk-lengte, toon een gevulde segmentbalk + teller ("deel 3/13"), en geef één keer per stuk een tril/geluid-alert binnen 50 m van de stuk-start.

**Architecture:** Puur horloge-zijde (Monkey C, `garmin-surface/`). De ondergrond-type is uniform binnen een stuk, dus het horloge leidt de deel-stukken lokaal af uit `s`/`e` — **geen** wijziging aan `protocol/schema.json`, `protocol/examples/`, de gegenereerde POJO's, `ClimbPayloadBuilder` of `CommListener.mc`. `SurfaceData.mc` krijgt deel-stuk-state in `updateProgress`; `SurfaceFieldView.mc` tekent de balk + teller en vuurt de entry-alert, idempotent per stuk en gereset bij route-wissel — exact het patroon van `ClimbProView.mc`.

**Tech Stack:** Monkey C (Connect IQ SDK 9.1.0), Forerunner 255 Music (`fr255m`), Connect IQ simulator. Geen Gradle/JUnit: de Monkey C-parsers en -views hebben **geen JVM-harness** en zijn per `CLAUDE.md` **review-only** — verificatie gaat via `monkeyc`-compile (vangt type-fouten) + simulator-observatie, niet via unit-tests. Dit volgt de projectconventie; de TDD-default van de skill wijkt hier voor de expliciete user-instructie in `CLAUDE.md`.

---

## Background: wat al bestaat (lees vóór je begint)

- **`garmin-surface/source/SurfaceData.mc`** — parallelle-array store voor de ondergrond-stukken van de actieve route. Relevante publieke velden: `count`, `secStart[]`, `secEnd[]`, `secType[]`, `secName[]`, `routeId`, `payloadReceived`. Runtime-state, ververst door `updateProgress(elapsed)`: `currentIdx` (-1 = nergens), `nextIdx`, `remainingInSection`, `distToNext`. `correctElapsed(elapsed, pos)` levert de gecorrigeerde afstand (low-pass offset op de checkpoints). `MAX_SECTIONS = 32`.
- **`updateProgress(elapsed)`** (regels ~139–154): reset alle runtime-velden, loopt dan over de stukken (gesorteerd op startafstand) om `currentIdx`/`remainingInSection` of `nextIdx`/`distToNext` te zetten. Hier komt de deel-stuk-berekening bij.
- **`garmin-surface/source/SurfaceFieldView.mc`** — de datafield. `compute(info)` haalt `elapsed` (`info.elapsedDistance`) en `pos` (`info.currentLocation.toDegrees()`) op en roept `data.updateProgress(data.correctElapsed(elapsed, pos))` aan. `onUpdate(dc)` tekent `drawCurrentSection`/`drawNextOnly`. `SURF_COLORS[0..5]` en `SURF_NAMES[0..5]` bestaan al. `formatDist(meters)` bestaat al.
- **`garmin/source/ClimbProView.mc`** is de referentie voor het alert- en route-wissel-patroon:
  - Route-wissel-detectie (regels ~67–76): `var rid = data.routeId; var routeChanged = (rid == null) ? (lastRouteId != null) : !rid.equals(lastRouteId);` → bij wissel `lastRouteId = rid` en alert-state resetten.
  - Climb-start-alert (regels ~104–110): `if (data.activeClimbIndex >= 0 && data.activeClimbIndex != alertedClimbIndex) { if (data.progressInClimb <= 50) { triggerClimbAlert(); alertedClimbIndex = ...; } }`.
  - `triggerClimbAlert()` (regels ~418–430): `Attention.vibrate([...VibeProfile...])` achter `Attention has :vibrate`, plus `Attention.playTone(Attention.TONE_LAP)` achter `Attention has :playTone`.
  - `routeId` is in de payload een **String** (`msg.get("routeId")`), dus `.equals` is veilig.
- **De 8%-regel bij klimmen**: in werkelijkheid splitst `Segmenter.java` op een vaste `ClimbConstants.SEGMENT_COUNT = 16`. De gebruiker koos hier expliciet **8% van de stuk-lengte** (≈13 deel-stukken), niet 16 — dit plan volgt de 8%-keuze.

### Conventies (dit project)
- Monkey C: integer-rekenwerk waar mogelijk (geen `Math.ceil`/`Math.floor` nodig — `Number`-deling kapt af). Ceil van `a/b` met integers = `(a + b - 1) / b`.
- Kleuren als rauwe `0xRRGGBB`-literals, consistent met de bestaande `SURF_COLORS`.
- `hidden` voor view-interne helpers/state, net als de bestaande methodes.
- Commentaar in het Nederlands waar de omringende code dat ook is.

### Build/verify-command (dit project, Windows PowerShell)
Vanuit `garmin-surface/`:

```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o Surface.prg -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Verwacht: compileert zonder fouten (warnings over ongebruikte vars zijn ok). Daarna in de Connect IQ simulator laden, device-profiel **Forerunner 255 Music**, een opgeslagen `surface_payload` met ≥ 1 stuk, en de activiteit-simulatie laten lopen om de balk/teller/alert te observeren.

> **Sleutel/SDK-paden (geverifieerd 2026-06-15):** SDK = `connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b`; developer key = `C:\Users\svenv\Documents\CLIMBPRODEF\developer_key` (zelfde als de `garmin-surface`-builds in `docs/superpowers/plans/2026-06-10-...`). De **simulator-stappen zijn GUI-only** en kunnen niet headless door een agent worden gedraaid — voer die zelf uit; een agent leunt op de geslaagde `monkeyc`-compile + code-review.

---

## Task 1: Deel-stuk-state in `SurfaceData.mc`

**Files:**
- Modify: `garmin-surface/source/SurfaceData.mc`

Leid per GPS-tick af in welk deel-stuk (8% van de huidige stuk-lengte) de rijder zit. Pure integer-rekenkunde, afgeleid uit `secStart`/`secEnd` — geen payload-data nodig.

- [ ] **Step 1: Voeg de deel-stuk-constanten toe**

In `SurfaceData.mc`, bij de bestaande `const`-blokken bovenin de klasse (na `const SNAP_M = 40;`, regel ~13):

```monkeyc
    const SNAP_M = 40;          // only snap when a checkpoint is within this many metres
    const SUBPIECE_FRACTION_PCT = 8;   // elk deel-stuk = 8% van de stuk-lengte (zoals klimmen)
    const MAX_SUBPIECES = 16;          // veiligheidscap op het aantal balk-cellen
```

- [ ] **Step 2: Voeg de deel-stuk-runtime-state toe**

Bij de runtime-state-declaraties (na `var distToNext = -1;`, regel ~38):

```monkeyc
    var distToNext = -1;        // metres to next section start
    var subPieceCount = 0;      // deel-stukken in het huidige stuk (0 = niet in een stuk)
    var currentSubPiece = -1;   // 0-based index van het deel-stuk waarin de rijder zit
    var subPieceLen = 0;        // meters per deel-stuk in het huidige stuk
```

- [ ] **Step 3: Bereken de deel-stukken aan het eind van `updateProgress`**

Vervang de hele bestaande `updateProgress`-methode (regels ~137–154) door deze versie (de loop is ongewijzigd; de reset bovenin en het deel-stuk-blok onderaan zijn nieuw):

```monkeyc
    // elapsed = activity elapsedDistance in metres (already corrected by correctElapsed).
    // Sections are sorted by startDistance (phone keeps them sorted).
    function updateProgress(elapsed) {
        currentIdx = -1;
        nextIdx = -1;
        remainingInSection = 0;
        distToNext = -1;
        subPieceCount = 0;
        currentSubPiece = -1;
        subPieceLen = 0;
        for (var i = 0; i < count; i++) {
            if (elapsed >= secStart[i] && elapsed < secEnd[i]) {
                currentIdx = i;
                remainingInSection = secEnd[i] - elapsed;
            } else if (secStart[i] > elapsed) {
                nextIdx = i;
                distToNext = secStart[i] - elapsed;
                break;
            }
        }

        // Tussentijdse deel-stuk-check: splits het huidige stuk in deel-stukken van
        // 8% van de stuk-lengte en bepaal in welk deel-stuk de rijder zit.
        if (currentIdx >= 0) {
            var secLen = secEnd[currentIdx] - secStart[currentIdx];
            if (secLen > 0) {
                subPieceLen = (secLen * SUBPIECE_FRACTION_PCT) / 100;  // integer floor van 8%
                if (subPieceLen < 1) { subPieceLen = 1; }
                subPieceCount = (secLen + subPieceLen - 1) / subPieceLen;  // ceil
                if (subPieceCount > MAX_SUBPIECES) { subPieceCount = MAX_SUBPIECES; }
                var into = elapsed - secStart[currentIdx];
                if (into < 0) { into = 0; }
                var idx = into / subPieceLen;
                if (idx > subPieceCount - 1) { idx = subPieceCount - 1; }
                currentSubPiece = idx;
            }
        }
    }
```

- [ ] **Step 4: Tijdelijke trace voor simulator-verificatie**

Voeg tijdelijk, direct vóór de afsluitende `}` van `updateProgress`, een trace toe zodat je in de simulator-console de waarden ziet:

```monkeyc
        Sys.println("Surf progress: idx=" + currentIdx + " sub=" + currentSubPiece
            + "/" + subPieceCount + " subLen=" + subPieceLen);
```

(`Sys` is al geïmporteerd bovenin het bestand.)

- [ ] **Step 5: Compileer**

Run het build-command uit *Background*. Expected: compileert zonder fouten.

- [ ] **Step 6: Verifieer in de simulator**

Laad in de simulator (device **fr255m**) een payload met een stuk van bekende lengte, bv. `s=1000, e=1800` (800 m). Laat de activiteit-afstand toenemen en lees de console:
- Bij `elapsed ≈ 1000`: `sub=0/13 subLen=64` (800·8/100 = 64; ceil(800/64) = 13).
- Bij `elapsed ≈ 1400` (halverwege): `sub=6/13`.
- Bij `elapsed ≈ 1790` (vlak voor eind): `sub=12/13`.
- Bij `elapsed ≥ 1800` (buiten het stuk): `idx=-1 sub=-1/0`.

Komt dit niet overeen, controleer de integer-deling vóór je verder gaat.

- [ ] **Step 7: Verwijder de trace en commit**

Verwijder de `Sys.println`-regel uit Step 4.

```bash
git add garmin-surface/source/SurfaceData.mc
git commit -m "feat(surface): derive 8% sub-piece progress per surface section on watch"
```

---

## Task 2: Segmentbalk + teller in `SurfaceFieldView.mc`

**Files:**
- Modify: `garmin-surface/source/SurfaceFieldView.mc`

Teken in het huidige-stuk-scherm een gevulde balk opgedeeld in `subPieceCount` cellen (afgereden = ondergrondkleur, huidig = blauw, komend = lichtgrijs) plus een teller "deel X/Y".

- [ ] **Step 1: Voeg de teller toe aan de resterende-afstand-regel**

In `drawCurrentSection` (regels ~71–94), vervang de "nog ..."-`drawText` (regels ~85–87) door een versie met de deel-stuk-teller:

```monkeyc
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        var remLine = "nog " + formatDist(data.remainingInSection);
        if (data.subPieceCount > 0) {
            remLine = remLine + "  ·  deel " + (data.currentSubPiece + 1) + "/" + data.subPieceCount;
        }
        dc.drawText(w / 2, h / 2 + 8, Gfx.FONT_SMALL, remLine, Gfx.TEXT_JUSTIFY_CENTER);
```

- [ ] **Step 2: Roep de balk-helper aan in `drawCurrentSection`**

Direct ná de zojuist gewijzigde "nog ..."-regel en vóór het `if (data.nextIdx >= 0)`-blok (regel ~89), voeg toe:

```monkeyc
        drawSubPieceBar(dc, data, w, h);
```

- [ ] **Step 3: Voeg de `drawSubPieceBar`-helper toe**

Voeg deze `hidden`-methode toe direct ná `drawCurrentSection` (na zijn afsluitende `}`, rond regel 94):

```monkeyc
    // Gevulde segmentbalk: afgereden deel-stukken in de ondergrondkleur, het huidige
    // deel-stuk blauw, komende deel-stukken lichtgrijs. Onder de balk een teller.
    hidden function drawSubPieceBar(dc, data, w, h) {
        var n = data.subPieceCount;
        if (n <= 0) { return; }
        var cur = data.currentSubPiece;
        var t = data.secType[data.currentIdx];

        var x0 = 20;
        var barW = w - 40;
        var y = (h * 60) / 100;
        var barH = 6;
        var cellW = barW / n;
        if (cellW < 1) { cellW = 1; }

        for (var i = 0; i < n; i++) {
            var cx = x0 + i * cellW;
            if (i < cur) {
                dc.setColor(SURF_COLORS[t], Gfx.COLOR_TRANSPARENT);   // afgereden
            } else if (i == cur) {
                dc.setColor(Gfx.COLOR_BLUE, Gfx.COLOR_TRANSPARENT);   // huidig
            } else {
                dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT); // komend
            }
            dc.fillRectangle(cx, y, cellW - 1, barH);
        }
    }
```

- [ ] **Step 4: Compileer**

Run het build-command uit *Background*. Expected: compileert zonder fouten.

- [ ] **Step 5: Verifieer in de simulator**

Met dezelfde payload als Task 1 Step 6, terwijl je in een stuk zit:
- Onder de "nog ..."-regel staat nu "... · deel 7/13" (op de helft).
- Er verschijnt een balk met 13 cellen: ~6 cellen in de ondergrondkleur (afgereden), 1 blauwe cel (huidig), de rest lichtgrijs.
- De blauwe cel schuift naar rechts naarmate de afstand toeneemt.
- Past de balk niet netjes binnen het veld op het fr255m-profiel? Stem `y` (`h*60/100`), `barH` of `x0`/`barW` bij tot het past, en hercompileer.

- [ ] **Step 6: Commit**

```bash
git add garmin-surface/source/SurfaceFieldView.mc
git commit -m "feat(surface): render sub-piece progress bar and counter"
```

---

## Task 3: Entry-alert per ondergrond-stuk in `SurfaceFieldView.mc`

**Files:**
- Modify: `garmin-surface/source/SurfaceFieldView.mc`

Eén tril+geluid binnen 50 m van de start van elk stuk, idempotent per stuk (overleeft GPS-jitter via een per-stuk vlag), gereset bij route-wissel — exact het `ClimbProView.mc`-patroon.

- [ ] **Step 1: Importeer `Attention`**

Bovenin `SurfaceFieldView.mc`, bij de bestaande `using`-regels (na `using Toybox.Application as App;`, regel ~3):

```monkeyc
using Toybox.Application as App;
using Toybox.Attention as Attention;
```

- [ ] **Step 2: Voeg alert-state toe en initialiseer die**

Vervang de bestaande `initialize` (regels ~20–22) door een versie die de per-stuk-alertvlaggen en `lastRouteId` opzet:

```monkeyc
    // Alert-state (voorkom her-trigger; idempotent per stuk, gereset bij route-wissel)
    hidden var lastRouteId = null;
    hidden var alertedSec;   // Boolean per stuk-index (grootte = SurfaceData MAX_SECTIONS)

    function initialize() {
        DataField.initialize();
        alertedSec = new [32];   // MAX_SECTIONS
        for (var i = 0; i < 32; i++) { alertedSec[i] = false; }
    }
```

- [ ] **Step 3: Reset bij route-wissel en vuur de alert in `compute`**

Vervang de hele bestaande `compute`-methode (regels ~24–36) door deze versie:

```monkeyc
    function compute(info) {
        var data = App.getApp().surfaceData;
        if (data == null || !data.payloadReceived) { return; }

        // Een nieuwe payload (route-wissel) maakt de per-stuk alert-state ongeldig.
        var rid = data.routeId;
        var routeChanged = (rid == null) ? (lastRouteId != null) : !rid.equals(lastRouteId);
        if (routeChanged) {
            lastRouteId = rid;
            for (var i = 0; i < alertedSec.size(); i++) { alertedSec[i] = false; }
        }

        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
        var pos = null;
        if (info != null && info has :currentLocation && info.currentLocation != null) {
            pos = info.currentLocation.toDegrees();  // [lat, lon] decimal degrees
        }
        var corrected = data.correctElapsed(elapsed, pos);
        data.updateProgress(corrected);

        // Stuk-start-alert: één keer per stuk, binnen 50 m van de stuk-start.
        var ci = data.currentIdx;
        if (ci >= 0 && ci < alertedSec.size() && !alertedSec[ci]) {
            var into = corrected - data.secStart[ci];
            if (into >= 0 && into <= 50) {
                triggerSurfaceAlert();
                alertedSec[ci] = true;
            }
        }
    }
```

- [ ] **Step 4: Voeg `triggerSurfaceAlert` toe**

Voeg deze `hidden`-methode toe onderaan de klasse, direct vóór de afsluitende `}` van de klasse (na `formatDist`, regel ~111):

```monkeyc
    // Tril + geluid bij binnenkomst van een ondergrond-stuk (zelfde modaliteit als de klim-alert).
    hidden function triggerSurfaceAlert() {
        if (Attention has :vibrate) {
            var vibePattern = [
                new Attention.VibeProfile(100, 400),
                new Attention.VibeProfile(0, 150),
                new Attention.VibeProfile(100, 400)
            ];
            Attention.vibrate(vibePattern);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_LAP);
        }
    }
```

- [ ] **Step 5: Compileer**

Run het build-command uit *Background*. Expected: compileert zonder fouten.

- [ ] **Step 6: Verifieer in de simulator**

Met een payload met twee stukken (bv. `s=500,e=1300` en `s=2000,e=2600`), simuleer de activiteit-afstand vanaf 0:
- Bij het oversteken van 500 m: één tril + lap-toon; daarna géén her-alert zolang je in stuk 0 zit.
- Simuleer een korte GPS-jitter terug over de 500 m-grens en weer vooruit → **geen** tweede alert (de vlag van stuk 0 staat al op `true`).
- Bij het oversteken van 2000 m: opnieuw één alert (stuk 1).
- Stuur een nieuwe payload met een ander `routeId` → de vlaggen resetten; de eerstvolgende stuk-binnenkomst alarmeert weer.

(Geen simulator beschikbaar? Noteer deze stap als uitgesteld en steun op de geslaagde compile + code-review tegen het `ClimbProView.mc`-patroon.)

- [ ] **Step 7: Commit**

```bash
git add garmin-surface/source/SurfaceFieldView.mc
git commit -m "feat(surface): one-shot vibrate+tone alert on entering each surface section"
```

---

## Task 4: Documentatie bijwerken

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`

De architectuur verandert materieel aan de horloge-zijde (nieuwe per-stuk voortgangscheck + alert), dus werk de ondergrond-datafield-beschrijving bij. Geen wire-format-wijziging, dus `schema.*`/`README.md`-wiresecties blijven ongemoeid.

- [ ] **Step 1: Vul de ondergrond-datafield-beschrijving aan**

Zoek in `Documentation/ARCHITECTURE.md` de zin (rond regel 360) die begint met "On the watch, `SurfaceData` keeps `elapsedDistance` as the primary matching axis ...". Voeg er direct ná deze zin aan toe:

```markdown
`SurfaceData` deelt daarnaast het huidige stuk lokaal in deel-stukken van 8% van de
stuk-lengte (`SUBPIECE_FRACTION_PCT`, ≈13 deel-stukken) en houdt `currentSubPiece`/
`subPieceCount` bij — puur afgeleid uit `s`/`e`, dus zonder extra payload. `SurfaceFieldView`
tekent dit als een gevulde segmentbalk met teller ("deel X/Y") en geeft één tril+toon-alert
binnen 50 m van elke stuk-start, idempotent per stuk en gereset bij route-wissel (zelfde
patroon als de klim-start-alert in `ClimbProView`).
```

- [ ] **Step 2: Commit**

```bash
git add Documentation/ARCHITECTURE.md
git commit -m "docs: describe surface sub-piece progress and entry alert on the watch"
```

---

## Final verification

- [ ] **Step 1: Volledige `garmin-surface`-compile**

Run het build-command uit *Background* vanuit `garmin-surface/`. Expected: compileert zonder fouten; `Surface.prg` wordt geschreven.

- [ ] **Step 2: Integrale simulator-doorloop**

Laad `Surface.prg` (device **fr255m**) met een payload met ≥ 2 stukken en doorloop één activiteit-simulatie. Bevestig in één run:
- per stuk één entry-alert (geen her-trigger bij jitter),
- de segmentbalk + "deel X/Y"-teller schuiven correct mee,
- buiten een stuk verschijnt geen balk en blijft `currentSubPiece = -1`,
- na een payload met nieuw `routeId` resetten de alerts.

- [ ] **Step 3: Geen wire-format-drift bevestigen**

Bevestig dat er **niets** is gewijzigd aan `protocol/schema.json`, `protocol/examples/`, gegenereerde POJO's, `ClimbPayloadBuilder.java` of `CommListener.mc` (deze feature is puur horloge-render + horloge-state). Run vanuit `android/`:

```powershell
cd android; .\gradlew.bat test --tests nl.paree.climbpro.protocol.ProtocolRoundTripTest
```

Expected: `BUILD SUCCESSFUL` — de round-trip-test blijft groen omdat het wire-format ongewijzigd is.

---

## Self-review (voor de implementer)

- **Spec-dekking:** "deel-stukken van 8% (zoals klimmen)" → Task 1 `SUBPIECE_FRACTION_PCT = 8` + ceil-telling. "balk + teller" → Task 2 `drawSubPieceBar` + "deel X/Y". "alert per stuk, idempotent, zoals klim-start" → Task 3 per-stuk `alertedSec`-vlag, 50 m-drempel, route-wissel-reset, `triggerSurfaceAlert` = zelfde `vibrate`/`TONE_LAP` als `triggerClimbAlert`.
- **Geen wire-/payload-wijziging:** alle nieuwe data wordt op het horloge afgeleid uit `secStart`/`secEnd`; geen aanpassing aan schema/examples/POJO's/`CommListener.mc` → `ProtocolRoundTripTest` blijft groen (Final verification Step 3). Dit respecteert de `CLAUDE.md`-waarschuwing tegen asymmetrische wire-updates: er ís geen wire-update.
- **Type-consistentie:** `data.subPieceCount`/`data.currentSubPiece`/`data.subPieceLen` worden in Task 1 als publieke `var` op `SurfaceData` gezet en in Task 2 zo gelezen. `data.secStart[ci]`/`data.currentIdx`/`data.routeId` bestaan al als publieke velden op `SurfaceData`. `alertedSec` is `[32]` = `MAX_SECTIONS`.
- **Edge cases:** stuk-lengte 0 → `subPieceCount` blijft 0, balk wordt overgeslagen, geen deling door 0; heel kort stuk → `subPieceLen` geklemd op ≥ 1 en `subPieceCount` op ≤ `MAX_SUBPIECES`; `currentSubPiece` geklemd op `count-1`; buiten elk stuk → `currentIdx = -1`, geen balk/teller/alert; GPS-jitter over de stuk-grens → geen her-alert (per-stuk vlag); `routeId == null` → route-wissel-check valt terug op `lastRouteId != null`.
- **Review-only Monkey C:** geen JUnit mogelijk (per `CLAUDE.md`); elke code-stap wordt gedekt door `monkeyc`-compile (type-check) + een concrete simulator-observatie met verwachte waarden.
```
