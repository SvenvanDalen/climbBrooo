package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.WindImpactEstimator;

import java.time.Instant;
import java.util.List;

/**
 * Issue #47: glue between a stored climb, an Open-Meteo forecast and
 * {@link WindImpactEstimator}. Pure; returns null whenever no honest wind correction is
 * possible (no forecast, hour not covered, missing direction/speed, no route geometry), so the
 * UI can label the estimate as uncorrected.
 */
public final class ClimbWindImpact {

    private ClimbWindImpact() {}

    public static WindImpactEstimator.Result compute(StoredRoute r, StoredClimb c,
                                                     HourlyForecast forecast, Instant when,
                                                     double totalMassKg, double powerWatts) {
        if (forecast == null || c == null || c.segments == null || c.segments.isEmpty()) {
            return null;
        }
        int h = forecast.indexAt(when);
        if (h < 0) return null;
        List<StoredSegment> segs = c.segments;
        int n = segs.size();
        int[] dist = new int[n];
        double[] grad = new double[n];
        int[] surface = new int[n];
        for (int i = 0; i < n; i++) {
            dist[i] = segs.get(i).distance;
            grad[i] = segs.get(i).gradient;
            surface[i] = segs.get(i).surfaceType;
        }
        double[] bearings = ClimbSegmentBearings.compute(r, c);
        return WindImpactEstimator.estimate(dist, grad, surface, bearings, totalMassKg, powerWatts,
                forecast.windKmh[h], forecast.windDirDeg[h]);
    }
}
