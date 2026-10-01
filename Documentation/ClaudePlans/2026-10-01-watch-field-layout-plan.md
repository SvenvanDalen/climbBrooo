# Horloge-velden instelbaar vanaf de telefoon — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** De gebruiker kiest in de Android-app per vak (5 vakken) welke waarde de ClimbPro-datafield toont op de pagina "HUIDIGE KLIM".

**Architecture:** De keuze wordt op de telefoon in `SharedPreferences` bewaard als 5 metriekcodes en gaat als optionele top-level sleutel `lay` mee in het bestaande v3-datafieldbericht. Het horloge parseert `lay` (met fallback naar de default per vak) en tekent elk vak via één `drawSlot`. Default = huidig scherm; dan wordt `lay` niet verstuurd.

**Tech Stack:** Java (Android, JUnit4 + Robolectric, Jackson), Monkey C (Connect IQ, Toybox.Test), JSON Schema draft-07.

**Spec:** `Documentation/ClaudePlans/2026-10-01-watch-field-layout-design.md`

## Global Constraints

- Branch `feat/watch-field-layout` (vanaf `origin/staging`); PR gaat naar `staging`. Nooit direct op `staging` committen.
- **Commit altijd met expliciete paden** (`git add -- <paden>` + `git commit -- <paden>`): de index bevat veel ongerelateerde gestagede bestanden die NIET mee mogen.
- Commit-trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01Qq9svXrLq78svZM9EddEtN`.
- Java, geen Kotlin; geen `List.of` (minSdk 26).
- Metriekcodes 0–15 zijn identiek in `WatchFieldLayout.java`, `FieldLayout.mc` en `protocol/schema.json`.
- Default-layout `[0, 1, 2, 14, 15]`; default ⇒ geen `lay` op de wire.
- `lay`: precies 5 integers 0..15. Watch: ontbrekend/verkeerde lengte/geen array → volledige default; los fout element → default voor dat vak.
- Geen schemaversie-bump (blijft `v: 3`).
- Wire-wijziging in lockstep: `schema.json`, `schema.md`, `protocol/examples/`, `ClimbPayloadBuilder`, `CommListener.mc`, `ARCHITECTURE.md`, `README.md`.
- Monkey C-tests schrijven maar **niet** in de simulator draaien (gebruikersvoorkeur). `./gradlew test` draait wel (incl. `MonkeyCSourceGuard`: accolades in balans, elke `(:test)` eindigt met `return true;`).
- Gradle: als nieuwe deps PKIX falen, `-Djavax.net.ssl.trustStoreType=Windows-ROOT` (hier niet verwacht: geen nieuwe deps).
- UI-teksten Nederlands.

## Review Focus

1. **Wijziging terwijl het horloge niet verbonden is** → keuze moet bij de volgende sync alsnog aankomen. Gedekt door `wantHash` met layout (Task 4) + `triggerImmediateSync` (WorkManager retry). Test: `RouteSyncWorkerWantHashTest` in Task 4.
2. **Opgeslagen voorkeur kapot/van een oudere versie** (bv. `"1,2"` of `"a,b,c,d,e"`) → default, geen crash. Test: `WatchFieldLayoutTest.parse_*` (Task 1) en `WatchFieldLayoutStoreTest.corruptValue_loadsDefault` (Task 2).
3. **Nieuw bericht zonder `lay` na eentje mét** (gebruiker zet terug naar standaard) → horloge moet terug naar default, niet de oude layout houden. Test: `fieldLayout_resyncWithoutLay_restoresDefaults` (Task 5).
4. **Sensor ontbreekt** (geen HR-band, geen vermogensmeter, geen cadans) → `--`, geen crash. Test: `fieldLayout_metricText_missingSensorsShowDashes` (Task 5).
5. **Grote-tekstmodus + aangepaste layout** → middenvak en regel 4 verborgen, behalve een intervalblok in regel 4; elke code in elk vak tekent zonder crash. Test: `fieldLayout_onUpdate_everyCodeInEverySlot_rendersWithoutThrow` (Task 6), ook met `largeTextMode` aan.

---

### Task 1: Domeinmodel `WatchFieldLayout`

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/watch/WatchFieldLayout.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/watch/WatchFieldLayoutTest.java`

**Interfaces:**
- Produces: `WatchFieldLayout` met constanten `SLOT_COUNT=5`, `REM_DIST..AUTO_BOTTOM` (0..15), `MAX_CODE=15`; `static defaults()`, `static of(int[])`, `static parse(String)`, `static isValidCode(int)`, `serialize()`, `code(int slot)`, `codes()`, `isDefault()`, `withCode(int slot, int code)`, `static slotLabel(int)`, `static metricLabel(int)`, `static metricCount()`.

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain.watch;

import org.junit.Test;

import static org.junit.Assert.*;

public class WatchFieldLayoutTest {

    @Test
    public void defaults_matchCurrentScreen() {
        WatchFieldLayout d = WatchFieldLayout.defaults();
        assertArrayEquals(new int[]{0, 1, 2, 14, 15}, d.codes());
        assertTrue(d.isDefault());
        assertEquals("0,1,2,14,15", d.serialize());
    }

