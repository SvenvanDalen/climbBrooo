# Pacing-systeem — Fase A implementatieplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bereken op de telefoon een streeftijd per klim-segment, stuur die mee in de wire-payload (`tsec`), toon een pacing-overzicht op het routedetailscherm, en laat de klim-datafield live tonen of je vóór/achter ligt op je plan plus een korte samenvatting na de top.

**Architecture:** De telefoon hergebruikt de bestaande fatigue-aware `RouteAwareClimbEstimator` (met fallback naar `ClimbTimeEstimator`) via een nieuwe pure service `RoutePacingPlanner`. De streeftijden gaan als optionele packed int-array `tsec` per klim mee in de bestaande v3-payload (zoals `surf`/`calib`). De watch parseert `tsec`, legt bij klimstart de timer vast, en vergelijkt de verstreken tijd met de geïnterpoleerde streeftijd op de huidige positie.

**Tech Stack:** Java 11 (Android, Gradle Groovy DSL, JUnit4 + Mockito), Monkey C (Connect IQ datafield, FR255M), JSON Schema (`protocol/`), Jackson.

**Scope (Fase A):** secties 1–5 van `docs/superpowers/specs/2026-06-13-pacing-system-design.md`. Sectie 6 (post-rit +/− terug op de telefoon) zit NIET in dit plan — eerst de feasibility-spike.

**Bewuste vereenvoudiging t.o.v. de spec:** de spec noemt een "profiel-signatuur in de wijzigingsdetectie". De dominante weg naar de watch is `SET_ACTIVE_ROUTE` (`WatchRequestHandler`), die de payload élke keer vers bouwt met het actuele profiel — daar is geen signatuur nodig. Alleen de achtergrond-`RouteSyncWorker` heeft een gate die een ongewijzigde route overslaat; die gate breiden we in Task 6 minimaal uit met een profiel-signatuur zodat een profielwijziging ook daar een re-sync triggert. Er komt geen nieuw opslagveld bij.

**Monkey C testopmerking:** dit project heeft geen geautomatiseerd Monkey C-testharnas. Watch-taken (8–11) worden geverifieerd door (a) `monkeyc` te compileren en (b) gedrag te controleren in de Connect IQ-simulator (FR255M device profile). Waar mogelijk wordt logica in kleine, met de hand na te rekenen functies gezet. Als de CIQ SDK niet geïnstalleerd is, verifieer je via code-review tegen de in dit plan gegeven verwachte waarden.

---

## Bestandsoverzicht

**Aanmaken (Java):**
- `android/app/src/main/java/nl/paree/climbpro/service/RoutePacingPlanner.java` — pure planner: route + profiel → per-klim streeftijden.
- `android/app/src/main/java/nl/paree/climbpro/ui/routes/RoutePassport.java` — pure waardenobject + builder voor het overzicht.
- `android/app/src/test/java/nl/paree/climbpro/service/RoutePacingPlannerTest.java`
- `android/app/src/test/java/nl/paree/climbpro/ui/routes/RoutePassportTest.java`

**Wijzigen (Java):**
- `android/app/src/main/java/nl/paree/climbpro/domain/power/RiderProfile.java` — `signature()` toevoegen.
- `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java` — `tsec` (overloads).
- `android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java` — profiel laden + plan meegeven.
- `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java` — `WatchRequestHandler`-constructie.
- `android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java` — plan + profiel-signatuur in de route-gate.
- `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java` — passport + per-klim streeftijden.
- `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java` — passport binden, reload op `onResume`.
- `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java` — streeftijd in klim-rij.
- `android/app/src/main/res/layout/activity_route_detail.xml` — passport-blok.
- `android/app/src/test/java/nl/paree/climbpro/connectiq/WatchRequestHandlerTest.java` — constructor-aanroepen bijwerken.

**Wijzigen (protocol):**
- `protocol/schema.json` — `targetSeconds`/`tsec` op `Climb`.
- `protocol/schema.md` — changelog + byte-budget.
- `protocol/examples/route_mode_full.json` — referentie-`tsec` op eerste klim.

**Wijzigen (Monkey C):**
- `garmin/source/ClimbData.mc` — `segTargetSec` array, parse + helper `targetSecondsAt`, klimstart-timer, samenvatting-state.
- `garmin/source/CommListener.mc` — `tsec` parsen.
- `garmin/source/ClimbProView.mc` — ghost-delta renderen + samenvatting na de top.

**Wijzigen (docs):**
- `Documentation/ARCHITECTURE.md`, `README.md`.

---

## Task 1: Protocol — `tsec`-veld toevoegen

**Files:**
- Modify: `protocol/schema.json` (definitions → Climb → properties)
- Modify: `protocol/schema.md`
- Modify: `protocol/examples/route_mode_full.json`

- [ ] **Step 1: Voeg `targetSeconds` toe aan de Climb-definitie in `protocol/schema.json`**

In `definitions.Climb.properties`, na het `segments`-blok (vóór de afsluitende `}` van `properties`), voeg toe:

```json
        "segments": {
          "description": "8%-of-climb-length segments covering the full climb. Count is typically 12 or 13.",
          "type": "array",
          "minItems": 1,
          "maxItems": 16,
          "items": { "$ref": "#/definitions/Segment" }
        },
        "targetSeconds": {
          "description": "Optional per-segment target time in whole seconds, parallel to 'segments' (same length). Precomputed phone-side from the rider profile (route-mode only). Wire encoding: packed int array 'tsec'. Omitted when no pacing plan is available.",
          "type": "array",
          "minItems": 1,
          "maxItems": 16,
          "items": { "type": "integer", "minimum": 0 }
        }
```

(Let op: de comma achter het `segments`-blok die er nu niet staat, moet er nu wél komen omdat `targetSeconds` erna komt.)

- [ ] **Step 2: Documenteer de wijziging in `protocol/schema.md`**

Voeg een changelog-regel toe (bovenaan de changelog-sectie; als die niet bestaat, voeg een `## Changelog`-kop toe onderaan):

```markdown
- 2026-06-13: Added optional `tsec` (targetSeconds) packed int array on Climb —
  per-segment target time in whole seconds, parallel to `segs`. Route-mode only,
  omitted when no pacing plan. ~13 ints per climb; additive, no version bump.
```

- [ ] **Step 3: Voeg een referentie-`tsec` toe aan het eerste climb-object in `protocol/examples/route_mode_full.json`**

In de eerste climb ("Cauberg"), direct ná de `segments`-array (voeg een comma toe achter de `]` van `segments`), voeg toe — 13 waarden, één per segment:

```json
      "targetSeconds": [60, 58, 56, 55, 53, 50, 56, 60, 62, 64, 66, 62, 40]
```

