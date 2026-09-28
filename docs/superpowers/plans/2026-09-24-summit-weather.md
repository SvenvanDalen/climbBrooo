# Weather at the Summit (issue #246) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Follow superpowers:test-driven-development for every code step.

**Goal:** On a climb's detail screen, show temperature, feels-like temperature, wind and rain chance at the summit next to the valley at the climb's foot, for now and in 3 hours, with a clothing tip.

**Architecture:** Weather comes from **Open-Meteo** (free, no API key, takes an `elevation` parameter and applies the lapse-rate correction itself), fetched once for the climb's foot and once for its top. Everything that can be pure is pure: the endpoint geometry (`ClimbEndpoints`), the JSON parser plus hourly lookup (`HourlyForecast`), and the comparison with the clothing tip (`SummitWeather`). A thin OkHttp client (`OpenMeteoClient`) and a dialog in `ClimbDetailActivity` sit on top.

**Tech Stack:** Java 17 (Android, minSdk 26, compileSdk 34), OkHttp 4.12 and Jackson 2.17 (both already dependencies), JUnit 4.

**Spec:** GitHub issue #246 ("Toon temperatuur en wind op de top van een klim, naast de waarden in het dal. Op hoogte kan het veel kouder zijn; helpt bij kledingkeuze. Telefoon-only, weer-API met hoogtecorrectie. Geen wire-format wijziging. Bounded — vereist een weer-API.")

## Global Constraints

- Phone-only: no changes under `protocol/`, `garmin*/`, or to `ClimbPayloadBuilder`.
- Network is opportunistic: offline or HTTP failure shows a message, never crashes, never blocks the UI thread.
- No API key, no new dependency (`INTERNET` permission already exists).
- Java, not Kotlin. minSdk 26 without desugaring: do NOT use `List.of`/`Set.of`/`Map.of`.
- Dutch UI text; decimal comma (`Locale("nl")`).
- Build/test (from `android/`): `./gradlew :app:testDebugUnitTest :app:assembleDebug -Djavax.net.ssl.trustStoreType=Windows-ROOT --console=plain`
- Branch from `origin/main` as `feat/summit-weather`; PR ends with `Closes #246` + Claude Code footer; commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- Route stored without elevations or distances → the elevation parameter is left out (Open-Meteo then uses its own terrain model) and top coordinates fall back to the climb start.
- `precipitation_probability` entries that are JSON `null` → treated as unknown, shown as "regenkans onbekend", not 0 %.
- The requested hour is outside the forecast window → the "over 3 uur" line is skipped, not an exception.
- Strong wind on top (≥ 30 km/h) → the tip warns about it even when it is warm.
- No connection / HTTP 5xx → the dialog says "Weer ophalen mislukt" with the reason.
(The first four are pinned by tests in Tasks 1–3; the failure path is covered by `OpenMeteoClient` throwing `IOException`, handled in Task 4.)

---

### Task 1: `ClimbEndpoints` (foot/top position and elevation)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/weather/ClimbEndpoints.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/weather/ClimbEndpointsTest.java`

**Interfaces:**
- Consumes: `StoredRoute` (`lats`, `lons`, `elevations`, `distances`), `StoredClimb` (`startDistance`, `endDistance`, `startLat`, `startLon`).
- Produces: `public static Point foot(StoredRoute r, StoredClimb c)`, `public static Point top(StoredRoute r, StoredClimb c)`; `public static final class Point { public final double lat, lon, elevationM; }` where `elevationM` is `Double.NaN` when unknown.

- [ ] **Step 1: Failing tests**