    @Test
    public void serializeParse_roundTrips() {
        WatchFieldLayout l = WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5});
        assertFalse(l.isDefault());
        assertArrayEquals(new int[]{8, 9, 10, 6, 5}, WatchFieldLayout.parse(l.serialize()).codes());
    }

    @Test
    public void parse_nullOrWrongLengthGivesDefaults() {
        assertTrue(WatchFieldLayout.parse(null).isDefault());
        assertTrue(WatchFieldLayout.parse("").isDefault());
        assertTrue(WatchFieldLayout.parse("1,2").isDefault());
        assertTrue(WatchFieldLayout.parse("1,2,3,4,5,6").isDefault());
    }

    @Test
    public void parse_badElementFallsBackPerSlot() {
        assertArrayEquals(new int[]{8, 1, 2, 9, 15},
                WatchFieldLayout.parse("8,x,99,9,-1").codes());
    }

    @Test
    public void of_nullOrWrongLengthGivesDefaults() {
        assertTrue(WatchFieldLayout.of(null).isDefault());
        assertTrue(WatchFieldLayout.of(new int[]{1, 2, 3}).isDefault());
    }

    @Test
    public void codes_isDefensiveCopy() {
        WatchFieldLayout l = WatchFieldLayout.defaults();
        l.codes()[0] = 13;
        assertEquals(0, l.code(0));
    }

    @Test
    public void withCode_replacesOneSlotAndValidates() {
        WatchFieldLayout l = WatchFieldLayout.defaults().withCode(4, WatchFieldLayout.ELAPSED);
        assertArrayEquals(new int[]{0, 1, 2, 14, 12}, l.codes());
        assertEquals(15, l.withCode(4, 42).code(4));
    }

    @Test
    public void labels_coverEverySlotAndCode() {
        assertEquals(16, WatchFieldLayout.metricCount());
        for (int c = 0; c < WatchFieldLayout.metricCount(); c++) {
            assertNotNull(WatchFieldLayout.metricLabel(c));
        }
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            assertNotNull(WatchFieldLayout.slotLabel(s));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (in `android/`): `./gradlew test --tests nl.paree.climbpro.domain.watch.WatchFieldLayoutTest`
Expected: compilation FAIL ("cannot find symbol WatchFieldLayout").

- [ ] **Step 3: Write the implementation**

```java
package nl.paree.climbpro.domain.watch;

import java.util.Arrays;

/**
 * Which value each of the five stat slots on the datafield's "HUIDIGE KLIM" page shows
 * (left, middle, right, row 4, bottom line). The codes are the wire values of the optional
 * top-level 'lay' key (protocol/schema.json) and must match garmin/source/FieldLayout.mc.
 * The default reproduces the screen as it was before the layout became configurable.
 */
public final class WatchFieldLayout {

    public static final int SLOT_COUNT = 5;

    public static final int REM_DIST    = 0;
    public static final int REM_ELEV    = 1;
    public static final int CUR_GRAD    = 2;
    public static final int AVG_GRAD    = 3;
    public static final int VAM         = 4;
    public static final int ETA         = 5;
    public static final int GHOST       = 6;
    public static final int BLOCK       = 7;
    public static final int SPEED       = 8;
    public static final int HEART_RATE  = 9;
    public static final int POWER       = 10;
    public static final int CADENCE     = 11;
    public static final int ELAPSED     = 12;
    public static final int EMPTY       = 13;
    /** Interval block when the climb has one, otherwise VAM (the pre-layout row 4). */
    public static final int AUTO_ROW4   = 14;
    /** "vs PR"/"vs plan" when there is a reference, otherwise ETA (the pre-layout bottom line). */
    public static final int AUTO_BOTTOM = 15;
    public static final int MAX_CODE    = AUTO_BOTTOM;

    private static final int[] DEFAULT_CODES = {REM_DIST, REM_ELEV, CUR_GRAD, AUTO_ROW4, AUTO_BOTTOM};

    private static final String[] SLOT_LABELS = {
            "Links", "Midden", "Rechts", "Regel 4", "Onderste regel"};

    private static final String[] METRIC_LABELS = {
            "Rest-afstand klim",
            "Rest-hoogtemeters",
            "Stijging huidig segment",
            "Gemiddelde stijging klim",
            "VAM huidig segment",
            "ETA tot de top",
            "Tijd t.o.v. PR / plan",
            "Intervalblok",
            "Snelheid",
            "Hartslag",
            "Vermogen",
            "Cadans",
            "Verstreken tijd",
            "Leeg",
            "Automatisch: intervalblok, anders VAM",
            "Automatisch: PR/plan, anders ETA"};

    private final int[] codes;

    private WatchFieldLayout(int[] codes) {
        this.codes = codes;
    }

    public static WatchFieldLayout defaults() {
        return new WatchFieldLayout(DEFAULT_CODES.clone());
    }

    /** Null or wrong length → defaults; an out-of-range element → that slot's default. */
    public static WatchFieldLayout of(int[] codes) {
        if (codes == null || codes.length != SLOT_COUNT) return defaults();
        int[] out = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            out[i] = isValidCode(codes[i]) ? codes[i] : DEFAULT_CODES[i];
        }
        return new WatchFieldLayout(out);
    }

    /** Parses {@link #serialize()} output; anything unparseable falls back like {@link #of}. */
    public static WatchFieldLayout parse(String csv) {
        if (csv == null) return defaults();
        String[] parts = csv.split(",", -1);
        if (parts.length != SLOT_COUNT) return defaults();
        int[] codes = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            try {
                codes[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                codes[i] = -1;
            }
        }
        return of(codes);
    }

    public static boolean isValidCode(int code) {
        return code >= 0 && code <= MAX_CODE;
    }

    /** "0,1,2,14,15" — stored in preferences and folded into the sync hash. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(codes[i]);
        }
        return sb.toString();
    }

    public int code(int slot) {
        return codes[slot];
    }

    public int[] codes() {
        return codes.clone();
    }

    public boolean isDefault() {
        return Arrays.equals(codes, DEFAULT_CODES);
    }

    public WatchFieldLayout withCode(int slot, int code) {
        int[] c = codes.clone();
        c[slot] = code;
        return of(c);
    }

    public static String slotLabel(int slot) {
        return SLOT_LABELS[slot];
    }

    public static String metricLabel(int code) {
        return METRIC_LABELS[code];
    }

    public static int metricCount() {
        return MAX_CODE + 1;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.domain.watch.WatchFieldLayoutTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add -- android/app/src/main/java/nl/paree/climbpro/domain/watch/WatchFieldLayout.java android/app/src/test/java/nl/paree/climbpro/domain/watch/WatchFieldLayoutTest.java
git commit -m "feat: WatchFieldLayout domeinmodel voor horloge-velden" -- android/app/src/main/java/nl/paree/climbpro/domain/watch/WatchFieldLayout.java android/app/src/test/java/nl/paree/climbpro/domain/watch/WatchFieldLayoutTest.java
```

---

### Task 2: Opslag `WatchFieldLayoutStore`

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/watch/WatchFieldLayoutStore.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/watch/WatchFieldLayoutStoreTest.java`

**Interfaces:**
- Consumes: `WatchFieldLayout` (Task 1).
- Produces: `new WatchFieldLayoutStore(Context)`, `WatchFieldLayout load()`, `void save(WatchFieldLayout)`, `static final String PREF_KEY = "watch_field_layout"`.

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.data.watch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.watch.WatchFieldLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WatchFieldLayoutStoreTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
    }

    @Test
    public void nothingStored_loadsDefault() {
        assertTrue(new WatchFieldLayoutStore(app).load().isDefault());
    }

    @Test
    public void saveLoad_roundTrips() {
        WatchFieldLayoutStore store = new WatchFieldLayoutStore(app);
        store.save(WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5}));
        assertArrayEquals(new int[]{8, 9, 10, 6, 5}, new WatchFieldLayoutStore(app).load().codes());
    }

    @Test
    public void savingDefault_removesKey() {
        WatchFieldLayoutStore store = new WatchFieldLayoutStore(app);
        store.save(WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5}));
        store.save(WatchFieldLayout.defaults());
        assertFalse(PreferenceManager.getDefaultSharedPreferences(app)
                .contains(WatchFieldLayoutStore.PREF_KEY));
        assertTrue(store.load().isDefault());
    }

    @Test
    public void corruptValue_loadsDefault() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(WatchFieldLayoutStore.PREF_KEY, "a,b").commit();
        assertTrue(new WatchFieldLayoutStore(app).load().isDefault());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.data.watch.WatchFieldLayoutStoreTest`
Expected: compilation FAIL ("cannot find symbol WatchFieldLayoutStore").

- [ ] **Step 3: Write the implementation**

```java
package nl.paree.climbpro.data.watch;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.watch.WatchFieldLayout;

/**
 * Persists the datafield slot layout chosen on the "Horloge-velden" screen. A setting, not
 * route data, so it lives in the default SharedPreferences; the default layout is stored as
 * "no value" so a reset leaves nothing behind.
 */
public final class WatchFieldLayoutStore {

    public static final String PREF_KEY = "watch_field_layout";

    private final SharedPreferences prefs;

    public WatchFieldLayoutStore(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public WatchFieldLayout load() {
        return WatchFieldLayout.parse(prefs.getString(PREF_KEY, null));
    }

    public void save(WatchFieldLayout layout) {
        if (layout == null || layout.isDefault()) {
            prefs.edit().remove(PREF_KEY).apply();
        } else {
            prefs.edit().putString(PREF_KEY, layout.serialize()).apply();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.data.watch.WatchFieldLayoutStoreTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add -- android/app/src/main/java/nl/paree/climbpro/data/watch/WatchFieldLayoutStore.java android/app/src/test/java/nl/paree/climbpro/data/watch/WatchFieldLayoutStoreTest.java
git commit -m "feat: opslag voor horloge-veldindeling" -- android/app/src/main/java/nl/paree/climbpro/data/watch/WatchFieldLayoutStore.java android/app/src/test/java/nl/paree/climbpro/data/watch/WatchFieldLayoutStoreTest.java
```

---

### Task 3: Wire-formaat `lay` (schema + builder + voorbeeld)

**Files:**
- Modify: `protocol/schema.json` (top-level `properties`, na `fss`)
- Modify: `protocol/schema.md` (sectie "Wire keys for packed encoding" + "Change log")
- Modify: `protocol/examples/route_mode_full.json` (na `"rtl": 8000,`)
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderFieldLayoutTest.java`

**Interfaces:**
- Consumes: `WatchFieldLayout` (Task 1).
- Produces: `ClimbPayloadBuilder withFieldLayout(WatchFieldLayout layout)` — null of default ⇒ geen `lay`. `lay` verschijnt in `buildRoutePayload`, `buildRadiusPayload` en `buildSingleClimbPayload`; **niet** in `buildSurfaceSectionPayload` (gaat naar het surface-veld).

- [ ] **Step 1: Write the failing builder test**

```java
package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.watch.WatchFieldLayout;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** Optional top-level 'lay' = datafield slot layout chosen on the phone. */
public class ClimbPayloadBuilderFieldLayoutTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final WatchFieldLayout CUSTOM = WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5});

    private static StoredRoute route() {
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

    private static void assertLay(JsonNode payload) {
        JsonNode lay = payload.get("lay");
        assertNotNull("lay present", lay);
        assertEquals(5, lay.size());
        int[] expected = {8, 9, 10, 6, 5};
        for (int i = 0; i < 5; i++) assertEquals(expected[i], lay.get(i).asInt());
    }

    @Test
    public void layOmittedByDefault() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        assertFalse(mapper.readTree(b.buildRoutePayload(route())).has("lay"));
    }

    @Test
    public void layOmittedForDefaultLayout() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper)
                .withFieldLayout(WatchFieldLayout.defaults());
        assertFalse(mapper.readTree(b.buildRoutePayload(route())).has("lay"));
    }

    @Test
    public void layOmittedForNullLayout() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(null);
        assertFalse(mapper.readTree(b.buildRoutePayload(route())).has("lay"));
    }

    @Test
    public void layEmittedInRouteRadiusAndSingleClimbPayloads() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(CUSTOM);
        assertLay(mapper.readTree(b.buildRoutePayload(route())));
        assertLay(mapper.readTree(b.buildRadiusPayload(route().climbs)));
        assertLay(mapper.readTree(b.buildSingleClimbPayload(route(), 0)));
    }

    @Test
    public void layNotInSurfacePayload() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(CUSTOM);
        assertFalse(mapper.readTree(b.buildSurfaceSectionPayload(route())).has("lay"));
    }

    @Test
    public void laySurvivesOtherWithers() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(CUSTOM)
                .withFtpWatts(280)
                .withIntensityZones(new nl.paree.climbpro.domain.power.RiderProfile(250, 75, 8));
        assertLay(mapper.readTree(b.buildRoutePayload(route())));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.service.ClimbPayloadBuilderFieldLayoutTest`
Expected: compilation FAIL ("cannot find symbol withFieldLayout").

- [ ] **Step 3: Implement in `ClimbPayloadBuilder`**

Add import `nl.paree.climbpro.domain.watch.WatchFieldLayout`. Add field next to `ftpWatts`:

```java
    /** Optional top-level 'lay' (datafield slot layout); null = default layout, key omitted. */
    private final int[] fieldLayout;