- [ ] **Step 4: Valideer dat de JSON nog geldig is**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.PayloadCodecTest`
Expected: PASS (de codec-tests parsen generieke JSON; ze breken niet door het extra veld). Op Windows: `cd android; .\gradlew.bat :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.PayloadCodecTest`.

- [ ] **Step 5: Commit**

```bash
git add protocol/schema.json protocol/schema.md protocol/examples/route_mode_full.json
git commit -m "feat(protocol): optional tsec per-segment target time on Climb"
```

---

## Task 2: `RiderProfile.signature()`

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/power/RiderProfile.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/RiderProfileSignatureTest.java`

- [ ] **Step 1: Schrijf de falende test**

Create `android/app/src/test/java/nl/paree/climbpro/domain/RiderProfileSignatureTest.java`:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.RiderProfile;
import org.junit.Test;
import static org.junit.Assert.*;

public class RiderProfileSignatureTest {

    @Test
    public void identicalProfilesShareSignature() {
        RiderProfile a = new RiderProfile(250, 72.0, 8.0, 65);
        RiderProfile b = new RiderProfile(250, 72.0, 8.0, 65);
        assertEquals(a.signature(), b.signature());
    }

    @Test
    public void differentFieldsChangeSignature() {
        RiderProfile base = new RiderProfile(250, 72.0, 8.0, 65);
        assertNotEquals(base.signature(), new RiderProfile(260, 72.0, 8.0, 65).signature());
        assertNotEquals(base.signature(), new RiderProfile(250, 73.0, 8.0, 65).signature());
        assertNotEquals(base.signature(), new RiderProfile(250, 72.0, 9.0, 65).signature());
        assertNotEquals(base.signature(), new RiderProfile(250, 72.0, 8.0, 70).signature());
    }
}
```

- [ ] **Step 2: Draai de test, verifieer dat hij faalt**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.domain.RiderProfileSignatureTest`
Expected: FAIL met "cannot find symbol: method signature()".

- [ ] **Step 3: Implementeer `signature()`**

In `RiderProfile.java`, voeg toe direct ná `rideIntensityFraction()`:

```java
    /** Stable signature of the profile fields that affect a pacing estimate. */
    public String signature() {
        return ftpWatts + ":" + riderWeightKg + ":" + bikeWeightKg + ":" + rideIntensityPct;
    }
```

- [ ] **Step 4: Draai de test, verifieer dat hij slaagt**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.domain.RiderProfileSignatureTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/RiderProfile.java android/app/src/test/java/nl/paree/climbpro/domain/RiderProfileSignatureTest.java
git commit -m "feat(power): RiderProfile.signature() for change detection"
```

---

## Task 3: `RoutePacingPlanner`

Pure service: gegeven een `StoredRoute` + `RiderProfile`, lever per klim een `int[]` streeftijden (parallel aan de segmenten), of `null` per klim die niet te schatten is. Volgt exact dezelfde logica als `ClimbDetailViewModel.computeEstimate` (fatigue-aware met fallback), maar voor álle klimmen.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/service/RoutePacingPlanner.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/RoutePacingPlannerTest.java`

- [ ] **Step 1: Schrijf de falende test**

Create `android/app/src/test/java/nl/paree/climbpro/service/RoutePacingPlannerTest.java`:

```java
package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class RoutePacingPlannerTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 72.0, 8.0, 65);

    /** A route with distance/elevation arrays and one climb (so the fatigue-aware path runs). */
    private static StoredRoute routeWithOneClimb() {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        // 3 km route: flat to 1km, climb 1km @ ~8%, flat to 3km.
        r.distances  = new double[]{0, 1000, 2000, 3000};
        r.elevations = new double[]{0, 0, 80, 80};
        r.climbs = new ArrayList<>();
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 2000;
        c.length = 1000;
        c.elevationGain = 80;
        c.avgGradient = 0.08;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 125;
            s.elevationGain = 10;
            s.gradient = 0.08;
            s.colorIndex = 4;
            s.surfaceType = SurfaceType.ASPHALT;
            c.segments.add(s);
        }
        r.climbs.add(c);
        return r;
    }

    @Test
    public void incompleteProfileReturnsNull() {
        assertNull(RoutePacingPlanner.plan(routeWithOneClimb(), new RiderProfile(0, 72, 8)));
    }

    @Test
    public void planHasOneEntryPerClimbWithSegmentLengthArrays() {
        int[][] plan = RoutePacingPlanner.plan(routeWithOneClimb(), RIDER);
        assertNotNull(plan);
        assertEquals(1, plan.length);
        assertNotNull(plan[0]);
        assertEquals("one target per segment", 8, plan[0].length);
        for (int sec : plan[0]) assertTrue("each segment time positive", sec > 0);
    }

    @Test
    public void fallsBackToPerClimbWhenRouteHasNoElevationArrays() {
        StoredRoute r = routeWithOneClimb();
        r.distances = null;   // forces RouteEffortProfileBuilder.build() to return null
        r.elevations = null;
        int[][] plan = RoutePacingPlanner.plan(r, RIDER);
        assertNotNull(plan);
        assertEquals(1, plan.length);
        assertNotNull("fallback per-climb estimate fills the entry", plan[0]);
        assertEquals(8, plan[0].length);
    }

    @Test
    public void nullOrEmptyClimbsReturnsEmptyPlan() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        int[][] plan = RoutePacingPlanner.plan(r, RIDER);
        assertNotNull(plan);
        assertEquals(0, plan.length);
    }

    @Test
    public void climbWithNullSegmentsGetsNullEntry() {
        StoredRoute r = routeWithOneClimb();
        r.climbs.get(0).segments = null;
        int[][] plan = RoutePacingPlanner.plan(r, RIDER);
        assertNotNull(plan);
        assertEquals(1, plan.length);
        assertNull(plan[0]);
    }
}
```

- [ ] **Step 2: Draai de test, verifieer dat hij faalt**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.service.RoutePacingPlannerTest`
Expected: FAIL met "cannot find symbol: class RoutePacingPlanner".

- [ ] **Step 3: Implementeer `RoutePacingPlanner`**

Create `android/app/src/main/java/nl/paree/climbpro/service/RoutePacingPlanner.java`:

```java
package nl.paree.climbpro.service;

import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;

/**
 * Precomputes a per-segment target time (seconds) for every climb on a route,
 * mirroring {@code ClimbDetailViewModel}'s fatigue-aware-with-fallback logic but
 * for all climbs at once. Pure and unit-testable.
 *
 * Returns null when the rider profile is incomplete. Otherwise returns an array
 * indexed by climb position; each entry is the per-segment seconds array, or null
 * when that climb cannot be estimated (e.g. it has no segments).
 */
public final class RoutePacingPlanner {

    private RoutePacingPlanner() {}

