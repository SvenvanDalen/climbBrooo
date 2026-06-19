# Ondergrond-stukken op de kaart — Implementatieplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Teken de handmatig ingevoerde Ondergrond-stukken (`StoredSurfaceSection`) als gekleurde overlay-lijnen op de bestaande route-kaart in `RouteDetailActivity`; tikken op een stuk toont naam + ondergrondtype.

**Architecture:** Puur een weergavelaag op de telefoon — geen wijziging aan opslag, ViewModel of het horloge-payload. Een nieuwe pure helper `SurfaceSectionGeometry` zet een meterbereik (`startDistance`/`endDistance`) om naar routepunten via de bestaande `distances[]`/`lats[]`/`lons[]`-arrays. Een nieuwe `SurfaceColorPalette` geeft een kleur per ondergrondtype. `RouteDetailActivity.drawRoute` tekent per stuk een dikkere, gekleurde `Polyline` bovenop de blauwe route met een tik-listener.

**Tech Stack:** Java (Android, MVVM + Repository), osmdroid 6.1.18, JUnit 4 (pure JVM, geen Robolectric nodig voor de nieuwe helpers), Gradle Groovy DSL.

---

## Background: wat al bestaat (lees vóór je begint)

- **`StoredSurfaceSection`** (`android/app/src/main/java/nl/paree/climbpro/data/route/StoredSurfaceSection.java`): `{int startDistance, int endDistance, int surfaceType, String name}`. Alleen meters — **geen** coördinaten.
- **`StoredRoute`** heeft parallelle arrays `double[] distances`, `double[] lats`, `double[] lons` (oplopende cumulatieve afstand in meters) en `List<StoredSurfaceSection> surfaceSections`.
- **`SurfaceType`** (`domain/segment/SurfaceType.java`): `ASPHALT=0, GRAVEL=1, DIRT=2, COBBLESTONE=3, MIXED=4, UNKNOWN=5`; `fromInt(v)` clampt buiten 0..5 naar `UNKNOWN`.
- **`RouteDetailActivity`** tekent de route in `drawRoute(StoredRoute)` als één blauwe `Polyline` (`org.osmdroid.views.overlay.Polyline`, breedte `5f`). Het roept eerst `binding.mapView.getOverlays().clear()` aan, dus oude overlays worden bij elke herteken opgeruimd. De constante `SURFACE_LABELS_NL = {"Asfalt","Gravel","Onverhard","Kasseien","Mixed","Onbekend"}` bestaat al in deze klasse.
- **`SegmentColorPalette`** (`ui/climbs/`) is voor klim-gradiënten — **niet** hergebruiken; ondergrondtypes krijgen een eigen palette.
- **osmdroid `Polyline`** heeft `setOnClickListener(Polyline.OnClickListener)`; de interface is `boolean onClick(Polyline polyline, MapView mapView, GeoPoint eventPos)` (één methode → lambda werkt).

### Conventies
- Java, POJO/utility-stijl: `final` klasse met private constructor voor utilities; geen getters/setters.
- Afstanden zijn integer meters intern.
- Kleuren als rauwe `0xFFRRGGBB` int-literals (een Android color-int), **niet** `Color.parseColor`, zodat de palette zonder Android-stub testbaar is.

### Commands (dit project)
- Eén testklasse (Windows PowerShell): `cd android; .\gradlew.bat test --tests nl.paree.climbpro.<FQCN>`
- Compileren: `cd android; .\gradlew.bat compileDebugJavaWithJavac`
- Debug-build: `cd android; .\gradlew.bat assembleDebug`
- (Op macOS/Linux: `cd android && ./gradlew test --tests <FQCN>`.)

---

## Task 1: `SurfaceSectionGeometry` — meterbereik → routepunten (pure helper)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometry.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometryTest.java`

Het hart van de feature. Pure Java, geretourneerd als `double[]`-paren `{lat, lon}` zodat het zonder Android/Robolectric te testen is.

- [ ] **Step 1: Schrijf de falende test**

Create `android/app/src/test/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometryTest.java`:

```java
package nl.paree.climbpro.domain.route;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SurfaceSectionGeometryTest {

    // Rechte as: 0..4000 m, lat 51.0..51.4, lon 5.0..5.4 (vertex elke 1000 m).
    private static final double[] DIST = {0, 1000, 2000, 3000, 4000};
    private static final double[] LAT  = {51.0, 51.1, 51.2, 51.3, 51.4};
    private static final double[] LON  = {5.0, 5.1, 5.2, 5.3, 5.4};

    @Test
    public void returnsVerticesInRange_inOrder() {
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, 1000, 3000);
        assertEquals(3, pts.size());
        assertEquals(51.1, pts.get(0)[0], 1e-9);
        assertEquals(5.1,  pts.get(0)[1], 1e-9);
        assertEquals(51.3, pts.get(2)[0], 1e-9);
        assertEquals(5.3,  pts.get(2)[1], 1e-9);
    }

    @Test
    public void clampsToRouteBounds() {
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, -500, 9000);
        assertEquals("alle vertices, geclamped op 0..4000", 5, pts.size());
    }

    @Test
    public void nullArrays_returnEmpty() {
        assertTrue(SurfaceSectionGeometry.pointsBetween(null, LAT, LON, 0, 1000).isEmpty());
        assertTrue(SurfaceSectionGeometry.pointsBetween(DIST, null, LON, 0, 1000).isEmpty());
        assertTrue(SurfaceSectionGeometry.pointsBetween(DIST, LAT, null, 0, 1000).isEmpty());
    }

    @Test
    public void inconsistentLengths_returnEmpty() {
        double[] shortLat = {51.0, 51.1};
        assertTrue(SurfaceSectionGeometry.pointsBetween(DIST, shortLat, LON, 0, 1000).isEmpty());
    }

    @Test
    public void emptyArrays_returnEmpty() {
        assertTrue(SurfaceSectionGeometry.pointsBetween(
                new double[0], new double[0], new double[0], 0, 1000).isEmpty());
    }

    @Test
    public void shortSectionBetweenVertices_fallsBackToTwoNearest() {
        // 1200..1800 valt tussen vertices 1000 en 2000: geen vertex in bereik.
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, 1200, 1800);
        assertEquals("fallback geeft de twee dichtstbijzijnde vertices", 2, pts.size());
        assertEquals(51.1, pts.get(0)[0], 1e-9); // dichtst bij 1200
        assertEquals(51.2, pts.get(1)[0], 1e-9); // dichtst bij 1800
    }

    @Test
    public void degenerateZeroLength_returnsAtMostOnePoint() {
        // start == end op een vertex: één punt, niet tekenbaar -> caller slaat over.
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, 2000, 2000);
        assertTrue(pts.size() <= 1);
    }
}
```

- [ ] **Step 2: Run de test — verwacht FAIL**

Run: `cd android; .\gradlew.bat test --tests nl.paree.climbpro.domain.route.SurfaceSectionGeometryTest`
Expected: FAIL — `cannot find symbol: class SurfaceSectionGeometry`.

- [ ] **Step 3: Implementeer de helper**

Create `android/app/src/main/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometry.java`:

```java
package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Zet een meterbereik [startM, endM] op een route om naar de routepunten die binnen dat
 * bereik vallen, met behulp van de parallelle distances/lats/lons-arrays.
 *
 * Puur Java (geen Android), zodat het met gewone JUnit te testen is; punten worden als
 * double[]{lat, lon} teruggegeven en pas door de aanroeper naar osmdroid GeoPoint omgezet.
 */
public final class SurfaceSectionGeometry {

    private SurfaceSectionGeometry() {}

    /**
     * @param distances oplopende cumulatieve afstand in meters (lengte n)
     * @param lats      breedtegraden (lengte n)
     * @param lons      lengtegraden (lengte n)
     * @param startM    startafstand in meters (geclamped op [0, lengte])
     * @param endM      eindafstand in meters (geclamped op [start, lengte])
     * @return routepunten {lat, lon} in routevolgorde binnen het bereik; ≥ 2 punten zolang
     *         de route ≥ 2 vertices heeft en het bereik niet ontaard is; lege lijst bij
     *         null/lege/inconsistente arrays.
     */
    public static List<double[]> pointsBetween(double[] distances, double[] lats, double[] lons,
                                               int startM, int endM) {
        List<double[]> result = new ArrayList<>();
        if (distances == null || lats == null || lons == null) return result;
        int n = distances.length;
        if (n == 0 || lats.length != n || lons.length != n) return result;

        double last  = distances[n - 1];
        double start = clamp(startM, 0, last);
        double end   = clamp(endM, start, last);

        for (int i = 0; i < n; i++) {
            if (distances[i] >= start && distances[i] <= end) {
                result.add(new double[]{lats[i], lons[i]});
            }
        }

        // Te kort om een vertex te raken? Val terug op de dichtstbijzijnde vertex bij start en eind.
        if (result.size() < 2 && n >= 2) {
            result.clear();
            int si = nearestIndex(distances, start);
            int ei = nearestIndex(distances, end);
            result.add(new double[]{lats[si], lons[si]});
            if (ei != si) {
                result.add(new double[]{lats[ei], lons[ei]});
            }
        }
        return result;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int nearestIndex(double[] distances, double target) {
        int best = 0;
        double bestDiff = Math.abs(distances[0] - target);
        for (int i = 1; i < distances.length; i++) {
            double diff = Math.abs(distances[i] - target);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }
}
```

