# Fatigue-Aware Climb-Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the climb-detail time estimate account for fatigue from the rest of the route, by replacing the fresh-per-climb estimate with a whole-route W'-balance simulation (Approach B).

**Architecture:** All climbs are ridden at `FTP + x` watts (one shared offset). Non-climb stretches are ridden at a configurable ride-intensity (% FTP) where W' recovers. A bisection finds the largest `x` whose route-wide minimum W'-balance stays above a 10% reserve. The target climb's displayed time uses `FTP + x*`. Phone-only; no protocol/watch changes.

**Tech Stack:** Java (Android), JUnit 4, Robolectric (existing test setup), Gradle Groovy DSL.

**Design doc:** `docs/superpowers/specs/2026-06-12-fatigue-aware-climb-time-design.md`

**Test commands:** all Gradle commands run from the `android/` directory.
- Windows PowerShell: `cd android; .\gradlew.bat test --tests "<FQCN>"`
- bash/macOS/Linux: `cd android && ./gradlew test --tests "<FQCN>"`

The tasks below show the bash form; on Windows substitute `.\gradlew.bat` for `./gradlew`.

---

## File Structure

**Create:**
- `android/app/src/main/java/nl/paree/climbpro/domain/power/RouteTile.java` — value object for one route stretch.
- `android/app/src/main/java/nl/paree/climbpro/domain/power/WPrimeBalance.java` — W'-balance integrator.
- `android/app/src/main/java/nl/paree/climbpro/domain/power/RouteAwareClimbEstimator.java` — bisection + simulation.
- `android/app/src/main/java/nl/paree/climbpro/service/RouteEffortProfileBuilder.java` — `StoredRoute` → tiles.
- Test files mirroring each of the above under `src/test/java`.

**Modify:**
- `domain/power/PowerConstants.java` — new tuning constants.
- `domain/power/RiderProfile.java` — `rideIntensityPct` field + helper.
- `domain/power/ClimbTimeEstimator.java` — extract `estimateAtFixedPower(...)`.
- `data/rider/RiderProfileRepository.java` — persist ride intensity.
- `ui/settings/SettingsViewModel.java`, `ui/settings/SettingsActivity.java`, `res/layout/activity_settings.xml` — ride-intensity input.
- `ui/climbs/ClimbDetailViewModel.java` — build tiles + call route-aware estimator with fallback.

---

## Task 1: Tuning constants in PowerConstants

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/power/PowerConstants.java`

- [ ] **Step 1: Add the constants**

Add these fields to `PowerConstants` (after the existing `W_PRIME` line):

```java
    /** Recovery time constant for W' reconstitution below CP (seconds). */
    public static final double W_PRIME_TAU_SECONDS = 400.0;
    /** Fraction of W'max kept in reserve at the route finish (buffer, not empty). */
    public static final double RESERVE_FRACTION = 0.10;
    /** Upper bound for the per-climb power offset above CP during bisection (watts). */
    public static final double X_MAX_OFFSET_W = 600.0;
    /** Bisection iterations for solving the shared climb offset x. */
    public static final int BISECTION_ITERATIONS = 40;
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/PowerConstants.java
git commit -m "feat(power): add W'-balance tuning constants"
```

---

## Task 2: Ride-intensity field on RiderProfile

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/power/RiderProfile.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/RiderProfileTest.java`

- [ ] **Step 1: Write the failing tests**

Append these tests to `RiderProfileTest`:

```java
    @Test
    public void threeArgConstructorUsesDefaultIntensity() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0);
        assertEquals(RiderProfile.DEFAULT_RIDE_INTENSITY_PCT, p.rideIntensityPct);
    }

    @Test
    public void intensityFractionClampsLow() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0, 10);
        assertEquals(RiderProfile.RIDE_INTENSITY_MIN_PCT / 100.0, p.rideIntensityFraction(), 1e-9);
    }

    @Test
    public void intensityFractionClampsHigh() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0, 130);
        assertEquals(RiderProfile.RIDE_INTENSITY_MAX_PCT / 100.0, p.rideIntensityFraction(), 1e-9);
    }

    @Test
    public void intensityFractionInRange() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0, 65);
        assertEquals(0.65, p.rideIntensityFraction(), 1e-9);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.RiderProfileTest"`
Expected: FAIL — `rideIntensityPct`, `DEFAULT_RIDE_INTENSITY_PCT`, `rideIntensityFraction()` do not exist.

- [ ] **Step 3: Implement**

Replace the body of `RiderProfile.java` with:

```java
package nl.paree.climbpro.domain.power;

/**
 * Immutable rider/bike profile used to estimate climbing time.
 * ftpWatts is functional threshold power in watts; weights are in kilograms.
 * rideIntensityPct is the percent of FTP held on non-climb stretches (for the
 * whole-route W'-balance estimate).
 */
public final class RiderProfile {

    public static final int RIDE_INTENSITY_MIN_PCT     = 40;
    public static final int RIDE_INTENSITY_MAX_PCT     = 95;
    public static final int DEFAULT_RIDE_INTENSITY_PCT = 65;

    public final int ftpWatts;
    public final double riderWeightKg;
    public final double bikeWeightKg;
    public final int rideIntensityPct;

    public RiderProfile(int ftpWatts, double riderWeightKg, double bikeWeightKg) {
        this(ftpWatts, riderWeightKg, bikeWeightKg, DEFAULT_RIDE_INTENSITY_PCT);
    }

    public RiderProfile(int ftpWatts, double riderWeightKg, double bikeWeightKg, int rideIntensityPct) {
        this.ftpWatts = ftpWatts;
        this.riderWeightKg = riderWeightKg;
        this.bikeWeightKg = bikeWeightKg;
        this.rideIntensityPct = rideIntensityPct;
    }

    public double totalMassKg() {
        return riderWeightKg + bikeWeightKg;
    }

    /** True only when every field needed for the basic estimate is a usable positive value. */
    public boolean isComplete() {
        return ftpWatts > 0 && riderWeightKg > 0 && bikeWeightKg > 0;
    }

    /** Clamped fraction (0..1) of FTP held on non-climb stretches. */
    public double rideIntensityFraction() {
        int p = Math.max(RIDE_INTENSITY_MIN_PCT, Math.min(RIDE_INTENSITY_MAX_PCT, rideIntensityPct));
        return p / 100.0;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.RiderProfileTest"`
Expected: PASS (all, including the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/RiderProfile.java \
        android/app/src/test/java/nl/paree/climbpro/domain/RiderProfileTest.java
git commit -m "feat(power): add configurable ride-intensity to RiderProfile"
```

---

## Task 3: Persist ride intensity in RiderProfileRepository

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/rider/RiderProfileRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/rider/RiderProfileRepositoryTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/data/rider/RiderProfileRepositoryTest.java`:

```java
package nl.paree.climbpro.data.rider;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.power.RiderProfile;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class RiderProfileRepositoryTest {

    @Test
    public void defaultIntensityWhenUnset() {
        Application app = ApplicationProvider.getApplicationContext();
        RiderProfile p = new RiderProfileRepository(app).load();
        assertEquals(RiderProfile.DEFAULT_RIDE_INTENSITY_PCT, p.rideIntensityPct);
    }

    @Test
    public void roundTripsIntensity() {
        Application app = ApplicationProvider.getApplicationContext();
        RiderProfileRepository repo = new RiderProfileRepository(app);
        repo.save(new RiderProfile(250, 72.0, 8.0, 58));
        assertEquals(58, repo.load().rideIntensityPct);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.data.rider.RiderProfileRepositoryTest"`
Expected: FAIL — `roundTripsIntensity` returns the default (65), not 58.

- [ ] **Step 3: Implement**

In `RiderProfileRepository.java`, add the pref key constant next to the others:

```java
    public static final String PREF_RIDE_INTENSITY_PCT = "rider_ride_intensity_pct";
```

Replace `load()` with:

```java
    public RiderProfile load() {
        int ftp = prefs.getInt(PREF_FTP_WATTS, 0);
        double rider = prefs.getFloat(PREF_RIDER_WEIGHT_KG, 0f);
        double bike = prefs.getFloat(PREF_BIKE_WEIGHT_KG, 0f);
        int intensity = prefs.getInt(PREF_RIDE_INTENSITY_PCT, RiderProfile.DEFAULT_RIDE_INTENSITY_PCT);
        return new RiderProfile(ftp, rider, bike, intensity);
    }
```

Replace the `save(...)` body's `edit()` chain with:

```java
        prefs.edit()
                .putInt(PREF_FTP_WATTS, profile.ftpWatts)
                .putFloat(PREF_RIDER_WEIGHT_KG, (float) profile.riderWeightKg)
                .putFloat(PREF_BIKE_WEIGHT_KG, (float) profile.bikeWeightKg)
                .putInt(PREF_RIDE_INTENSITY_PCT, profile.rideIntensityPct)
                .apply();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.data.rider.RiderProfileRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/rider/RiderProfileRepository.java \
        android/app/src/test/java/nl/paree/climbpro/data/rider/RiderProfileRepositoryTest.java
git commit -m "feat(rider): persist ride-intensity preference"
```

---

## Task 4: WPrimeBalance integrator

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/WPrimeBalance.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/WPrimeBalanceTest.java`

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/nl/paree/climbpro/domain/WPrimeBalanceTest.java`:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.PowerConstants;
import nl.paree.climbpro.domain.power.WPrimeBalance;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WPrimeBalanceTest {

    private static final double CP = 250.0;
    private static final double WMAX = 20_000.0;

    @Test
    public void startsFull() {
        assertEquals(WMAX, new WPrimeBalance(WMAX, CP).current(), 1e-9);
    }

    @Test
    public void depletesAboveCp() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyTile(CP + 100, 10); // 100 W above CP for 10 s = 1000 J
        assertEquals(WMAX - 1000, b.current(), 1e-6);
    }

    @Test
    public void depletionClampsAtZero() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyTile(CP + 100, 10_000); // would remove 1,000,000 J
        assertEquals(0.0, b.current(), 1e-9);
    }

    @Test
    public void recoversBelowCpTowardMax() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyTile(CP + 100, 100);     // deplete 10,000 J -> 10,000 remaining
        double afterDeplete = b.current();
        b.applyTile(CP - 50, 1000);     // recover for 1000 s
        assertTrue("must recover", b.current() > afterDeplete);
        assertTrue("never exceeds max", b.current() <= WMAX + 1e-9);
    }

    @Test
    public void recoveryMatchesExponential() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyTile(CP + 100, 100);     // -> 10,000 J
        double before = b.current();
        double dt = PowerConstants.W_PRIME_TAU_SECONDS; // one tau
        b.applyTile(CP, dt);            // power == CP counts as recovery branch
        double expected = WMAX - (WMAX - before) * Math.exp(-1.0);
        assertEquals(expected, b.current(), 1e-6);
    }

    @Test
    public void zeroDurationIsNoOp() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyTile(CP + 500, 0);
        assertEquals(WMAX, b.current(), 1e-9);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.WPrimeBalanceTest"`