    public static int[][] plan(StoredRoute route, RiderProfile profile) {
        if (profile == null || !profile.isComplete() || route == null) {
            return null;
        }
        List<StoredClimb> climbs = route.climbs;
        if (climbs == null || climbs.isEmpty()) {
            return new int[0][];
        }

        // Preferred path: whole-route fatigue-aware tiles (null when arrays are missing).
        List<RouteTile> tiles = RouteEffortProfileBuilder.build(route);

        int[][] result = new int[climbs.size()][];
        for (int ci = 0; ci < climbs.size(); ci++) {
            ClimbTimeEstimate est = null;
            if (tiles != null) {
                est = RouteAwareClimbEstimator.estimate(tiles, ci, profile);
            }
            if (est == null) {
                est = perClimbFallback(climbs.get(ci), profile);
            }
            result[ci] = est != null ? est.segmentSeconds : null;
        }
        return result;
    }

    private static ClimbTimeEstimate perClimbFallback(StoredClimb c, RiderProfile profile) {
        if (c.segments == null || c.segments.isEmpty()) {
            return null;
        }
        List<StoredSegment> segs = c.segments;
        int[] dist = new int[segs.size()];
        double[] grad = new double[segs.size()];
        int[] surface = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            dist[i] = segs.get(i).distance;
            grad[i] = segs.get(i).gradient;
            surface[i] = segs.get(i).surfaceType;
        }
        return ClimbTimeEstimator.estimate(dist, grad, surface, profile);
    }
}
```

- [ ] **Step 4: Draai de test, verifieer dat hij slaagt**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.service.RoutePacingPlannerTest`
Expected: PASS (alle 5 tests)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/RoutePacingPlanner.java android/app/src/test/java/nl/paree/climbpro/service/RoutePacingPlannerTest.java
git commit -m "feat(service): RoutePacingPlanner computes per-climb target times"
```

---

## Task 4: `ClimbPayloadBuilder` — `tsec` serialiseren

Voeg overloads toe die per-klim streeftijden meekrijgen en als `tsec` emitten. Bestaande signatures blijven werken (delegeren met `null`), zodat bestaande tests/aanroepers ongemoeid blijven.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTsecTest.java`

- [ ] **Step 1: Schrijf de falende test**

Create `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTsecTest.java`:

```java
package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class ClimbPayloadBuilderTsecTest {

    private static StoredRoute routeWith4SegClimb() {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name = "R1";
        route.climbs = new ArrayList<>();
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3000;
        c.length = 2000;
        c.elevationGain = 80;
        c.avgGradient = 0.04;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 500;
            s.elevationGain = 20;
            s.gradient = 0.04;
            s.colorIndex = 2;
            c.segments.add(s);
        }
        route.climbs.add(c);
        return route;
    }

    @Test
    public void tsecOmittedWhenPlanNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), null))
                .get("climbs").get(0);
        assertFalse("tsec absent when no plan", climb.has("tsec"));
    }

    @Test
    public void tsecEmittedAsFlatArrayWhenPlanPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { {61, 62, 63, 40} };
        JsonNode tsec = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), plan))
                .get("climbs").get(0).get("tsec");
        assertNotNull("tsec present when plan provided", tsec);
        assertTrue(tsec.isArray());
        assertEquals(4, tsec.size());
        assertEquals(61, tsec.get(0).asInt());
        assertEquals(40, tsec.get(3).asInt());
    }

    @Test
    public void tsecOmittedWhenLengthMismatchesSegments() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { {61, 62} }; // climb has 4 segments
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), plan))
                .get("climbs").get(0);
        assertFalse("tsec absent on length mismatch", climb.has("tsec"));
    }

    @Test
    public void tsecOmittedWhenClimbEntryNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { null };
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), plan))
                .get("climbs").get(0);
        assertFalse(climb.has("tsec"));
    }

    @Test
    public void singleClimbPayloadEmitsTsec() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { {61, 62, 63, 40} };
        JsonNode tsec = mapper.readTree(b.buildSingleClimbPayload(routeWith4SegClimb(), 0, plan))
                .get("climbs").get(0).get("tsec");
        assertNotNull(tsec);
        assertEquals(4, tsec.size());
    }

    @Test
    public void legacyBuildRoutePayloadStillOmitsTsec() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb()))
                .get("climbs").get(0);
        assertFalse(climb.has("tsec"));
    }
}
```

- [ ] **Step 2: Draai de test, verifieer dat hij faalt**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.service.ClimbPayloadBuilderTsecTest`
Expected: FAIL — de overloads `buildRoutePayload(route, int[][])` / `buildSingleClimbPayload(route, idx, int[][])` bestaan nog niet.

- [ ] **Step 3: Implementeer de `tsec`-overloads in `ClimbPayloadBuilder`**

Vervang de bestaande `buildRoutePayload(StoredRoute)`-methode door deze twee (oude delegeert):

```java
    public byte[] buildRoutePayload(StoredRoute route) throws IOException {
        return buildRoutePayload(route, null);
    }

    /**
     * @param targetSeconds per-climb per-segment target seconds (indexed by climb
     *                      position); null, or a null/short entry, omits 'tsec' for
     *                      that climb.
     */
    public byte[] buildRoutePayload(StoredRoute route, int[][] targetSeconds) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>();
        if (route.climbs != null) {
            for (int i = 0; i < route.climbs.size(); i++) {
                int[] tsec = (targetSeconds != null && i < targetSeconds.length)
                        ? targetSeconds[i] : null;
                climbs.add(buildRouteClimb(route.climbs.get(i), tsec));
            }
        }
        payload.put("climbs", climbs);
        return mapper.writeValueAsBytes(payload);
    }
```

Vervang `buildSingleClimbPayload(StoredRoute, int)` door deze twee:

```java
    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex) throws IOException {
        return buildSingleClimbPayload(route, climbIndex, null);
    }

    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex,
                                          int[][] targetSeconds) throws IOException {
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            throw new IllegalArgumentException("climbIndex out of range: " + climbIndex);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>(1);
        int[] tsec = (targetSeconds != null && climbIndex < targetSeconds.length)
                ? targetSeconds[climbIndex] : null;
        climbs.add(buildRouteClimb(route.climbs.get(climbIndex), tsec));
        payload.put("climbs", climbs);
        return mapper.writeValueAsBytes(payload);
    }
```

Vervang de bestaande `buildRouteClimb(StoredClimb)` door:

```java
    private Map<String, Object> buildRouteClimb(StoredClimb sc, int[] targetSeconds) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sd", sc.startDistance);
        c.put("ed", sc.endDistance);
        addCommonClimbFields(c, sc);
        addTargetSeconds(c, sc, targetSeconds);
        return c;
    }
```

Voeg deze helper toe (bijv. direct ná `addCommonClimbFields`):

```java
    /** Emits 'tsec' only when the array is non-null and exactly one value per segment. */
    private static void addTargetSeconds(Map<String, Object> c, StoredClimb sc, int[] targetSeconds) {
        if (targetSeconds == null || sc.segments == null
                || targetSeconds.length != sc.segments.size()) {
            return;
        }
        c.put("tsec", targetSeconds);
    }
