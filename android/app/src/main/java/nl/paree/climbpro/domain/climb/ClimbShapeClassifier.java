package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.segment.Segment;

import java.util.List;

/**
 * Classifies a climb's "shape" from its ordered per-segment gradients: STEADY, STEEP_FINISH,
 * EASY_START or IRREGULAR (see {@link ClimbShape}). Pure and static, no Android framework
 * dependency, mirroring {@link LogbookCalculator}'s style — safe to unit-test in a plain JVM
 * test and safe to call from both the initial-detection path ({@link ClimbDetector}) and
 * re-segmentation ({@code RouteRepository#reSegmentClimb}).
 *
 * <p>Heuristic (deliberately simple — this is a hint badge, not a scored metric):
 * <ol>
 *   <li>Split the segments into three equal-ish chunks and compare the average gradient of the
 *       last chunk to the first chunk. If the gap exceeds
 *       {@link ClimbConstants#SHAPE_TREND_DELTA_GRADIENT}, the climb has a real trend:
 *       steeper at the end → {@link ClimbShape#STEEP_FINISH}, gentler at the end →
 *       {@link ClimbShape#EASY_START}. A monotonic ramp also has high variance, but a clear
 *       trend is a more useful label than "irregular", so trend is checked first.</li>
 *   <li>Otherwise, compute the population standard deviation of all segment gradients. Above
 *       {@link ClimbConstants#SHAPE_IRREGULAR_STDDEV} with no clear trend means segments swing
 *       between steep and shallow with no pattern → {@link ClimbShape#IRREGULAR}.</li>
 *   <li>Otherwise the gradient is roughly constant throughout → {@link ClimbShape#STEADY}.</li>
 * </ol>
 * Climbs always have 4-32 segments (see {@link ClimbConstants#defaultSegmentCount()} and the
 * re-segment picker range), so the "thirds" split always has at least one segment per third.
 */
public final class ClimbShapeClassifier {

    private ClimbShapeClassifier() {}

    /**
     * @param gradients ordered segment gradients as fractions (0.072 = 7.2%), climb start to end.
     *                  Fewer than 2 segments always yields STEADY (not enough data for a trend
     *                  or a meaningful variance).
     */
    public static ClimbShape classify(double[] gradients) {
        if (gradients == null || gradients.length < 2) {
            return ClimbShape.STEADY;
        }

        int n = gradients.length;
        int thirdSize = Math.max(1, n / 3);

        double firstThirdAvg = average(gradients, 0, thirdSize);
        double lastThirdAvg  = average(gradients, n - thirdSize, n);
        double trendDelta    = lastThirdAvg - firstThirdAvg;

        if (trendDelta >= ClimbConstants.SHAPE_TREND_DELTA_GRADIENT) {
            return ClimbShape.STEEP_FINISH;
        }
        if (trendDelta <= -ClimbConstants.SHAPE_TREND_DELTA_GRADIENT) {
            return ClimbShape.EASY_START;
        }

        if (stdDev(gradients) > ClimbConstants.SHAPE_IRREGULAR_STDDEV) {
            return ClimbShape.IRREGULAR;
        }

        return ClimbShape.STEADY;
    }

    /** Convenience overload for the freshly detected/segmented domain model. */
    public static ClimbShape classify(List<Segment> segments) {
        if (segments == null) return ClimbShape.STEADY;
        double[] gradients = new double[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            gradients[i] = segments.get(i).gradient;
        }
        return classify(gradients);
    }

    /** Convenience overload for the persisted wire/storage model (used after re-segmentation). */
    public static ClimbShape classifyStored(List<StoredSegment> segments) {
        if (segments == null) return ClimbShape.STEADY;
        double[] gradients = new double[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            gradients[i] = segments.get(i).gradient;
        }
        return classify(gradients);
    }

    /**
     * Resolves the shape that should actually be shown/used for a stored climb: the user's
     * manual override (issue #36) when set, otherwise the auto-computed {@link StoredClimb#shape}
     * when set, otherwise a fresh classification of the currently stored segments. This is the
     * single place override-wins-over-auto logic lives — callers should never read
     * {@link StoredClimb#shape} or {@link StoredClimb#shapeOverride} directly.
     */
    public static ClimbShape effectiveShape(StoredClimb climb) {
        if (climb == null) return ClimbShape.STEADY;
        ClimbShape override = parse(climb.shapeOverride);
        if (override != null) return override;
        ClimbShape auto = parse(climb.shape);
        if (auto != null) return auto;
        return classifyStored(climb.segments);
    }

    private static ClimbShape parse(String name) {
        if (name == null) return null;
        try {
            return ClimbShape.valueOf(name);
        } catch (IllegalArgumentException unknownValue) {
            return null;
        }
    }

    private static double average(double[] values, int fromInclusive, int toExclusive) {
        double sum = 0;
        int count = toExclusive - fromInclusive;
        for (int i = fromInclusive; i < toExclusive; i++) sum += values[i];
        return count > 0 ? sum / count : 0;
    }

    private static double stdDev(double[] values) {
        double mean = average(values, 0, values.length);
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / values.length);
    }
}