- [ ] **Step 4: Run de test — verwacht PASS**

Run: `cd android; .\gradlew.bat test --tests nl.paree.climbpro.domain.route.SurfaceSectionGeometryTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometry.java android/app/src/test/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometryTest.java
git commit -m "feat(route): pure helper mapping a metre range to route points"
```

---

## Task 2: `SurfaceColorPalette` — kleur per ondergrondtype

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/routes/SurfaceColorPalette.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/ui/routes/SurfaceColorPaletteTest.java`

Eigen palette, los van `SegmentColorPalette`. Rauwe `0xFFRRGGBB` int-literals zodat het zonder Android testbaar is. De waarden komen exact overeen met de Android color-ints van de hex-codes in het ontwerp.

- [ ] **Step 1: Schrijf de falende test**

Create `android/app/src/test/java/nl/paree/climbpro/ui/routes/SurfaceColorPaletteTest.java`:

```java
package nl.paree.climbpro.ui.routes;

import org.junit.Test;

import nl.paree.climbpro.domain.segment.SurfaceType;

import static org.junit.Assert.assertEquals;

public class SurfaceColorPaletteTest {

    @Test
    public void eachSurfaceTypeMapsToExpectedColor() {
        assertEquals(0xFF616161, SurfaceColorPalette.toColor(SurfaceType.ASPHALT));
        assertEquals(0xFFA1887F, SurfaceColorPalette.toColor(SurfaceType.GRAVEL));
        assertEquals(0xFF795548, SurfaceColorPalette.toColor(SurfaceType.DIRT));
        assertEquals(0xFF7E57C2, SurfaceColorPalette.toColor(SurfaceType.COBBLESTONE));
        assertEquals(0xFF26A69A, SurfaceColorPalette.toColor(SurfaceType.MIXED));
        assertEquals(0xFF9E9E9E, SurfaceColorPalette.toColor(SurfaceType.UNKNOWN));
    }

    @Test
    public void outOfRangeClampsToUnknown() {
        assertEquals(0xFF9E9E9E, SurfaceColorPalette.toColor(99));
        assertEquals(0xFF9E9E9E, SurfaceColorPalette.toColor(-1));
    }
}
```

- [ ] **Step 2: Run de test — verwacht FAIL**

Run: `cd android; .\gradlew.bat test --tests nl.paree.climbpro.ui.routes.SurfaceColorPaletteTest`
Expected: FAIL — `cannot find symbol: class SurfaceColorPalette`.

- [ ] **Step 3: Implementeer de palette**

Create `android/app/src/main/java/nl/paree/climbpro/ui/routes/SurfaceColorPalette.java`:

```java
package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * Kleur per ondergrondtype voor de kaart-overlay, geïndexeerd op SurfaceType (0..5).
 * Los van SegmentColorPalette (dat is voor klim-gradiënten). Waarden zijn Android
 * color-ints (0xAARRGGBB) als rauwe literals, zodat de klasse zonder Android testbaar is.
 */
public final class SurfaceColorPalette {

    /** index = SurfaceType-constante. */
    public static final int[] COLORS = {
        0xFF616161, // 0 Asfalt    - donkergrijs
        0xFFA1887F, // 1 Gravel    - zandbruin
        0xFF795548, // 2 Onverhard - donkerbruin
        0xFF7E57C2, // 3 Kasseien  - paars
        0xFF26A69A, // 4 Mixed     - teal
        0xFF9E9E9E  // 5 Onbekend  - grijs
    };

    private SurfaceColorPalette() {}

    /** Android color-int voor het ondergrondtype; clampt buiten 0..5 naar Onbekend. */
    public static int toColor(int surfaceType) {
        return COLORS[SurfaceType.fromInt(surfaceType)];
    }
}
```

- [ ] **Step 4: Run de test — verwacht PASS**

Run: `cd android; .\gradlew.bat test --tests nl.paree.climbpro.ui.routes.SurfaceColorPaletteTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/SurfaceColorPalette.java android/app/src/test/java/nl/paree/climbpro/ui/routes/SurfaceColorPaletteTest.java
git commit -m "feat(routes): color palette per surface type for map overlay"
```

---

## Task 3: Teken de ondergrond-stukken in `RouteDetailActivity`

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`

Geen geautomatiseerde test (osmdroid-overlay + Activity); geverifieerd via compile, assemble en een handmatige check. We tekenen na de route-polyline per stuk een gekleurde overlay met tik-listener.

- [ ] **Step 1: Voeg de import voor de geometrie-helper toe**

In `RouteDetailActivity.java`, voeg bij de bestaande `nl.paree.climbpro`-imports toe (na de `org.osmdroid.views.overlay.Polyline`-import, regel ~19):

```java
import org.osmdroid.views.overlay.Polyline;

import nl.paree.climbpro.domain.route.SurfaceSectionGeometry;
```

