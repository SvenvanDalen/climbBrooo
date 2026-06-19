# Debug-All-Code Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each fix MUST follow superpowers:systematic-debugging (root cause before fix) and superpowers:test-driven-development (failing test first).

**Goal:** Systematisch alle Java- en Monkey C-bugs in deze repo vinden en fixen, evidence-first: eerst een testharnas en een baseline, dan per bevinding root-cause → falende test → fix → verify → commit.

**Architecture:** Drie modules — Android (Java, JUnit-harnas aanwezig), Connect IQ datafield (`garmin/`), widget (`garmin-widget/`), surface-datafield (`garmin-surface/`). De Monkey C-kant heeft vandaag **geen** testharnas; taak 1 bouwt die met `Toybox.Test` zodat watch-logica net als Java falende tests kan krijgen. Wire-format is v3 (`protocol/schema.json` is bron van waarheid).

**Tech Stack:** Java 17 + Gradle (Groovy DSL), JUnit; Monkey C SDK (`monkeyc`, `--unit-test`), Connect IQ simulator (Forerunner 255 Music); Jackson voor payload-serialisatie.

---

## Bevestigde bugs (root cause bekend, fix in dit plan)

| # | Bestand(en) | Bug | Bewijs / root cause | Taak |
|---|-------------|-----|---------------------|------|
| B1 | `garmin/source/ClimbProView.mc`, `garmin-widget/source/*View*.mc` | `ClimbData.checkCalibration()` wordt **nooit aangeroepen**. GPS-kalibratie (calib-punten) is end-to-end bekabeld (detector emit → `ClimbPayloadBuilder` `calib` → `CommListener.parseClimb` vult `calibLat/Lon/Dist`) maar `compute()` leest `info.currentLocation` niet en roept `checkCalibration` niet aan. | `grep checkCalibration` → alleen definitie + comment "no-op until CommListener wires these up". `compute()` gebruikt enkel `info.elapsedDistance`. Surface-field doet het GPS-werk wél (`refineSubPieceByGPS`), klim-field niet → asymmetrische, onafgemaakte integratie. | T3 |

> Slechts één bevinding is met hoge zekerheid een echte bug zonder verdere uitvoering. De rest staat hieronder als te-verifiëren; ze worden niet als bug "geclaimd" maar via een karakteriseringstest bevestigd of weerlegd vóór er iets verandert (systematic-debugging Fase 1).

## Onzekere bevindingen (eerst verifiëren met een test — kan false positive zijn)

| # | Bestand:regel | Vermoeden | Waarom onzeker | Taak |
|---|---------------|-----------|----------------|------|
| U1 | `domain/matching/NearestPointFinder.java` | Mogelijk **dode code**: watch matcht op `elapsedDistance`, deze phone-side matcher lijkt nergens aangeroepen. Als wél gebruikt: bij uitsluitend "backward" kandidaten blijft `bestRoutePos == lastDistance` (stilstand) — bedoeld of niet? | Onbekend of er een caller is; "stay put" kan correct hysterese-gedrag zijn. | T5 |
| U2 | `garmin/source/CommListener.mc:141-158` (+widget) | `segSurf` wordt bij een `surf`-array korter dan `segCount` niet voor de resterende segmenten op 5 gereset → stale waarde bij re-sync. | Producer (`buildSurf`) zendt altijd exact `segCount` entries, dus nu niet triggerbaar. Defensief alsnog fixen. | T6 |
| U3 | `garmin/source/ClimbProApp.mc:21-22` | `new ClimbData()` roept `initialize()` al aan; de expliciete `climbData.initialize()` is een **dubbele init** (arrays 2× gealloceerd). | Functioneel onschadelijk; wel verspilling op geheugenkrap horloge. | T6 |
| U4 | `garmin/source/ClimbProView.mc:88-96` | Post-klim "summary" wordt overgeslagen bij twee **direct aangrenzende** klimmen (overgang klim→klim zet `summaryClimbIndex` maar `onUpdate` tekent alleen bij `activeClimbIndex < 0`). | Zeldzame route-geometrie; mogelijk bewuste UX-keuze. | T5 |
| U5 | `garmin-surface/source/SurfaceData.mc:118-139` | `correctElapsed` vereist elke tick een checkpoint binnen `SNAP_M=40`; zonder dat groeit `distanceOffset` niet en blijft drift staan. | Bewuste smoothing; "bug" alleen als checkpoints te dun staan (200 m spacing vs 40 m snap). | T5 |
| U6 | `garmin/source/ClimbProView.mc:412-419`, `garmin-surface/.../SurfaceFieldView.mc:173-178` | `formatDist` verliest precisie (`(m%1000)/100` → 1 decimaal, 1050 m toont "1.0km"). | Cosmetisch, geen correctheidsbug. | T5 |
| U7 | Hele Java-codebase buiten de gelezen kernbestanden (power/pacing, GPX-parser, sync, UI, repositories) | Niet handmatig geauditeerd. | Wordt afgedekt door de bestaande JUnit-suite (T1) i.p.v. giswerk. | T1, T7 |