```

Replace the constructors and withers so every one carries `fieldLayout`:

```java
    public ClimbPayloadBuilder(ObjectMapper mapper) {
        this(mapper, null, 0, null);
    }

    private ClimbPayloadBuilder(ObjectMapper mapper, RiderProfile zoneProfile, int ftpWatts,
                                int[] fieldLayout) {
        this.mapper = mapper;
        this.zoneProfile = zoneProfile;
        this.ftpWatts = ftpWatts;
        this.fieldLayout = fieldLayout;
    }
```

In `withIntensityZones`: `return new ClimbPayloadBuilder(mapper, profile, ftpWatts, fieldLayout);`
In `withFtpWatts`: `return new ClimbPayloadBuilder(mapper, zoneProfile, Math.max(0, ftpWatts), fieldLayout);`
(Keep their existing javadoc.) Add after `withFtpWatts`:

```java
    /**
     * Sets the stat-slot layout the datafield uses on its active-climb page ('lay'). A null or
     * default layout emits nothing, so riders who never customise send the same bytes as before.
     */
    public ClimbPayloadBuilder withFieldLayout(WatchFieldLayout layout) {
        int[] lay = (layout == null || layout.isDefault()) ? null : layout.codes();
        return new ClimbPayloadBuilder(mapper, zoneProfile, ftpWatts, lay);
    }
```

Add a helper next to `putRouteTotalLength`:

```java
    private void putFieldLayout(Map<String, Object> payload) {
        if (fieldLayout == null) return;
        List<Integer> lay = new ArrayList<>(fieldLayout.length);
        for (int code : fieldLayout) lay.add(code);
        payload.put("lay", lay);
    }
```

Call `putFieldLayout(payload);` directly before `payload.put("climbs", …)` in `buildRoutePayload(route, targetSeconds, refSeconds)`, in `buildRadiusPayload`, and in the full `buildSingleClimbPayload` overload (the one that does `payload.put("v", …)`). Do **not** call it in `buildSurfaceSectionPayload`.

- [ ] **Step 4: Run the builder test**

Run: `./gradlew test --tests nl.paree.climbpro.service.ClimbPayloadBuilderFieldLayoutTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Add the round-trip test (fails until the schema knows `lay`)**

In `ProtocolRoundTripTest`, add import `nl.paree.climbpro.domain.watch.WatchFieldLayout` and, after `builderPayloadsWithIntensityZonesValidateAgainstSchema`:

```java
    @Test
    public void builderPayloadsWithFieldLayoutValidateAgainstSchema() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER).withFieldLayout(
                WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5}));
        JsonNode route = MAPPER.readTree(b.buildRoutePayload(routeFixture()));
        assertTrue("route payload carries 'lay'", route.has("lay"));
        assertValid(route, "route payload with lay");
        assertValid(MAPPER.readTree(b.buildRadiusPayload(routeFixture().climbs)),
                "radius payload with lay");
    }
```

Run: `./gradlew test --tests nl.paree.climbpro.protocol.ProtocolRoundTripTest`
Expected: FAIL in `builderPayloadsWithFieldLayoutValidateAgainstSchema` (schema has `additionalProperties: false`).

- [ ] **Step 6: Schema, example, schema.md**

`protocol/schema.json` — add after the `"fss": { … }` property (mind the comma):

```json
    "lay": {
      "description": "Optional datafield stat-slot layout chosen in the phone app: 5 metric codes for [left, middle, right, row 4, bottom line] of the active-climb page. 0 remaining distance, 1 remaining elevation, 2 current-segment gradient, 3 climb average gradient, 4 VAM, 5 ETA, 6 ghost delta (vs PR/plan), 7 interval block, 8 speed, 9 heart rate, 10 power, 11 cadence, 12 elapsed time, 13 empty, 14 auto row 4 (block else VAM), 15 auto bottom (ghost else ETA). Omitted = default [0,1,2,14,15]. Both modes. The watch falls back to the default per slot for an unknown code and to the full default for a malformed array. Codes mirror domain/watch/WatchFieldLayout.java and garmin/source/FieldLayout.mc.",
      "type": "array",
      "minItems": 5,
      "maxItems": 5,
      "items": { "type": "integer", "minimum": 0, "maximum": 15 }
    }
```

`protocol/examples/route_mode_full.json` — after `"rtl": 8000,` add:

```json
  "lay": [8, 9, 2, 4, 6],
```

`protocol/schema.md` — under "## Wire keys for packed encoding", after the `rtl` bullet, add:

```markdown
- `lay` (both modes, optional): datafield stat-slot layout chosen on the phone ("Horloge-velden"), exactly 5 metric codes for `[left, middle, right, row 4, bottom line]` of the active-climb page. Codes 0–15 as listed in `schema.json` (`WatchFieldLayout.java` / `FieldLayout.mc`). Omitted for the default `[0,1,2,14,15]`, which is the pre-layout screen. The watch uses the default per slot for an unknown code and the full default for a missing or malformed array.
```

and as the first row of the Change log table (below the header separator):

```markdown
| 3       | 2026-10-01 | Added optional top-level `lay` (5 datafield stat-slot metric codes, chosen on the phone). Both modes; omitted for the default layout. Additive, no version bump. |
```

- [ ] **Step 7: Run all protocol + builder tests**

Run: `./gradlew test --tests "nl.paree.climbpro.protocol.*" --tests "nl.paree.climbpro.service.ClimbPayloadBuilder*"`
Expected: PASS. If another test reads `route_mode_full.json` and asserts its exact key set, update that assertion to include `lay`.

- [ ] **Step 8: Commit**

```bash
P="protocol/schema.json protocol/schema.md protocol/examples/route_mode_full.json android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderFieldLayoutTest.java"
git add -- $P
git commit -m "feat: optionele 'lay' (veldindeling) in het datafieldbericht" -- $P
```

---

### Task 4: Telefoon-wiring (sync-worker, watch-requests, app)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java` (builder ~r.84, `wantHash` ~r.185, `buildPayloadJob` ~r.195)
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java` (fields r.30-34, ctors r.36-52, `payloadBuilder()` r.73)
- Modify: `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java` (r.31)
- Test: `android/app/src/test/java/nl/paree/climbpro/service/RouteSyncWorkerWantHashTest.java`

**Interfaces:**
- Consumes: `WatchFieldLayoutStore.load()` (Task 2), `ClimbPayloadBuilder.withFieldLayout` (Task 3).
- Produces: `static String RouteSyncWorker.wantHash(StoredRoute, RiderProfile, GhostTarget, WatchFieldLayout)` (package-private); `WatchRequestHandler(RouteRepository, ConnectIqClient, RiderProfileRepository, ClimbAttemptRepository, WatchFieldLayoutStore)`.

- [ ] **Step 1: Write the failing hash test**

```java
package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.power.GhostTarget;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.watch.WatchFieldLayout;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** A layout change must change the sync hash, or the watch never receives the new layout. */
public class RouteSyncWorkerWantHashTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.sourceHash = "abc";
        r.climbs = new ArrayList<>();
        return r;
    }

    private static GhostTarget ghost() {
        return GhostTarget.NONE;
    }

    @Test
    public void layoutChangeChangesHash() {
        RiderProfile p = new RiderProfile(250, 75, 8);
        String a = RouteSyncWorker.wantHash(route(), p, ghost(), WatchFieldLayout.defaults());
        String b = RouteSyncWorker.wantHash(route(), p, ghost(),
                WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5}));
        assertNotEquals(a, b);
    }

    @Test
    public void sameLayoutSameHash() {
        RiderProfile p = new RiderProfile(250, 75, 8);
        assertEquals(
                RouteSyncWorker.wantHash(route(), p, ghost(), WatchFieldLayout.defaults()),
                RouteSyncWorker.wantHash(route(), p, ghost(), WatchFieldLayout.defaults()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.service.RouteSyncWorkerWantHashTest`
Expected: compilation FAIL (`wantHash` is private / has 3 params).

- [ ] **Step 3: Update `RouteSyncWorker`**

Imports: `nl.paree.climbpro.data.watch.WatchFieldLayoutStore`, `nl.paree.climbpro.domain.watch.WatchFieldLayout`.

Builder (replace the existing `payloadBuilder` declaration):

```java
        // The datafield's slot layout ('lay') rides along in every climb payload; it is part
        // of wantHash, so changing it on the "Horloge-velden" screen triggers a resync.
        WatchFieldLayout fieldLayout = new WatchFieldLayoutStore(ctx).load();
        ClimbPayloadBuilder  payloadBuilder  = new ClimbPayloadBuilder(mapper)
                .withIntensityZones(profile).withFtpWatts(profile.ftpWatts)
                .withFieldLayout(fieldLayout);
```

Pass `fieldLayout` as a new last argument of `buildPayloadJob(...)`, add parameter `WatchFieldLayout fieldLayout` to its signature, and change both `wantHash(route, profile, ghost)` calls inside it to `wantHash(route, profile, ghost, fieldLayout)`.

`wantHash` becomes package-private with the extra parameter (extend the javadoc with one sentence: "and the datafield slot layout, which only lives in preferences"):

```java
    static String wantHash(StoredRoute route, nl.paree.climbpro.domain.power.RiderProfile profile,
                           nl.paree.climbpro.domain.power.GhostTarget ghost,
                           WatchFieldLayout fieldLayout) {
        return route.sourceHash + "|" + profile.signature()
                + "|" + SegmentTargetOverrideMerger.signature(route)
                + "|" + ghost.signature()
                + "|" + nl.paree.climbpro.domain.power.IntervalBlock.signature(route)
                + "|" + fieldLayout.serialize();
    }
```

(Gevolg, bewust: het hashformaat verandert, dus de actieve route synct één keer opnieuw na de update.)

- [ ] **Step 4: Update `WatchRequestHandler` and `ClimbProApplication`**

Field + constructors in `WatchRequestHandler`:

```java
    private final WatchFieldLayoutStore layoutStore;

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient,
                               RiderProfileRepository riderRepo, ClimbAttemptRepository attemptRepo) {
        this(routeRepo, connectIqClient, riderRepo, attemptRepo, null);
    }

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient,
                               RiderProfileRepository riderRepo, ClimbAttemptRepository attemptRepo,
                               WatchFieldLayoutStore layoutStore) {
        this.routeRepo       = routeRepo;
        this.connectIqClient = connectIqClient;
        this.mapper          = new ObjectMapper();
        this.riderRepo       = riderRepo;
        this.attemptRepo     = attemptRepo;
        this.layoutStore     = layoutStore;
    }
```

(The existing 2- and 3-arg constructors keep delegating to the 4-arg one.) `payloadBuilder()`:

```java
    private ClimbPayloadBuilder payloadBuilder() {
        ClimbPayloadBuilder builder = new ClimbPayloadBuilder(mapper)
                .withFieldLayout(layoutStore != null ? layoutStore.load() : null);
        return riderRepo != null ? builder.withIntensityZones(riderRepo.load()) : builder;
    }
```

Update its javadoc: "…and the datafield slot layout ('lay') when a layout store is wired up."

`ClimbProApplication` — add the fifth argument:

```java
        ciqClient.setWatchRequestHandler(new WatchRequestHandler(
                routeRepo, ciqClient,
                new nl.paree.climbpro.data.rider.RiderProfileRepository(this),
                new nl.paree.climbpro.data.route.ClimbAttemptRepository(this),
                new nl.paree.climbpro.data.watch.WatchFieldLayoutStore(this)));
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests nl.paree.climbpro.service.RouteSyncWorkerWantHashTest --tests "nl.paree.climbpro.connectiq.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
P="android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java android/app/src/test/java/nl/paree/climbpro/service/RouteSyncWorkerWantHashTest.java"
git add -- $P
git commit -m "feat: veldindeling mee in sync en watch-requests" -- $P
```

---

### Task 5: Horloge — `FieldLayout`-module + parsen

**Files:**
- Create: `garmin/source/FieldLayout.mc`
- Modify: `garmin/source/ClimbData.mc` (vars bij `currentPower` ~r.124; `initialize()`)
- Modify: `garmin/source/CommListener.mc` (`onMessage`, na de `rtl`-regels)
- Modify: `garmin/source/ClimbProView.mc` (verwijder `formatDist`/`formatEta` ~r.686-702; 4 aanroepen → `FieldLayout.`)
- Test: `garmin/test/FieldLayoutTest.mc`

**Interfaces:**
- Produces (Monkey C module `FieldLayout`): consts `SLOT_COUNT`, `REM_DIST`…`AUTO_BOTTOM`, `MAX_CODE`; `defaults()` → Array(5); `parse(raw)` → Array(5); `metricText(code, v, wide)` → String of `null` (null voor GHOST/BLOCK/AUTO_*; `""` voor EMPTY); `formatDist(m)`, `formatEta(s)`, `formatGrad(fp)`, `formatElapsed(ms)`.
  `v` is een Dictionary met symbol-keys `:remaining, :remElev, :curGrad, :avgGrad, :hasVam, :vamAvg, :vamPeak, :etaSec, :speedMps, :hr, :power, :cadence, :timerMs`.
- Produces (`ClimbData`): `var layout`, `var currentHeartRate = null`, `var currentCadence = null`.

- [ ] **Step 1: Write the tests**

```monkeyc
using Toybox.Test;
using Toybox.Application as App;

// Phone-chosen stat-slot layout ('lay'): parsing guards and the pure text formatter.

function fieldLayoutVals() {
    return {
        :remaining => 1500, :remElev => 120, :curGrad => 74, :avgGrad => 61,
        :hasVam => true, :vamAvg => 900, :vamPeak => 1100, :etaSec => 200,
        :speedMps => 5.0, :hr => null, :power => null, :cadence => null,
        :timerMs => 3725000
    };
}

function fieldLayoutPayload(lay) {
    var p = {
        "v" => 3, "mode" => "route", "routeId" => "lay", "name" => "Lay",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 5] }
        ]
    };
    if (lay != null) { p.put("lay", lay); }
    return p;
}

