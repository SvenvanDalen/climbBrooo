# Pacing-systeem — ontwerp

**Datum:** 2026-06-13
**Status:** goedgekeurd (secties 1–5 = eerste leverbare versie; sectie 6 na feasibility-spike)
**Modus:** route-modus only (radius-modus ongewijzigd)

## Doel

Eén nieuw stukje precomputed data — **streeftijd per segment** — voedt drie
rij- en planning-features, plus een vierde apart gefaseerde feature:

1. **Route-paspoort & pacing-plan** (telefoon, routedetailscherm).
2. **Live pacing-ghost** (klim-datafield): toont tijdens de klim of je vóór of
   achter ligt op je eigen geschatte tijd, als tijd-delta `+12s` / `−8s`.
3. **Klim-samenvatting na de top** (klim-datafield): kort kaartje met jouw tijd
   vs. plan direct na het kruinen.
4. **Post-rit +/− op de telefoon** (apart gefaseerd, met haalbaarheidsrisico):
   werkelijke klimtijden terug naar de telefoon, per klim de +/− tonen.

Het primaire live-signaal is bewust de **tijd-delta**, niet streefvermogen — zo
is geen vermogensmeter nodig op de Forerunner 255 Music.

## Niet in scope

- Radius-modus krijgt geen ghost/samenvatting (geen route-brede fatigue-context).
- Streefvermogen op de watch (alleen tijd-delta).
- Sociaal/delen, badges, leaderboards, ghost tegen je vorige werkelijke tijd.
- Een los paspoort-scherm (het wordt een sectie in het bestaande routedetail).

## Hergebruik van bestaande code

- `domain/power/RouteAwareClimbEstimator` (fatigue-aware, W'-balans over de hele
  route) levert al `ClimbTimeEstimate { totalSeconds, segmentSeconds[],
  assumedPowerWatts }`. De per-klim `ClimbTimeEstimator` is de fallback.
- Rijderprofiel staat al in `RiderProfileRepository` (FTP, gewichten,
  ride-intensity).
- Payload-bouwer `service/ClimbPayloadBuilder` heeft al een patroon voor
  optionele packed int-arrays per klim (`segs`, `surf`, `calib`).
- Klim-datafield `garmin/source` (`ClimbData`, `CommListener`, `ClimbProView`)
  krijgt in `compute(info)` al `info.elapsedDistance`; `info.timerTime` levert de
  verstreken tijd.

---

## Sectie 1 — Pacing-plan berekenen (telefoon)

Nieuwe domeinservice **`RoutePacingPlanner`** (`domain/power/`):

- Input: een `StoredRoute` (of het domein-`Route` + climbs) + het rijderprofiel.
- Draait `RouteAwareClimbEstimator` over álle klimmen en levert per klim een
  `int[] segmentSeconds` (streeftijd per segment, parallel aan de segmenten).
- Fallback naar de verse per-klim `ClimbTimeEstimator` als route-brede
  elevatie/afstand ontbreekt.
- Levert **niets** (geen pacing-plan) als het profiel onvolledig is.
- Pure functie, unit-testbaar, geen I/O.

**Wanneer berekend:** bij het bouwen van de payload (sync-tijd) met het huidige
profiel. De berekening gebeurt **buiten** `ClimbPayloadBuilder` (die blijft pure
serialisatie); de builder serialiseert alleen de al-berekende streeftijden.

**Incrementele sync:** het rijderprofiel gaat als **signatuur** (hash van de
relevante profielvelden) mee in de "is deze route gewijzigd?"-detectie naast de
bestaande bron-bestand-hash. Zo triggert een profielwijziging een re-sync van
`tsec`, ook al verandert het bron-GPX/FIT niet.

---

## Sectie 2 — Wire-format (protocol)

Volgorde per `CLAUDE.md`: eerst `protocol/schema.json`, dan Java-POJO's
regenereren, dan Monkey C met de hand bijwerken, dan `protocol/examples/`
bijwerken zodat de round-trip-tests blijven slagen.

- Nieuw **optioneel** veld op `Climb`: `targetSeconds`, wire-key **`tsec`**.
- Packed int-array, één streeftijd in hele seconden per segment, **parallel aan
  `segs`** (zelfde volgorde en lengte als het aantal segmenten).
- Weggelaten als er geen pacing-plan is (net als `surf`/`calib` conditioneel zijn).
- Grootte ~13 ints per klim; ruim binnen het payload-budget.
- `schema.md` changelog en byte-budget-notitie bijwerken.

---

## Sectie 3 — Live pacing-ghost (klim-datafield)

In `garmin/source`:

- `ClimbData`: nieuwe parallelle array `segTargetSec[climb][seg]`; `CommListener`
  parst `tsec` naast `segs` (lengte = `segCount`, ontbreekt → ghost uit).
- Bij betreden van een klim (`activeClimbIndex` wisselt naar ≥0): leg
  `climbStartTimerMs = info.timerTime` vast. Reset bij klimwissel en route-wissel.