```java
package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

public class ClimbEndpointsTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.lats = new double[]{50.0, 50.01, 50.02};
        r.lons = new double[]{5.0, 5.0, 5.0};
        r.distances = new double[]{0, 1_000, 2_000};
        r.elevations = new double[]{100, 150, 250};
        return r;
    }

    private static StoredClimb climb(int start, int end) {
        StoredClimb c = new StoredClimb();
        c.startDistance = start;
        c.endDistance = end;
        c.startLat = 49.0;
        c.startLon = 4.0;
        return c;
    }

    @Test public void interpolatesFootAndTop() {
        ClimbEndpoints.Point foot = ClimbEndpoints.foot(route(), climb(500, 2_000));
        ClimbEndpoints.Point top = ClimbEndpoints.top(route(), climb(500, 2_000));
        assertEquals(50.005, foot.lat, 1e-9);
        assertEquals(125, foot.elevationM, 1e-9);
        assertEquals(50.02, top.lat, 1e-9);
        assertEquals(250, top.elevationM, 1e-9);
    }

    @Test public void clampsBeyondRouteEnd() {
        assertEquals(250, ClimbEndpoints.top(route(), climb(0, 9_999)).elevationM, 1e-9);
    }

    @Test public void missingGeometryFallsBackToClimbStartWithUnknownElevation() {
        StoredRoute r = new StoredRoute();
        ClimbEndpoints.Point top = ClimbEndpoints.top(r, climb(0, 1_000));
        assertEquals(49.0, top.lat, 1e-9);
        assertEquals(4.0, top.lon, 1e-9);
        assertTrue(Double.isNaN(top.elevationM));
    }

    @Test public void missingElevationsOnlyLeavesElevationUnknown() {
        StoredRoute r = route();
        r.elevations = null;
        ClimbEndpoints.Point foot = ClimbEndpoints.foot(r, climb(1_000, 2_000));
        assertEquals(50.01, foot.lat, 1e-9);
        assertTrue(Double.isNaN(foot.elevationM));
    }
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "nl.paree.climbpro.domain.weather.ClimbEndpointsTest" -Djavax.net.ssl.trustStoreType=Windows-ROOT` → compile error.
- [ ] **Step 3: Implement**

