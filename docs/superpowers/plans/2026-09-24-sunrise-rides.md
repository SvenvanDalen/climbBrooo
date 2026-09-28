# Sunrise Rides (issue #247) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Follow superpowers:test-driven-development for every code step.

**Goal:** On a climb's detail screen, pick a date and get the departure time from the route start that puts you on the summit just before sunrise, optionally saved as a planned climb with a reminder.

**Architecture:** Two pure domain classes: `SunriseCalculator` (the standard sunrise equation, offline, no API) and `SunriseRidePlanner` (works back from sunrise: buffer, climb time, approach time). `ClimbDetailActivity` gets a button that opens a date picker and shows the plan in a dialog. The dialog can save it through the existing planning stack (`PlannedClimbRepository` + `PlannedClimbWorkScheduler`).

**Tech Stack:** Java 17 (Android, minSdk 26, compileSdk 34), `java.time`, JUnit 4.

**Spec:** GitHub issue #247 ("Plan een rit zo dat je bij zonsopkomst (gouden uur) op een top staat. Bijzondere ervaring en mooie foto's. Telefoon-only, zonsopkomsttijd + tijdschatting naar de top. Geen wire-format wijziging.")

## Global Constraints

- Phone-only: no changes under `protocol/`, `garmin*/`, or to `ClimbPayloadBuilder`.
- Offline: sunrise is computed locally; no network.
- Java, not Kotlin. minSdk 26 without desugaring: do NOT use `List.of`/`Set.of`/`Map.of`.
- Dutch UI text; times shown as `HH:mm` in the device zone.
- Build/test (from `android/`): `./gradlew :app:testDebugUnitTest :app:assembleDebug -Djavax.net.ssl.trustStoreType=Windows-ROOT --console=plain`
- Branch from `origin/main` as `feat/sunrise-rides`; PR ends with `Closes #247` + Claude Code footer; commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- Polar day or night (Tromsø in June and December) → no sunrise: return `null`, and the dialog says so. No crash, no nonsense time.
- Rider profile incomplete (no climb-time estimate) → fall back to the climb length at 10 km/h instead of refusing.
- Climb at the very start of the route (approach 0 m) → departure = arrival − climb time.
- The departure falls on the previous calendar day (long approach before an early summer sunrise) → show the date next to the time.
- Saving a plan whose departure is already in the past (a date of today, picked in the afternoon) → refuse with a message rather than scheduling an instant reminder.
(The first four are pinned by tests in Tasks 1–2; the past-departure check is a pure helper tested in Task 2.)

---

### Task 1: `SunriseCalculator`

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/sun/SunriseCalculator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/sun/SunriseCalculatorTest.java`

**Interfaces:**
- Produces: `public static Instant sunrise(LocalDate date, double lat, double lon)` → sunrise (upper limb, standard refraction −0.833°) or `null` when the sun does not rise or does not set that day. Longitude is east-positive.

- [ ] **Step 1: Write the failing tests** (reference times are published values for Amsterdam, 52.37 N 4.90 E; the algorithm is accurate to about a minute, so the tests allow 3 minutes)

```java
package nl.paree.climbpro.domain.sun;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

public class SunriseCalculatorTest {

    private static void assertNear(String expectedUtc, Instant actual) {
        long diff = Math.abs(Duration.between(Instant.parse(expectedUtc), actual).getSeconds());
        assertTrue("expected ~" + expectedUtc + " but was " + actual, diff <= 180);
    }

    @Test public void amsterdamSummerSolstice() {   // 05:18 CEST
        assertNear("2026-06-21T03:18:00Z", SunriseCalculator.sunrise(LocalDate.of(2026, 6, 21), 52.37, 4.90));
    }

    @Test public void amsterdamWinterSolstice() {   // 08:48 CET
        assertNear("2026-12-21T07:48:00Z", SunriseCalculator.sunrise(LocalDate.of(2026, 12, 21), 52.37, 4.90));
    }

    @Test public void westernLongitudeRisesLater() {
        Instant ams = SunriseCalculator.sunrise(LocalDate.of(2026, 3, 20), 52.37, 4.90);
        Instant dublin = SunriseCalculator.sunrise(LocalDate.of(2026, 3, 20), 53.35, -6.26);
        assertTrue(dublin.isAfter(ams.plusSeconds(40 * 60)));
    }