```

(`buildRadiusClimb` blijft `addCommonClimbFields` aanroepen zonder `tsec` — radius-modus krijgt geen pacing.)

- [ ] **Step 4: Draai de nieuwe + bestaande payload-tests, verifieer groen**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "nl.paree.climbpro.service.ClimbPayloadBuilder*"`
Expected: PASS (zowel `ClimbPayloadBuilderTest`, `ClimbPayloadBuilderActivePayloadTest` als de nieuwe `ClimbPayloadBuilderTsecTest`).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTsecTest.java
git commit -m "feat(service): serialise tsec target seconds in route payloads"
```

---

## Task 5: `WatchRequestHandler` — profiel laden en plan meegeven

De dominante weg naar de datafield. Voeg een `RiderProfileRepository` toe, bereken het plan en geef het mee aan de payload-builds voor `LOAD_ROUTE`, `SET_ACTIVE_ROUTE` en `SET_ACTIVE_CLIMB`.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java`

> Geen test-wijziging nodig: we houden een 2-argument convenience-constructor (delegeert met `riderRepo = null`), zodat de bestaande `WatchRequestHandlerTest` blijft compileren en als regressietest dient. We mocken `RiderProfileRepository` bewust niet — het is een `final` klasse (Mockito kan die zonder inline mock-maker niet mocken). De pacing-logica zelf is al gedekt door `RoutePacingPlannerTest` en `ClimbPayloadBuilderTsecTest`.

- [ ] **Step 1: Breid `WatchRequestHandler` uit met het profiel en het plan**

Voeg imports toe:

```java
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.RoutePacingPlanner;
```

Voeg een veld + nieuwe constructor toe en houd een gemaks-constructor voor bestaande aanroepers (geeft `null` repo):

```java
    private final RouteRepository routeRepo;
    private final ConnectIqClient connectIqClient;
    private final ObjectMapper    mapper;
    private final RiderProfileRepository riderRepo;

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient) {
        this(routeRepo, connectIqClient, null);
    }

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient,
                               RiderProfileRepository riderRepo) {
        this.routeRepo       = routeRepo;
        this.connectIqClient = connectIqClient;
        this.mapper          = new ObjectMapper();
        this.riderRepo       = riderRepo;
    }
```

Voeg een private helper toe die het plan berekent (null-safe als er geen profiel-repo is):

```java
    /** Per-climb target seconds for the route, or null when no profile repo / incomplete profile. */
    private int[][] pacingPlan(StoredRoute route) {
        if (riderRepo == null) return null;
        RiderProfile profile = riderRepo.load();
        return RoutePacingPlanner.plan(route, profile);
    }
```

Pas de drie build-aanroepen aan:

In `handleLoadRoute`:
```java
            StoredRoute route   = routeRepo.loadRoute(routeId);
            byte[]      payload = new ClimbPayloadBuilder(mapper)
                    .buildRoutePayload(route, pacingPlan(route));
```

In `handleSetActiveRoute`, vervang de datafield-build:
```java
            StoredRoute route = routeRepo.loadRoute(routeId);
            ClimbPayloadBuilder builder = new ClimbPayloadBuilder(mapper);
            boolean ok = connectIqClient.sendPayloadToDatafield(
                    builder.buildRoutePayload(route, pacingPlan(route)));
```

In `handleSetActiveClimb`, vervang de build:
```java
            StoredRoute route = routeRepo.loadRoute(routeId);
            byte[] payload = new ClimbPayloadBuilder(mapper)
                    .buildSingleClimbPayload(route, climbIndex, pacingPlan(route));
```

- [ ] **Step 2: Werk de productie-constructie bij in `ClimbProApplication`**

In `ClimbProApplication.java`, vervang regel 26 (`ciqClient.setWatchRequestHandler(new WatchRequestHandler(routeRepo, ciqClient));`) door:

```java
        ciqClient.setWatchRequestHandler(new WatchRequestHandler(
                routeRepo, ciqClient,
                new nl.paree.climbpro.data.rider.RiderProfileRepository(this)));
```

- [ ] **Step 3: Draai de bestaande test als regressie + compileer**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.WatchRequestHandlerTest`
Expected: PASS (ongewijzigd gedrag via de 2-arg constructor). Verifieer ook: `cd android && ./gradlew :app:compileDebugJavaWithJavac`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java
git commit -m "feat(connectiq): include pacing plan in watch-requested payloads"
```

---

## Task 6: `RouteSyncWorker` — plan + profiel-signatuur in de route-gate

De achtergrond-sync moet (a) `tsec` meesturen en (b) opnieuw syncen als het profiel wijzigde, ook al bleef de route gelijk. We bewaren in het bestaande `lastSyncedHash`-veld een gecombineerde signatuur `sourceHash|profileSig` — geen nieuw opslagveld.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java`

> Geen aparte unit-test: `RouteSyncWorker` is WorkManager-glue rond de al-geteste `SyncOrchestrator`, `RoutePacingPlanner` en `ClimbPayloadBuilder`. Verificatie via de bestaande `SyncOrchestratorTest` (gedrag ongewijzigd) plus de build.

- [ ] **Step 1: Laad het profiel in `doWork` en geef het door aan de payload-job**

In `RouteSyncWorker.doWork()`, ná de regel die `prefs` aanmaakt, voeg toe:

```java
        nl.paree.climbpro.data.rider.RiderProfileRepository riderRepo =
                new nl.paree.climbpro.data.rider.RiderProfileRepository(ctx);
        nl.paree.climbpro.domain.power.RiderProfile profile = riderRepo.load();
```

Wijzig de aanroep `buildPayloadJob(prefs, routeRepo, syncStateRepo, payloadBuilder)` naar:

```java
        SyncOrchestrator.PayloadJob job = buildPayloadJob(
                prefs, routeRepo, syncStateRepo, payloadBuilder, profile);
```

- [ ] **Step 2: Werk de `buildPayloadJob`-signatuur en de route-branch bij**

Wijzig de methodesignatuur naar:

```java
    private SyncOrchestrator.PayloadJob buildPayloadJob(
            SharedPreferences prefs, RouteRepository routeRepo,
            SyncStateRepository syncStateRepo, ClimbPayloadBuilder payloadBuilder,
            nl.paree.climbpro.domain.power.RiderProfile profile) {
```

Vervang in de **route-branch** (de tweede `return new SyncOrchestrator.PayloadJob() {...}`) de `build()`- en `onSent()`-methodes door:

```java
            @Override public byte[] build() throws IOException {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId == null) {
                    Log.i(TAG, "No active route selected — nothing to send");
                    return null;
                }
                SyncState state = syncStateRepo.get(routeId);
                StoredRoute route = routeRepo.loadRoute(routeId);
                String wantHash = route.sourceHash + "|" + profile.signature();
                if (SyncState.Status.SYNCED.equals(state.status)
                        && wantHash.equals(state.lastSyncedHash)) {
                    Log.i(TAG, "Route " + routeId + " unchanged (incl. profile), no re-sync needed");
                    return null;
                }
                int[][] plan = nl.paree.climbpro.service.RoutePacingPlanner.plan(route, profile);
                byte[] payload = payloadBuilder.buildRoutePayload(route, plan);
                if (payload.length > PayloadBudget.MAX_BYTES) {
                    Log.e(TAG, "Payload exceeds budget: " + payload.length + " bytes — skipping send");
                    return null;
                }
                return payload;
            }
            @Override public void onSent() throws IOException {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId != null) {
                    StoredRoute route = routeRepo.loadRoute(routeId);
                    syncStateRepo.markSynced(routeId, route.sourceHash + "|" + profile.signature());
                }
            }
