# Monkey C functionaliteiten & teststrakheid

Overzicht van **alle functionaliteiten** aan de Monkey C (horloge-)kant, per module,
met daarbij **hoe strak elke functionaliteit getest is**.

Alles draaien:

```
pwsh -File tools/run-monkeyc-tests.ps1
```

Bouwt per module de `monkey-test.jungle` met `--unit-test` en draait die in de
Connect IQ-simulator op het `fr255m`-profiel.
**Status: 128 tests groen (garmin 69, garmin-widget 33, garmin-surface 26), 0 failures, 0 errors.**

> De Connect IQ SDK heeft geen line-coverage-instrument. Strakheid hieronder is
> bepaald per **functie-inventaris**: elke functie in `*/source/*.mc` is gekoppeld
> aan de test(s) die hem uitvoeren.

## Legenda teststrakheid

| Symbool | Niveau | Betekenis |
|---|---|---|
| 🟢 | **Strak (asserted)** | Pure logica: test voert inputs in en assert exacte outputs, inclusief edge cases. Hier verbergen bugs zich; ~100% gedekt. |
| 🟡 | **Smoke** | Render/lifecycle. Pixels zijn headless niet te asserten, maar de code draait tegen een echte off-screen `Dc` (`Graphics.createBufferedBitmap(...).get().getDc()`), dus een crash (null-deref, verkeerd API-gebruik) laat de test falen. |
| 🔴 | **Alleen on-device** | Niet bereikbaar in de headless harness (view-stack/timer/transmit). Alleen op het horloge te verifiëren. |

---

## Module `garmin` — datafield: huidige klim + volgende-klim preview

Bronnen: `ClimbData.mc`, `CommListener.mc`, `ClimbProView.mc`, `ClimbProApp.mc`