---

## File Structure

**Nieuw (Monkey C testharnas):**
- Create: `garmin/test/ClimbDataTest.mc` — `(:test)`-functies voor `ClimbData.updateProgress` en `checkCalibration`.
- Create: `garmin/monkey-test.jungle` — build-target dat `source` + `test` bundelt voor `--unit-test`.
- (Analoog later voor `garmin-surface/test/SurfaceDataTest.mc` indien nodig — pas toevoegen wanneer een surface-bug een test vereist.)

**Te wijzigen (fixes):**
- Modify: `garmin/source/ClimbProView.mc` — `compute()` roept `checkCalibration` aan.
- Modify: `garmin-widget/source/ClimbDetailView.mc` (of de view die `compute` host) — idem.
- Modify: `garmin/source/CommListener.mc`, `garmin-widget/source/CommListener.mc` — U2 defensieve reset.
- Modify: `garmin/source/ClimbProApp.mc` — U3 dubbele init.

**Bestaande Java-tests (instrument voor triage):**
- `android/app/src/test/java/...` — volledige suite via `gradlew test`.

---

## Task 1: Baseline-bewijs verzamelen (Fase 1, geen fixes)

**Files:** geen wijzigingen — alleen draaien en vastleggen.

- [ ] **Step 1: Draai de volledige Java-testsuite**

Run (vanuit `android/`):
```
.\gradlew.bat test
```
Expected: een groene of rode suite. Leg de exacte output vast (welke `*Test` falen, met stacktrace). Dit is het Fase-1-bewijs voor U7 en alle Java-modules.

- [ ] **Step 2: Compileer alle drie de Monkey C-projecten**

Run (per project, met je developer key):
```
monkeyc -f garmin/monkey.jungle -o build\climbpro.prg -y <key> -d fr255m
monkeyc -f garmin-widget/monkey.jungle -o build\widget.prg -y <key> -d fr255m
monkeyc -f garmin-surface/monkey.jungle -o build\surface.prg -y <key> -d fr255m
```
Expected: compile-warnings/errors vastgelegd. Compileert het niet, dan is dát de eerste bug.

- [ ] **Step 3: Leg de baseline vast in het plan**

Noteer onder elke taak (T5–T7) welke testfailures er al zijn, zodat "root cause bekend" pas wordt afgevinkt als de failure verklaard is.

- [ ] **Step 4: Commit (alleen indien je een bevindingen-logbestand toevoegt)**

```
git add docs/superpowers/plans/2026-06-17-debug-all-code.md
git commit -m "docs: baseline test/compile evidence for debug sweep"
```

---

## Task 2: Monkey C testharnas opzetten

**Files:**
- Create: `garmin/monkey-test.jungle`
- Create: `garmin/test/ClimbDataTest.mc`

- [ ] **Step 1: Maak het test-build-target**

Create `garmin/monkey-test.jungle`:
```
project.manifest = manifest.xml
base.sourcePath = source;test
base.resourcePath = resources
```

- [ ] **Step 2: Schrijf een falende/karakteriserende test voor `updateProgress`**

Create `garmin/test/ClimbDataTest.mc`:
```
using Toybox.Test;

(:test)
function updateProgress_onClimb_setsActiveAndSegment(logger) {
    var d = new ClimbData();          // new roept initialize() aan
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 2;
    d.segDist[0][0] = 400;
    d.segDist[0][1] = 400;

    d.updateProgress(1500);           // 500 m in de klim → segment 1

    Test.assertEqual(d.activeClimbIndex, 0);
    Test.assertEqual(d.progressInClimb, 500);
    Test.assertEqual(d.activeSegmentIndex, 1);
    return true;
}
```