```

- [ ] **Step 3: Compileer en draai de sync-tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.service.SyncOrchestratorTest`
Expected: PASS (orchestrator-gedrag ongewijzigd). Verifieer ook dat de module compileert: `cd android && ./gradlew :app:compileDebugJavaWithJavac`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java
git commit -m "feat(sync): send tsec in background sync; re-sync on profile change"
```

---

## Task 7: `RoutePassport` — overzicht-waardenobject

Pure samenvatting voor het routedetailscherm.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RoutePassport.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/ui/routes/RoutePassportTest.java`

- [ ] **Step 1: Schrijf de falende test**

Create `android/app/src/test/java/nl/paree/climbpro/ui/routes/RoutePassportTest.java`:

```java
package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class RoutePassportTest {

    private static StoredClimb climb(String name, int len, int elev, double grad) {
        StoredClimb c = new StoredClimb();
        c.name = name;
        c.length = len;
        c.elevationGain = elev;
        c.avgGradient = grad;
        return c;
    }

    private static StoredRoute twoClimbRoute() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        r.climbs.add(climb("Cauberg", 1200, 80, 0.067));
        r.climbs.add(climb("Keutenberg", 1600, 150, 0.094));
        return r;
    }

    @Test
    public void totalsAreSummed() {
        RoutePassport p = RoutePassport.from(twoClimbRoute(), null);
        assertEquals(2, p.climbCount);
        assertEquals(230, p.totalElevationGain);
    }

    @Test
    public void hardestClimbIsSteepest() {
        RoutePassport p = RoutePassport.from(twoClimbRoute(), null);
        assertEquals("Keutenberg", p.hardestClimbName);
    }

    @Test
    public void totalTimeIsMinusOneWithoutPlan() {
        RoutePassport p = RoutePassport.from(twoClimbRoute(), null);
        assertEquals(-1, p.totalEstimatedSeconds);
    }

    @Test
    public void totalTimeSumsAllSegmentSecondsWhenPlanComplete() {
        int[][] plan = { {30, 30}, {40, 40, 40} }; // 60 + 120 = 180
        RoutePassport p = RoutePassport.from(twoClimbRoute(), plan);
        assertEquals(180, p.totalEstimatedSeconds);
    }

    @Test
    public void totalTimeIsMinusOneWhenAnyClimbEntryMissing() {
        int[][] plan = { {30, 30}, null };
        RoutePassport p = RoutePassport.from(twoClimbRoute(), plan);
        assertEquals(-1, p.totalEstimatedSeconds);
    }

    @Test
    public void emptyRouteIsSafe() {
        StoredRoute r = new StoredRoute();
        RoutePassport p = RoutePassport.from(r, null);
        assertEquals(0, p.climbCount);
        assertEquals(0, p.totalElevationGain);
        assertNull(p.hardestClimbName);
        assertEquals(-1, p.totalEstimatedSeconds);
    }
}
```

- [ ] **Step 2: Draai de test, verifieer dat hij faalt**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.ui.routes.RoutePassportTest`
Expected: FAIL met "cannot find symbol: class RoutePassport".

- [ ] **Step 3: Implementeer `RoutePassport`**

Create `android/app/src/main/java/nl/paree/climbpro/ui/routes/RoutePassport.java`:

```java
package nl.paree.climbpro.ui.routes;

import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Read-only summary of a route for the detail screen's pacing passport.
 * totalEstimatedSeconds is -1 when no complete pacing plan is available.
 */
public final class RoutePassport {

    public final int climbCount;
    public final int totalElevationGain;
    public final String hardestClimbName;       // null when no climbs
    public final double hardestClimbGradient;   // fraction (0.094 = 9.4%)
    public final int totalEstimatedSeconds;     // -1 when incomplete

    public RoutePassport(int climbCount, int totalElevationGain, String hardestClimbName,
                         double hardestClimbGradient, int totalEstimatedSeconds) {
        this.climbCount = climbCount;
        this.totalElevationGain = totalElevationGain;
        this.hardestClimbName = hardestClimbName;
        this.hardestClimbGradient = hardestClimbGradient;
        this.totalEstimatedSeconds = totalEstimatedSeconds;
    }

    /** @param plan per-climb per-segment seconds (RoutePacingPlanner output), or null. */
    public static RoutePassport from(StoredRoute route, int[][] plan) {
        List<StoredClimb> climbs = route != null ? route.climbs : null;
        if (climbs == null || climbs.isEmpty()) {
            return new RoutePassport(0, 0, null, 0.0, -1);
        }
        int totalElev = 0;
        String hardestName = null;
        double hardestGrad = -1.0;
        for (StoredClimb c : climbs) {
            totalElev += c.elevationGain;
            if (c.avgGradient > hardestGrad) {
                hardestGrad = c.avgGradient;
                hardestName = c.userDisplayName != null ? c.userDisplayName : c.name;
            }
        }

        int totalSeconds = totalSeconds(climbs.size(), plan);
        return new RoutePassport(climbs.size(), totalElev, hardestName,
                Math.max(hardestGrad, 0.0), totalSeconds);
    }

    /** Sum of all segment seconds; -1 if the plan is null or any climb entry is missing. */
    private static int totalSeconds(int climbCount, int[][] plan) {
        if (plan == null || plan.length < climbCount) {
            return -1;
        }
        int total = 0;
        for (int ci = 0; ci < climbCount; ci++) {
            if (plan[ci] == null) {
                return -1;
            }
            for (int sec : plan[ci]) total += sec;
        }
        return total;
    }
}
```

- [ ] **Step 4: Draai de test, verifieer dat hij slaagt**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.ui.routes.RoutePassportTest`
Expected: PASS (alle 6 tests)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RoutePassport.java android/app/src/test/java/nl/paree/climbpro/ui/routes/RoutePassportTest.java
git commit -m "feat(ui): RoutePassport summary value object"
```

---

## Task 8: Passport in de ViewModel + per-klim streeftijden

`RouteDetailViewModel` berekent het plan bij `loadRoute` en exposeert het paspoort + een per-klim totaaltijd-array.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java`

