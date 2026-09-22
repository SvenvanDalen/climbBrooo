package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.FtpEstimator;
import nl.paree.climbpro.domain.power.FtpEstimator.Bucket;
import nl.paree.climbpro.domain.power.FtpEstimator.Effort;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FtpEstimatorTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 72.0, 8.0); // 80 kg total

    private static int[] asphalt(int n) {
        int[] s = new int[n];
        Arrays.fill(s, SurfaceType.ASPHALT);
        return s;
    }

    // ---- inverse power / FTP round-trip against the forward ClimbTimeEstimator ----

    @Test
    public void recoversApproximateFtpFromEstimatedTime() {
        int[] dist = {1000, 1000, 1000, 1000, 1000}; // 5 km climb -> lands in the medium bucket
        double[] grad = {0.06, 0.07, 0.06, 0.08, 0.07};
        ClimbTimeEstimate estimate = ClimbTimeEstimator.estimate(dist, grad, asphalt(5), RIDER);
        assertNotNull(estimate);

        Effort effort = new Effort(dist, grad, asphalt(5), estimate.totalSeconds);
        double impliedFtp = FtpEstimator.impliedFtpWatts(effort, RIDER.totalMassKg());

        assertEquals("implied FTP should round-trip close to the original " + RIDER.ftpWatts,
                RIDER.ftpWatts, impliedFtp, RIDER.ftpWatts * 0.05);
    }

    @Test
    public void higherImpliedPowerForFasterTimeOnSameClimb() {
        int[] dist = {2000};
        double[] grad = {0.06};
        Effort slow = new Effort(dist, grad, asphalt(1), 700);
        Effort fast = new Effort(dist, grad, asphalt(1), 550);
        double mass = RIDER.totalMassKg();
        assertTrue("a faster time must imply more power",
                FtpEstimator.impliedPowerWatts(fast, mass) > FtpEstimator.impliedPowerWatts(slow, mass));
    }

    // ---- duration bucket assignment ----

    @Test
    public void bucketBoundaries() {
        assertEquals(Bucket.SHORT, FtpEstimator.bucketFor(180));
        assertEquals(Bucket.SHORT, FtpEstimator.bucketFor(479));
        assertEquals(Bucket.MEDIUM, FtpEstimator.bucketFor(480));
        assertEquals(Bucket.MEDIUM, FtpEstimator.bucketFor(1199));
        assertEquals(Bucket.LONG, FtpEstimator.bucketFor(1200));
        assertEquals(Bucket.LONG, FtpEstimator.bucketFor(3600));
        assertNull(FtpEstimator.bucketFor(179));
        assertNull(FtpEstimator.bucketFor(3601));
        assertNull(FtpEstimator.bucketFor(0));
    }

    // ---- suggestFtpWatts: best-per-bucket + overall-max-across-buckets ----

    private static Effort climbEffort(int lengthM, double gradient, int elapsedSec) {
        return new Effort(new int[]{lengthM}, new double[]{gradient}, new int[]{SurfaceType.ASPHALT}, elapsedSec);
    }

    @Test
    public void insufficientDataReturnsNull_noAttempts() {
        assertNull(FtpEstimator.suggestFtpWatts(new ArrayList<>(), RIDER));
        assertNull(FtpEstimator.suggestFtpWatts(null, RIDER));
    }

    @Test
    public void insufficientDataReturnsNull_singleEffort() {
        List<Effort> efforts = new ArrayList<>();
        efforts.add(climbEffort(3000, 0.07, 600)); // one medium-bucket effort only
        assertNull("a single data point must not produce a suggestion",
                FtpEstimator.suggestFtpWatts(efforts, RIDER));
    }

    @Test
    public void insufficientDataReturnsNull_sameBucketOnly() {
        List<Effort> efforts = new ArrayList<>();
        efforts.add(climbEffort(3000, 0.07, 600));
        efforts.add(climbEffort(2800, 0.065, 650)); // still medium bucket -> no duration spread
        assertNull("attempts confined to one bucket must not produce a suggestion",
                FtpEstimator.suggestFtpWatts(efforts, RIDER));
    }

    @Test
    public void insufficientDataReturnsNull_incompleteProfile() {
        List<Effort> efforts = new ArrayList<>();
        efforts.add(climbEffort(1000, 0.07, 300));
        efforts.add(climbEffort(6000, 0.06, 1500));
        assertNull(FtpEstimator.suggestFtpWatts(efforts, new RiderProfile(0, 72, 8)));
    }

    @Test
    public void suggestsOverallMaxAcrossPopulatedBuckets() {
        // Two duration buckets, each with two efforts of differing implied difficulty.
        // The weaker effort in each bucket must not win, and the suggestion must equal
        // whichever bucket's best implies the higher FTP.
        List<Effort> efforts = new ArrayList<>();
        efforts.add(climbEffort(1200, 0.09, 250));  // short bucket, hard
        efforts.add(climbEffort(1200, 0.05, 300));  // short bucket, easier -> should lose
        efforts.add(climbEffort(6000, 0.05, 1400)); // medium/long-ish bucket, easier
        efforts.add(climbEffort(6000, 0.08, 1400)); // same duration, steeper -> should win its bucket

        Integer suggestion = FtpEstimator.suggestFtpWatts(efforts, RIDER);
        assertNotNull(suggestion);

        double mass = RIDER.totalMassKg();
        double bestShort = FtpEstimator.impliedFtpWatts(efforts.get(0), mass);
        double bestLong = FtpEstimator.impliedFtpWatts(efforts.get(3), mass);
        double expected = Math.max(bestShort, bestLong);
        assertEquals(Math.round(expected), suggestion.intValue());
    }

    // ---- implausible/mismatched efforts must never produce absurd suggestions ----

    @Test
    public void implausiblyFastEffortImpliesSaturatedNearMaxPower() {
        // 8 km at 10% covered in 200s (40 m/s uphill) is physically impossible — the
        // geometry doesn't match the elapsed time at all (e.g. a resync re-segmented the
        // climb underneath a stored attempt). The bisection has nowhere to go but its
        // ceiling.
        Effort impossible = climbEffort(8000, 0.10, 200);
        double power = FtpEstimator.impliedPowerWatts(impossible, RIDER.totalMassKg());
        assertTrue("mismatched geometry/time must saturate the bisection near its ceiling",
                power > 1900);
    }

    @Test
    public void implausibleEffortIsExcludedRatherThanSkewingSuggestion() {
        // A normal medium-bucket and long-bucket effort give a sane, expected suggestion.
        List<Effort> sane = new ArrayList<>();
        sane.add(climbEffort(3000, 0.06, 700));   // medium bucket
        sane.add(climbEffort(6000, 0.07, 1400));  // long bucket
        Integer saneSuggestion = FtpEstimator.suggestFtpWatts(sane, RIDER);
        assertNotNull(saneSuggestion);
        assertTrue(saneSuggestion < FtpEstimator.MAX_PLAUSIBLE_FTP_WATTS);

        // Adding a geometry-mismatched short-bucket effort (implied power saturates at
        // ~MAX_POWER_WATTS) must NOT change the outcome — it must be excluded from bucket
        // selection entirely, not trusted as a real (huge) implied FTP.
        List<Effort> withBadEffort = new ArrayList<>(sane);
        withBadEffort.add(climbEffort(8000, 0.10, 200)); // short bucket, impossible pace
        Integer suggestionWithBad = FtpEstimator.suggestFtpWatts(withBadEffort, RIDER);

        assertEquals("a saturated/implausible effort must be excluded, not change the result",
                saneSuggestion, suggestionWithBad);
    }

    @Test
    public void onlyImplausibleEffortsAcrossBucketsYieldsNoSuggestion() {
        // Every candidate effort is a geometry/time mismatch (saturated) — even though
        // they nominally span buckets, none is usable, so there must be no suggestion.
        List<Effort> efforts = new ArrayList<>();
        efforts.add(climbEffort(8000, 0.10, 200));   // short bucket, impossible pace
        efforts.add(climbEffort(20000, 0.10, 900));  // medium bucket, impossible pace
        assertNull("only-saturated efforts must never produce a suggestion",
                FtpEstimator.suggestFtpWatts(efforts, RIDER));
    }

    // ---- meaningful-difference gating ----

    @Test
    public void withinThresholdIsNotMeaningfullyDifferent() {
        assertFalse(FtpEstimator.isMeaningfullyDifferent(250, 250));
        assertFalse(FtpEstimator.isMeaningfullyDifferent(255, 250));
        assertFalse(FtpEstimator.isMeaningfullyDifferent(245, 250));
        assertFalse(FtpEstimator.isMeaningfullyDifferent(259, 250)); // just under the 10 W threshold
    }

    @Test
    public void beyondThresholdIsMeaningfullyDifferent() {
        assertTrue(FtpEstimator.isMeaningfullyDifferent(261, 250));
        assertTrue(FtpEstimator.isMeaningfullyDifferent(239, 250));
    }
}