    @Test public void polarDayAndNightHaveNoSunrise() {
        assertNull(SunriseCalculator.sunrise(LocalDate.of(2026, 6, 21), 69.65, 18.96));
        assertNull(SunriseCalculator.sunrise(LocalDate.of(2026, 12, 21), 69.65, 18.96));
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "nl.paree.climbpro.domain.sun.SunriseCalculatorTest" -Djavax.net.ssl.trustStoreType=Windows-ROOT` → compile error.

- [ ] **Step 3: Implement** (the "sunrise equation", Julian-day form)

```java
package nl.paree.climbpro.domain.sun;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Sunrise time for a date and place (issue #247), computed offline with the standard sunrise
 * equation (accuracy about a minute at mid latitudes). Pure.
 */
public final class SunriseCalculator {

    private static final double J2000 = 2451545.0;
    private static final long J2000_EPOCH_DAY = 10957; // 2000-01-01
    private static final double UNIX_EPOCH_JD = 2440587.5;

    private SunriseCalculator() {}

    /** @return sunrise, or null when the sun stays up or stays down all day. */
    public static Instant sunrise(LocalDate date, double lat, double lon) {
        double n = date.toEpochDay() - J2000_EPOCH_DAY;
        double jStar = n - lon / 360.0;
        double m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360);
        double c = 1.9148 * Math.sin(m) + 0.0200 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m);
        double lambda = Math.toRadians((Math.toDegrees(m) + c + 180 + 102.9372) % 360);
        double jTransit = J2000 + jStar + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * lambda);
        double sinDecl = Math.sin(lambda) * Math.sin(Math.toRadians(23.4397));
        double cosDecl = Math.cos(Math.asin(sinDecl));
        double phi = Math.toRadians(lat);
        double cosOmega = (Math.sin(Math.toRadians(-0.833)) - Math.sin(phi) * sinDecl)
                / (Math.cos(phi) * cosDecl);
        if (cosOmega > 1 || cosOmega < -1) return null;
        double jRise = jTransit - Math.toDegrees(Math.acos(cosOmega)) / 360.0;
        return Instant.ofEpochSecond(Math.round((jRise - UNIX_EPOCH_JD) * 86400.0));
    }
}
```

- [ ] **Step 4: Run the test** → PASS.
- [ ] **Step 5: Commit** — `feat(android): offline sunrise calculator (#247)`

### Task 2: `SunriseRidePlanner`

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/sun/SunriseRidePlanner.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/sun/SunriseRidePlannerTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 (it takes the sunrise `Instant` as input).
- Produces:
  - `public static final double DEFAULT_APPROACH_KMH = 25.0;` `public static final double FALLBACK_CLIMB_KMH = 10.0;` `public static final int DEFAULT_BUFFER_MIN = 10;`
  - `public static Plan plan(Instant sunrise, int approachM, double approachKmh, int climbSec, int climbLengthM, int bufferMin)`
  - `public static final class Plan { public final Instant departure, arrivalTop, sunrise; public final int approachSec, climbSec; }`
  - `public static boolean isInPast(Plan plan, Instant now)` → `plan.departure` is not after `now`.

Rules: `approachKmh <= 0` uses `DEFAULT_APPROACH_KMH`; `climbSec <= 0` uses `round(climbLengthM / (FALLBACK_CLIMB_KMH / 3.6))`; `approachM < 0` counts as 0; `approachSec = round(approachM / (kmh / 3.6))`; `arrivalTop = sunrise − bufferMin`; `departure = arrivalTop − climbSec − approachSec`.

- [ ] **Step 1: Write the failing tests**

```java
package nl.paree.climbpro.domain.sun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public class SunriseRidePlannerTest {

    private static final Instant SUNRISE = Instant.parse("2026-06-21T03:18:00Z");

    @Test public void worksBackFromSunrise() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 25_000, 25.0, 1_800, 5_000, 10);
        assertEquals(Instant.parse("2026-06-21T03:08:00Z"), p.arrivalTop);
        assertEquals(3_600, p.approachSec);
        assertEquals(Instant.parse("2026-06-21T01:38:00Z"), p.departure);
    }

    @Test public void climbAtRouteStartHasNoApproach() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 0, 25.0, 1_200, 4_000, 0);
        assertEquals(0, p.approachSec);
        assertEquals(SUNRISE.minusSeconds(1_200), p.departure);
    }

    @Test public void missingClimbEstimateFallsBackToTenKmh() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 0, 25.0, 0, 5_000, 0);
        assertEquals(1_800, p.climbSec); // 5 km at 10 km/h
    }

    @Test public void invalidSpeedUsesDefaultAndDepartureMayBePreviousDay() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 100_000, 0, 3_600, 10_000, 10);
        assertEquals(14_400, p.approachSec); // 100 km at 25 km/h
        assertEquals(Instant.parse("2026-06-20T22:08:00Z"), p.departure);
    }

    @Test public void pastDepartureIsDetected() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 0, 25.0, 600, 0, 0);
        assertTrue(SunriseRidePlanner.isInPast(p, SUNRISE));
        assertFalse(SunriseRidePlanner.isInPast(p, SUNRISE.minusSeconds(3_600)));
    }
}
```

- [ ] **Step 2: Run to verify failure** → compile error.
- [ ] **Step 3: Implement**

```java
package nl.paree.climbpro.domain.sun;

import java.time.Instant;

/** Works back from sunrise to a departure time for a summit-at-sunrise ride (issue #247). Pure. */
public final class SunriseRidePlanner {

    public static final double DEFAULT_APPROACH_KMH = 25.0;
    public static final double FALLBACK_CLIMB_KMH = 10.0;
    public static final int DEFAULT_BUFFER_MIN = 10;

    public static final class Plan {
        public final Instant departure;
        public final Instant arrivalTop;
        public final Instant sunrise;
        public final int approachSec;
        public final int climbSec;

        Plan(Instant departure, Instant arrivalTop, Instant sunrise, int approachSec, int climbSec) {
            this.departure = departure;
            this.arrivalTop = arrivalTop;
            this.sunrise = sunrise;
            this.approachSec = approachSec;
            this.climbSec = climbSec;
        }
    }

    private SunriseRidePlanner() {}

    public static Plan plan(Instant sunrise, int approachM, double approachKmh, int climbSec,
                            int climbLengthM, int bufferMin) {
        double kmh = approachKmh > 0 ? approachKmh : DEFAULT_APPROACH_KMH;
        int approachSec = (int) Math.round(Math.max(0, approachM) / (kmh / 3.6));
        int climb = climbSec > 0 ? climbSec
                : (int) Math.round(Math.max(0, climbLengthM) / (FALLBACK_CLIMB_KMH / 3.6));
        Instant arrival = sunrise.minusSeconds(60L * Math.max(0, bufferMin));
        Instant departure = arrival.minusSeconds((long) climb + approachSec);
        return new Plan(departure, arrival, sunrise, approachSec, climb);
    }

    public static boolean isInPast(Plan plan, Instant now) {
        return !plan.departure.isAfter(now);
    }
}
```

- [ ] **Step 4: Run the test** → PASS.
- [ ] **Step 5: Commit** — `feat(android): sunrise ride planner (#247)`

### Task 3: "Zonsopkomst-rit plannen" on the climb detail screen

**Files:**
- Modify: `android/app/src/main/res/layout/activity_climb_detail.xml` — add after the `btn_export_gpx` Button a Button `@+id/btn_sunrise_ride`, same style/attributes as `btn_export_gpx`, text `Zonsopkomst-rit plannen`.
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java`

**Interfaces:**
- Consumes: `SunriseCalculator.sunrise`, `SunriseRidePlanner.plan/isInPast`, `viewModel.climb()` (`StoredClimb`: `startLat`, `startLon`, `startDistance`, `length`, `userDisplayName`/`name`), `viewModel.timeEstimate()` (`ClimbTimeEstimate.totalSeconds`, may be null), `nl.paree.climbpro.data.planning.PlannedClimb(id, routeId, climbIndex, displayName, plannedAtEpochSec, createdAtMs)`, `PlannedClimbRepository.add(plan)`, `nl.paree.climbpro.service.PlannedClimbWorkScheduler.schedule(ctx, plan)`. The activity already has `routeId` and the climb index it was opened with (see `intentFor(ctx, routeId, climbIndex)`).

- [ ] **Step 1:** In `onCreate`, wire `binding.btnSunriseRide.setOnClickListener(v -> pickSunriseDate());`.
- [ ] **Step 2:** Add these methods to `ClimbDetailActivity`:

```java
/** Issue #247: plan a ride that reaches this climb's top just before sunrise. */
private void pickSunriseDate() {
    java.time.LocalDate tomorrow = java.time.LocalDate.now().plusDays(1);
    new android.app.DatePickerDialog(this, (dp, y, m, d) ->
            showSunrisePlan(java.time.LocalDate.of(y, m + 1, d)),
            tomorrow.getYear(), tomorrow.getMonthValue() - 1, tomorrow.getDayOfMonth()).show();
}

private void showSunrisePlan(java.time.LocalDate date) {
    StoredClimb c = viewModel.climb().getValue();
    if (c == null) return;
    java.time.Instant sunrise = nl.paree.climbpro.domain.sun.SunriseCalculator
            .sunrise(date, c.startLat, c.startLon);
    if (sunrise == null) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Op deze datum komt de zon hier niet op of gaat ze niet onder.")
                .setPositiveButton("OK", null).show();
        return;
    }
    nl.paree.climbpro.domain.power.ClimbTimeEstimate est = viewModel.timeEstimate().getValue();
    nl.paree.climbpro.domain.sun.SunriseRidePlanner.Plan plan =
            nl.paree.climbpro.domain.sun.SunriseRidePlanner.plan(sunrise, c.startDistance,
                    nl.paree.climbpro.domain.sun.SunriseRidePlanner.DEFAULT_APPROACH_KMH,
                    est != null ? est.totalSeconds : 0, c.length,
                    nl.paree.climbpro.domain.sun.SunriseRidePlanner.DEFAULT_BUFFER_MIN);
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    java.time.format.DateTimeFormatter hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
    java.time.format.DateTimeFormatter dayHm =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", new java.util.Locale("nl"));
    String name = c.userDisplayName != null ? c.userDisplayName : c.name;
    String msg = "Zon op: " + hm.format(sunrise.atZone(zone))
            + "\nOp de top: " + hm.format(plan.arrivalTop.atZone(zone))
            + " (" + nl.paree.climbpro.domain.sun.SunriseRidePlanner.DEFAULT_BUFFER_MIN + " min vooraf)"
            + "\nVertrek vanaf de routestart: " + dayHm.format(plan.departure.atZone(zone))
            + "\n\nAanrit " + String.format(new java.util.Locale("nl"), "%.1f", c.startDistance / 1000.0)
            + " km à 25 km/u (" + nl.paree.climbpro.domain.power.DurationFormat.format(plan.approachSec)
            + ") + klim " + nl.paree.climbpro.domain.power.DurationFormat.format(plan.climbSec)
            + (est == null ? " (schatting op 10 km/u; vul je profiel in voor een betere schatting)" : "");
    new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Zonsopkomst op " + name)
            .setMessage(msg)
            .setPositiveButton("Zet in klimplanning", (d, w) -> saveSunrisePlan(plan, name))
            .setNegativeButton("Sluiten", null)
            .show();
}