> Geen aparte unit-test: de pure logica zit al getest in `RoutePassportTest` en `RoutePacingPlannerTest`; de ViewModel is dunne glue (LiveData + executor + `RiderProfileRepository`, dat een Android `Context` vereist en daarom niet in een gewone JVM-unit-test draait). Verificatie via build + handmatige UI-check in Task 9.

- [ ] **Step 1: Voeg velden, LiveData en imports toe**

Voeg imports toe:

```java
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.RoutePacingPlanner;
```

Voeg naast de bestaande velden toe:

```java
    private final RiderProfileRepository riderRepo;
    private final MutableLiveData<RoutePassport> passport = new MutableLiveData<>();
    private final MutableLiveData<int[]> climbTargetSeconds = new MutableLiveData<>();
```

Initialiseer `riderRepo` in de constructor (naast `routeRepo = new RouteRepository(app);`):

```java
        riderRepo = new RiderProfileRepository(app);
```

Voeg getters toe (naast de bestaande):

```java
    public LiveData<RoutePassport> passport()           { return passport; }
    public LiveData<int[]>         climbTargetSeconds()  { return climbTargetSeconds; }
```

- [ ] **Step 2: Bereken plan + paspoort in `loadRoute`**

In `loadRoute(...)`, binnen de `try` ná `routeItems.postValue(buildRouteItems(r));`, voeg toe:

```java
                RiderProfile profile = riderRepo.load();
                int[][] plan = RoutePacingPlanner.plan(r, profile);
                passport.postValue(RoutePassport.from(r, plan));
                climbTargetSeconds.postValue(perClimbTotals(r, plan));
```

Voeg de private helper toe (boven `onCleared`):

```java
    /** Per-climb total target seconds (index = climb position); -1 when that climb has no plan. */
    private static int[] perClimbTotals(StoredRoute r, int[][] plan) {
        int n = r.climbs != null ? r.climbs.size() : 0;
        int[] totals = new int[n];
        for (int ci = 0; ci < n; ci++) {
            int[] segs = (plan != null && ci < plan.length) ? plan[ci] : null;
            if (segs == null) {
                totals[ci] = -1;
            } else {
                int sum = 0;
                for (int s : segs) sum += s;
                totals[ci] = sum;
            }
        }
        return totals;
    }
```

- [ ] **Step 3: Compileer**

Run: `cd android && ./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java
git commit -m "feat(ui): compute pacing passport + per-climb target times in VM"
```

---

## Task 9: Passport-UI op het routedetailscherm

Toon het paspoort-blok en de per-klim streeftijd; herlaad op `onResume` zodat een gewijzigd profiel wordt opgepikt.

**Files:**
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java`

- [ ] **Step 1: Voeg het paspoort-blok toe aan de layout**

In `activity_route_detail.xml`, direct ná de `MapView` (na regel met `</org.osmdroid...` afsluiting van de MapView, dwz na de `MapView`-tag) en vóór de "Notes"-`TextView`, voeg toe:

```xml
            <TextView
                android:id="@+id/passport_title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="4dp"
                android:text="Pacing-paspoort"
                android:textStyle="bold"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/passport_summary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="12dp"
                android:textSize="14sp" />
```

- [ ] **Step 2: Bind het paspoort + reload op resume in de Activity**

In `RouteDetailActivity.onCreate`, ná het bestaande `viewModel.route().observe(...)`-blok, voeg toe:

```java
        viewModel.passport().observe(this, this::renderPassport);
        viewModel.climbTargetSeconds().observe(this, secs -> adapter.setClimbTargetSeconds(secs));
```

Voeg de render-helper toe (bijv. ná `drawRoute`):

```java
    private void renderPassport(RoutePassport p) {
        if (p == null) { binding.passportSummary.setText(""); return; }
        StringBuilder sb = new StringBuilder();
        sb.append(p.climbCount).append(" klimmen · ")
          .append(p.totalElevationGain).append(" hm");
        if (p.hardestClimbName != null) {
            sb.append("\nZwaarste: ").append(p.hardestClimbName)
              .append(String.format(java.util.Locale.US, " (%.1f%%)", p.hardestClimbGradient * 100));
        }
        if (p.totalEstimatedSeconds >= 0) {
            sb.append("\nGeschatte tijd: ")
              .append(nl.paree.climbpro.domain.power.DurationFormat.format(p.totalEstimatedSeconds));
        } else {
            sb.append("\nGeschatte tijd: vul je profiel in (Instellingen)");
        }
        binding.passportSummary.setText(sb.toString());
    }
```

Voeg de import toe:

```java
import nl.paree.climbpro.ui.routes.RoutePassport;
```

(Als de Activity al in package `nl.paree.climbpro.ui.routes` zit, is de import overbodig — `RoutePassport` staat in dezelfde package. Laat de import dan weg.)

Werk `onResume` bij zodat de route (en dus het plan) opnieuw wordt geladen:

```java
    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
        if (routeId != null) viewModel.loadRoute(routeId);
    }
```

- [ ] **Step 3: Toon de streeftijd per klim in de adapter**

In `RouteDetailAdapter.java`, voeg een veld + setter toe (naast de bestaande velden):

```java
    private int[] climbTargetSeconds; // index = climb position; -1 = none

    public void setClimbTargetSeconds(int[] secs) {
        this.climbTargetSeconds = secs;
        notifyDataSetChanged();
    }
```

In `bindClimb`, ná het zetten van `h.statsView`, voeg toe:

```java
        if (climbTargetSeconds != null && climbIndex < climbTargetSeconds.length
                && climbTargetSeconds[climbIndex] >= 0) {
            h.statsView.setText(h.statsView.getText() + "  ·  ⏱ "
                    + nl.paree.climbpro.domain.power.DurationFormat.format(
                            climbTargetSeconds[climbIndex]));
        }
```

- [ ] **Step 4: Bouw de debug-APK en controleer dat hij compileert**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Handmatige verificatie**

Open een route met klimmen in de app met een ingevuld rijderprofiel. Verwacht: paspoort-blok toont aantal klimmen, hoogtemeters, zwaarste klim en geschatte tijd; elke klim-rij toont een ⏱-streeftijd. Wis (tijdelijk) FTP in Instellingen en heropen het scherm: paspoort toont "vul je profiel in" en de ⏱ verdwijnt.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/res/layout/activity_route_detail.xml android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java
git commit -m "feat(ui): pacing passport block and per-climb target time on route detail"
```

---

## Task 10: Watch — `tsec` parsen + streeftijd-op-positie helper

**Files:**
- Modify: `garmin/source/ClimbData.mc`
- Modify: `garmin/source/CommListener.mc`

- [ ] **Step 1: Voeg de `segTargetSec`-array + klimstart-state toe aan `ClimbData`**