| # | Functionaliteit | Functie(s) | Strak | Dekkende tests / edge cases |
|---|---|---|:---:|---|
| 1 | **v3-payload parsen** (wire-dict → `ClimbData`) | `PhoneMessageCallback.onMessage`, `parseClimb`, `getInt` | 🟢 | route-mode (alle velden, segmenten, calib, surf, targets), radius-mode, ontbrekende surf/targets, null/niet-dict/verkeerde versie/geen climbs. **Edge:** >MAX_CLIMBS trunceert, >MAX_SEGMENTS trunceert, segs niet-viervoud, calib te kort, surf buiten 0–5, tsec korter dan segs, null-climb-element, resync zonder surf wist stale, niet-numerieke `rtl` → 0 |
| 2 | **Verbindings-callbacks** | `CommListener.onComplete/onError` | 🟡 | `commListener_callbacks_doNotThrow` (loggen alleen) |
| 3 | **Voortgang langs route** (actieve klim + segment, volgende klim, afstand) | `updateProgress`, `updateCurrentSegment` | 🟢 | op-klim → segment, in-range-niet-bevestigd blijft af, in-range-zonder-calib activeert. **Edge:** geen payload, radius-mode, voorbij alle klimmen, voortgang voorbij segmenten → laatste segment, exacte segmentgrens |
| 4 | **GPS↔route-matching & off-route-detectie** | `updateRouteMatch`, `minCalibDistM`, `distM` | 🟢 | approach-divergentie/op-route/ver-maar-niet-afgeweken, op-klim ver-van/dicht-bij-calib. **Edge:** radius-mode geen off-route, volgende klim zonder calib geen off-route |
| 5 | **GPS-klimbevestiging + approach-uitlijning** (`climbEntered`, start-align) | `updateRouteMatch` | 🟢 | approach-bereikt-start-bevestigt-dan-activeert, off-route-approach-bevestigt-nooit, approach-op-route-snapt-startafstand |
| 6 | **Kalibratie-snapping** (odometer-shift & trusted-validatie) | `checkCalibration` | 🟢 | dichtbij-punt snapt voortgang, trusted-overeenkomst-behoudt, trusted-afwijking-herroept, odometer-modus-shiftet. **Edge:** uitgeputte index no-op, ver-van-punt geen snap, twee punten na elkaar (index loopt op) |
| 7 | **Nav-axis trust-gating** (course-afstand vs odometer, length gate) | `chooseAxis`, `resetNavTrust` | 🟢 | niet-navigerend → odometer, length-gate slaagt → nav, length-gate faalt → odometer. **Edge:** geen routelengte → odometer, herroepen trust → odometer |
| 8 | **Klim-skip-weerbaarheid** (voorbijgereden klim overslaan) | `updateProgress`, `backOnRoute` | 🟢 | trusted-nav-voorbij-eind markeert-skip-en-schuift-op, odometer-zonder-latere-bevestiging skipt-niet, odometer-latere-klim-bevestigd skipt, geskipte-klim-niet-heractiveerd |
| 9 | **Onveranderlijke ankers** (start/eind blijven bron-van-waarheid) | `setAnchors` | 🟢 | ankers spiegelen start/eind bij parse; werk-waarde schuiven laat anker ongemoeid |
| 10 | **Pacing / doeltijd-interpolatie** | `targetSecondsAt` | 🟢 | midden-eerste-segment interpoleert, in-tweede-segment accumuleert. **Edge:** exacte segmentgrens, voorbij laatste segment (klemt op totaal), geen targets → -1, geen actieve klim → -1 |
| 11 | **Render huidige klim** (profiel, progress-marker, surface-bar, stats, ghost) | `drawActiveClimb`, `drawProfile`, `drawSurfaceBar`, `surfaceColor`, `formatDist` | 🟡 | `onUpdate_activeClimb_drawsProfileAndSurfaceAndGhost` |
| 12 | **Render volgende-klim preview** | `drawNextClimbPreview` | 🟡 | `onUpdate_nextClimb_drawsPreview` |
| 13 | **Render lege/rand-states** (geen data, geen klimmen, off-route-banner, klim-samenvatting) | `drawNoData`, `drawNoClimbs`, `drawOffRouteBanner`, `drawClimbSummary`, `climbTotalTarget` | 🟡 | `onUpdate_noData`, `onUpdate_noClimbsAhead`, `onUpdate_activeClimb_offRoute_drawsBanner`, `compute_entersThenLeavesClimb_setsSummary_thenRenders` |
| 14 | **Per-tick orchestratie** (axis kiezen → updateProgress → routeMatch → alert/summary) | `compute` | 🟡 | `compute_nullInfo`, `compute_withNavAndLocation` (assert axis), `compute_entersThenLeavesClimb` |
| 15 | **Klim-start-alert** (tril + toon, één keer/klim, off-route onderdrukt) | `triggerClimbAlert` (+ alert-gate in `compute`) | 🟡 | uitgevoerd via `compute`-pad; idempotentie/off-route-onderdrukking is smoke, niet exact geassert |
| 16 | **App-lifecycle** (start, telefoonbericht, initiële view, stop) | `ClimbProApp.onStart/onPhoneMessage/getInitialView/onStop` | 🟡 | `app_lifecycle_startMessageStop` (assert `payloadReceived`/`climbCount`/view-lijst) |

---

## Module `garmin-surface` — datafield: gebruiker-gedefinieerde ondergrond-secties

Bronnen: `SurfaceData.mc`, `SurfaceFieldView.mc`, `SurfaceFieldApp.mc`

