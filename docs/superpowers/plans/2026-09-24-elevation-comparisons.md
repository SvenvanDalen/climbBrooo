# Elevation Comparisons (issue #251) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Follow superpowers:test-driven-development for every code step.

**Goal:** Translate elevation-gain totals into recognisable comparisons ("3,2× de Eiffeltoren", "halve Mont Ventoux") on the Wrapped screen and the route passport.

**Architecture:** One pure domain class `ElevationComparisons` holds a fixed landmark table and turns metres into a Dutch phrase. Two UI call sites (Wrapped card, route-detail passport line) render it. No persistence, no network.

**Tech Stack:** Java 17 (Android, minSdk 26, compileSdk 34), JUnit 4, Gradle Groovy DSL.

**Spec:** GitHub issue #251 ("Vertaal je hoogtemeters naar herkenbare vergelijkingen (bv. '3× de Eiffeltoren', 'halve Mont Ventoux'). Maakt statistieken tastbaar en deelbaar. Telefoon-only, eenvoudige omrekening. Geen wire-format wijziging.")

## Global Constraints

- Phone-only: no changes under `protocol/`, `garmin*/`, or to `ClimbPayloadBuilder`.
- Java, not Kotlin. minSdk 26 without desugaring: do NOT use `List.of`/`Set.of`/`Map.of` (API 30+); use `Arrays.asList`/`Collections`.
- User-facing text is Dutch; numbers use a decimal comma (`Locale("nl")` / `Locale.GERMANY` formatting).
- Build/test command (run from `android/`): `./gradlew :app:testDebugUnitTest :app:assembleDebug -Djavax.net.ssl.trustStoreType=Windows-ROOT --console=plain`
- Single test: `./gradlew :app:testDebugUnitTest --tests "<FQCN>" -Djavax.net.ssl.trustStoreType=Windows-ROOT`
- Branch from `origin/main` as `feat/elevation-comparisons`; PR body ends with `Closes #251` and the Claude Code footer; commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- 0 or negative metres → no comparison at all (UI hides the line), never "0× …".
- Less than the smallest landmark (e.g. 40 m) → percentage phrase, not "0,4×".
- Very large totals (100 000 m) → uses Mount Everest with an integer ratio, not an absurd decimal.
- A value right at half a big landmark (≈ 800 m for Mont Ventoux 1 610 hm) → "halve Mont Ventoux" wording.
- Rounding: 2,96× must show "3,0×" (one decimal), ratios ≥ 10 show as whole numbers.
  (All five are pinned by tests in Task 1.)

---