- [ ] **Step 3: Draai de unit-tests in de simulator**

Run:
```
monkeyc -f garmin/monkey-test.jungle -o build\climbpro-test.prg -y <key> -d fr255m --unit-test
connectiq            # start simulator
monkeydo build\climbpro-test.prg fr255m -t
```
Expected: test groen. Faalt het door een harnesfout, fix het harnas (niet de productiecode) tot deze baseline-test groen is.

- [ ] **Step 4: Commit**

```
git add garmin/monkey-test.jungle garmin/test/ClimbDataTest.mc
git commit -m "test(ciq): add Monkey C unit-test harness for ClimbData"
```

---

## Task 3: Fix B1 — `checkCalibration` daadwerkelijk aanroepen

**Files:**
- Test: `garmin/test/ClimbDataTest.mc` (uitbreiden)
- Modify: `garmin/source/ClimbProView.mc:59-111` (`compute`)
- Modify: `garmin-widget/source/` view die `compute(info)` host (zoek met `grep "function compute(info)" garmin-widget/source`)

- [ ] **Step 1: Root cause bevestigen (Fase 1/2)**

Run:
```
```
Grep `checkCalibration` over `garmin*/source` en bevestig: definitie + parse aanwezig, géén call. Bevestig dat `compute(info)` `info.currentLocation` niet uitleest. Documenteer dit als root cause.

- [ ] **Step 2: Falende test — kalibratie corrigeert progress**

Voeg toe aan `garmin/test/ClimbDataTest.mc`:
```
(:test)
function checkCalibration_nearPoint_snapsProgress(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1;
    d.calibDist[0][0] = 400;          // 400 m vanaf klimstart
    d.calibLat[0][0]  = 52.0f;
    d.calibLon[0][0]  = 5.0f;

    d.updateProgress(1300);           // GPS zegt 300 m in de klim
    d.checkCalibration(52.0f, 5.0f);  // maar we staan exact op calib-punt (400 m)

    Test.assertEqual(d.progressInClimb, 400);
    return true;
}
```
Run de harnas-tests; deze test slaagt al (de `ClimbData`-functie zelf werkt). Hij borgt het gedrag dat de view straks moet aanroepen.

- [ ] **Step 3: Roep `checkCalibration` aan in `compute()`**

In `garmin/source/ClimbProView.mc`, ná `data.updateProgress(elapsed);` (regel 86), toevoegen:
```
        if (data.activeClimbIndex >= 0
                && info != null && info has :currentLocation
                && info.currentLocation != null) {
            var ll = info.currentLocation.toDegrees();   // [lat, lon]
            data.checkCalibration(ll[0], ll[1]);
        }
```
Pas dezelfde wijziging toe in de widget-view die `compute(info)` host (gebruik daar `data.checkCalibration` met dezelfde guard).

- [ ] **Step 4: Verifiëren — compile + simulator-rit**

Run:
```
monkeyc -f garmin/monkey.jungle -o build\climbpro.prg -y <key> -d fr255m
monkeyc -f garmin/monkey-test.jungle -o build\climbpro-test.prg -y <key> -d fr255m --unit-test
monkeydo build\climbpro-test.prg fr255m -t
```
Expected: compileert zonder errors; harnas-tests groen. Doe daarna een simulator-rit met een GPX-track door een klim en bevestig dat `progressInClimb` "verspringt" bij een calib-punt (Sys.println-log of zichtbare marker-correctie). Documenteer de waarneming (verification-before-completion).

- [ ] **Step 5: Commit**

```
git add garmin/source/ClimbProView.mc garmin-widget/source/*.mc garmin/test/ClimbDataTest.mc
git commit -m "fix(ciq): invoke checkCalibration from compute so GPS calibration is applied"
```

---

## Task 4: Java-testfailures uit de baseline triëren en fixen (afdekking U7)

> Eén sub-cyclus per falende test uit Task 1, Step 1. Herhaal Steps 1–5 per failure. Géén fix zonder root cause.

**Files:** afhankelijk van de falende test — exact pad komt uit de stacktrace.

- [ ] **Step 1: Kies de eerste falende test en lees de assertie + stacktrace volledig**

Run:
```
.\gradlew.bat test --tests <FQCN van de falende test>
```
Expected: dezelfde failure, geïsoleerd. Noteer verwachte vs. werkelijke waarde.