(`SurfaceColorPalette` staat in hetzelfde package `ui.routes` → geen import nodig. `StoredFlatSegment`, `StoredRoute`, `SurfaceType`, `Toast`, `GeoPoint`, `ArrayList`, `List` zijn al geïmporteerd. `StoredSurfaceSection` wordt hieronder volledig gekwalificeerd gebruikt, net als elders in deze klasse.)

- [ ] **Step 2: Roep de tekenmethode aan in `drawRoute`**

In `drawRoute` (regels ~120–140), voeg de aanroep toe direct ná `binding.mapView.getOverlays().add(polyline);` en vóór de `BoundingBox box = ...`-regel:

```java
        binding.mapView.getOverlays().clear();
        binding.mapView.getOverlays().add(polyline);

        drawSurfaceSections(route);

        BoundingBox box = BoundingBox.fromGeoPoints(points);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 50));
        binding.mapView.invalidate();
```

- [ ] **Step 3: Voeg de `drawSurfaceSections`-methode toe**

In `RouteDetailActivity.java`, voeg deze methode toe direct ná `drawRoute` (na de afsluitende `}` ervan, rond regel 140):

```java
    /** Tekent elk handmatig ingevoerd ondergrond-stuk als gekleurde overlay op de route. */
    private void drawSurfaceSections(StoredRoute route) {
        if (route.surfaceSections == null) return;
        for (nl.paree.climbpro.data.route.StoredSurfaceSection s : route.surfaceSections) {
            List<double[]> coords = SurfaceSectionGeometry.pointsBetween(
                    route.distances, route.lats, route.lons,
                    s.startDistance, s.endDistance);
            if (coords.size() < 2) continue;

            List<GeoPoint> geo = new ArrayList<>(coords.size());
            for (double[] c : coords) geo.add(new GeoPoint(c[0], c[1]));

            Polyline overlay = new Polyline();
            overlay.setColor(SurfaceColorPalette.toColor(s.surfaceType));
            overlay.setWidth(12f);
            overlay.setPoints(geo);

            final String label = (s.name != null ? s.name : "(naamloos)")
                    + " · " + SURFACE_LABELS_NL[SurfaceType.fromInt(s.surfaceType)];
            overlay.setOnClickListener((polyline, mapView, eventPos) -> {
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
                return true;
            });

            binding.mapView.getOverlays().add(overlay);
        }
    }
```

- [ ] **Step 4: Compileer**

Run: `cd android; .\gradlew.bat compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Assembleer (resources linken)**

Run: `cd android; .\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Handmatige verificatie (emulator/toestel)**

Open een route die ≥ 1 ondergrond-stuk heeft (zo niet: open een route → **Ondergrond-stukken** → **Toevoegen**, bijv. start `1.0`, eind `2.0`, **Gravel**, naam "Bospad"). Verwacht:
- Op de kaart verschijnt tussen 1.0 en 2.0 km een dikkere, zandbruine lijn bovenop de blauwe route.
- Tik op die lijn → Toast `Bospad · Gravel`.
- Een route zónder ondergrond-stukken tekent ongewijzigd (alleen de blauwe lijn).

(Als je geen emulator kunt draaien: noteer deze stap als uitgesteld en steun op de geslaagde assemble.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java
git commit -m "feat(routes): draw custom surface sections as colored map overlays"
```

---

## Final verification

- [ ] **Step 1: Volledige unit-testsuite**

Run: `cd android; .\gradlew.bat test`
Expected: `BUILD SUCCESSFUL`, alle tests groen (incl. `SurfaceSectionGeometryTest` en `SurfaceColorPaletteTest`).

- [ ] **Step 2: Debug-build**

Run: `cd android; .\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

---

## Self-review (voor de implementer)

- **Spec coverage:** "alleen Ondergrond-stukken" → Task 3 itereert alleen over `route.surfaceSections`, niet over `flatSegments`. "gekleurde lijn per type" → Task 2 palette + Task 3 `setColor`/`setWidth(12f)`. "tikken toont naam/type" → Task 3 `setOnClickListener` met Toast. "meters → coördinaten" → Task 1.
- **Geen opslag/payload-wijziging:** alleen lees-toegang tot `route.distances/lats/lons/surfaceSections`; niets nieuws gepersisteerd, niets naar de horloge.
- **Type-consistentie:** `SurfaceSectionGeometry.pointsBetween(double[], double[], double[], int, int) -> List<double[]>` en `SurfaceColorPalette.toColor(int) -> int` worden in Task 3 exact zo aangeroepen.
- **Edge cases:** lege/ontbrekende geometrie → helper geeft lege lijst → geen overlays; stuk < 2 punten → overgeslagen (geen crash); `surfaceSections` null → vroege return.
</content>