```java
package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

/** Position and elevation of a climb's foot and top, interpolated along the route (issue #246). Pure. */
public final class ClimbEndpoints {

    public static final class Point {
        public final double lat;
        public final double lon;
        /** Metres above sea level, or NaN when the route has no elevations. */
        public final double elevationM;

        Point(double lat, double lon, double elevationM) {
            this.lat = lat;
            this.lon = lon;
            this.elevationM = elevationM;
        }
    }

    private ClimbEndpoints() {}

    public static Point foot(StoredRoute r, StoredClimb c) { return at(r, c, c.startDistance); }

    public static Point top(StoredRoute r, StoredClimb c) { return at(r, c, c.endDistance); }

    private static Point at(StoredRoute r, StoredClimb c, double distance) {
        if (r.lats == null || r.lons == null || r.distances == null) {
            return new Point(c.startLat, c.startLon, Double.NaN);
        }
        int n = Math.min(r.lats.length, Math.min(r.lons.length, r.distances.length));
        if (n == 0) return new Point(c.startLat, c.startLon, Double.NaN);
        boolean hasEle = r.elevations != null && r.elevations.length >= n;
        int i = 1;
        while (i < n && r.distances[i] < distance) i++;
        if (i >= n || distance <= r.distances[0]) {
            int k = distance <= r.distances[0] ? 0 : n - 1;
            return new Point(r.lats[k], r.lons[k], hasEle ? r.elevations[k] : Double.NaN);
        }
        double span = r.distances[i] - r.distances[i - 1];
        double t = span > 0 ? (distance - r.distances[i - 1]) / span : 0;
        return new Point(
                lerp(r.lats[i - 1], r.lats[i], t),
                lerp(r.lons[i - 1], r.lons[i], t),
                hasEle ? lerp(r.elevations[i - 1], r.elevations[i], t) : Double.NaN);
    }

    private static double lerp(double a, double b, double t) { return a + t * (b - a); }
}
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** — `feat(android): climb foot/top endpoints for weather (#246)`

### Task 2: `HourlyForecast` (Open-Meteo JSON + hour lookup)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/weather/HourlyForecast.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/weather/HourlyForecastTest.java`

**Interfaces:**
- Produces: `public static HourlyForecast parse(String json) throws IOException`; `public int indexAt(Instant when)` (index of the hour containing `when`, or −1 outside the window); fields `public final Instant[] times; public final double[] temperature, apparent, windKmh; public final Integer[] rainPct` (entry null when unknown).

The request (Task 4) uses `timezone=UTC`, so `hourly.time` values are UTC local-date-times like `"2026-09-24T10:00"`.

- [ ] **Step 1: Failing tests**

```java
package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class HourlyForecastTest {

    static final String JSON = "{\"latitude\":50.0,\"elevation\":380.0,\"hourly\":{"
            + "\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\",\"2026-09-24T12:00\"],"
            + "\"temperature_2m\":[12.4,13.0,13.9],"
            + "\"apparent_temperature\":[9.8,10.5,11.6],"
            + "\"wind_speed_10m\":[22.0,31.5,18.0],"
            + "\"precipitation_probability\":[10,null,40]}}";

    @Test public void parsesArraysIncludingNullRainChance() throws IOException {
        HourlyForecast f = HourlyForecast.parse(JSON);
        assertEquals(3, f.times.length);
        assertEquals(Instant.parse("2026-09-24T11:00:00Z"), f.times[1]);
        assertEquals(13.0, f.temperature[1], 1e-9);
        assertEquals(9.8, f.apparent[0], 1e-9);
        assertEquals(31.5, f.windKmh[1], 1e-9);
        assertEquals(Integer.valueOf(10), f.rainPct[0]);
        assertNull(f.rainPct[1]);
    }

    @Test public void indexAtUsesTheHourContainingTheInstant() throws IOException {
        HourlyForecast f = HourlyForecast.parse(JSON);
        assertEquals(0, f.indexAt(Instant.parse("2026-09-24T10:59:59Z")));
        assertEquals(2, f.indexAt(Instant.parse("2026-09-24T12:30:00Z")));
        assertEquals(-1, f.indexAt(Instant.parse("2026-09-24T09:59:00Z")));
        assertEquals(-1, f.indexAt(Instant.parse("2026-09-24T13:00:00Z")));
    }

    @Test(expected = IOException.class)
    public void missingHourlyBlockIsAnError() throws IOException {
        HourlyForecast.parse("{\"error\":true,\"reason\":\"bad\"}");
    }
}
```

- [ ] **Step 2: Run** → compile error.
- [ ] **Step 3: Implement**

```java
package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Open-Meteo hourly forecast (requested with timezone=UTC) for one location (issue #246). Pure. */
public final class HourlyForecast {

    public final Instant[] times;
    public final double[] temperature;
    public final double[] apparent;
    public final double[] windKmh;
    public final Integer[] rainPct;

    private HourlyForecast(Instant[] times, double[] temperature, double[] apparent,
                           double[] windKmh, Integer[] rainPct) {
        this.times = times;
        this.temperature = temperature;
        this.apparent = apparent;
        this.windKmh = windKmh;
        this.rainPct = rainPct;
    }

    public static HourlyForecast parse(String json) throws IOException {
        JsonNode hourly = new ObjectMapper().readTree(json).get("hourly");
        if (hourly == null || !hourly.has("time")) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        JsonNode time = hourly.get("time");
        int n = time.size();
        Instant[] times = new Instant[n];
        double[] temp = new double[n], app = new double[n], wind = new double[n];
        Integer[] rain = new Integer[n];
        for (int i = 0; i < n; i++) {
            times[i] = LocalDateTime.parse(time.get(i).asText()).toInstant(ZoneOffset.UTC);
            temp[i] = number(hourly, "temperature_2m", i);
            app[i] = number(hourly, "apparent_temperature", i);
            wind[i] = number(hourly, "wind_speed_10m", i);
            JsonNode r = hourly.path("precipitation_probability").get(i);
            rain[i] = r == null || r.isNull() ? null : r.asInt();
        }
        return new HourlyForecast(times, temp, app, wind, rain);
    }

    /** Index of the hour that contains {@code when}, or -1 outside the forecast. */
    public int indexAt(Instant when) {
        for (int i = 0; i < times.length; i++) {
            if (!when.isBefore(times[i]) && when.isBefore(times[i].plusSeconds(3600))) return i;
        }
        return -1;
    }

    private static double number(JsonNode hourly, String field, int i) {
        JsonNode v = hourly.path(field).get(i);
        return v == null || v.isNull() ? Double.NaN : v.asDouble();
    }
}
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** — `feat(android): Open-Meteo hourly forecast parser (#246)`

### Task 3: `SummitWeather` comparison + clothing tip

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/weather/SummitWeather.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/weather/SummitWeatherTest.java`

**Interfaces:**
- Consumes: `HourlyForecast` (Task 2).
- Produces: `public static String describe(HourlyForecast foot, HourlyForecast top, Instant when, double footEleM, double topEleM)` → multi-line Dutch text, or `null` when either forecast lacks that hour; `public static String clothingTip(double topApparentC, double topWindKmh)`.

Text format (exact), using `%.1f` with `Locale("nl")` for temperatures and `%.0f` for wind:
```
Dal (125 m): 14,2 °C
Top (250 m): 12,4 °C, voelt als 9,8 °C
Wind op de top 22 km/u · regenkans 10%
Tip: Arm- en beenstukken plus een windjack
```
- Elevation `NaN` → just "Dal:" / "Top:". Rain `null` → "regenkans onbekend".
- Tip thresholds on the top's feels-like temperature: `< 5` "Winterkleding: lange broek, winterjack en handschoenen"; `< 10` "Arm- en beenstukken plus een windjack"; `< 15` "Neem een windvestje mee voor de afdaling"; else "Zomertenue volstaat". If wind `>= 30`, append `". Let op: harde wind op de top"`.

- [ ] **Step 1: Failing tests**

```java
package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class SummitWeatherTest {

    private static final String FOOT = "{\"hourly\":{\"time\":[\"2026-09-24T10:00\"],"
            + "\"temperature_2m\":[14.2],\"apparent_temperature\":[13.0],"
            + "\"wind_speed_10m\":[10.0],\"precipitation_probability\":[5]}}";

    @Test public void describesFootAndTopWithTip() throws IOException {
        String text = SummitWeather.describe(HourlyForecast.parse(FOOT),
                HourlyForecast.parse(HourlyForecastTest.JSON),
                Instant.parse("2026-09-24T10:15:00Z"), 125, 250);
        assertEquals("Dal (125 m): 14,2 °C\n"
                + "Top (250 m): 12,4 °C, voelt als 9,8 °C\n"
                + "Wind op de top 22 km/u · regenkans 10%\n"
                + "Tip: Arm- en beenstukken plus een windjack", text);
    }

    @Test public void unknownRainAndElevation() throws IOException {
        String text = SummitWeather.describe(HourlyForecast.parse(FOOT),
                HourlyForecast.parse(HourlyForecastTest.JSON),
                Instant.parse("2026-09-24T11:00:00Z"), Double.NaN, Double.NaN);
        assertNull(text); // foot forecast has no 11:00 hour
        String top = SummitWeather.describe(HourlyForecast.parse(HourlyForecastTest.JSON),
                HourlyForecast.parse(HourlyForecastTest.JSON),
                Instant.parse("2026-09-24T11:00:00Z"), Double.NaN, Double.NaN);
        assertEquals("Dal: 13,0 °C\nTop: 13,0 °C, voelt als 10,5 °C\n"
                + "Wind op de top 32 km/u · regenkans onbekend\n"
                + "Tip: Neem een windvestje mee voor de afdaling. Let op: harde wind op de top", top);
    }

    @Test public void clothingThresholds() {
        assertEquals("Winterkleding: lange broek, winterjack en handschoenen", SummitWeather.clothingTip(4.9, 0));
        assertEquals("Arm- en beenstukken plus een windjack", SummitWeather.clothingTip(5, 0));
        assertEquals("Neem een windvestje mee voor de afdaling", SummitWeather.clothingTip(10, 0));
        assertEquals("Zomertenue volstaat", SummitWeather.clothingTip(15, 0));
        assertEquals("Zomertenue volstaat. Let op: harde wind op de top", SummitWeather.clothingTip(25, 30));
    }
}
```

Note: `31.5` km/h formatted with `%.0f` rounds half-even in Java's `Formatter`? No — `Formatter` uses `RoundingMode.HALF_UP`, so `31.5` → `"32"`, as the test expects.

- [ ] **Step 2: Run** → compile error.
- [ ] **Step 3: Implement**

```java
package nl.paree.climbpro.domain.weather;

import java.time.Instant;
import java.util.Locale;

/** Valley-versus-summit weather text with a clothing tip (issue #246). Pure. */
public final class SummitWeather {

    private static final Locale NL = new Locale("nl");

    private SummitWeather() {}

    public static String describe(HourlyForecast foot, HourlyForecast top, Instant when,
                                  double footEleM, double topEleM) {
        int f = foot.indexAt(when);
        int t = top.indexAt(when);
        if (f < 0 || t < 0) return null;
        Integer rain = top.rainPct[t];
        return label("Dal", footEleM) + String.format(NL, "%.1f °C", foot.temperature[f]) + "\n"
                + label("Top", topEleM) + String.format(NL, "%.1f °C, voelt als %.1f °C",
                        top.temperature[t], top.apparent[t]) + "\n"
                + String.format(NL, "Wind op de top %.0f km/u", top.windKmh[t])
                + " · regenkans " + (rain != null ? rain + "%" : "onbekend") + "\n"
                + "Tip: " + clothingTip(top.apparent[t], top.windKmh[t]);
    }

    public static String clothingTip(double topApparentC, double topWindKmh) {
        String tip;
        if (topApparentC < 5) tip = "Winterkleding: lange broek, winterjack en handschoenen";
        else if (topApparentC < 10) tip = "Arm- en beenstukken plus een windjack";
        else if (topApparentC < 15) tip = "Neem een windvestje mee voor de afdaling";
        else tip = "Zomertenue volstaat";
        return topWindKmh >= 30 ? tip + ". Let op: harde wind op de top" : tip;
    }

    private static String label(String what, double eleM) {
        return Double.isNaN(eleM) ? what + ": " : String.format(NL, "%s (%.0f m): ", what, eleM);
    }
}
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** — `feat(android): summit vs valley weather text and clothing tip (#246)`

### Task 4: Open-Meteo client + "Weer op de top" button

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/weather/OpenMeteoClient.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/weather/OpenMeteoClientTest.java`
- Modify: `android/app/src/main/res/layout/activity_climb_detail.xml` — after the `btn_export_gpx` Button, add Button `@+id/btn_summit_weather` with the same style/attributes, text `Weer op de top`.
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java`

**Interfaces:**
- Consumes: `ClimbEndpoints`, `HourlyForecast`, `SummitWeather`; `viewModel.route()` (`StoredRoute`) and `viewModel.climb()` (`StoredClimb`) on `ClimbDetailViewModel`.
- Produces: `public static String url(double lat, double lon, double elevationM)` (pure, tested) and `public HourlyForecast fetch(ClimbEndpoints.Point p) throws IOException`.

- [ ] **Step 1: Failing test for the URL builder**

```java
package nl.paree.climbpro.data.weather;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OpenMeteoClientTest {

    @Test public void urlWithElevationUsesDotDecimals() {
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=50.85000&longitude=5.69000"
                + "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,precipitation_probability"
                + "&wind_speed_unit=kmh&timezone=UTC&forecast_days=2&elevation=312",
                OpenMeteoClient.url(50.85, 5.69, 312.4));
    }

    @Test public void unknownElevationIsLeftOut() {
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=50.85000&longitude=5.69000"
                + "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,precipitation_probability"
                + "&wind_speed_unit=kmh&timezone=UTC&forecast_days=2",
                OpenMeteoClient.url(50.85, 5.69, Double.NaN));
    }
}
```

- [ ] **Step 2: Run** → compile error.
- [ ] **Step 3: Implement the client**

```java
package nl.paree.climbpro.data.weather;

import nl.paree.climbpro.domain.weather.ClimbEndpoints;
import nl.paree.climbpro.domain.weather.HourlyForecast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Open-Meteo forecast (issue #246): free, keyless, and elevation-aware (it corrects the
 * temperature from the grid height to the given elevation). Blocking — call off the main thread.
 */
public final class OpenMeteoClient {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS).build();

    public static String url(double lat, double lon, double elevationM) {
        String base = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,precipitation_probability"
                        + "&wind_speed_unit=kmh&timezone=UTC&forecast_days=2", lat, lon);
        return Double.isNaN(elevationM) ? base
                : base + "&elevation=" + Math.round(elevationM);
    }

    public HourlyForecast fetch(ClimbEndpoints.Point p) throws IOException {
        Request req = new Request.Builder().url(url(p.lat, p.lon, p.elevationM)).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("weerdienst gaf HTTP " + resp.code());
            }
            return HourlyForecast.parse(resp.body().string());
        }
    }
}
```

- [ ] **Step 4: Run the test** → PASS.
- [ ] **Step 5: Wire the button** in `ClimbDetailActivity.onCreate`: `binding.btnSummitWeather.setOnClickListener(v -> showSummitWeather());` and add:

```java
/** Issue #246: valley vs summit weather, now and in 3 hours. */
private void showSummitWeather() {
    nl.paree.climbpro.data.route.StoredRoute r = viewModel.route().getValue();
    StoredClimb c = viewModel.climb().getValue();
    if (r == null || c == null) return;
    Toast.makeText(this, "Weer ophalen…", Toast.LENGTH_SHORT).show();
    nl.paree.climbpro.domain.weather.ClimbEndpoints.Point foot =
            nl.paree.climbpro.domain.weather.ClimbEndpoints.foot(r, c);
    nl.paree.climbpro.domain.weather.ClimbEndpoints.Point top =
            nl.paree.climbpro.domain.weather.ClimbEndpoints.top(r, c);
    new Thread(() -> {
        String msg;
        try {
            nl.paree.climbpro.data.weather.OpenMeteoClient client =
                    new nl.paree.climbpro.data.weather.OpenMeteoClient();
            nl.paree.climbpro.domain.weather.HourlyForecast f = client.fetch(foot);
            nl.paree.climbpro.domain.weather.HourlyForecast t = client.fetch(top);
            java.time.Instant now = java.time.Instant.now();
            String nowText = nl.paree.climbpro.domain.weather.SummitWeather.describe(
                    f, t, now, foot.elevationM, top.elevationM);
            String laterText = nl.paree.climbpro.domain.weather.SummitWeather.describe(
                    f, t, now.plusSeconds(3 * 3600), foot.elevationM, top.elevationM);
            StringBuilder sb = new StringBuilder();
            if (nowText != null) sb.append("NU\n").append(nowText);
            if (laterText != null) sb.append(sb.length() > 0 ? "\n\n" : "").append("OVER 3 UUR\n").append(laterText);
            msg = sb.length() > 0 ? sb.toString() : "Geen verwachting beschikbaar voor dit moment";
        } catch (Exception e) {
            msg = "Weer ophalen mislukt: " + e.getMessage();
        }
        String text = msg + "\n\nBron: Open-Meteo";
        runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Weer op de top")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show());
    }, "summit-weather").start();
}
```

`StoredClimb` and `Toast` are already imported in `ClimbDetailActivity`.

- [ ] **Step 6:** Run the full build/test command → green.
- [ ] **Step 7: Commit + PR** — `feat(android): weather at the summit vs the valley via Open-Meteo (#246)`; PR explains the Open-Meteo choice (no key, elevation correction, attribution line in the dialog); device checks: online (both blocks shown), airplane mode (failure message).