Expected: FAIL — `WPrimeBalance` does not exist.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/nl/paree/climbpro/domain/power/WPrimeBalance.java`:

```java
package nl.paree.climbpro.domain.power;

/**
 * Tracks the anaerobic work-capacity balance W' along a ride.
 *
 * Above CP the balance depletes linearly: dW' = -(P - CP)*dt (clamped at 0).
 * At or below CP it reconstitutes exponentially toward W'max with a fixed time
 * constant tau: W' = W'max - (W'max - W')*exp(-dt/tau). This is the common Skiba
 * simplification with a single recovery time constant.
 */
public final class WPrimeBalance {

    private final double wPrimeMax;
    private final double cp;
    private double current;

    public WPrimeBalance(double wPrimeMax, double cp) {
        this.wPrimeMax = wPrimeMax;
        this.cp = cp;
        this.current = wPrimeMax;
    }

    public double current() {
        return current;
    }

    /** Apply a stretch ridden at constant {@code power} for {@code durationSeconds}. */
    public void applyTile(double power, double durationSeconds) {
        if (durationSeconds <= 0) {
            return;
        }
        if (power > cp) {
            current -= (power - cp) * durationSeconds;
            if (current < 0) {
                current = 0;
            }
        } else {
            double deficit = wPrimeMax - current;
            current = wPrimeMax - deficit * Math.exp(-durationSeconds / PowerConstants.W_PRIME_TAU_SECONDS);
            if (current > wPrimeMax) {
                current = wPrimeMax;
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.WPrimeBalanceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/WPrimeBalance.java \
        android/app/src/test/java/nl/paree/climbpro/domain/WPrimeBalanceTest.java
git commit -m "feat(power): add W'-balance integrator"
```

---

## Task 5: Extract estimateAtFixedPower in ClimbTimeEstimator

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/power/ClimbTimeEstimator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/ClimbTimeEstimatorTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ClimbTimeEstimatorTest`:

```java
    @Test
    public void fixedPowerTotalEqualsSumOfSegments() {
        int[] dist = {1000, 1000};
        double[] grad = {0.06, 0.06};
        double[] crr = {nl.paree.climbpro.domain.power.SurfaceRollingResistance.CRR_ASPHALT,
                        nl.paree.climbpro.domain.power.SurfaceRollingResistance.CRR_ASPHALT};
        ClimbTimeEstimate e = ClimbTimeEstimator.estimateAtFixedPower(dist, grad, crr, 80.0, 280.0);
        int sum = 0;
        for (int s : e.segmentSeconds) sum += s;
        assertEquals(sum, e.totalSeconds);
        assertEquals(280.0, e.assumedPowerWatts, 1e-9);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.ClimbTimeEstimatorTest"`
Expected: FAIL — `estimateAtFixedPower` does not exist.

- [ ] **Step 3: Implement (extract + reuse)**

In `ClimbTimeEstimator.java`, replace the final-pass block (the lines from `// Final pass at the converged power` through the `return new ClimbTimeEstimate(total, segSeconds, power);`) with:

```java
        // Final pass at the converged power, rounding per segment so the total
        // shown equals the sum of the per-segment values shown.
        double power = PowerDurationModel.sustainablePower(profile.ftpWatts, durationGuess);
        return estimateAtFixedPower(segmentDistancesMeters, segmentGradients, crr, mass, power);
```

Then add this package-visible method to the class (e.g. above `totalSecondsAtPower`):

```java
    /**
     * Estimates per-segment and total seconds at a fixed pedal power, rounding each
     * segment so the displayed total equals the sum of the displayed segment times.
     * Shared by {@link #estimate} and {@link RouteAwareClimbEstimator}.
     */
    static ClimbTimeEstimate estimateAtFixedPower(int[] dist, double[] grad, double[] crr,
                                                  double mass, double power) {
        int n = dist.length;
        int[] segSeconds = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            double v = PowerSpeedSolver.speedMetersPerSecond(power, mass, grad[i], crr[i]);
            int secs = (int) Math.round(dist[i] / v);
            segSeconds[i] = secs;
            total += secs;
        }
        return new ClimbTimeEstimate(total, segSeconds, power);
    }
```

(The old final-pass loop is now gone — `estimateAtFixedPower` replaces it.)

- [ ] **Step 4: Run the estimator tests to verify all pass**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.ClimbTimeEstimatorTest"`
Expected: PASS (the new test plus all pre-existing ones — behavior is unchanged).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/ClimbTimeEstimator.java \
        android/app/src/test/java/nl/paree/climbpro/domain/ClimbTimeEstimatorTest.java
git commit -m "refactor(power): extract estimateAtFixedPower for reuse"
```

---

## Task 6: RouteTile value object

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/RouteTile.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/RouteTileTest.java`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/domain/RouteTileTest.java`:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteTileTest {

    @Test
    public void climbTileReportsIsClimb() {
        RouteTile t = new RouteTile(100, 0.07, SurfaceType.ASPHALT, 2);
        assertTrue(t.isClimb());
        assertEquals(2, t.climbIndex);
    }

    @Test
    public void nonClimbTileIsNotClimb() {
        RouteTile t = new RouteTile(100, 0.0, SurfaceType.ASPHALT, -1);
        assertFalse(t.isClimb());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.RouteTileTest"`
Expected: FAIL — `RouteTile` does not exist.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/nl/paree/climbpro/domain/power/RouteTile.java`:

```java
package nl.paree.climbpro.domain.power;

/**
 * One ordered stretch of a route for the whole-route W'-balance estimate.
 * Distances are integer metres; gradient is a fraction (0.072 = 7.2%).
 * climbIndex is -1 for non-climb stretches, otherwise the index of the climb
 * (matching its position in StoredRoute.climbs) this tile belongs to.
 */
public final class RouteTile {

    public final int distanceMeters;
    public final double gradient;
    public final int surfaceType;
    public final int climbIndex;

    public RouteTile(int distanceMeters, double gradient, int surfaceType, int climbIndex) {
        this.distanceMeters = distanceMeters;
        this.gradient = gradient;
        this.surfaceType = surfaceType;
        this.climbIndex = climbIndex;
    }

    public boolean isClimb() {
        return climbIndex >= 0;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.RouteTileTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/RouteTile.java \
        android/app/src/test/java/nl/paree/climbpro/domain/RouteTileTest.java
git commit -m "feat(power): add RouteTile value object"
```

---

## Task 7: RouteAwareClimbEstimator

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/RouteAwareClimbEstimator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/RouteAwareClimbEstimatorTest.java`

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/nl/paree/climbpro/domain/RouteAwareClimbEstimatorTest.java`:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RouteAwareClimbEstimatorTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 72.0, 8.0); // 80 kg

    /** A climb of {@code lengthM} at {@code grad}, split into 1 km asphalt tiles, tagged climbIndex. */
    private static void addClimb(List<RouteTile> tiles, int lengthM, double grad, int climbIndex) {
        int remaining = lengthM;
        while (remaining > 0) {
            int seg = Math.min(1000, remaining);
            tiles.add(new RouteTile(seg, grad, SurfaceType.ASPHALT, climbIndex));
            remaining -= seg;
        }
    }

    /** A flat non-climb stretch of {@code lengthM} at 0% asphalt. */
    private static void addFlat(List<RouteTile> tiles, int lengthM) {
        int remaining = lengthM;
        while (remaining > 0) {
            int seg = Math.min(1000, remaining);
            tiles.add(new RouteTile(seg, 0.0, SurfaceType.ASPHALT, -1));
            remaining -= seg;
        }
    }

    @Test
    public void incompleteProfileReturnsNull() {
        List<RouteTile> tiles = new ArrayList<>();
        addClimb(tiles, 2000, 0.08, 0);
        assertNull(RouteAwareClimbEstimator.estimate(tiles, 0, new RiderProfile(0, 72, 8)));
    }

    @Test
    public void unknownTargetReturnsNull() {
        List<RouteTile> tiles = new ArrayList<>();
        addClimb(tiles, 2000, 0.08, 0);
        assertNull(RouteAwareClimbEstimator.estimate(tiles, 5, RIDER));
    }

    @Test
    public void producesRealisticEstimateForLeadingClimb() {
        List<RouteTile> tiles = new ArrayList<>();
        addClimb(tiles, 2000, 0.08, 0); // climb first, fresh-ish
        addFlat(tiles, 5000);           // easy run-out after
        ClimbTimeEstimate e = RouteAwareClimbEstimator.estimate(tiles, 0, RIDER);
        assertTrue("power above CP", e.assumedPowerWatts > 250);
        assertTrue("2km@8% in a sane range, was " + e.totalSeconds,
                e.totalSeconds > 440 && e.totalSeconds < 700);
    }

    @Test
    public void sameClimbIsSlowerDeeperIntoAHardRoute() {
        // Route A: target climb is first.
        List<RouteTile> a = new ArrayList<>();
        addClimb(a, 2000, 0.08, 0);
        addFlat(a, 3000);
        int first = RouteAwareClimbEstimator.estimate(a, 0, RIDER).totalSeconds;

        // Route B: two hard climbs before an identical target climb (index 2).
        List<RouteTile> b = new ArrayList<>();
        addClimb(b, 4000, 0.08, 0);
        addFlat(b, 1000);
        addClimb(b, 4000, 0.08, 1);
        addFlat(b, 1000);
        addClimb(b, 2000, 0.08, 2); // identical geometry to route A's target
        int deep = RouteAwareClimbEstimator.estimate(b, 2, RIDER).totalSeconds;

        assertTrue("same climb must be slower when fatigued (first=" + first + ", deep=" + deep + ")",
                deep > first);
    }

    @Test
    public void higherRideIntensityLeavesLessForLaterClimb() {
        // A long flat (where intensity matters) then the target climb.
        List<RouteTile> easyTiles = new ArrayList<>();
        addFlat(easyTiles, 20000);
        addClimb(easyTiles, 3000, 0.08, 0);

        RiderProfile easy = new RiderProfile(250, 72.0, 8.0, 50); // recovers a lot on the flat
        RiderProfile hard = new RiderProfile(250, 72.0, 8.0, 90); // recovers little

        int easySecs = RouteAwareClimbEstimator.estimate(easyTiles, 0, easy).totalSeconds;
        int hardSecs = RouteAwareClimbEstimator.estimate(easyTiles, 0, hard).totalSeconds;
        assertTrue("higher ride intensity should not be faster (easy=" + easySecs + ", hard=" + hardSecs + ")",
                hardSecs >= easySecs);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.RouteAwareClimbEstimatorTest"`
Expected: FAIL — `RouteAwareClimbEstimator` does not exist.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/nl/paree/climbpro/domain/power/RouteAwareClimbEstimator.java`:

```java
package nl.paree.climbpro.domain.power;

import java.util.List;

/**
 * Whole-route, fatigue-aware climb-time estimate (Approach B).
 *
 * All climbs are ridden at CP + x watts (one shared offset). Non-climb stretches
 * are ridden at the rider's ride-intensity (fraction of CP), where W' recovers.
 * A bisection finds the largest x whose route-wide minimum W'-balance stays at or
 * above a reserve floor; the target climb's time uses CP + x*.
 */
public final class RouteAwareClimbEstimator {

    private RouteAwareClimbEstimator() {}

    /** Returns null if the profile is incomplete, the tiles are empty, or the target has no tiles. */
    public static ClimbTimeEstimate estimate(List<RouteTile> tiles, int targetClimbIndex,
                                             RiderProfile profile) {
        if (!profile.isComplete() || tiles == null || tiles.isEmpty()) {
            return null;
        }

        final double cp = profile.ftpWatts;
        final double wPrimeMax = PowerConstants.W_PRIME;
        final double mass = profile.totalMassKg();
        final double pNonClimb = profile.rideIntensityFraction() * cp;
        final double reserve = PowerConstants.RESERVE_FRACTION * wPrimeMax;

        int n = tiles.size();
        double[] crr = new double[n];
        for (int i = 0; i < n; i++) {
            crr[i] = SurfaceRollingResistance.crr(tiles.get(i).surfaceType);
        }

        // Bisection: largest offset x whose route-wide min balance stays >= reserve.
        // x = 0 is always feasible (climbs at CP never deplete). minBalance is monotone
        // decreasing in x, so we keep the largest tested feasible value.
        double xLo = 0.0;
        double xHi = PowerConstants.X_MAX_OFFSET_W;
        for (int iter = 0; iter < PowerConstants.BISECTION_ITERATIONS; iter++) {
            double mid = 0.5 * (xLo + xHi);
            if (minBalance(tiles, crr, mass, cp, wPrimeMax, pNonClimb, mid) >= reserve) {
                xLo = mid;
            } else {
                xHi = mid;
            }
        }
        double power = cp + xLo;

        // Collect the target climb's tiles (in order) and estimate them at the solved power.
        int count = 0;
        for (RouteTile t : tiles) {
            if (t.climbIndex == targetClimbIndex) count++;
        }
        if (count == 0) {
            return null;
        }
        int[] dist = new int[count];
        double[] grad = new double[count];
        double[] segCrr = new double[count];
        int k = 0;
        for (int i = 0; i < n; i++) {
            RouteTile t = tiles.get(i);
            if (t.climbIndex == targetClimbIndex) {
                dist[k] = t.distanceMeters;
                grad[k] = t.gradient;
                segCrr[k] = crr[i];
                k++;
            }
        }
        return ClimbTimeEstimator.estimateAtFixedPower(dist, grad, segCrr, mass, power);
    }

    /** Simulates the whole route at offset x and returns the lowest W'-balance reached. */
    private static double minBalance(List<RouteTile> tiles, double[] crr, double mass,
                                     double cp, double wPrimeMax, double pNonClimb, double x) {
        WPrimeBalance bal = new WPrimeBalance(wPrimeMax, cp);
        double min = wPrimeMax;
        for (int i = 0; i < tiles.size(); i++) {
            RouteTile t = tiles.get(i);
            double power = t.isClimb() ? cp + x : pNonClimb;
            double v = PowerSpeedSolver.speedMetersPerSecond(power, mass, t.gradient, crr[i]);
            double dt = t.distanceMeters / v;
            bal.applyTile(power, dt);
            if (bal.current() < min) {
                min = bal.current();
            }
        }
        return min;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.domain.RouteAwareClimbEstimatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/RouteAwareClimbEstimator.java \
        android/app/src/test/java/nl/paree/climbpro/domain/RouteAwareClimbEstimatorTest.java
git commit -m "feat(power): whole-route W'-balance climb-time estimator"
```

---

## Task 8: RouteEffortProfileBuilder

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/service/RouteEffortProfileBuilder.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/RouteEffortProfileBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/nl/paree/climbpro/service/RouteEffortProfileBuilderTest.java`:

```java
package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RouteEffortProfileBuilderTest {

    private static StoredSegment seg(int dist, double grad, int surface) {
        StoredSegment s = new StoredSegment();
        s.distance = dist;
        s.gradient = grad;
        s.surfaceType = surface;
        return s;
    }

    @Test
    public void nullArraysReturnNull() {
        StoredRoute r = new StoredRoute();
        r.distances = null;
        r.elevations = null;
        assertNull(RouteEffortProfileBuilder.build(r));
    }

    @Test
    public void climbSegmentsBecomeClimbTiles() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000};
        r.elevations = new double[]{0, 80, 160};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = 2000;
        List<StoredSegment> segs = new ArrayList<>();
        segs.add(seg(1000, 0.08, SurfaceType.GRAVEL));
        segs.add(seg(1000, 0.08, SurfaceType.GRAVEL));
        c.segments = segs;
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(2, tiles.size());
        assertEquals(0, tiles.get(0).climbIndex);
        assertEquals(SurfaceType.GRAVEL, tiles.get(0).surfaceType);
        assertEquals(0.08, tiles.get(0).gradient, 1e-9);
    }

    @Test
    public void nonClimbGapBecomesTilesWithDerivedGradient() {
        // Route: 0-1000 flat-ish non-climb, then climb 1000-3000.
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000, 3000};
        r.elevations = new double[]{0, 50, 130, 210}; // first 1km: +50m -> 5%
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3000;
        List<StoredSegment> segs = new ArrayList<>();
        segs.add(seg(2000, 0.08, SurfaceType.ASPHALT));
        c.segments = segs;
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        // First tile is the non-climb 0-1000 stretch.
        assertEquals(-1, tiles.get(0).climbIndex);
        assertEquals(1000, tiles.get(0).distanceMeters);
        assertEquals(0.05, tiles.get(0).gradient, 1e-9);
        // A climb tile follows.
        assertTrue(tiles.get(tiles.size() - 1).isClimb());
    }

    @Test
    public void surfaceSectionOverridesNonClimbSurface() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000};
        r.elevations = new double[]{0, 0};
        r.climbs = new ArrayList<>(); // no climbs -> whole route is one non-climb gap
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 0;
        s.endDistance = 1000;
        s.surfaceType = SurfaceType.COBBLESTONE;
        r.surfaceSections = new ArrayList<>();
        r.surfaceSections.add(s);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(SurfaceType.COBBLESTONE, tiles.get(0).surfaceType);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.service.RouteEffortProfileBuilderTest"`
Expected: FAIL — `RouteEffortProfileBuilder` does not exist.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/nl/paree/climbpro/service/RouteEffortProfileBuilder.java`:

```java
package nl.paree.climbpro.service;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * Builds the ordered {@link RouteTile} effort profile for a whole route, used by
 * the fatigue-aware {@link nl.paree.climbpro.domain.power.RouteAwareClimbEstimator}.
 *
 * Climb segments become climb tiles (tagged with the climb's list index). Stretches
 * between/around climbs become non-climb tiles whose gradient is derived from the
 * route's distance/elevation arrays and whose surface follows the precedence:
 * surfaceSection override -> containing flat segment -> asphalt.
 */
public final class RouteEffortProfileBuilder {

    private RouteEffortProfileBuilder() {}

    /** Returns null when the route lacks the distance/elevation arrays needed to model effort. */
    public static List<RouteTile> build(StoredRoute route) {
        if (route == null || route.distances == null || route.elevations == null
                || route.distances.length < 2
                || route.elevations.length < route.distances.length) {
            return null;
        }

        List<RouteTile> tiles = new ArrayList<>();
        int totalDistance = (int) Math.round(route.distances[route.distances.length - 1]);
        List<StoredClimb> climbs = route.climbs != null ? route.climbs : new ArrayList<>();

        int cursor = 0;
        for (int ci = 0; ci < climbs.size(); ci++) {
            StoredClimb climb = climbs.get(ci);
            if (climb.startDistance > cursor) {
                appendNonClimbTiles(tiles, route, cursor, climb.startDistance);
            }
            if (climb.segments != null) {
                for (StoredSegment seg : climb.segments) {
                    tiles.add(new RouteTile(seg.distance, seg.gradient, seg.surfaceType, ci));
                }
            }
            cursor = Math.max(cursor, climb.endDistance);
        }
        if (totalDistance > cursor) {
            appendNonClimbTiles(tiles, route, cursor, totalDistance);
        }
        return tiles;
    }

    private static void appendNonClimbTiles(List<RouteTile> tiles, StoredRoute route,
                                            int gapStart, int gapEnd) {
        double[] d = route.distances;
        double[] e = route.elevations;
        for (int i = 0; i + 1 < d.length; i++) {
            double a = Math.max(d[i], gapStart);
            double b = Math.min(d[i + 1], gapEnd);
            if (b <= a) {
                continue; // this point-pair is outside the gap
            }
            double pairLen = d[i + 1] - d[i];
            if (pairLen <= 0) {
                continue;
            }
            int tileLen = (int) Math.round(b - a);
            if (tileLen <= 0) {
                continue;
            }
            double grad = (e[i + 1] - e[i]) / pairLen;
            int surface = nonClimbSurface(route, (int) Math.round(a));
            tiles.add(new RouteTile(tileLen, grad, surface, -1));
        }
    }

    private static int nonClimbSurface(StoredRoute route, int distance) {
        if (route.surfaceSections != null) {
            for (StoredSurfaceSection s : route.surfaceSections) {
                if (distance >= s.startDistance && distance < s.endDistance) {
                    return s.surfaceType;
                }
            }
        }
        if (route.flatSegments != null) {
            for (StoredFlatSegment f : route.flatSegments) {
                if (distance >= f.startDistance && distance < f.endDistance) {
                    return f.surfaceType;
                }
            }
        }
        return SurfaceType.ASPHALT;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.service.RouteEffortProfileBuilderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/RouteEffortProfileBuilder.java \
        android/app/src/test/java/nl/paree/climbpro/service/RouteEffortProfileBuilderTest.java
git commit -m "feat(service): build route effort profile from StoredRoute"
```

---

## Task 9: Wire the route-aware estimate into ClimbDetailViewModel

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java`
- Test: existing `android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModelTest.java` (must stay green)

- [ ] **Step 1: Add imports and state fields**

In `ClimbDetailViewModel.java`, add imports:

```java
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.service.RouteEffortProfileBuilder;
```

Add fields next to `private volatile StoredClimb lastClimb;`:

```java
    private volatile StoredRoute lastRoute;
    private volatile int lastClimbIndex;
```

- [ ] **Step 2: Capture route + index in loadClimb**

In `loadClimb(...)`, inside the `try` block, set the fields. Replace:

```java
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                if (r.climbs != null && climbIndex < r.climbs.size()) {
                    StoredClimb loaded = r.climbs.get(climbIndex);
                    lastClimb = loaded;
                    climb.postValue(loaded);
                    computeEstimate(loaded);
```

with:

```java
                StoredRoute r = routeRepo.loadRoute(routeId);
                lastRoute = r;
                lastClimbIndex = climbIndex;
                route.postValue(r);
                if (r.climbs != null && climbIndex < r.climbs.size()) {
                    StoredClimb loaded = r.climbs.get(climbIndex);
                    lastClimb = loaded;
                    climb.postValue(loaded);
                    computeEstimate(loaded);
```

- [ ] **Step 3: Replace computeEstimate with the route-aware version + fallback**

Replace the whole `computeEstimate(StoredClimb c)` method with:

```java
    private void computeEstimate(StoredClimb c) {
        if (c.segments == null || c.segments.isEmpty()) {
            timeEstimate.postValue(null);
            return;
        }
        RiderProfile profile = riderRepo.load();

        // Preferred path: whole-route, fatigue-aware estimate.
        StoredRoute r = lastRoute;
        ClimbTimeEstimate estimate = null;
        if (r != null) {
            List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
            if (tiles != null) {
                estimate = RouteAwareClimbEstimator.estimate(tiles, lastClimbIndex, profile);
            }
        }

        // Fallback: fresh per-climb estimate when the route can't be profiled
        // (e.g. missing elevation/distance arrays) but the profile is usable.
        if (estimate == null && profile.isComplete()) {
            List<StoredSegment> segs = c.segments;
            int[] dist = new int[segs.size()];
            double[] grad = new double[segs.size()];
            int[] surface = new int[segs.size()];
            for (int i = 0; i < segs.size(); i++) {
                dist[i] = segs.get(i).distance;
                grad[i] = segs.get(i).gradient;
                surface[i] = segs.get(i).surfaceType;
            }
            estimate = ClimbTimeEstimator.estimate(dist, grad, surface, profile);
        }

        timeEstimate.postValue(estimate);
    }
```

- [ ] **Step 4: Run the affected tests**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.ui.climbs.ClimbDetailViewModelTest"`
Expected: PASS (the test uses an empty-segment climb, so `computeEstimate` returns null early and route posting is unaffected).

- [ ] **Step 5: Build the app module to confirm wiring compiles**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java
git commit -m "feat(climbs): use fatigue-aware route estimate with fresh fallback"
```

---

## Task 10: Ride-intensity setting in the UI

**Files:**
- Modify: `android/app/src/main/res/layout/activity_settings.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsActivity.java`

- [ ] **Step 1: Add the input to the layout**

In `activity_settings.xml`, insert this EditText immediately after the `input_bike_weight` EditText (before `btn_save_profile`):

```xml
            <EditText
                android:id="@+id/input_ride_intensity"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="Rit-intensiteit (% FTP, 40-95)"
                android:inputType="number"
                android:layout_marginBottom="8dp"/>
```

- [ ] **Step 2: Update SettingsViewModel.saveRiderProfile signature**

In `SettingsViewModel.java`, replace `saveRiderProfile(...)` with:

```java
    public void saveRiderProfile(int ftpWatts, double riderKg, double bikeKg, int rideIntensityPct) {
        RiderProfile profile = new RiderProfile(ftpWatts, riderKg, bikeKg, rideIntensityPct);
        riderRepo.save(profile);
        riderProfile.postValue(profile);
    }
```

- [ ] **Step 3: Populate and read the new field in SettingsActivity**

In `SettingsActivity.java`, in the `viewModel.riderProfile().observe(...)` block, after the `input_bike_weight` line add:

```java
            binding.inputRideIntensity.setText(String.valueOf(
                    profile.rideIntensityPct > 0
                            ? profile.rideIntensityPct
                            : nl.paree.climbpro.domain.power.RiderProfile.DEFAULT_RIDE_INTENSITY_PCT));
```

Replace the `btn_save_profile` click listener body with:

```java
        binding.btnSaveProfile.setOnClickListener(v -> {
            int ftp = parseIntSafe(binding.inputFtp.getText().toString());
            double rider = parseDoubleSafe(binding.inputRiderWeight.getText().toString());
            double bike = parseDoubleSafe(binding.inputBikeWeight.getText().toString());
            int intensityRaw = parseIntSafe(binding.inputRideIntensity.getText().toString());
            int intensity = intensityRaw <= 0
                    ? nl.paree.climbpro.domain.power.RiderProfile.DEFAULT_RIDE_INTENSITY_PCT
                    : Math.max(nl.paree.climbpro.domain.power.RiderProfile.RIDE_INTENSITY_MIN_PCT,
                        Math.min(nl.paree.climbpro.domain.power.RiderProfile.RIDE_INTENSITY_MAX_PCT, intensityRaw));
            viewModel.saveRiderProfile(ftp, rider, bike, intensity);
            Toast.makeText(this, "Profiel opgeslagen", Toast.LENGTH_SHORT).show();
        });
```

- [ ] **Step 4: Build to confirm the binding and wiring compile**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL (the generated `ActivitySettingsBinding` now exposes `inputRideIntensity`).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/layout/activity_settings.xml \
        android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsViewModel.java \
        android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsActivity.java
git commit -m "feat(settings): add ride-intensity input to rider profile"
```

---

## Task 11: Full test pass and documentation

**Files:**
- Modify: `Documentation/ARCHITECTURE.md` (estimate description, if present)
- Modify: `README.md` (feature list, if it mentions the time estimate)

- [ ] **Step 1: Run the full unit-test suite**

Run: `cd android && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Document the change**

In `Documentation/ARCHITECTURE.md`, find where the climb-time estimate is described (search for "ClimbTimeEstimator" or "estimate"). Add a short paragraph:

> The climb-detail time estimate is whole-route, fatigue-aware: all climbs are
> modelled at CP + x watts with a shared offset x solved by a W'-balance bisection
> over the entire route (`RouteAwareClimbEstimator`, fed by `RouteEffortProfileBuilder`).
> Non-climb stretches are ridden at the rider's configurable ride-intensity (% FTP).
> When a route lacks elevation/distance data the UI falls back to the fresh
> per-climb `ClimbTimeEstimator`. Phone-only; the wire payload is unchanged.

If `README.md` lists the time-estimate feature, update that line to mention it now
accounts for fatigue across the route and the ride-intensity setting.

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md
git commit -m "docs: describe fatigue-aware climb-time estimate"
```

---

## Self-Review Notes

- **Spec coverage:** ride-intensity setting (Tasks 2,3,10), W'-balance model (Task 4), bisection/Approach B (Task 7), tile builder with surface precedence + fallback (Task 8), wiring + fresh fallback (Task 9), constants incl. 10% reserve (Task 1), docs (Task 11). All design sections map to a task.
- **Type consistency:** `estimateAtFixedPower(int[],double[],double[],double,double)` defined in Task 5, called in Task 7. `RouteTile(int,double,int,int)` defined Task 6, used Tasks 7,8. `RouteAwareClimbEstimator.estimate(List<RouteTile>,int,RiderProfile)` defined Task 7, called Task 9. `RouteEffortProfileBuilder.build(StoredRoute)` defined Task 8, called Task 9. `saveRiderProfile(int,double,double,int)` defined Task 10. Names align across tasks.
- **Known modeling choices (from spec):** uniform `FTP+x` across climbs; single recovery τ; 10% finish reserve; constant non-climb power.