function fieldLayoutData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

(:test)
function fieldLayout_parse_missingGivesDefaults(logger) {
    var l = FieldLayout.parse(null);
    Test.assertEqual(l.size(), 5);
    Test.assertEqual(l[0], FieldLayout.REM_DIST);
    Test.assertEqual(l[1], FieldLayout.REM_ELEV);
    Test.assertEqual(l[2], FieldLayout.CUR_GRAD);
    Test.assertEqual(l[3], FieldLayout.AUTO_ROW4);
    Test.assertEqual(l[4], FieldLayout.AUTO_BOTTOM);
    return true;
}

(:test)
function fieldLayout_parse_wrongShapeGivesDefaults(logger) {
    Test.assertEqual(FieldLayout.parse([1, 2])[0], FieldLayout.REM_DIST);
    Test.assertEqual(FieldLayout.parse("8,9")[4], FieldLayout.AUTO_BOTTOM);
    return true;
}

(:test)
function fieldLayout_parse_badElementFallsBackPerSlot(logger) {
    var l = FieldLayout.parse([8, 99, -1, "x", 12]);
    Test.assertEqual(l[0], 8);
    Test.assertEqual(l[1], FieldLayout.REM_ELEV);
    Test.assertEqual(l[2], FieldLayout.CUR_GRAD);
    Test.assertEqual(l[3], FieldLayout.AUTO_ROW4);
    Test.assertEqual(l[4], 12);
    return true;
}

(:test)
function fieldLayout_message_setsLayout(logger) {
    var d = fieldLayoutData();
    new PhoneMessageCallback().onMessage(fieldLayoutPayload([8, 9, 10, 6, 5]));
    Test.assertEqual(d.layout[0], 8);
    Test.assertEqual(d.layout[4], 5);
    return true;
}

(:test)
function fieldLayout_resyncWithoutLay_restoresDefaults(logger) {
    var d = fieldLayoutData();
    new PhoneMessageCallback().onMessage(fieldLayoutPayload([8, 9, 10, 6, 5]));
    new PhoneMessageCallback().onMessage(fieldLayoutPayload(null));
    Test.assertEqual(d.layout[0], FieldLayout.REM_DIST);
    Test.assertEqual(d.layout[4], FieldLayout.AUTO_BOTTOM);
    return true;
}

(:test)
function fieldLayout_metricText_climbValues(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.REM_DIST, v, false), "1.5km");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.REM_ELEV, v, false), "120m↑");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.CUR_GRAD, v, false), "7.4%");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.AVG_GRAD, v, false), "~6.1%");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.ETA, v, true), "ETA 3:20");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.ELAPSED, v, true), "1:02:05");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.SPEED, v, true), "18.0km/u");
    return true;
}

(:test)
function fieldLayout_metricText_vamWideVsCompact(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.VAM, v, true), "VAM 900/1100");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.VAM, v, false), "900");
    v[:hasVam] = false;
    Test.assertEqual(FieldLayout.metricText(FieldLayout.VAM, v, true), "--");
    return true;
}

(:test)
function fieldLayout_metricText_missingSensorsShowDashes(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.HEART_RATE, v, false), "--");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.POWER, v, false), "--");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.CADENCE, v, false), "--");
    v[:hr] = 142;
    v[:power] = 252;
    v[:cadence] = 88;
    Test.assertEqual(FieldLayout.metricText(FieldLayout.HEART_RATE, v, false), "142bpm");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.POWER, v, false), "252W");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.CADENCE, v, false), "88rpm");
    return true;
}

(:test)
function fieldLayout_metricText_specialCodes(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.EMPTY, v, true), "");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.GHOST, v, true), null);
    Test.assertEqual(FieldLayout.metricText(FieldLayout.BLOCK, v, true), null);
    Test.assertEqual(FieldLayout.metricText(FieldLayout.AUTO_ROW4, v, true), null);
    Test.assertEqual(FieldLayout.metricText(FieldLayout.AUTO_BOTTOM, v, true), null);
    return true;
}

(:test)
function fieldLayout_formatEta_negativeIsPlaceholder(logger) {
    Test.assertEqual(FieldLayout.formatEta(-1), "--:--");
    return true;
}
```

- [ ] **Step 2: Write `garmin/source/FieldLayout.mc`**

```monkeyc
using Toybox.Lang;

// Stat-slot layout for the datafield's "HUIDIGE KLIM" page, chosen in the phone app and sent
// as the optional top-level 'lay' key. Codes must match domain/watch/WatchFieldLayout.java and
// protocol/schema.json. Kept free of Dc/Properties access so tests can call it directly.
module FieldLayout {

    const SLOT_COUNT = 5;

    const REM_DIST = 0;
    const REM_ELEV = 1;
    const CUR_GRAD = 2;
    const AVG_GRAD = 3;
    const VAM = 4;
    const ETA = 5;
    const GHOST = 6;
    const BLOCK = 7;
    const SPEED = 8;
    const HEART_RATE = 9;
    const POWER = 10;
    const CADENCE = 11;
    const ELAPSED = 12;
    const EMPTY = 13;
    const AUTO_ROW4 = 14;     // interval block, else VAM (the pre-layout row 4)
    const AUTO_BOTTOM = 15;   // vs PR / vs plan, else ETA (the pre-layout bottom line)
    const MAX_CODE = 15;

    // [left, middle, right, row 4, bottom line] -- the screen as it was before 'lay'.
    function defaults() {
        return [REM_DIST, REM_ELEV, CUR_GRAD, AUTO_ROW4, AUTO_BOTTOM];
    }

