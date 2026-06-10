# Climb Time Estimate (FTP-based) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show an estimated climbing time per climb (and per segment) on the phone, computed from the rider's FTP, body weight and bike weight, using a time-dependent power model.

**Architecture:** Pure-Java domain layer does all the physics and is fully unit-tested: a power→speed solver (power-balance equation), a Critical-Power power-duration model (longer climbs ⇒ power closer to FTP), a per-surface rolling-resistance lookup (each segment's `surfaceType` maps to its own Crr), and an estimator that finds the self-consistent climb duration by fixed-point iteration. Rider settings (FTP, rider kg, bike kg) live in `SharedPreferences` via a thin repository and are edited in the existing Settings screen. The climb-detail screen computes the estimate on its background executor and renders total + per-segment times. **Phone-only** — no protocol/`schema.json`/Monkey C changes.

**Tech Stack:** Java 11, Android (MVVM + AndroidViewModel + LiveData), JUnit 4 unit tests, View Binding, `androidx.preference.PreferenceManager` for prefs.

---

## File Structure

New domain package `nl.paree.climbpro.domain.power` (pure Java, no Android deps — unit-testable like the existing `domain.segment` package):

- `PowerConstants.java` — single source of truth for physics constants.
- `RiderProfile.java` — immutable POJO: FTP watts, rider kg, bike kg; `totalMassKg()`, `isComplete()`.
- `SurfaceRollingResistance.java` — maps a segment's `surfaceType` to a rolling-resistance coefficient (Crr).
- `PowerSpeedSolver.java` — `speedMetersPerSecond(power, totalMass, gradient, crr)` via bisection on the power-balance equation.
- `PowerDurationModel.java` — `sustainablePower(ftpWatts, durationSeconds)` (Critical-Power 2-parameter).
- `ClimbTimeEstimate.java` — result POJO: total seconds, per-segment seconds, assumed power.
- `ClimbTimeEstimator.java` — fixed-point iteration tying the three together.
- `DurationFormat.java` — `format(seconds)` → `"m:ss"` / `"h:mm:ss"`.

New data class:

- `nl.paree.climbpro.data.rider.RiderProfileRepository.java` — load/save `RiderProfile` from default `SharedPreferences`.

Modified files:

- `ui/settings/SettingsViewModel.java` — expose + persist FTP / rider kg / bike kg.
- `ui/settings/SettingsActivity.java` — wire the three new inputs.
- `res/layout/activity_settings.xml` — add "Rider profile" section.
- `ui/climbs/ClimbDetailViewModel.java` — compute estimate after climb loads; `refreshEstimate()`.
- `ui/climbs/ClimbDetailActivity.java` — render total time; pass per-segment seconds to adapter; refresh on resume.
- `ui/climbs/ClimbSegmentAdapter.java` — render per-segment time.
- `res/layout/activity_climb_detail.xml` — add a time TextView.
- `res/layout/item_segment.xml` — add a per-segment time TextView.
- `Documentation/ARCHITECTURE.md` + `README.md` — document the feature (per repo convention for user-facing features).

Tests (in `android/app/src/test/java/nl/paree/climbpro/domain/`):

- `RiderProfileTest.java`, `SurfaceRollingResistanceTest.java`, `PowerSpeedSolverTest.java`, `PowerDurationModelTest.java`, `ClimbTimeEstimatorTest.java`, `DurationFormatTest.java`.

**Run all tests with:** `cd android && ./gradlew test`
**Run one class with:** `cd android && ./gradlew test --tests nl.paree.climbpro.domain.PowerSpeedSolverTest`
(On Windows use `.\gradlew.bat` instead of `./gradlew`.)

---

### Task 1: Physics constants

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/PowerConstants.java`

- [ ] **Step 1: Create the constants holder**

```java
package nl.paree.climbpro.domain.power;

/**
 * Physical constants for the climb-time power model. Kept in one place so the
 * solver, the power-duration model and the estimator agree.
 */
public final class PowerConstants {

    private PowerConstants() {}

    /** Gravitational acceleration (m/s^2). */
    public static final double GRAVITY = 9.81;
    /** Air density at ~15 C, sea level (kg/m^3). */
    public static final double AIR_DENSITY = 1.225;
    /** Drag area CdA for a rider on the hoods while climbing (m^2). */
    public static final double CDA = 0.40;
    /** Drivetrain efficiency: fraction of pedal power reaching the wheel. */
    public static final double DRIVETRAIN_EFFICIENCY = 0.97;

    /** Anaerobic work capacity W' for the Critical-Power model (joules). */
    public static final double W_PRIME = 20_000.0;

    /** Speed cap so descents/flat segments never yield absurd times (m/s ~= 90 km/h). */
    public static final double MAX_SPEED_MPS = 25.0;
    /** Lower clamp so a tiny positive speed never divides to a huge time (m/s). */
    public static final double MIN_SPEED_MPS = 0.3;
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/PowerConstants.java
git commit -m "feat(power): add physics constants for climb-time model"
```

---

### Task 1b: Surface rolling resistance

Maps each segment's `surfaceType` (see `domain.segment.SurfaceType`) to a rolling-resistance coefficient. Asphalt rolls easiest; cobblestones worst. Unknown falls back to asphalt because most routes are paved.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/SurfaceRollingResistance.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceRollingResistanceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.SurfaceRollingResistance;
import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;

import static org.junit.Assert.*;

public class SurfaceRollingResistanceTest {

    @Test
    public void asphaltRollsEasierThanLooseSurfaces() {
        double asphalt = SurfaceRollingResistance.crr(SurfaceType.ASPHALT);
        assertTrue(asphalt < SurfaceRollingResistance.crr(SurfaceType.GRAVEL));
        assertTrue(asphalt < SurfaceRollingResistance.crr(SurfaceType.DIRT));
        assertTrue(asphalt < SurfaceRollingResistance.crr(SurfaceType.COBBLESTONE));
    }

    @Test
    public void cobblestoneIsTheRoughest() {
        double cobble = SurfaceRollingResistance.crr(SurfaceType.COBBLESTONE);
        for (int st = 0; st <= 5; st++) {
            assertTrue("cobblestone Crr should be >= surface " + st,
                    cobble >= SurfaceRollingResistance.crr(st));
        }
    }

    @Test
    public void unknownFallsBackToAsphalt() {
        assertEquals(SurfaceRollingResistance.crr(SurfaceType.ASPHALT),
                SurfaceRollingResistance.crr(SurfaceType.UNKNOWN), 1e-9);
    }

    @Test
    public void outOfRangeFallsBackToAsphalt() {
        assertEquals(SurfaceRollingResistance.crr(SurfaceType.ASPHALT),
                SurfaceRollingResistance.crr(99), 1e-9);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.SurfaceRollingResistanceTest`
Expected: FAIL — `SurfaceRollingResistance` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.power;

import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * Maps a segment's surface type to a rolling-resistance coefficient (Crr).
 * Values are representative road/off-road figures; unknown surfaces fall back
 * to asphalt because most routes are paved.
 */
public final class SurfaceRollingResistance {

    private SurfaceRollingResistance() {}

    public static final double CRR_ASPHALT     = 0.005;
    public static final double CRR_GRAVEL      = 0.012;
    public static final double CRR_DIRT        = 0.017;
    public static final double CRR_COBBLESTONE = 0.025;
    public static final double CRR_MIXED       = 0.011;

    public static double crr(int surfaceType) {
        switch (SurfaceType.fromInt(surfaceType)) {
            case SurfaceType.GRAVEL:      return CRR_GRAVEL;
            case SurfaceType.DIRT:        return CRR_DIRT;
            case SurfaceType.COBBLESTONE: return CRR_COBBLESTONE;
            case SurfaceType.MIXED:       return CRR_MIXED;
            case SurfaceType.ASPHALT:
            case SurfaceType.UNKNOWN:
            default:                      return CRR_ASPHALT;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.SurfaceRollingResistanceTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/SurfaceRollingResistance.java android/app/src/test/java/nl/paree/climbpro/domain/SurfaceRollingResistanceTest.java
git commit -m "feat(power): map surface type to rolling resistance"
```

---

### Task 2: RiderProfile POJO

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/RiderProfile.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/RiderProfileTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.RiderProfile;
import org.junit.Test;

import static org.junit.Assert.*;

public class RiderProfileTest {

    @Test
    public void totalMassIsRiderPlusBike() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.5);
        assertEquals(80.5, p.totalMassKg(), 1e-9);
    }

    @Test
    public void completeWhenAllPositive() {
        assertTrue(new RiderProfile(250, 72.0, 8.5).isComplete());
    }

    @Test
    public void incompleteWhenFtpZero() {
        assertFalse(new RiderProfile(0, 72.0, 8.5).isComplete());
    }

    @Test
    public void incompleteWhenRiderWeightZero() {
        assertFalse(new RiderProfile(250, 0.0, 8.5).isComplete());
    }

    @Test
    public void incompleteWhenBikeWeightZero() {
        assertFalse(new RiderProfile(250, 72.0, 0.0).isComplete());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.RiderProfileTest`
Expected: FAIL — `RiderProfile` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.power;

/**
 * Immutable rider/bike profile used to estimate climbing time.
 * ftpWatts is functional threshold power in watts; weights are in kilograms.
 */
public final class RiderProfile {

    public final int ftpWatts;
    public final double riderWeightKg;
    public final double bikeWeightKg;

    public RiderProfile(int ftpWatts, double riderWeightKg, double bikeWeightKg) {
        this.ftpWatts = ftpWatts;
        this.riderWeightKg = riderWeightKg;
        this.bikeWeightKg = bikeWeightKg;
    }

    public double totalMassKg() {
        return riderWeightKg + bikeWeightKg;
    }

    /** True only when every field is set to a usable positive value. */
    public boolean isComplete() {
        return ftpWatts > 0 && riderWeightKg > 0 && bikeWeightKg > 0;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.RiderProfileTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/RiderProfile.java android/app/src/test/java/nl/paree/climbpro/domain/RiderProfileTest.java
git commit -m "feat(power): add RiderProfile model"
```

---

### Task 3: PowerSpeedSolver

Solves `P_wheel = m*g*(sinθ + Crr*cosθ)*v + 0.5*ρ*CdA*v³` for `v`, where `P_wheel = pedalPower * efficiency`, `θ = atan(gradient)`, and `Crr` is the segment's surface-dependent rolling resistance (Task 1b). Uses bisection because the function is monotonic in `v` for `v > 0` on climbs.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/PowerSpeedSolver.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/PowerSpeedSolverTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.PowerConstants;
import nl.paree.climbpro.domain.power.PowerSpeedSolver;
import nl.paree.climbpro.domain.power.SurfaceRollingResistance;
import org.junit.Test;

import static org.junit.Assert.*;

public class PowerSpeedSolverTest {

    private static final double ASPHALT = SurfaceRollingResistance.CRR_ASPHALT;

    // 250 W, 80 kg total, flat asphalt: real-world ~33-35 km/h (9.0-9.8 m/s).
    @Test
    public void flatGroundSpeedIsRealistic() {
        double v = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.0, ASPHALT);
        assertTrue("flat speed should be ~9-10 m/s but was " + v, v > 9.0 && v < 10.0);
    }

    // 250 W, 80 kg, 8% asphalt climb: real-world ~12-14 km/h (3.3-3.9 m/s).
    @Test
    public void steepClimbSpeedIsRealistic() {
        double v = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.08, ASPHALT);
        assertTrue("8% speed should be ~3.3-3.9 m/s but was " + v, v > 3.3 && v < 3.9);
    }

    @Test
    public void steeperIsSlower() {
        double v4 = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.04, ASPHALT);
        double v8 = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.08, ASPHALT);
        assertTrue("8% must be slower than 4%", v8 < v4);
    }

    @Test
    public void morePowerIsFaster() {
        double low  = PowerSpeedSolver.speedMetersPerSecond(200, 80, 0.06, ASPHALT);
        double high = PowerSpeedSolver.speedMetersPerSecond(300, 80, 0.06, ASPHALT);
        assertTrue("more power must be faster", high > low);
    }

    @Test
    public void moreMassIsSlowerOnAClimb() {
        double light = PowerSpeedSolver.speedMetersPerSecond(250, 70, 0.06, ASPHALT);
        double heavy = PowerSpeedSolver.speedMetersPerSecond(250, 95, 0.06, ASPHALT);
        assertTrue("heavier must be slower uphill", heavy < light);
    }

    @Test
    public void higherRollingResistanceIsSlower() {
        double smooth = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.04, ASPHALT);
        double rough  = PowerSpeedSolver.speedMetersPerSecond(
                250, 80, 0.04, SurfaceRollingResistance.CRR_COBBLESTONE);
        assertTrue("rougher surface must be slower", rough < smooth);
    }

    @Test
    public void descentSpeedIsCapped() {
        double v = PowerSpeedSolver.speedMetersPerSecond(250, 80, -0.10, ASPHALT);
        assertEquals("steep descent should clamp to MAX_SPEED", PowerConstants.MAX_SPEED_MPS, v, 1e-6);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.PowerSpeedSolverTest`
Expected: FAIL — `PowerSpeedSolver` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.power;

/**
 * Solves the steady-state cycling power-balance equation for road speed.
 *
 * P_wheel = m*g*(sinθ + Crr*cosθ)*v + 0.5*ρ*CdA*v³
 * where P_wheel = pedalPower * DRIVETRAIN_EFFICIENCY, θ = atan(gradient) and
 * Crr is the segment's surface-dependent rolling resistance.
 *
 * The right-hand side is strictly increasing in v for v >= 0 whenever the
 * (gravity + rolling) term is non-negative (i.e. climbs and flats), so a
 * bisection between 0 and MAX_SPEED converges. On descents where even
 * MAX_SPEED needs less power than supplied, speed is clamped to MAX_SPEED.
 */
public final class PowerSpeedSolver {

    private PowerSpeedSolver() {}

    public static double speedMetersPerSecond(double pedalPowerWatts,
                                              double totalMassKg,
                                              double gradientFraction,
                                              double crr) {
        double wheelPower = pedalPowerWatts * PowerConstants.DRIVETRAIN_EFFICIENCY;
        double theta = Math.atan(gradientFraction);
        double gravRoll = totalMassKg * PowerConstants.GRAVITY
                * (Math.sin(theta) + crr * Math.cos(theta));
        double dragCoef = 0.5 * PowerConstants.AIR_DENSITY * PowerConstants.CDA;

        // residual(v) = required wheel power at speed v minus the power we have.
        // Root is the equilibrium speed.
        double lo = 0.0;
        double hi = PowerConstants.MAX_SPEED_MPS;
        if (residual(hi, gravRoll, dragCoef, wheelPower) <= 0) {
            return PowerConstants.MAX_SPEED_MPS; // even at top speed we have power to spare
        }
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (residual(mid, gravRoll, dragCoef, wheelPower) > 0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        double v = 0.5 * (lo + hi);
        return Math.max(PowerConstants.MIN_SPEED_MPS, Math.min(PowerConstants.MAX_SPEED_MPS, v));
    }

    private static double residual(double v, double gravRoll, double dragCoef, double wheelPower) {
        return gravRoll * v + dragCoef * v * v * v - wheelPower;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.PowerSpeedSolverTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/PowerSpeedSolver.java android/app/src/test/java/nl/paree/climbpro/domain/PowerSpeedSolverTest.java
git commit -m "feat(power): add power-balance speed solver"
```

---

### Task 4: PowerDurationModel

Critical-Power 2-parameter model: `P(t) = CP + W'/t`, with `CP = FTP`. Longer efforts approach FTP; short efforts allow surges above FTP. This is the "time-dependent" intensity model.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/PowerDurationModel.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/PowerDurationModelTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.PowerDurationModel;
import org.junit.Test;

import static org.junit.Assert.*;

public class PowerDurationModelTest {

    @Test
    public void longerDurationGivesLowerPower() {
        double tenMin  = PowerDurationModel.sustainablePower(250, 600);
        double oneHour = PowerDurationModel.sustainablePower(250, 3600);
        assertTrue("longer effort must be lower power", oneHour < tenMin);
    }

    @Test
    public void shortDurationIsAboveFtp() {
        double twoMin = PowerDurationModel.sustainablePower(250, 120);
        assertTrue("short effort allows surge above FTP", twoMin > 250);
    }

    @Test
    public void approachesFtpForVeryLongEfforts() {
        double twoHours = PowerDurationModel.sustainablePower(250, 7200);
        // W'/7200 = 20000/7200 ~= 2.8 W above FTP
        assertEquals(250 + 20000.0 / 7200.0, twoHours, 1e-6);
    }

    @Test
    public void nonPositiveDurationReturnsFtp() {
        assertEquals(250.0, PowerDurationModel.sustainablePower(250, 0), 1e-9);
        assertEquals(250.0, PowerDurationModel.sustainablePower(250, -5), 1e-9);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.PowerDurationModelTest`
Expected: FAIL — `PowerDurationModel` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.power;

/**
 * Critical-Power 2-parameter model: P(t) = CP + W'/t.
 * We approximate CP with the rider's FTP and use the constant W_PRIME.
 * Returns the power a rider can sustain for the given duration.
 */
public final class PowerDurationModel {

    private PowerDurationModel() {}

    public static double sustainablePower(int ftpWatts, double durationSeconds) {
        if (durationSeconds <= 0) {
            return ftpWatts;
        }
        return ftpWatts + PowerConstants.W_PRIME / durationSeconds;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.PowerDurationModelTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/PowerDurationModel.java android/app/src/test/java/nl/paree/climbpro/domain/PowerDurationModelTest.java
git commit -m "feat(power): add critical-power power-duration model"
```

---

### Task 5: ClimbTimeEstimate result + ClimbTimeEstimator

The estimator iterates to a self-consistent duration: a climb's total time determines the sustainable power (Task 4), which (via Task 3) determines the speed on each segment, which sums back to the total time.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/ClimbTimeEstimate.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/ClimbTimeEstimator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/ClimbTimeEstimatorTest.java`

- [ ] **Step 1: Create the result POJO**

```java
package nl.paree.climbpro.domain.power;

/** Result of a climb-time estimate: total and per-segment seconds, plus the power assumed. */
public final class ClimbTimeEstimate {

    public final int totalSeconds;
    public final int[] segmentSeconds;
    public final double assumedPowerWatts;

    public ClimbTimeEstimate(int totalSeconds, int[] segmentSeconds, double assumedPowerWatts) {
        this.totalSeconds = totalSeconds;
        this.segmentSeconds = segmentSeconds;
        this.assumedPowerWatts = assumedPowerWatts;
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;

import static org.junit.Assert.*;

public class ClimbTimeEstimatorTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 72.0, 8.0); // 80 kg total

    /** Builds an all-asphalt surface array of the given length. */
    private static int[] asphalt(int n) {
        int[] s = new int[n];
        java.util.Arrays.fill(s, SurfaceType.ASPHALT);
        return s;
    }

    @Test
    public void incompleteProfileReturnsNull() {
        int[] dist = {1000, 1000};
        double[] grad = {0.06, 0.06};
        assertNull(ClimbTimeEstimator.estimate(dist, grad, asphalt(2), new RiderProfile(0, 72, 8)));
    }

    @Test
    public void totalEqualsSumOfSegmentSeconds() {
        int[] dist = {1000, 1000, 1000};
        double[] grad = {0.05, 0.06, 0.07};
        ClimbTimeEstimate e = ClimbTimeEstimator.estimate(dist, grad, asphalt(3), RIDER);
        int sum = 0;
        for (int s : e.segmentSeconds) sum += s;
        assertEquals(sum, e.totalSeconds);
        assertEquals(3, e.segmentSeconds.length);
    }

    // 2 km at 8% asphalt, 80 kg, ~FTP power: real-world ~9-11 min.
    @Test
    public void estimateIsInRealisticRange() {
        int[] dist = {1000, 1000};
        double[] grad = {0.08, 0.08};
        ClimbTimeEstimate e = ClimbTimeEstimator.estimate(dist, grad, asphalt(2), RIDER);
        assertTrue("2km@8% should be ~540-660s but was " + e.totalSeconds,
                e.totalSeconds > 540 && e.totalSeconds < 660);
    }

    @Test
    public void higherFtpIsFaster() {
        int[] dist = {2000};
        double[] grad = {0.07};
        int slow = ClimbTimeEstimator.estimate(dist, grad, asphalt(1), new RiderProfile(200, 72, 8)).totalSeconds;
        int fast = ClimbTimeEstimator.estimate(dist, grad, asphalt(1), new RiderProfile(320, 72, 8)).totalSeconds;
        assertTrue("higher FTP must be faster", fast < slow);
    }

    @Test
    public void rougherSurfaceIsSlower() {
        int[] dist = {2000};
        double[] grad = {0.05};
        int asphaltSecs = ClimbTimeEstimator.estimate(
                dist, grad, new int[]{SurfaceType.ASPHALT}, RIDER).totalSeconds;
        int cobbleSecs = ClimbTimeEstimator.estimate(
                dist, grad, new int[]{SurfaceType.COBBLESTONE}, RIDER).totalSeconds;
        assertTrue("cobblestone must be slower than asphalt", cobbleSecs > asphaltSecs);
    }

    @Test
    public void emptyClimbReturnsZeroTotal() {
        ClimbTimeEstimate e = ClimbTimeEstimator.estimate(new int[0], new double[0], new int[0], RIDER);
        assertEquals(0, e.totalSeconds);
        assertEquals(0, e.segmentSeconds.length);
    }

    @Test
    public void mismatchedArrayLengthsThrow() {
        try {
            ClimbTimeEstimator.estimate(new int[]{1000}, new double[]{0.05}, asphalt(2), RIDER);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.ClimbTimeEstimatorTest`
Expected: FAIL — `ClimbTimeEstimator` does not exist.

- [ ] **Step 4: Write minimal implementation**

```java
package nl.paree.climbpro.domain.power;

/**
 * Estimates how long a climb takes given a rider profile.
 *
 * The sustainable power depends on how long the effort lasts, and the effort
 * length depends on the power — so we solve for it by fixed-point iteration:
 * guess a duration, derive the power (PowerDurationModel), compute per-segment
 * speeds (PowerSpeedSolver, using each segment's surface Crr) and sum to a new
 * duration; repeat until stable.
 */
public final class ClimbTimeEstimator {

    private ClimbTimeEstimator() {}

    private static final int MAX_ITERATIONS = 12;
    private static final double CONVERGENCE_SECONDS = 0.5;
    private static final double INITIAL_GUESS_SPEED_MPS = 4.0;

    /** Returns null if the profile is incomplete (caller shows a hint instead). */
    public static ClimbTimeEstimate estimate(int[] segmentDistancesMeters,
                                             double[] segmentGradients,
                                             int[] segmentSurfaceTypes,
                                             RiderProfile profile) {
        if (segmentDistancesMeters.length != segmentGradients.length
                || segmentDistancesMeters.length != segmentSurfaceTypes.length) {
            throw new IllegalArgumentException(
                    "distances, gradients and surface types must be the same length");
        }
        if (!profile.isComplete()) {
            return null;
        }
        int n = segmentDistancesMeters.length;
        double mass = profile.totalMassKg();

        // Pre-resolve the per-segment rolling resistance once.
        double[] crr = new double[n];
        for (int i = 0; i < n; i++) {
            crr[i] = SurfaceRollingResistance.crr(segmentSurfaceTypes[i]);
        }

        int totalDistance = 0;
        for (int d : segmentDistancesMeters) totalDistance += d;
        if (n == 0 || totalDistance == 0) {
            return new ClimbTimeEstimate(0, new int[n], profile.ftpWatts);
        }

        double durationGuess = totalDistance / INITIAL_GUESS_SPEED_MPS;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double power = PowerDurationModel.sustainablePower(profile.ftpWatts, durationGuess);
            double total = totalSecondsAtPower(segmentDistancesMeters, segmentGradients, crr, mass, power);
            if (Math.abs(total - durationGuess) < CONVERGENCE_SECONDS) {
                durationGuess = total;
                break;
            }
            durationGuess = total;
        }

        // Final pass at the converged power, rounding per segment so the total
        // shown equals the sum of the per-segment values shown.
        double power = PowerDurationModel.sustainablePower(profile.ftpWatts, durationGuess);
        int[] segSeconds = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            double v = PowerSpeedSolver.speedMetersPerSecond(power, mass, segmentGradients[i], crr[i]);
            int secs = (int) Math.round(segmentDistancesMeters[i] / v);
            segSeconds[i] = secs;
            total += secs;
        }
        return new ClimbTimeEstimate(total, segSeconds, power);
    }

    private static double totalSecondsAtPower(int[] dist, double[] grad, double[] crr,
                                              double mass, double power) {
        double total = 0;
        for (int i = 0; i < dist.length; i++) {
            double v = PowerSpeedSolver.speedMetersPerSecond(power, mass, grad[i], crr[i]);
            total += dist[i] / v;
        }
        return total;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.ClimbTimeEstimatorTest`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/ClimbTimeEstimate.java android/app/src/main/java/nl/paree/climbpro/domain/power/ClimbTimeEstimator.java android/app/src/test/java/nl/paree/climbpro/domain/ClimbTimeEstimatorTest.java
git commit -m "feat(power): add climb-time estimator with fixed-point iteration"
```

---

### Task 6: DurationFormat

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/power/DurationFormat.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/DurationFormatTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.DurationFormat;
import org.junit.Test;

import static org.junit.Assert.*;

public class DurationFormatTest {

    @Test
    public void zeroSeconds() {
        assertEquals("0:00", DurationFormat.format(0));
    }

    @Test
    public void underOneMinute() {
        assertEquals("0:05", DurationFormat.format(5));
    }

    @Test
    public void minutesAndSeconds() {
        assertEquals("1:05", DurationFormat.format(65));
        assertEquals("12:34", DurationFormat.format(754));
    }

    @Test
    public void hoursMinutesSeconds() {
        assertEquals("1:01:01", DurationFormat.format(3661));
    }

    @Test
    public void negativeIsClampedToZero() {
        assertEquals("0:00", DurationFormat.format(-10));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.DurationFormatTest`
Expected: FAIL — `DurationFormat` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.power;

import java.util.Locale;

/** Formats a duration in seconds as "m:ss" or "h:mm:ss". */
public final class DurationFormat {

    private DurationFormat() {}

    public static String format(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.DurationFormatTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/power/DurationFormat.java android/app/src/test/java/nl/paree/climbpro/domain/DurationFormatTest.java
git commit -m "feat(power): add duration formatter"
```

---

### Task 7: RiderProfileRepository

Thin wrapper over default `SharedPreferences` (same mechanism `SettingsViewModel` already uses). No unit test — it is a direct pref read/write with no logic, consistent with the project's untested pref/IO wrappers.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/rider/RiderProfileRepository.java`

- [ ] **Step 1: Create the repository**

```java
package nl.paree.climbpro.data.rider;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.power.RiderProfile;

/** Persists the rider's FTP, body weight and bike weight in default SharedPreferences. */
public final class RiderProfileRepository {

    public static final String PREF_FTP_WATTS      = "rider_ftp_watts";
    public static final String PREF_RIDER_WEIGHT_KG = "rider_weight_kg";
    public static final String PREF_BIKE_WEIGHT_KG  = "bike_weight_kg";

    private final SharedPreferences prefs;

    public RiderProfileRepository(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public RiderProfile load() {
        int ftp = prefs.getInt(PREF_FTP_WATTS, 0);
        double rider = prefs.getFloat(PREF_RIDER_WEIGHT_KG, 0f);
        double bike = prefs.getFloat(PREF_BIKE_WEIGHT_KG, 0f);
        return new RiderProfile(ftp, rider, bike);
    }

    public void save(RiderProfile profile) {
        prefs.edit()
                .putInt(PREF_FTP_WATTS, profile.ftpWatts)
                .putFloat(PREF_RIDER_WEIGHT_KG, (float) profile.riderWeightKg)
                .putFloat(PREF_BIKE_WEIGHT_KG, (float) profile.bikeWeightKg)
                .apply();
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/rider/RiderProfileRepository.java
git commit -m "feat(rider): add RiderProfileRepository backed by SharedPreferences"
```

---

### Task 8: Rider-profile inputs in Settings

**Files:**
- Modify: `android/app/src/main/res/layout/activity_settings.xml` (add section before the final "Sync now" button, after the Strava divider at line 102-107)
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsActivity.java`

- [ ] **Step 1: Add the layout section**

In `activity_settings.xml`, insert this block immediately **before** the `<Button android:id="@+id/btn_sync_now" .../>` (currently at lines 109-113):

```xml
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:background="?android:attr/listDivider"
                android:layout_marginTop="16dp"
                android:layout_marginBottom="16dp"/>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Rider profile"
                android:textStyle="bold"
                android:textSize="16sp"
                android:layout_marginBottom="8dp"/>

            <EditText
                android:id="@+id/input_ftp"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="FTP (watts)"
                android:inputType="number"
                android:layout_marginBottom="8dp"/>

            <EditText
                android:id="@+id/input_rider_weight"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="Rider weight (kg)"
                android:inputType="numberDecimal"
                android:layout_marginBottom="8dp"/>

            <EditText
                android:id="@+id/input_bike_weight"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="Bike weight (kg)"
                android:inputType="numberDecimal"
                android:layout_marginBottom="8dp"/>

            <Button
                android:id="@+id/btn_save_profile"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Save rider profile"
                android:layout_marginBottom="16dp"/>

```

- [ ] **Step 2: Extend SettingsViewModel**

Add the repository field, LiveData and load/save methods. Apply these edits to `SettingsViewModel.java`:

Add imports near the other imports:

```java
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
```

Add fields after `private final StravaAuthRepository authRepo;`:

```java
    private final RiderProfileRepository riderRepo;
    private final MutableLiveData<RiderProfile> riderProfile = new MutableLiveData<>();
```

In the constructor, after `authRepo = new StravaAuthRepository(app);`, add:

```java
        riderRepo = new RiderProfileRepository(app);
```

Add an accessor next to the other `LiveData` accessors:

```java
    public LiveData<RiderProfile> riderProfile() { return riderProfile; }
```

In `reload()`, add at the end (before the closing brace):

```java
        riderProfile.postValue(riderRepo.load());
```

Add a save method (e.g. after `setRadiusKm`):

```java
    public void saveRiderProfile(int ftpWatts, double riderKg, double bikeKg) {
        RiderProfile profile = new RiderProfile(ftpWatts, riderKg, bikeKg);
        riderRepo.save(profile);
        riderProfile.postValue(profile);
    }
```

- [ ] **Step 3: Wire SettingsActivity**

Add to `SettingsActivity.onCreate(...)`, after the existing `viewModel.syncStatus().observe(...)` block:

```java
        viewModel.riderProfile().observe(this, profile -> {
            if (profile == null) return;
            binding.inputFtp.setText(profile.ftpWatts > 0 ? String.valueOf(profile.ftpWatts) : "");
            binding.inputRiderWeight.setText(
                    profile.riderWeightKg > 0 ? String.valueOf(profile.riderWeightKg) : "");
            binding.inputBikeWeight.setText(
                    profile.bikeWeightKg > 0 ? String.valueOf(profile.bikeWeightKg) : "");
        });

        binding.btnSaveProfile.setOnClickListener(v -> {
            int ftp = parseIntSafe(binding.inputFtp.getText().toString());
            double rider = parseDoubleSafe(binding.inputRiderWeight.getText().toString());
            double bike = parseDoubleSafe(binding.inputBikeWeight.getText().toString());
            viewModel.saveRiderProfile(ftp, rider, bike);
            Toast.makeText(this, "Profiel opgeslagen", Toast.LENGTH_SHORT).show();
        });
```

Add these helper methods to the `SettingsActivity` class (before the closing brace):

```java
    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
```

- [ ] **Step 4: Build to verify it compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (View Binding generates `inputFtp`, `inputRiderWeight`, `inputBikeWeight`, `btnSaveProfile` from the new IDs.)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/layout/activity_settings.xml android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsViewModel.java android/app/src/main/java/nl/paree/climbpro/ui/settings/SettingsActivity.java
git commit -m "feat(settings): add rider profile (FTP, rider + bike weight) inputs"
```

---

### Task 9: Compute the estimate in ClimbDetailViewModel

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java`

- [ ] **Step 1: Add imports**

Add near the existing imports:

```java
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;

import java.util.List;
```

- [ ] **Step 2: Add fields**

After `private final RouteRepository routeRepo;` add:

```java
    private final RiderProfileRepository riderRepo;
```

Add a LiveData next to the other `MutableLiveData` fields:

```java
    private final MutableLiveData<ClimbTimeEstimate> timeEstimate = new MutableLiveData<>();
```

Add a field to remember the loaded climb so we can recompute on resume:

```java
    private volatile StoredClimb lastClimb;
```

- [ ] **Step 3: Initialise the repository**

In the constructor, after `routeRepo = new RouteRepository(app);`, add:

```java
        riderRepo = new RiderProfileRepository(app);
```

- [ ] **Step 4: Expose the LiveData**

Add next to the other accessors:

```java
    public LiveData<ClimbTimeEstimate> timeEstimate() { return timeEstimate; }
```

- [ ] **Step 5: Compute after the climb loads**

In `loadClimb(...)`, replace the line `climb.postValue(r.climbs.get(climbIndex));` with:

```java
                    StoredClimb loaded = r.climbs.get(climbIndex);
                    lastClimb = loaded;
                    climb.postValue(loaded);
                    computeEstimate(loaded);
```

- [ ] **Step 6: Add the compute + refresh methods**

Add to the class (before `onCleared`):

```java
    /** Recompute using the latest saved rider profile (call from Activity.onResume). */
    public void refreshEstimate() {
        StoredClimb c = lastClimb;
        if (c != null) {
            executor.execute(() -> computeEstimate(c));
        }
    }

    private void computeEstimate(StoredClimb c) {
        if (c.segments == null || c.segments.isEmpty()) {
            timeEstimate.postValue(null);
            return;
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
        RiderProfile profile = riderRepo.load();
        timeEstimate.postValue(ClimbTimeEstimator.estimate(dist, grad, surface, profile));
    }
```

- [ ] **Step 7: Build to verify it compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java
git commit -m "feat(climb-detail): compute climb-time estimate from rider profile"
```

---

### Task 10: Show total estimated time in the climb-detail screen

**Files:**
- Modify: `android/app/src/main/res/layout/activity_climb_detail.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java`

- [ ] **Step 1: Add the TextView**

In `activity_climb_detail.xml`, immediately **after** the `@+id/climb_stats` TextView (closes at line 34), insert:

```xml
            <TextView
                android:id="@+id/climb_time_estimate"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="15sp"
                android:textStyle="bold"
                android:layout_marginBottom="12dp"/>
```

- [ ] **Step 2: Add import in the Activity**

Add near the other imports in `ClimbDetailActivity.java`:

```java
import nl.paree.climbpro.domain.power.DurationFormat;
```

- [ ] **Step 3: Observe the estimate**

In `onCreate(...)`, after the `viewModel.climb().observe(...)` block (it ends at line 94), add:

```java
        viewModel.timeEstimate().observe(this, estimate -> {
            if (estimate == null) {
                binding.climbTimeEstimate.setText(
                        "Stel je FTP en gewicht in (Instellingen) voor een tijdschatting");
                adapter.setSegmentSeconds(null);
            } else {
                binding.climbTimeEstimate.setText(String.format(
                        "Geschatte tijd: %s · %.0f W",
                        DurationFormat.format(estimate.totalSeconds),
                        estimate.assumedPowerWatts));
                adapter.setSegmentSeconds(estimate.segmentSeconds);
            }
        });
```

(`adapter.setSegmentSeconds(...)` is added in Task 11; this references it so do Task 11 in the same working session before building.)

- [ ] **Step 4: Refresh on resume**

In `ClimbDetailActivity.onResume()`, after `binding.mapView.onResume();`, add:

```java
        viewModel.refreshEstimate();
```

- [ ] **Step 5: Commit (after Task 11 compiles)**

This task is committed together with Task 11 since they are mutually dependent. See Task 11 Step 5.

---

### Task 11: Show per-segment time in the segment list

**Files:**
- Modify: `android/app/src/main/res/layout/item_segment.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbSegmentAdapter.java`

- [ ] **Step 1: Add the TextView to the row**

In `item_segment.xml`, insert this **between** the `@+id/segment_distance` TextView (closes at line 30) and the `@+id/segment_surface_badge` TextView (starts at line 32):

```xml
    <TextView
        android:id="@+id/segment_time"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="13sp"
        android:layout_marginEnd="8dp"
        android:textColor="?android:attr/textColorSecondary"/>
```

- [ ] **Step 2: Add import + field + setter to the adapter**

In `ClimbSegmentAdapter.java`, add the import:

```java
import nl.paree.climbpro.domain.power.DurationFormat;
```

Add a field next to `private List<StoredSegment> items = new ArrayList<>();`:

```java
    private int[] segmentSeconds; // null when no estimate available
```

Add a setter next to `setItems(...)`:

```java
    public void setSegmentSeconds(int[] seconds) {
        this.segmentSeconds = seconds;
        notifyDataSetChanged();
    }
```

- [ ] **Step 3: Render the time in onBindViewHolder**

In `onBindViewHolder`, after the line `h.distView.setText(s.distance + " m");`, add:

```java
        if (segmentSeconds != null && position < segmentSeconds.length) {
            h.timeView.setVisibility(View.VISIBLE);
            h.timeView.setText(DurationFormat.format(segmentSeconds[position]));
        } else {
            h.timeView.setVisibility(View.GONE);
        }
```

- [ ] **Step 4: Add the view to the ViewHolder**

In the `ViewHolder` inner class, add the field and lookup:

```java
        TextView timeView;
```

and in the `ViewHolder(View v)` constructor:

```java
            timeView = v.findViewById(R.id.segment_time);
```

- [ ] **Step 5: Build and commit Tasks 10 + 11 together**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add android/app/src/main/res/layout/activity_climb_detail.xml android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java android/app/src/main/res/layout/item_segment.xml android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbSegmentAdapter.java
git commit -m "feat(climb-detail): show estimated total and per-segment climb time"
```

---

### Task 12: Full test + build verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit-test suite**

Run: `cd android && ./gradlew test`
Expected: BUILD SUCCESSFUL — all existing tests plus the 6 new test classes pass.

- [ ] **Step 2: Build the debug APK**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: No commit needed** (verification only). If anything fails, fix it under the relevant task before continuing.

---

### Task 13: Documentation

Per repo convention (CLAUDE.md "Working in this repo"), user-facing features are documented in `Documentation/ARCHITECTURE.md` and `README.md`.

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: Document in ARCHITECTURE.md**

Add a subsection describing the climb-time estimate. Use this content (place it under the phone-app analysis section; adjust the surrounding heading level to match the file):

```markdown
### Climb time estimate (phone-only)

The phone estimates how long each climb takes from a rider profile (FTP in
watts, rider weight, bike weight) stored in `SharedPreferences`
(`RiderProfileRepository`). The estimate is computed on demand in the
climb-detail screen and is **not** part of the wire payload — the watch never
sees it.

The model lives in `domain.power`:

- `PowerSpeedSolver` solves the steady-state power-balance equation
  (`P = m·g·(sinθ + Crr·cosθ)·v + ½·ρ·CdA·v³`) for speed via bisection, taking
  the rolling resistance `Crr` as a parameter.
- `SurfaceRollingResistance` maps each segment's `surfaceType` (asphalt, gravel,
  dirt, cobblestone, mixed) to its own `Crr`, so rougher surfaces are estimated
  as slower. Unknown surfaces fall back to asphalt.
- `PowerDurationModel` is a Critical-Power 2-parameter curve `P(t) = FTP + W'/t`,
  so longer climbs are ridden closer to FTP and short climbs allow a surge.
- `ClimbTimeEstimator` ties them together by fixed-point iteration: a climb's
  duration sets the sustainable power, which sets per-segment speeds (each using
  its surface `Crr`), which sum back to the duration; iterate until stable. It
  returns total and per-segment seconds.

Constants (CdA, air density, drivetrain efficiency, W') live in `PowerConstants`;
the per-surface `Crr` values live in `SurfaceRollingResistance`.
```

- [ ] **Step 2: Document in README.md**

Add a bullet to the features list (match the existing list style):

```markdown
- **Estimated climb time** — per-climb and per-segment time estimates based on your FTP, body weight and bike weight (set these in Settings), with rolling resistance adjusted per segment surface (asphalt rolls faster than gravel/cobbles). Phone-only; not synced to the watch.
```

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md
git commit -m "docs: describe phone-only FTP-based climb-time estimate"
```

---

## Self-Review Notes

- **Spec coverage:** FTP entered separately ✓ (Task 8 `input_ftp`); rider weight + bike weight ✓ (Task 8); expected climb time ✓ (Tasks 5, 10, 11); time-dependent model ✓ (Task 4 Critical-Power + Task 5 fixed-point); surface-dependent rolling resistance ✓ (Task 1b `SurfaceRollingResistance`, threaded through Tasks 3, 5, 9); phone-only ✓ (no protocol/Monkey C files touched).
- **Type consistency:** `RiderProfile(int, double, double)` constructor used identically in Tasks 2, 5, 7, 8, 9. `PowerSpeedSolver.speedMetersPerSecond(double, double, double, double)` (last arg = Crr) used in Tasks 3 and 5. `SurfaceRollingResistance.crr(int)` defined in Task 1b, used in Task 5. `ClimbTimeEstimator.estimate(int[], double[], int[], RiderProfile)` returns `ClimbTimeEstimate` (nullable) — consumed the same way in Task 9. `ClimbTimeEstimate` fields `totalSeconds`, `segmentSeconds`, `assumedPowerWatts` used in Tasks 10/11. `adapter.setSegmentSeconds(int[])` defined in Task 11, called in Task 10. `DurationFormat.format(int)` defined in Task 6, used in Tasks 10/11.
- **Mutual dependency:** Tasks 10 and 11 reference each other (`setSegmentSeconds`) and are built + committed together (Task 11 Step 5).
- **No placeholders:** every code step contains complete code; every run step has an expected result.
```