In `ClimbData.mc`, bij de segment-level arrays (na `var segSurf;`), voeg toe:

```monkeyc
    var segTargetSec;     // per-segment target seconds (parallel to seg arrays); 0 = none
    var hasTargets;       // bool per climb: true when tsec was provided
```

In `initialize()`, naast de andere segment-arrays (`segSurf = new [MAX_CLIMBS];`), voeg toe:

```monkeyc
        segTargetSec = new [MAX_CLIMBS];
        hasTargets = new [MAX_CLIMBS];
```

In de eerste `for (var i = 0; i < MAX_CLIMBS; i++)`-lus, naast `segSurf[i] = new [MAX_SEGMENTS];`, voeg toe:

```monkeyc
            segTargetSec[i] = new [MAX_SEGMENTS];
            hasTargets[i] = false;
```

En in de binnenste `for (var s ...)`-lus, naast `segSurf[i][s] = 5;`, voeg toe:

```monkeyc
                segTargetSec[i][s] = 0;
```

Voeg bij de runtime-state (na `var lastElapsedDistance = 0;`) toe:

```monkeyc
    var climbStartTimerMs = -1;    // timerTime (ms) when the active climb was entered; -1 = not set
```

- [ ] **Step 2: Voeg de helper `targetSecondsAt` toe aan `ClimbData`**

Voeg deze functie toe (bijv. ná `updateCurrentSegment`). Hij geeft de cumulatieve streeftijd tot de huidige positie binnen de actieve klim, met lineaire interpolatie binnen het lopende segment. Geeft -1 als er geen targets zijn.

```monkeyc
    // Cumulative target seconds at the current progressInClimb for the active climb,
    // linearly interpolated within the running segment. Returns -1 when no targets.
    function targetSecondsAt() {
        var ci = activeClimbIndex;
        if (ci < 0 || !hasTargets[ci]) { return -1; }
        var cum = 0;            // cumulative target seconds for completed segments
        var cumDist = 0;        // cumulative distance at end of completed segments
        for (var s = 0; s < segCount[ci]; s++) {
            var segLen = segDist[ci][s];
            var segEnd = cumDist + segLen;
            if (progressInClimb <= segEnd || s == segCount[ci] - 1) {
                var into = progressInClimb - cumDist;
                if (into < 0) { into = 0; }
                if (into > segLen) { into = segLen; }
                var frac = (segLen > 0) ? (into.toFloat() / segLen.toFloat()) : 0.0;
                return cum + (segTargetSec[ci][s] * frac);
            }
            cum += segTargetSec[ci][s];
            cumDist = segEnd;
        }
        return cum;
    }
```

- [ ] **Step 3: Parse `tsec` in `CommListener.parseClimb`**

In `CommListener.mc`, in `parseClimb`, ná het `surf`-blok (vóór de afsluitende `}` van `parseClimb`), voeg toe:

```monkeyc
        // Optional tsec array: [targetSeconds, ...] one int per segment (parallel to segs)
        var tsec = climbDict.get("tsec");
        if (tsec != null && tsec instanceof Toybox.Lang.Array && tsec.size() >= data.segCount[idx]
                && data.segCount[idx] > 0) {
            data.hasTargets[idx] = true;
            for (var s = 0; s < data.segCount[idx]; s++) {
                var tv = tsec[s];
                data.segTargetSec[idx][s] =
                    (tv instanceof Toybox.Lang.Number) ? tv.toNumber() : 0;
            }
        } else {
            data.hasTargets[idx] = false;
        }
```

- [ ] **Step 4: Compileer de datafield**

Run (vanuit `garmin/`): `monkeyc -o bin/app.prg -f monkey.jungle -y <developer_key> -d fr255m`
Expected: compileert zonder fouten. (Als de CIQ SDK niet beschikbaar is: review de wijzigingen tegen de bestaande `surf`/`calib`-patronen — `tsec` volgt exact dezelfde parse-vorm.)

- [ ] **Step 5: Commit**

```bash
git add garmin/source/ClimbData.mc garmin/source/CommListener.mc
git commit -m "feat(watch): parse tsec and interpolate target time at position"
```

---

## Task 11: Watch — live ghost + klim-samenvatting renderen

**Files:**
- Modify: `garmin/source/ClimbProView.mc`

- [ ] **Step 1: Voeg de hidden-velden en de `climbTotalTarget`-helper toe**

Voeg bovenaan de klasse, bij de andere `hidden var`-velden (naast `alertedClimbIndex`/`lastActiveClimb`), toe:

```monkeyc
    hidden var lastGhostTimerMs = 0;
    hidden var summaryUntilMs = -1;        // show post-summit summary until this timer value (ms)
    hidden var summaryClimbIndex = -1;     // which climb the summary is for
    hidden var summaryActualSec = 0;
    hidden var summaryDeltaSec = 0;
```

Voeg een helper toe (bij de andere `hidden function`s) die de totale streeftijd van een klim sommeert:

```monkeyc
    hidden function climbTotalTarget(data, ci) {
        if (ci < 0 || !data.hasTargets[ci]) { return -1; }
        var sum = 0;
        for (var s = 0; s < data.segCount[ci]; s++) { sum += data.segTargetSec[ci][s]; }
        return sum;
    }
```

- [ ] **Step 2: Herschrijf `compute` met correcte volgorde (samenvatting vóór timer-overschrijving)**

Vervang de hele `compute(info)`-functie door deze versie. De volgorde is cruciaal: we detecteren het verlaten van een klim (en lezen de oude `climbStartTimerMs`) vóórdat we de timer voor een nieuw betreden klim overschrijven — zo klopt ook een directe klim-naar-klim-overgang.

```monkeyc
    function compute(info) {
        var data = App.getApp().climbData;
        if (data == null || !data.payloadReceived) {
            return;
        }

        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
        var timerMs = (info != null && info has :timerTime && info.timerTime != null)
                ? info.timerTime : 0;
        lastGhostTimerMs = timerMs;

        data.updateProgress(elapsed);

        // Detect leaving a climb (summary) BEFORE overwriting the climb-start timer.
        if (lastActiveClimb >= 0 && data.activeClimbIndex != lastActiveClimb
                && data.climbStartTimerMs >= 0) {
            summaryClimbIndex = lastActiveClimb;
            summaryActualSec = ((timerMs - data.climbStartTimerMs) / 1000.0).toNumber();
            var totalTarget = climbTotalTarget(data, lastActiveClimb);
            summaryDeltaSec = (totalTarget >= 0) ? (summaryActualSec - totalTarget) : 0;
            summaryUntilMs = timerMs + 12000;   // show for 12 s
        }

        // Capture the timer at the start of a newly entered climb.
        if (data.activeClimbIndex >= 0 && data.activeClimbIndex != lastActiveClimb) {
            data.climbStartTimerMs = timerMs;
        }
        lastActiveClimb = data.activeClimbIndex;

        // Climb-start alert: vibrate when entering a new climb within 50m.
        if (data.activeClimbIndex >= 0 && data.activeClimbIndex != alertedClimbIndex) {
            if (data.progressInClimb <= 50) {
                triggerClimbAlert();
                alertedClimbIndex = data.activeClimbIndex;
            }
        }
    }
```