### Task 1: `ElevationComparisons` domain class

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ElevationComparisons.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/climb/ElevationComparisonsTest.java`

**Interfaces:**
- Produces: `public static String describe(long metres)` → Dutch phrase or `null` when `metres <= 0`.

Landmark table (height in metres, ascending), name as it appears after the ratio:
Domtoren 112 · Euromast 185 · Eiffeltoren 330 · Burj Khalifa 828 · Alpe d'Huez 1 071 (klim) · Mont Ventoux 1 610 (klim vanaf Bédoin) · Kilimanjaro 5 895 · Mount Everest 8 849.

Rules:
1. `metres <= 0` → `null`.
2. **"Halve" first**: iterate landmarks descending; for a landmark with `h >= 1000` and `|metres − h/2| <= 0.05·h/2` → `"een halve " + name`.
3. Otherwise pick the **largest landmark with `h <= metres`**. None (metres < 112) → `Math.round(100·metres/112) + "% van de Domtoren"`.
4. ratio = metres / h; ratio < 10 → one decimal with comma (`"3,0"`), else `Math.round(ratio)`; result `ratio + "× de " + name` for towers/buildings (Domtoren, Euromast, Eiffeltoren, Burj Khalifa) and `ratio + "× " + name` for mountains/climbs (no article).

- [ ] **Step 1: Write the failing tests** (expectations hand-computed from the rules above)

```java
package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ElevationComparisonsTest {

    @Test public void nothingForZeroOrNegative() {
        assertNull(ElevationComparisons.describe(0));
        assertNull(ElevationComparisons.describe(-5));
    }

    @Test public void belowSmallestLandmarkIsPercentage() {
        assertEquals("36% van de Domtoren", ElevationComparisons.describe(40));   // 40/112
    }

    @Test public void picksLargestLandmarkNotExceedingTotal() {
        assertEquals("1,8× de Euromast", ElevationComparisons.describe(329));     // 329/185
        assertEquals("2,1× de Eiffeltoren", ElevationComparisons.describe(700));  // 700/330
        assertEquals("1,2× de Burj Khalifa", ElevationComparisons.describe(988)); // 988/828
    }

    @Test public void mountainsHaveNoArticle() {
        assertEquals("1,6× Mont Ventoux", ElevationComparisons.describe(2_500));  // 2500/1610
    }

    @Test public void halfOfABigClimb() {
        assertEquals("een halve Mont Ventoux", ElevationComparisons.describe(800)); // within 5% of 805
    }

    @Test public void oneDecimalRoundsUpToWholeValue() {
        assertEquals("3,0× Mont Ventoux", ElevationComparisons.describe(4_766));  // 2,96
    }

    @Test public void largeRatiosAreWholeNumbers() {
        assertEquals("11× Mount Everest", ElevationComparisons.describe(100_000)); // 11,3
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "nl.paree.climbpro.domain.climb.ElevationComparisonsTest" -Djavax.net.ssl.trustStoreType=Windows-ROOT` → compilation failure (class missing).

- [ ] **Step 3: Implement**

```java
package nl.paree.climbpro.domain.climb;

import java.util.Locale;

/**
 * Makes elevation totals tangible (issue #251): "3,2× de Eiffeltoren", "een halve Mont
 * Ventoux". Pure; phone-only presentation helper.
 */
public final class ElevationComparisons {

    private static final class Landmark {
        final String name;
        final int heightM;
        final boolean building;
        Landmark(String name, int heightM, boolean building) {
            this.name = name; this.heightM = heightM; this.building = building;
        }
    }

    /** Ascending by height. Climbs use their elevation gain, not the summit altitude. */
    private static final Landmark[] LANDMARKS = {
            new Landmark("Domtoren", 112, true),
            new Landmark("Euromast", 185, true),
            new Landmark("Eiffeltoren", 330, true),
            new Landmark("Burj Khalifa", 828, true),
            new Landmark("Alpe d'Huez", 1_071, false),
            new Landmark("Mont Ventoux", 1_610, false),
            new Landmark("Kilimanjaro", 5_895, false),
            new Landmark("Mount Everest", 8_849, false),
    };

    private ElevationComparisons() {}

    public static String describe(long metres) {
        if (metres <= 0) return null;
        for (int i = LANDMARKS.length - 1; i >= 0; i--) {
            Landmark l = LANDMARKS[i];
            double half = l.heightM / 2.0;
            if (l.heightM >= 1000 && Math.abs(metres - half) <= 0.05 * half) {
                return "een halve " + l.name;
            }
        }
        Landmark pick = null;
        for (Landmark l : LANDMARKS) if (l.heightM <= metres) pick = l;
        if (pick == null) {
            return Math.round(100.0 * metres / LANDMARKS[0].heightM) + "% van de " + LANDMARKS[0].name;
        }
        double ratio = (double) metres / pick.heightM;
        String r = ratio < 10 ? String.format(new Locale("nl"), "%.1f", ratio)
                : String.valueOf(Math.round(ratio));
        return r + "× " + (pick.building ? "de " : "") + pick.name;
    }
}
```

- [ ] **Step 4: Run the test** → PASS.
- [ ] **Step 5: Commit** — `feat(android): elevation comparisons domain helper (#251)`

### Task 2: Show comparisons on Wrapped and the route passport

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/wrapped/ClimbWrappedActivity.java` (`render(Summary s)`: the "Totale hoogtemeters" card)
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java` (`renderPassport(RoutePassport p)`)

**Interfaces:**
- Consumes: `ElevationComparisons.describe(long)`; `Summary.totalElevationGainM` (long); `RoutePassport.totalElevationGain` (int).

- [ ] **Step 1:** In `ClimbWrappedActivity.render`, replace
  `addCard("Totale hoogtemeters", formatMeters(s.totalElevationGainM));` with
```java
String cmp = ElevationComparisons.describe(s.totalElevationGainM);
addCard("Totale hoogtemeters", formatMeters(s.totalElevationGainM)
        + (cmp != null ? "\nDat is " + cmp : ""));
```
- [ ] **Step 2:** In `RouteDetailActivity.renderPassport`, after appending `" hm"`, append
```java
String cmp = nl.paree.climbpro.domain.climb.ElevationComparisons.describe(p.totalElevationGain);
if (cmp != null) sb.append(" (≈ ").append(cmp).append(")");
```
- [ ] **Step 3:** Run the full build/test command from Global Constraints → all tests pass, APK builds.
- [ ] **Step 4: Commit** — `feat(android): show elevation comparisons on Wrapped and route passport (#251)`; push; open PR (summary, test plan with the real test count, device checks: open Wrapped + a route).