    // Missing, non-array or wrong-length 'lay' -> full default; a bad element -> that
    // slot's default. Never throws: a malformed payload must not blank the datafield.
    function parse(raw) {
        var out = defaults();
        if (raw == null || !(raw instanceof Toybox.Lang.Array) || raw.size() != SLOT_COUNT) {
            return out;
        }
        for (var i = 0; i < SLOT_COUNT; i++) {
            var c = raw[i];
            if (c != null && c instanceof Toybox.Lang.Number && c >= 0 && c <= MAX_CODE) {
                out[i] = c;
            }
        }
        return out;
    }

    // Text for the plain metrics. GHOST, BLOCK and the AUTO_* codes need colour or a
    // fallback and are drawn by the view, so they return null; EMPTY returns "".
    // v: values the view gathers once per redraw. wide: centred slot (middle, row 4,
    // bottom line) -- the narrow side slots get the compact form.
    function metricText(code, v, wide) {
        if (code == REM_DIST) { return formatDist(v[:remaining]); }
        if (code == REM_ELEV) { return v[:remElev] + "m↑"; }
        if (code == CUR_GRAD) { return formatGrad(v[:curGrad]); }
        if (code == AVG_GRAD) { return "~" + formatGrad(v[:avgGrad]); }
        if (code == VAM) {
            if (!v[:hasVam]) { return "--"; }
            return wide ? "VAM " + v[:vamAvg] + "/" + v[:vamPeak] : "" + v[:vamAvg];
        }
        if (code == ETA) { return "ETA " + formatEta(v[:etaSec]); }
        if (code == SPEED) { return (v[:speedMps] * 3.6).format("%.1f") + "km/u"; }
        if (code == HEART_RATE) { return v[:hr] == null ? "--" : v[:hr] + "bpm"; }
        if (code == POWER) { return v[:power] == null ? "--" : v[:power].toNumber() + "W"; }
        if (code == CADENCE) { return v[:cadence] == null ? "--" : v[:cadence] + "rpm"; }
        if (code == ELAPSED) { return formatElapsed(v[:timerMs]); }
        if (code == EMPTY) { return ""; }
        return null;
    }

    function formatDist(meters) {
        if (meters >= 1000) {
            var km = meters / 1000;
            var hm = (meters % 1000) / 100;
            return km + "." + hm + "km";
        }
        return meters + "m";
    }

    // Whole-seconds ETA as "m:ss"; a negative value (speed too low/unknown, see
    // ClimbData.etaSeconds) renders as a placeholder rather than a bogus duration.
    function formatEta(seconds) {
        if (seconds < 0) { return "--:--"; }
        var m = seconds / 60;
        var s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : "" + s);
    }

    // Fixed-point gradient (pct x 10) as "7.4%".
    function formatGrad(fp) {
        var whole = fp / 10;
        var frac = fp % 10;
        if (frac < 0) { frac = -frac; }
        return whole + "." + frac + "%";
    }

    // Activity timer (ms) as "h:mm:ss".
    function formatElapsed(ms) {
        var total = ms / 1000;
        var h = total / 3600;
        var m = (total % 3600) / 60;
        var s = total % 60;
        return h + ":" + (m < 10 ? "0" + m : "" + m) + ":" + (s < 10 ? "0" + s : "" + s);
    }
}
```

- [ ] **Step 3: Wire `ClimbData` and `CommListener`**

`ClimbData.mc`, next to `currentPower`:

```monkeyc
    var currentHeartRate = null;  // most recent Activity.Info.currentHeartRate (bpm); null = no sensor
    var currentCadence = null;    // most recent Activity.Info.currentCadence (rpm); null = no sensor
    var layout;                   // stat-slot metric codes for the active-climb page ('lay')
```

In `initialize()`, as the first statement: `layout = FieldLayout.defaults();`

`CommListener.mc` `onMessage`, directly after the `data.routeTotalLen = …` line:

```monkeyc
        // Every payload sets the layout: one without 'lay' (default chosen on the phone)
        // must reset an earlier custom layout, not keep it.
        data.layout = FieldLayout.parse(msg.get("lay"));
```

- [ ] **Step 4: Move the formatters out of the view**

In `ClimbProView.mc`: delete `hidden function formatDist(meters)` and `hidden function formatEta(seconds)` (with the `formatEta` comment), and replace every `formatDist(` call with `FieldLayout.formatDist(` and `formatEta(` with `FieldLayout.formatEta(` (`grep -n "formatDist(\|formatEta(" garmin/source/ClimbProView.mc` — 4 call sites).

- [ ] **Step 5: Run the JVM-side guard**

Run (in `android/`): `./gradlew test --tests "nl.paree.climbpro.protocol.*"`
Expected: PASS (includes `MonkeyCSourceGuard`: braces balanced, every `(:test)` ends with `return true;`). Do **not** run the simulator (user preference); mention in the PR that `pwsh -File tools/run-monkeyc-tests.ps1` was not run.

- [ ] **Step 6: Commit**

```bash
P="garmin/source/FieldLayout.mc garmin/source/ClimbData.mc garmin/source/CommListener.mc garmin/source/ClimbProView.mc garmin/test/FieldLayoutTest.mc"
git add -- $P
git commit -m "feat(watch): FieldLayout-module en 'lay' parsen" -- $P
```

---

### Task 6: Horloge — vakken tekenen

**Files:**
- Modify: `garmin/source/ClimbProView.mc` (`compute()` sensor-reads ~r.118-122; `drawActiveClimb` stats-gedeelte ~r.344-425; `drawIntervalBlock` ~r.430; `drawGhostDelta` ~r.449)
- Test: `garmin/test/FieldLayoutViewTest.mc`

**Interfaces:**
- Consumes: `FieldLayout.*`, `data.layout`, `data.currentHeartRate`, `data.currentCadence` (Task 5).
- Produces: `hidden function drawSlot(dc, data, ci, code, x, y, font, justify, wide, color, vals)`, `hidden function drawGhost(dc, data, ci, x, y, font, justify, wide)` → Boolean; `drawIntervalBlock(dc, data, ci, x, y, font, justify)`; `drawGhostDelta(dc, x, y, font, justify, deltaSec, suffix)`; `function slotShowsBlock(code, hasBlock)` (niet hidden, voor tests).

- [ ] **Step 1: Write the tests**

```monkeyc
using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
using Toybox.Graphics as Gfx;

// Rendering every metric code in every slot must never throw, with or without
// large-text mode. Pixel output can't be asserted (see LargeTextModeTest).

function fieldLayoutViewDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

function fieldLayoutViewRender(code, large) {
    try { Properties.setValue("largeTextMode", large); } catch (e) { }
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    var d = app.climbData;
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "lay" + code, "name" => "Lay",
        "lay" => [code, code, code, code, code],
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 5],
              "tsec" => [80, 90], "vam" => [900, 1100, 950, 1200], "ib" => [250, 240, 260] }
        ]
    });
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 1;
    d.progressInClimb = 500;
    d.climbStartTimerMs = 0;
    new ClimbProView().onUpdate(fieldLayoutViewDc());
}

(:test)
function fieldLayout_onUpdate_everyCodeInEverySlot_rendersWithoutThrow(logger) {
    for (var c = 0; c <= FieldLayout.MAX_CODE; c++) {
        fieldLayoutViewRender(c, false);
        fieldLayoutViewRender(c, true);
    }
    try { Properties.setValue("largeTextMode", false); } catch (e) { }
    return true;
}