- [ ] **Step 3: Teken de ghost-delta in `drawActiveClimb`**

In `drawActiveClimb`, ná het tekenen van de gradient-tekst (na het laatste `dc.drawText(w - 32, statsY, ...)`-blok), voeg de ghost-weergave toe boven de stats-regel:

```monkeyc
        // Pacing ghost: actual elapsed minus target time at current position.
        if (data.hasTargets[ci] && data.climbStartTimerMs >= 0) {
            var target = data.targetSecondsAt();
            if (target >= 0) {
                var actual = (lastGhostTimerMs - data.climbStartTimerMs) / 1000.0;
                var delta = (actual - target).toNumber();   // + = behind, - = ahead
                var label;
                if (delta > 0) {
                    label = "+" + delta + "s";
                    dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
                } else {
                    label = delta + "s";   // negative sign already included
                    dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
                }
                dc.drawText(w / 2, profileTop.toNumber() - 2, Gfx.FONT_TINY, label,
                        Gfx.TEXT_JUSTIFY_CENTER);
            }
        }
```

> `profileTop` is in scope binnen `drawActiveClimb`. De ghost staat gecentreerd net boven de profielzone; de bestaande progress-marker (4px breed) en de ghost-tekst overlappen niet noemenswaardig op de FR255M. Controleer in de simulator en verschuif de y desgewenst 2–3 px.

- [ ] **Step 4: Render de samenvatting in `onUpdate`**

In `onUpdate`, ná het ophalen van `data` en vóór de bestaande `if (data.activeClimbIndex >= 0)`-tak, voeg de samenvatting-tak toe (verschijnt zolang de timer onder `summaryUntilMs` zit en we niet alweer op een klim zitten):

```monkeyc
        if (summaryUntilMs > 0 && lastGhostTimerMs < summaryUntilMs
                && data.activeClimbIndex < 0 && summaryClimbIndex >= 0) {
            drawClimbSummary(dc, data);
            return;
        }
```

Voeg de teken-functie toe (bij de andere `hidden function`s):

```monkeyc
    hidden function drawClimbSummary(dc, data) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var ci = summaryClimbIndex;

        var name = data.climbName[ci];
        if (name == null) { name = "Climb " + (ci + 1); }

        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY, "KLIM KLAAR", Gfx.TEXT_JUSTIFY_CENTER);

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.18).toNumber(), Gfx.FONT_TINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        var mins = summaryActualSec / 60;
        var secs = summaryActualSec % 60;
        if (secs < 0) { secs = -secs; }
        dc.drawText(w / 2, (h * 0.40).toNumber(), Gfx.FONT_NUMBER_MEDIUM,
                mins + ":" + (secs < 10 ? "0" + secs : "" + secs), Gfx.TEXT_JUSTIFY_CENTER);

        dc.drawText(w / 2, (h * 0.62).toNumber(), Gfx.FONT_XTINY,
                data.climbElevGain[ci] + "m↑", Gfx.TEXT_JUSTIFY_CENTER);

        if (data.hasTargets[ci]) {
            var d = summaryDeltaSec;
            if (d > 0) {
                dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, (h * 0.78).toNumber(), Gfx.FONT_XTINY,
                        "+" + d + "s vs plan", Gfx.TEXT_JUSTIFY_CENTER);
            } else {
                dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, (h * 0.78).toNumber(), Gfx.FONT_XTINY,
                        d + "s vs plan", Gfx.TEXT_JUSTIFY_CENTER);
            }
        }
    }
```

- [ ] **Step 5: Compileer de datafield**

Run (vanuit `garmin/`): `monkeyc -o bin/app.prg -f monkey.jungle -y <developer_key> -d fr255m`
Expected: compileert zonder fouten.

- [ ] **Step 6: Verifieer in de Connect IQ-simulator (FR255M)**

Laad een route-payload mét `tsec` (gebruik `protocol/examples/route_mode_full.json`, of pas een test-payload aan). Simuleer een activiteit met oplopende `elapsedDistance` en `timerTime`. Verwacht:
- Op een klim: een gecentreerde `+Ns`/`−Ns`-ghost net boven het profiel, groen als je vóór ligt, rood als je achter ligt.
- Na het verlaten van de klim: ~12 s lang een "KLIM KLAAR"-kaartje met tijd, hoogtemeters en "±Ns vs plan", daarna terug naar de "next climb"-preview.
- Payload zónder `tsec`: geen ghost en geen "vs plan"-regel; gedrag verder als voorheen.

- [ ] **Step 7: Commit**

```bash
git add garmin/source/ClimbProView.mc
git commit -m "feat(watch): live pacing ghost and post-summit summary"
```

---

## Task 12: Documentatie bijwerken

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: Werk `ARCHITECTURE.md` bij**

Voeg een korte subsectie toe onder het kopje over de wire-payload / domeinmodel (bijv. ná "Climb time estimate (phone-only)"):

```markdown
### Pacing plan (phone → watch)

The phone precomputes a per-segment target time for every climb via
`service/RoutePacingPlanner` (route-aware fatigue model with a per-climb
fallback, using the rider profile). These are serialised as an optional packed
int array `tsec` on each climb (parallel to `segs`), route-mode only, omitted
when no plan is available. The climb datafield parses `tsec`, records the timer
at climb start, and shows a live time-delta ghost (`+/−s` vs plan) plus a short
post-summit summary. The route detail screen shows a pacing passport (totals +
per-climb target time) via `ui/routes/RoutePassport`. Background sync re-sends a
route when the rider-profile signature changes (combined with the source hash in
the sync gate). Radius mode is unchanged.
```

- [ ] **Step 2: Werk `README.md` bij**

Voeg de pacing-features toe aan de feature-lijst (zoek de bestaande opsomming van features en voeg een regel toe):

```markdown
- **Pacing-paspoort & live ghost**: de telefoon schat per klim een streeftijd; de
  watch toont tijdens de klim of je vóór/achter ligt op je plan en geeft een korte
  samenvatting na de top.
```

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md
git commit -m "docs: document pacing system (passport, ghost, summary)"
```

---

## Eindverificatie

- [ ] **Draai de volledige Android-testsuite**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — alle bestaande + nieuwe tests slagen.

- [ ] **Bouw de debug-APK**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Compileer de datafield** (indien CIQ SDK aanwezig)

Run (vanuit `garmin/`): `monkeyc -o bin/app.prg -f monkey.jungle -y <developer_key> -d fr255m`
Expected: compileert zonder fouten.
```