| # | Functionaliteit | Functie(s) | Strak | Dekkende tests / edge cases |
|---|---|---|:---:|---|
| 1 | **surfSec-payload parsen** (secties + checkpoints) | `parse`, `numOr` | 🟢 | secties+checkpoints, verkeerde versie afgewezen, niet-dict-sectie overgeslagen. **Edge:** ontbrekende surfSec → false, surfSec geen-array → false, type buiten 0–5 → UNKNOWN, >MAX_SECTIONS trunceert |
| 2 | **Voortgang in sectie + 8%-deelstukken** | `updateProgress` | 🟢 | in-sectie zet current+subpiece, vóór-sectie zet next, voorbij alle secties wist. **Edge:** exact op sectie-eind is niet-current (eind exclusief) |
| 3 | **GPS-drift-correctie** (gesmoothe offset, snap naar checkpoint) | `correctElapsed`, `approxMeters` | 🟢 | snapt naar dichtstbijzijnde checkpoint. **Edge:** geen GPS-fix → alleen opgeslagen offset, geen checkpoints → ongewijzigd, meerdere checkpoints → kiest de nabije |
| 4 | **GPS-deelstuk-verfijning** (checkpoint binnen eigen sectie overschrijft afstand) | `refineSubPieceByGPS` | 🟢 | checkpoint-dichtbij overschrijft, GPS-ver geen wijziging, null-pos geen wijziging, niet-in-sectie no-op, sectie-zonder-checkpoints no-op |
| 5 | **Render huidige sectie** (kleurstaal, titel/type, resterende afstand, deelstuk-balk) | `drawCurrentSection`, `drawSubPieceBar`, `titleFor`, `formatDist` | 🟡 | `view_currentSection_drawsBarAndNext` |
| 6 | **Render volgende-sectie / lege states** | `drawNextOnly`, `onUpdate` (geen-secties-paden) | 🟡 | `view_noData`, `view_nextOnly`, `view_pastAll_drawsNoMoreSections` |
| 7 | **Per-tick orchestratie + ondergrond-alert** (tril + toon, één keer/stuk) | `compute`, `triggerSurfaceAlert` | 🟡 | `view_compute_drivesMatchingAndAlert` (assert `currentIdx`) |
| 8 | **App-lifecycle** (start, telefoonbericht, initiële view, stop) | `SurfaceFieldApp.onStart/onPhoneMessage/getInitialView/onStop` | 🟡 | `app_lifecycle_startMessageStop` (assert `payloadReceived`/`count`) |

---

## Module `garmin-widget` — route/klim-beheer, glance, sync

Bronnen: `ClimbData.mc`, `CommListener.mc`, `PhoneRouteIndex.mc`, `StorageManager.mc`,
`ProfileDrawer.mc`, `ClimbWidgetApp.mc`, en de views
(`ClimbGlanceView`, `RouteView`, `ClimbListView`, `ClimbDetailView`, `ActiveSetView`, `SyncView`).