- [ ] **Step 2: Trace naar de bron**

Lees de geteste productieklasse. Volg de slechte waarde terug naar zijn oorsprong (root-cause-tracing). Schrijf één hypothese op: "X is de oorzaak omdat Y."

- [ ] **Step 3: Borg met een minimale extra test indien de failure een symptoom van iets dieper is**

Voeg, alleen indien nodig, een test toe die de root cause direct vastlegt (niet het symptoom). Anders gebruik je de bestaande falende test als borging.

- [ ] **Step 4: Eén fix, dan verifiëren**

Pas de root cause aan (één wijziging). Run:
```
.\gradlew.bat test --tests <FQCN>
.\gradlew.bat test
```
Expected: de test slaagt en geen andere test breekt. Lukt het na 3 pogingen niet → STOP en bevraag de architectuur (systematic-debugging Fase 4.5).

- [ ] **Step 5: Commit per fix**

```
git add <gewijzigde bestanden> <testbestanden>
git commit -m "fix(android): <root cause in één zin>"
```

---

## Task 5: Onzekere bevindingen U1, U4, U5, U6 verifiëren (bevestigen of weerleggen)

> Per bevinding: schrijf eerst een karakteriseringstest die het *verwachte* gedrag vastlegt. Slaagt hij → geen bug, sluit de bevinding. Faalt hij → het is een bug, fix volgens Task 4-cyclus.

- [ ] **Step 1: U1 — is `NearestPointFinder` in gebruik?**

Run:
```
```
Grep `new NearestPointFinder` over `android/app/src/main`. Geen treffers → dode code: noteer en stel verwijderen voor aan de gebruiker (niet eigenmachtig verwijderen — het kan publieke API zijn). Wél treffers → schrijf een test die "alleen-backward kandidaten ⇒ progress blijft staan" vastlegt en bevestig of dat gewenst is.

- [ ] **Step 2: U4 — summary bij aangrenzende klimmen**

Voeg een `(:test)` toe die twee aangrenzende klimmen simuleert en bevestigt of een gemiste summary acceptabel is. Is het ongewenst → fix door de summary-trigger los te koppelen van `activeClimbIndex < 0`. Anders: documenteer als bewuste keuze en sluit.

- [ ] **Step 3: U5 — checkpoint-dichtheid vs. SNAP_M**

Controleer `ClimbPayloadBuilder.CHECKPOINT_SPACING_M` (200) vs. `SurfaceData.SNAP_M` (40). Beredeneer/test of een rijder ooit binnen 40 m van een checkpoint komt bij 200 m spacing (ja, hij passeert ze). Als drift-correctie te zelden grijpt: stel grotere `SNAP_M` of dichtere checkpoints voor. Documenteer de afweging.

- [ ] **Step 4: U6 — `formatDist` precisie**

Cosmetisch. Alleen aanpassen als de gebruiker dat wil; anders sluiten als "no fix". Geen test nodig.

- [ ] **Step 5: Commit per bevestigde fix (zie Task 4, Step 5).**

---

## Task 6: Defensieve fixes U2 en U3

**Files:**
- Modify: `garmin/source/CommListener.mc:141-158`, `garmin-widget/source/CommListener.mc:141-158`
- Modify: `garmin/source/ClimbProApp.mc:20-22`

- [ ] **Step 1: U2 — reset `segSurf` vóór het vullen**

In beide `CommListener.mc`, vervang de `surf`-branch zodat álle segmenten eerst op 5 staan:
```
        var surf = climbDict.get("surf");
        var segCnt = data.segCount[idx];
        for (var s = 0; s < segCnt; s++) { data.segSurf[idx][s] = 5; }
        if (surf != null && surf instanceof Toybox.Lang.Array) {
            var surfSize = surf.size();
            for (var s = 0; s < segCnt && s < surfSize; s++) {
                var sv = surf[s];
                data.segSurf[idx][s] =
                    (sv instanceof Toybox.Lang.Number && sv.toNumber() >= 0 && sv.toNumber() <= 5)
                        ? sv.toNumber() : 5;
            }
        }
```

- [ ] **Step 2: U3 — verwijder de dubbele init**