(:test)
function fieldLayout_slotShowsBlock(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.slotShowsBlock(FieldLayout.BLOCK, false), true);
    Test.assertEqual(v.slotShowsBlock(FieldLayout.AUTO_ROW4, true), true);
    Test.assertEqual(v.slotShowsBlock(FieldLayout.AUTO_ROW4, false), false);
    Test.assertEqual(v.slotShowsBlock(FieldLayout.VAM, true), false);
    return true;
}
```

- [ ] **Step 2: Read the extra sensors in `compute()`**

Directly after the existing `data.currentPower = …` statement:

```monkeyc
        data.currentHeartRate = (info != null && info has :currentHeartRate && info.currentHeartRate != null)
                ? info.currentHeartRate : null;
        data.currentCadence = (info != null && info has :currentCadence && info.currentCadence != null)
                ? info.currentCadence : null;
```

- [ ] **Step 3: Replace the stats part of `drawActiveClimb`**

Keep everything up to and including the `curGrad` computation (`var curGrad = 0; if (data.activeSegmentIndex … }`). Delete the old `gradWhole`/`gradFrac` lines and everything after them up to the end of `drawActiveClimb` (stats row, VAM/interval-block line, ghost/ETA bottom line), and put in their place:

```monkeyc
        // Values every slot may need, gathered once per redraw for FieldLayout.metricText.
        var vals = {
            :remaining => remaining, :remElev => remElev, :curGrad => curGrad,
            :avgGrad => data.climbAvgGrad[ci],
            :etaSec => data.etaSeconds(remaining, data.currentSpeedMps),
            :hasVam => false, :vamAvg => 0, :vamPeak => 0,
            :speedMps => data.currentSpeedMps, :hr => data.currentHeartRate,
            :power => data.currentPower, :cadence => data.currentCadence,
            :timerMs => lastGhostTimerMs
        };
        if (data.hasVam[ci] && data.activeSegmentIndex >= 0
                && data.activeSegmentIndex < data.segCount[ci]) {
            vals[:hasVam] = true;
            vals[:vamAvg] = data.segVamAvg[ci][data.activeSegmentIndex];
            vals[:vamPeak] = data.segVamPeak[ci][data.activeSegmentIndex];
        }

        // Five phone-chosen slots ('lay'); the default layout is the pre-layout screen.
        // Large-text mode (#82) drops the middle slot and row 4 so the rest can be bigger,
        // except an interval block in row 4: that is the rider's training target (#180).
        var lay = data.layout;
        var sf = statFont(large);
        drawSlot(dc, data, ci, lay[0], 32, statsY, sf, Gfx.TEXT_JUSTIFY_LEFT, false,
            Gfx.COLOR_BLACK, vals);
        if (showSecondaryStat(large)) {
            drawSlot(dc, data, ci, lay[1], w / 2, statsY, sf, Gfx.TEXT_JUSTIFY_CENTER, true,
                Gfx.COLOR_BLACK, vals);
        }
        drawSlot(dc, data, ci, lay[2], w - 32, statsY, sf, Gfx.TEXT_JUSTIFY_RIGHT, false,
            Gfx.COLOR_BLACK, vals);
        if (showSecondaryStat(large) || slotShowsBlock(lay[3], data.hasBlock[ci])) {
            drawSlot(dc, data, ci, lay[3], w / 2, (h * 0.80).toNumber(), Gfx.FONT_XTINY,
                Gfx.TEXT_JUSTIFY_CENTER, true, Gfx.COLOR_DK_GRAY, vals);
        }
        drawSlot(dc, data, ci, lay[4], w / 2, (h * 0.88).toNumber(), sf,
            Gfx.TEXT_JUSTIFY_CENTER, true, Gfx.COLOR_DK_GRAY, vals);
    }

    // True when this slot code ends up drawing the interval block for a climb with/without one.
    function slotShowsBlock(code, hasBlock) {
        return code == FieldLayout.BLOCK || (code == FieldLayout.AUTO_ROW4 && hasBlock);
    }

    // Draws one stat slot. AUTO_ROW4 / AUTO_BOTTOM resolve to their pre-layout fallbacks;
    // GHOST and BLOCK keep their own colours and show "--" when the climb has neither.
    hidden function drawSlot(dc, data, ci, code, x, y, font, justify, wide, color, vals) {
        if (code == FieldLayout.AUTO_ROW4) {
            if (data.hasBlock[ci]) {
                code = FieldLayout.BLOCK;
            } else if (vals[:hasVam]) {
                code = FieldLayout.VAM;
            } else {
                return;
            }
        } else if (code == FieldLayout.AUTO_BOTTOM) {
            if (drawGhost(dc, data, ci, x, y, font, justify, wide)) {
                return;
            }
            code = FieldLayout.ETA;
        }

        var text = null;
        if (code == FieldLayout.GHOST) {
            if (drawGhost(dc, data, ci, x, y, font, justify, wide)) {
                return;
            }
            text = "--";
        } else if (code == FieldLayout.BLOCK) {
            if (data.hasBlock[ci]) {
                drawIntervalBlock(dc, data, ci, x, y, font, justify);
                return;
            }
            text = "--";
        } else {
            text = FieldLayout.metricText(code, vals, wide);
        }
        if (text != null && text.length() > 0) {
            dc.setColor(color, Gfx.COLOR_TRANSPARENT);
            dc.drawText(x, y, font, text, justify);
        }
    }

    // Live delta against the per-segment PR ("vs PR"), else the manual pacing plan
    // ("vs plan"); PR takes priority as the always-on repeat-climb signal. Returns false
    // (nothing drawn) before the climb timer started or without a reference.
    hidden function drawGhost(dc, data, ci, x, y, font, justify, wide) {
        if (data.climbStartTimerMs < 0) {
            return false;
        }
        var actual = (lastGhostTimerMs - data.climbStartTimerMs) / 1000.0;
        if (data.hasRefTargets[ci]) {
            var ref = data.refSecondsAt();
            if (ref >= 0) {
                drawGhostDelta(dc, x, y, font, justify, (actual - ref).toNumber(),
                    wide ? " vs PR" : "");
                return true;
            }
        } else if (data.hasTargets[ci]) {
            var target = data.targetSecondsAt();
            if (target >= 0) {
                drawGhostDelta(dc, x, y, font, justify, (actual - target).toNumber(),
                    wide ? " vs plan" : "");
                return true;
            }
        }
        return false;
```

(The final `}` of the old `drawActiveClimb` now closes `drawGhost`.)

- [ ] **Step 4: Generalise `drawIntervalBlock` and `drawGhostDelta`**

```monkeyc
    // Interval-block line (issue #180): "Doel 266-280W" without a power meter, otherwise
    // "252W 266-280" coloured blue (under), green (in band) or red (over).
    hidden function drawIntervalBlock(dc, data, ci, x, y, font, justify) {
        var band = data.blockLow[ci] + "-" + data.blockHigh[ci];
        var zone = data.blockZone(ci, data.currentPower);
        dc.setColor(intervalZoneColor(zone), Gfx.COLOR_TRANSPARENT);
        var text = (zone == data.ZONE_NONE)
            ? "Doel " + band + "W"
            : data.currentPower.toNumber() + "W " + band;
        dc.drawText(x, y, font, text, justify);
    }
```

```monkeyc
    hidden function drawGhostDelta(dc, x, y, font, justify, deltaSec, suffix) {
        if (deltaSec > 0) {
            dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
            dc.drawText(x, y, font, "+" + deltaSec + "s" + suffix, justify);
        } else {
            dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
            dc.drawText(x, y, font, deltaSec + "s" + suffix, justify);
        }
    }
