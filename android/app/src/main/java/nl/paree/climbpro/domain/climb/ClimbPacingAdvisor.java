package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.power.FtpEstimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Issue #64 "Gepersonaliseerd trainingsadvies na een klim": turns how the rider actually paced a
 * climb (the per-segment split times already stored on every {@link StoredClimbAttempt}) into a
 * few short, concrete tips — "begin de eerste 400 m rustiger", "hier verloor je de meeste tijd".
 * Phone-only presentation logic; nothing here reaches the watch.
 *
 * <h2>Effort, not speed</h2>
 * Raw speed per segment says little on a climb whose gradient varies: a steep final ramp is
 * always slow. Each segment's split is therefore converted to the steady pedal power that would
 * have ridden that segment in that time ({@link FtpEstimator#impliedPowerWatts}, the same
 * physics model the climb time estimate uses). Absolute watts are only as good as the mass and
 * CdA assumptions, but the <i>ratios</i> between segments — which is all the advice uses — are
 * robust against those.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>The climb is cut into distance thirds; each third's power is its work divided by its
 *       time. Last third ≥ {@link #PACING_THRESHOLD} below the first → {@link TipType#FADED};
 *       ≥ {@link #PACING_THRESHOLD} above → {@link TipType#HELD_BACK}; otherwise
 *       {@link TipType#EVEN}.</li>
 *   <li>The single segment furthest below the climb average, if at least
 *       {@link #WEAK_SEGMENT_THRESHOLD} below → {@link TipType#WEAKEST_SEGMENT} (skipped when it
 *       lies in the last third of a climb already flagged as faded — same message twice).</li>
 *   <li>With the rider's per-segment best splits, the segment with the biggest time loss against
 *       them, if at least {@link #MIN_PR_LOSS_SEC} → {@link TipType#LOST_MOST_VS_PR}.</li>
 * </ul>
 * Pure; the UI maps each {@link Tip} to a Dutch string resource.
 */
public final class ClimbPacingAdvisor {

    private ClimbPacingAdvisor() {}

    /** Relative first-vs-last-third power difference that counts as uneven pacing. */
    public static final double PACING_THRESHOLD = 0.10;
    /** A segment this far below the climb's average power is called out. */
    public static final double WEAK_SEGMENT_THRESHOLD = 0.15;
    /** Minimum loss (s) against the segment best before it is worth a tip. */
    public static final int MIN_PR_LOSS_SEC = 5;
    /** Below this many segments there are no meaningful thirds. */
    public static final int MIN_SEGMENTS = 3;
    /** Keep the advice short. */
    public static final int MAX_TIPS = 3;
    /** Rider + bike mass used when the rider profile has none. */
    public static final double DEFAULT_MASS_KG = 80.0;
    /** Segments descending more steeply than this have no meaningful implied power. */
    private static final double MIN_ANALYSED_GRADIENT = -0.005;

    public enum TipType { FADED, HELD_BACK, EVEN, WEAKEST_SEGMENT, LOST_MOST_VS_PR }

    /** One advice line. Only the fields relevant to {@link #type} are meaningful. */
    public static final class Tip {
        public final TipType type;
        /** FADED/HELD_BACK/EVEN: first-vs-last-third difference; WEAKEST_SEGMENT: drop below average. */
        public final int percent;
        /** FADED/HELD_BACK: length of the first third (rounded to 100 m); segment tips: segment start. */
        public final int startM;
        /** Segment tips: segment end (m from climb start). */
        public final int endM;
        /** Segment tips: 0-based segment index, else -1. */
        public final int segmentIndex;
        /** LOST_MOST_VS_PR: seconds lost against the segment best. */
        public final int seconds;
        /** Segment tips: the segment's gradient (fraction). */
        public final double gradient;

        Tip(TipType type, int percent, int startM, int endM, int segmentIndex, int seconds,
            double gradient) {
            this.type = type;
            this.percent = percent;
            this.startM = startM;
            this.endM = endM;
            this.segmentIndex = segmentIndex;
            this.seconds = seconds;
            this.gradient = gradient;
        }
    }

    /** The advice for one attempt; {@link #tips} is never empty. */
    public static final class Advice {
        public final List<Tip> tips;

        Advice(List<Tip> tips) {
            this.tips = Collections.unmodifiableList(tips);
        }
    }

    /**
     * The attempt the advice is about: the most recent (then highest pass) attempt of
     * {@code climbId} whose splits match the climb's current {@code segCount} and whose track
     * followed the climb (route-deviation splits are not comparable). Null if none.
     */
    public static StoredClimbAttempt latestAnalyzable(String climbId, int segCount,
                                                      List<StoredClimbAttempt> attempts) {
        if (climbId == null || attempts == null || segCount <= 0) return null;
        StoredClimbAttempt best = null;
        for (StoredClimbAttempt a : attempts) {
            if (a == null || !climbId.equals(a.climbId) || a.routeDeviation) continue;
            if (a.segSplitSec == null || a.segSplitSec.length != segCount) continue;
            if (best == null
                    || a.dateEpochSec > best.dateEpochSec
                    || (a.dateEpochSec == best.dateEpochSec && a.passIndex > best.passIndex)) {
                best = a;
            }
        }
        return best;
    }

    /**
     * @param segDist    per-segment length (m)
     * @param segGrad    per-segment gradient (fraction)
     * @param segSurface per-segment {@link nl.paree.climbpro.domain.segment.SurfaceType}
     * @param splitSec   the attempt's per-segment elapsed seconds
     * @param bestSplits per-segment best splits of this climb (may be null / other length: ignored)
     * @param massKg     rider + bike mass; non-positive falls back to {@link #DEFAULT_MASS_KG}
     * @return the advice, or null when the attempt can't be analysed (missing/mismatched splits,
     *         a zero split, too few segments).
     */
    public static Advice analyze(int[] segDist, double[] segGrad, int[] segSurface,
                                 int[] splitSec, int[] bestSplits, double massKg) {
        if (segDist == null || segGrad == null || segSurface == null || splitSec == null) {
            return null;
        }
        int n = segDist.length;
        if (n < MIN_SEGMENTS || segGrad.length != n || segSurface.length != n
                || splitSec.length != n) {
            return null;
        }
        int total = 0;
        for (int i = 0; i < n; i++) {
            if (segDist[i] <= 0 || splitSec[i] <= 0) return null;
            total += segDist[i];
        }
        double mass = massKg > 0 ? massKg : DEFAULT_MASS_KG;

        double[] power = new double[n];
        boolean[] analysed = new boolean[n];
        int[] third = new int[n];
        int[] startM = new int[n];
        int cum = 0;
        for (int i = 0; i < n; i++) {
            startM[i] = cum;
            double mid = cum + segDist[i] / 2.0;
            third[i] = Math.min(2, (int) (3 * mid / total));
            cum += segDist[i];
            analysed[i] = segGrad[i] >= MIN_ANALYSED_GRADIENT;
            if (analysed[i]) {
                FtpEstimator.Effort e = new FtpEstimator.Effort(new int[]{segDist[i]},
                        new double[]{segGrad[i]}, new int[]{segSurface[i]}, splitSec[i]);
                power[i] = FtpEstimator.impliedPowerWatts(e, mass);
            }
        }

        double[] work = new double[3];
        double[] time = new double[3];
        double allWork = 0, allTime = 0;
        for (int i = 0; i < n; i++) {
            if (!analysed[i]) continue;
            work[third[i]] += power[i] * splitSec[i];
            time[third[i]] += splitSec[i];
            allWork += power[i] * splitSec[i];
            allTime += splitSec[i];
        }

        List<Tip> tips = new ArrayList<>();
        boolean faded = false;
        if (time[0] > 0 && time[2] > 0) {
            double first = work[0] / time[0];
            double last = work[2] / time[2];
            int firstThirdM = roundTo100(total / 3);
            double rel = (last - first) / first;
            int pct = (int) Math.round(Math.abs(rel) * 100);
            if (rel <= -PACING_THRESHOLD) {
                faded = true;
                tips.add(new Tip(TipType.FADED, pct, firstThirdM, 0, -1, 0, 0));
            } else if (rel >= PACING_THRESHOLD) {
                tips.add(new Tip(TipType.HELD_BACK, pct, firstThirdM, 0, -1, 0, 0));
            } else {
                tips.add(new Tip(TipType.EVEN, pct, 0, 0, -1, 0, 0));
            }
        }

        if (allTime > 0) {
            double avg = allWork / allTime;
            int weakest = -1;
            double weakestRatio = 1;
            for (int i = 0; i < n; i++) {
                if (!analysed[i]) continue;
                double ratio = power[i] / avg;
                if (ratio < weakestRatio) {
                    weakestRatio = ratio;
                    weakest = i;
                }
            }
            if (weakest >= 0 && weakestRatio <= 1 - WEAK_SEGMENT_THRESHOLD
                    && !(faded && third[weakest] == 2)) {
                tips.add(new Tip(TipType.WEAKEST_SEGMENT,
                        (int) Math.round((1 - weakestRatio) * 100),
                        startM[weakest], startM[weakest] + segDist[weakest], weakest, 0,
                        segGrad[weakest]));
            }
        }

        if (bestSplits != null && bestSplits.length == n) {
            int worst = -1;
            int worstLoss = 0;
            for (int i = 0; i < n; i++) {
                int loss = splitSec[i] - bestSplits[i];
                if (loss > worstLoss) {
                    worstLoss = loss;
                    worst = i;
                }
            }
            if (worst >= 0 && worstLoss >= MIN_PR_LOSS_SEC) {
                tips.add(new Tip(TipType.LOST_MOST_VS_PR, 0, startM[worst],
                        startM[worst] + segDist[worst], worst, worstLoss, segGrad[worst]));
            }
        }

        if (tips.isEmpty()) return null;
        while (tips.size() > MAX_TIPS) tips.remove(tips.size() - 1);
        return new Advice(tips);
    }

    /** Rounds a distance to the nearest 100 m, never below 100 m. */
    static int roundTo100(int meters) {
        return Math.max(100, (int) Math.round(meters / 100.0) * 100);
    }
}