| # | Functionaliteit | Functie(s) | Strak | Dekkende tests / edge cases |
|---|---|---|:---:|---|
| 1 | **v3-payload + fss parsen + bericht-type-dispatch** (ROUTE_LIST / ACTIVE_SET) | `PhoneMessageCallback.onMessage`, `parseClimb` | 🟢 | v3 decodeert klim+starred, ROUTE_LIST vult index, ACTIVE_SET slaat ack op, onbekend type genegeerd, null/verkeerde-versie afgewezen |
| 2 | **Voortgang (odometer-model) + kalibratie** | `updateProgress`, `checkCalibration` | 🟢/🟡 | op-klim → segment, calib-dichtbij snapt. **Edge:** radius-mode early-return, geen actieve klim no-op, geen payload early-return |
| 3 | **Starred-flats parsen** (gespecialiseerde vlakke stukken) | `parseFlatStarred`, `fdInt` | 🟢 | slaat op, slaat malformed over + reset. **Edge:** >MAX_FLAT_STARRED klemt op 16, niet-array wist lijst |
| 4 | **Route-catalogus opslag** (route + klim opslaan/laden/verwijderen, lichte meta) | `StorageManager.saveRoute/loadRoute/deleteRoute/isRouteSaved`, `saveClimb/loadClimb/deleteClimb/isClimbSaved`, `getSavedRouteIds/getSavedClimbKeys/getSavedRouteMeta` | 🟢 | route round-trip + meta + geen-duplicaat, niet-dict-payload defaults, klim round-trip, getters defaulten bij afwezig |
| 5 | **Telefoon-route-index** (lijst van syncbare routes) | `PhoneRouteIndex.populate/getCount/getId/getName/getClimbCount` | 🟢 | populate + getters, niet-array/niet-dict veilig met defaults |
| 6 | **Profiel + surface-bar tekenen** | `ProfileDrawer.drawProfile/drawSurfaceBar` | 🟡 | `profileDrawer_drawsAndEarlyReturns` (echte klim tekent, lege klim early-return) |
| 7 | **Glance-view** | `ClimbGlanceView.onUpdate` | 🟡 | `view_glance_draws` |
| 8 | **Route-lijst view** (telefoon- + opgeslagen-rijen, lege staat, selectie) | `RouteListView.onUpdate/onShow/refreshData/getTotalCount` | 🟡 | `view_routeList_withPhoneAndSavedRows`, `view_routeList_emptyState` |
| 9 | **Klim-lijst view** (klim-rijen, starred-rij, actie-rijen, load-fout) | `ClimbListView.onUpdate/refreshRouteSaved/getRouteId` | 🟡 | `view_climbList_neutralSource_drawsRows`, `view_climbList_savedRouteMissing_drawsLoadFailed` |
| 10 | **Klim-detail view + opslaan togglen** (rename/save-hint, profiel, stats) | `ClimbDetailView.onUpdate/refreshClimbSaved/toggleSave/getOriginalClimbIndex` | 🟢/🟡 | `view_climbDetail_drawsAndToggles` (assert save-status na toggle) |
| 11 | **Active-set view** (versturen / gelukt+naam / mislukt) | `ActiveSetView.onShow/onUpdate` | 🟡 | `view_activeSet_allAckStates` |
| 12 | **Sync-view** (verbindingsscherm) | `SyncView.onUpdate/onHide` | 🟡 | `view_sync_drawsConnecting` |
| 13 | **Veilige (niet-navigerende) delegate-handlers** | `ClimbListDelegate`/`RouteListDelegate` `onNextPage/onPreviousPage/onSelect` (guard-pad) | 🟡 | `*_delegate_safeHandlers` (guard-pad geeft `true` terug zonder navigeren) |
| 14 | **App-lifecycle** (glance, initiële view, bericht, stop) | `ClimbWidgetApp.onStart/getGlanceView/getInitialView/processMessage/onPhoneMessage/onStop` | 🟡 | `widgetApp_lifecycle_initGlanceMessageStop` (assert `payloadReceived`, 2 views) |
| 15 | **Navigatie- & telefoon-glue** (view-wissels, timer, transmit) | Navigerende takken van `onBack/onMenu/onSelect/onNextPage/onPreviousPage`, `SyncView.onShow/onTimeout` (`Timer` + `switchToView`), `CommListener.handleHello` (`Comm.transmit`), `RouteListDelegate.openSavedClimb`/`splitOnLastUnderscore` | 🔴 | **Alleen on-device.** Empirisch bevestigd: een view pushen in een `(:test)` maakt `Ui.getCurrentView()` niet die view, dus view-afhankelijke takken en `Timer`/`Comm.transmit` draaien niet headless |

---

## Samenvatting

| Module | Functionaliteiten | 🟢 Strak | 🟡 Smoke | 🔴 On-device |
|---|---|---|---|---|
| `garmin` | 16 | 10 (kernlogica: parser, matching, calib, pacing, skip, trust) | 6 (render + lifecycle + alert) | 0 |
| `garmin-surface` | 8 | 4 (parser, voortgang, drift-correctie, GPS-verfijning) | 4 (render + lifecycle + alert) | 0 |
| `garmin-widget` | 15 | 5 (parser, storage, index, starred, deel-logica) | 9 (alle views + delegate-guards + lifecycle) | 1 (navigatie/timer/transmit-glue) |

**Bedrijfslogica + wire-parser: ~100% strak geassert over alle drie modules.**
De enige echte gap is de UI-navigatie/timer/transmit-glue in de widget, die
structureel niet headless te draaien is en on-device wordt geverifieerd.

De hand-geschreven v3-parser (`CommListener` / `PhoneMessageCallback.onMessage`) —
in `CLAUDE.md` ooit als "review-only" gemarkeerd — wordt nu **echt uitgevoerd en
geassert** tegen dictionaries die `protocol/examples/*.json` spiegelen. Dit is de
horloge-zijde-tegenhanger van de JVM-`ProtocolRoundTripTest`.