```

Check `grep -n "drawIntervalBlock(\|drawGhostDelta(" garmin/source/*.mc` — only the new call sites may remain. Default output is unchanged: the default bottom line still reads "+12s vs PR" (suffix now carries its leading space).

- [ ] **Step 5: Run the JVM-side guard**

Run (in `android/`): `./gradlew test --tests "nl.paree.climbpro.protocol.*"`
Expected: PASS. Simulator not run (user preference).

- [ ] **Step 6: Commit**

```bash
P="garmin/source/ClimbProView.mc garmin/test/FieldLayoutViewTest.mc"
git add -- $P
git commit -m "feat(watch): vakken op HUIDIGE KLIM volgen de gekozen indeling" -- $P
```

---

### Task 7: Telefoon — scherm "Horloge-velden"

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/settings/WatchFieldLayoutActivity.java`
- Create: `android/app/src/main/res/layout/activity_watch_field_layout.xml`
- Modify: `android/app/src/main/res/layout/activity_settings.xml` (laatste kind van de "Watch mode"-kaart, ~r.47)
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsActivity.java` (~r.238)
- Modify: `android/app/src/main/AndroidManifest.xml` (na `IntervalsIcuSettingsActivity`, ~r.446)

**Interfaces:**
- Consumes: `WatchFieldLayout`, `WatchFieldLayoutStore` (Tasks 1-2), `SyncScheduler.triggerImmediateSync(Context)`, `((ClimbProApplication) getApplication()).connectIqClient().isConnected()`.

- [ ] **Step 1: Layout XML**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/color_bg">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content">
        <androidx.appcompat.widget.Toolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="@color/color_bg"
            android:titleTextColor="@color/color_text_primary"
            android:title="Horloge-velden"
            app:popupTheme="@style/ThemeOverlay.Material3.Dark"/>
    </com.google.android.material.appbar.AppBarLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@color/color_bg"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp">

            <LinearLayout
                android:id="@+id/slot_container"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:background="@drawable/bg_card"
                android:padding="16dp"
                android:layout_marginBottom="16dp">

                <TextView
                    style="@style/TextAppearance.ClimbPro.Label"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Pagina HUIDIGE KLIM"
                    android:layout_marginBottom="10dp"/>
            </LinearLayout>

            <Button
                android:id="@+id/btn_save_layout"
                style="@style/Widget.ClimbPro.Button.Outline"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Opslaan"/>

            <Button
                android:id="@+id/btn_reset_layout"
                style="@style/Widget.ClimbPro.Button.Outline"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="Standaard herstellen"/>
        </LinearLayout>
    </ScrollView>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 2: Activity**

```java
package nl.paree.climbpro.ui.settings;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.R;
import nl.paree.climbpro.data.watch.WatchFieldLayoutStore;
import nl.paree.climbpro.domain.watch.WatchFieldLayout;
import nl.paree.climbpro.service.SyncScheduler;

/**
 * Lets the rider choose what each of the five stat slots on the datafield's "HUIDIGE KLIM"
 * page shows. Saving stores the layout and starts a sync; the layout rides along in the
 * climb payload ('lay'), so a watch that is not connected gets it on the next sync.
 */
public final class WatchFieldLayoutActivity extends AppCompatActivity {

    private final Spinner[] spinners = new Spinner[WatchFieldLayout.SLOT_COUNT];
    private WatchFieldLayoutStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch_field_layout);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        store = new WatchFieldLayoutStore(this);
        String[] labels = new String[WatchFieldLayout.metricCount()];
        for (int c = 0; c < labels.length; c++) labels[c] = WatchFieldLayout.metricLabel(c);

        LinearLayout container = findViewById(R.id.slot_container);
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            TextView title = new TextView(this);
            title.setText(WatchFieldLayout.slotLabel(s));
            title.setTextColor(getColor(R.color.color_text_primary));
            container.addView(title);

            Spinner spinner = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            container.addView(spinner);
            spinners[s] = spinner;
        }
        show(store.load());

        findViewById(R.id.btn_reset_layout).setOnClickListener(v -> show(WatchFieldLayout.defaults()));
        findViewById(R.id.btn_save_layout).setOnClickListener(v -> save());
    }

    private void show(WatchFieldLayout layout) {
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            spinners[s].setSelection(layout.code(s));
        }
    }

    private void save() {
        WatchFieldLayout layout = WatchFieldLayout.defaults();
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            layout = layout.withCode(s, spinners[s].getSelectedItemPosition());
        }
        store.save(layout);
        SyncScheduler.triggerImmediateSync(this);
        boolean connected = ((ClimbProApplication) getApplication()).connectIqClient().isConnected();
        Toast.makeText(this, connected
                ? "Opgeslagen — wordt naar het horloge gestuurd"
                : "Opgeslagen — wordt meegestuurd bij de volgende sync",
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
```

(Verify `R.color.color_text_primary` exists — it is used by the toolbar XML; `connectIqClient()` is the accessor used in `RouteSyncWorker`.)

- [ ] **Step 3: Entry point in Settings + manifest**

`activity_settings.xml` — as the last child inside the "Watch mode" card's `LinearLayout` (the card whose label reads `Watch mode`):

```xml
                <Button
                    android:id="@+id/btn_watch_field_layout"
                    style="@style/Widget.ClimbPro.Button.Outline"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:text="Horloge-velden"/>
```

`SettingsActivity.java`, next to the `btnIntervalsIcu` listener:

```java
        binding.btnWatchFieldLayout.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, WatchFieldLayoutActivity.class)));
```

`AndroidManifest.xml`, after the `IntervalsIcuSettingsActivity` entry:

```xml
        <activity
            android:name=".ui.settings.WatchFieldLayoutActivity"
            android:exported="false"
            android:parentActivityName=".ui.settings.SettingsActivity"/>
```

- [ ] **Step 4: Build + full test suite**

Run (in `android/`): `./gradlew assembleDebug test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
P="android/app/src/main/java/nl/paree/climbpro/ui/settings/WatchFieldLayoutActivity.java android/app/src/main/res/layout/activity_watch_field_layout.xml android/app/src/main/res/layout/activity_settings.xml android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsActivity.java android/app/src/main/AndroidManifest.xml"
git add -- $P
git commit -m "feat: scherm Horloge-velden in Instellingen" -- $P
```

---

### Task 8: Documentatie

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: ARCHITECTURE.md** — in the section that describes the datafield payload/wire keys (search for `rtl` near r.208), add a paragraph:

```markdown
**Veldindeling (`lay`).** De gebruiker kiest op de telefoon (Instellingen → Horloge-velden) welke
waarde elk van de vijf vakken op de pagina HUIDIGE KLIM toont. De keuze staat in
`SharedPreferences` (`WatchFieldLayoutStore`) en gaat als optionele top-level sleutel `lay`
(5 metriekcodes) mee in elk route-, radius- en enkele-klimbericht; bij de default wordt niets
verstuurd. De layout zit in de sync-hash van `RouteSyncWorker`, zodat een wijziging een resync
triggert. Op het horloge parseert `FieldLayout.parse` de array (fallback per vak) en tekent
`ClimbProView.drawSlot` elk vak. Codes: `WatchFieldLayout.java` = `FieldLayout.mc` = `schema.json`.
```

- [ ] **Step 2: README.md** — in the feature list, add:

```markdown
- **Horloge-velden** — kies in Instellingen per vak wat de datafield op de pagina HUIDIGE KLIM toont (rest-afstand, hoogtemeters, stijging, VAM, ETA, PR/plan, intervalblok, snelheid, hartslag, vermogen, cadans, tijd of leeg).
```

- [ ] **Step 3: Commit**

```bash
P="Documentation/ARCHITECTURE.md README.md"
git add -- $P
git commit -m "docs: horloge-velden in architectuur en README" -- $P
```

---

### Afronding

- [ ] `./gradlew test` (in `android/`) volledig groen.
- [ ] Push `feat/watch-field-layout`, PR naar **`staging`**; vermeld dat de Monkey C-simulatortests niet gedraaid zijn.