In `garmin/source/ClimbProApp.mc`, verwijder regel 22 (`climbData.initialize(); // belangrijk!`) zodat `initialize()` alleen door `new ClimbData()` loopt. Verifieer met een simulator-start dat arrays gevuld zijn (geen null-deref).

- [ ] **Step 3: Verifiëren**

Run:
```
monkeyc -f garmin/monkey.jungle -o build\climbpro.prg -y <key> -d fr255m
monkeyc -f garmin-widget/monkey.jungle -o build\widget.prg -y <key> -d fr255m
monkeyc -f garmin/monkey-test.jungle -o build\climbpro-test.prg -y <key> -d fr255m --unit-test
monkeydo build\climbpro-test.prg fr255m -t
```
Expected: compileert, harnas-tests groen.

- [ ] **Step 4: Commit**

```
git add garmin/source/CommListener.mc garmin-widget/source/CommListener.mc garmin/source/ClimbProApp.mc
git commit -m "fix(ciq): reset segSurf on reparse and drop duplicate ClimbData.initialize"
```

---

## Task 7: Resterende handmatige audit (niet-gelezen Java-modules)

> Modules nog niet handmatig gelezen: `domain/power/*` (behalve `PowerSpeedSolver`), `domain/route/GpxParser`, `RouteSimplifier`, `service/SyncOrchestrator`, `RouteSyncWorker`, `connectiq/WatchRequestHandler`, alle `ui/*` en `data/*` repositories. De JUnit-suite (Task 1) dekt het gedrag; deze taak is een gerichte leesronde voor bugs die geen test heeft.

- [ ] **Step 1: Lees per module en noteer concrete verdenkingen**

Lees elk bestand. Voor elke concrete verdenking: voeg een rij toe aan de "Onzekere bevindingen"-tabel hierboven (bestand:regel + vermoeden + waarom onzeker).

- [ ] **Step 2: Voor elke nieuwe verdenking — karakteriseringstest**

Schrijf een falende/borgende JUnit-test (TDD). Faalt hij → fix via Task 4-cyclus. Slaagt hij → sluit de bevinding.

- [ ] **Step 3: Dekkingscheck**

Run:
```
.\gradlew.bat test
```
Expected: hele suite groen. Bevestig dat elke bevestigde bug een bijbehorende test heeft.

- [ ] **Step 4: Commit per fix (zie Task 4, Step 5).**

---

## Task 8: Afronden

- [ ] **Step 1: Volledige suites groen**

Run:
```
.\gradlew.bat test
monkeydo build\climbpro-test.prg fr255m -t
```
Expected: alles groen; alle drie de `.prg`'s compileren.

- [ ] **Step 2: Documentatie bijwerken indien architectuur/wire-format raakte**

Werk `Documentation/ARCHITECTURE.md` en `README.md` bij als een fix het matchingsgedrag of de wire-format veranderde (per CLAUDE.md). De B1-fix raakt het matchingsgedrag (GPS-kalibratie nu actief) → vermeld dit.

- [ ] **Step 3: Geheugen bijwerken**

Update `.../memory/`: de note over kalibratie ([[resync-wipes-climb-user-data]]) vermeldt "ClimbDetector now emits calibration points" — vul aan dat de watch-consumptie (B1) nu ook bekabeld is.

- [ ] **Step 4: Eindcommit / branch afronden**

Gebruik superpowers:finishing-a-development-branch voor merge/PR-keuze.

---

## Self-Review (uitgevoerd)

- **Spec-dekking:** "alle code debuggen" = Java (Task 1+4+7 via suite + leesronde) + Monkey C (Task 2 harnas, Task 3/6 fixes, simulator-verificatie). Bevestigde bug (B1) en onzekere bevindingen (U1–U7) hebben elk een taak.
- **Placeholder-scan:** fixes met concrete code (B1, U2, U3, harnas). Triage-taken (T4/T5/T7) bevatten echte commando's + falende-test-eis i.p.v. "fix de bugs" — dit is bewust evidence-first, niet giswerk, omdat ~80 bestanden niet betrouwbaar blind te auditen zijn.
- **Type-consistentie:** `checkCalibration(lat, lon)`, `updateProgress(elapsed)`, `info.currentLocation.toDegrees()` consistent gebruikt met de gelezen broncode.
- **Bekende harnasgrens:** Monkey C-tests draaien alleen in de simulator (`monkeydo -t`), niet in CI — expliciet benoemd in Task 1/2.