- Per tick:
  - **streeftijd-op-positie** = som van `tsec` t/m het volledige laatste
    afgeronde segment + lineaire interpolatie binnen het huidige segment op basis
    van `progressInClimb` t.o.v. de segmentgrens.
  - **werkelijk** = `info.timerTime − climbStartTimerMs` (ms → s).
  - **delta = werkelijk − streef**.
- Weergave in `ClimbProView.drawActiveClimb`: compact en groot, `+12s` (achter,
  rood) / `−8s` (vóór, groen). Plaatsing in/boven de bestaande stats-regel zonder
  de profielzone te verstoren.
- Geen `tsec` voor de actieve klim → ghost niet tekenen; rest van de view
  ongewijzigd.

**Edge cases:** ontbrekende/0 `timerTime`; klim opnieuw betreden na off-route
(geen herstart van de timer als `activeClimbIndex` gelijk blijft); zeer korte
laatste segmenten (deel-door-nul vermijden).

---

## Sectie 4 — Klim-samenvatting na de top (klim-datafield)

- Detecteer klim-einde: `activeClimbIndex` van ≥0 → −1 (of naar een volgende
  klim). Bewaar `lastClimbActualSec`, `lastClimbTargetTotalSec`, naam,
  hoogtemeters, gem. gradient en de delta.
- Toon **N seconden** (default 12s) een tijdelijke render-state in dezelfde view:
  klimnaam, jouw tijd, hoogtemeters, gem. gradient, en **vs. plan (+/−)**.
- Na N seconden (timer) automatisch terug naar de "next climb"-preview.
- Geen modal, geen gebruikersinteractie vereist (datafield-vriendelijk).
- Geen `tsec` → samenvatting toont alleen tijd/hoogtemeters/gradient zonder +/−.

---

## Sectie 5 — Route-paspoort (telefoon, routedetailscherm)

Uitbreiding van het bestaande routedetailscherm (`ui/...RouteDetail...`) met een
rustige overzichtssectie boven/in de bestaande inhoud:

- **Totaal:** aantal klimmen, totale hoogtemeters, zwaarste klim (naam +
  kerncijfers), **totale geschatte tijd**.
- **Per klim:** streeftijd uit het pacing-plan (en bestaande kerncijfers).
- Berekening via dezelfde `RoutePacingPlanner` / bestaande estimator, on-demand in
  de ViewModel (`onResume`, zodat een gewijzigd profiel wordt opgepikt — zoals de
  bestaande klim-tijdschatting al doet).
- Geen los scherm, geen deel-knop. Onvolledig profiel → toon de overzichtscijfers
  zonder tijden, met een hint om het profiel in Settings in te vullen.

---

## Sectie 6 — Post-rit +/− op de telefoon ⚠️ (apart gefaseerd, met risico)

**Risico:** de klim-datafield zendt nu niet naar de telefoon; CIQ-apps hebben
geïsoleerde storage (dus niet via de widget laten versturen); en een **datafield
mag tijdens een activiteit lang niet altijd `Comm.transmit`** doen op de FR255.

**Feasibility-spike (eerst, vóór bouwen):**

- Kan de FR255-datafield betrouwbaar `Comm.transmit` naar de companion-app doen
  (tijdens en/of net na de activiteit)?
- Zo niet: is een FIT developer-field + latere uitlezing een werkbaar alternatief,
  of vervalt deze feature?

**Beoogde aanpak bij groen licht:**

- Datafield buffert per voltooide klim `{climbIdx, actualSec, targetSec, delta}`
  in eigen Storage (`ride_results`).
- Na afloop transmit naar de telefoon.
- Telefoon (`WatchRequestHandler` + repository) slaat ritresultaten op en toont
  per klim de +/− in het routedetail.

Pas inplannen ná de spike.

---

## Tests

**Telefoon (pure unit-tests):**

- `RoutePacingPlanner`: correcte `segmentSeconds` per klim; fallback naar
  per-klim-estimator; leeg resultaat bij onvolledig profiel.
- Payload round-trip met én zonder `tsec` (`protocol/examples/`).
- Profiel-signatuur in change-detectie triggert re-sync na profielwijziging.

**Watch (Monkey C):**

- `CommListener` parst `tsec` correct (lengte-mismatch, ontbrekend veld).
- Ghost-delta-berekening incl. lineaire interpolatie binnen een segment.
- Klim-einde-detectie triggert de samenvatting; timer reset naar preview.

## Fasering

1. **Fase A (eerste leverbare versie):** secties 1–5.
2. **Fase B:** feasibility-spike voor sectie 6 (datafield → telefoon).
3. **Fase C:** sectie 6 bij groen licht.

## Documentatie bij oplevering

Per `CLAUDE.md`/globale instructies: `Documentation/ARCHITECTURE.md` en `README.md`
bijwerken bij wijziging van wire-format/architectuur (het nieuwe `tsec`-veld en de
pacing-dataflow).