private void saveSunrisePlan(nl.paree.climbpro.domain.sun.SunriseRidePlanner.Plan plan, String name) {
    if (nl.paree.climbpro.domain.sun.SunriseRidePlanner.isInPast(plan, java.time.Instant.now())) {
        Toast.makeText(this, "Het vertrektijdstip is al voorbij", Toast.LENGTH_LONG).show();
        return;
    }
    nl.paree.climbpro.data.planning.PlannedClimb p = new nl.paree.climbpro.data.planning.PlannedClimb(
            java.util.UUID.randomUUID().toString(), routeId, climbIndex,
            "Zonsopkomst: " + name, plan.departure.getEpochSecond(), System.currentTimeMillis());
    new Thread(() -> {
        try {
            new nl.paree.climbpro.data.planning.PlannedClimbRepository(this).add(p);
            nl.paree.climbpro.service.PlannedClimbWorkScheduler.schedule(this, p);
            runOnUiThread(() -> Toast.makeText(this, "Gepland; je krijgt een herinnering",
                    Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Plannen mislukt: " + e.getMessage(),
                    Toast.LENGTH_LONG).show());
        }
    }, "sunrise-plan").start();
}
```

`routeId` and `climbIndex` are existing fields of `ClimbDetailActivity` (lines 42-43); `Toast` is already imported.

- [ ] **Step 3:** Run the full build/test command → green.
- [ ] **Step 4: Commit + PR** — `feat(android): plan a summit-at-sunrise ride from the climb screen (#247)`; device checks: pick tomorrow, check times vs a sunrise website; save to planning and check the entry and reminder; pick a date where the departure is past.
