package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.data.route.StoredRoute;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Expected temperature along a planned ride (issue #153): every route sample gets the forecast
 * of its own location at the moment the rider is expected to pass it, given a start time and
 * a ride duration spread evenly over the distance. Phone-only; pure.
 */
public final class TemperatureTrend {

    /** Pace used when no pacing plan is available (no rider profile): 25 km/h. */
    public static final double DEFAULT_SPEED_MPS = 25.0 / 3.6;

    private static final Locale NL = new Locale("nl");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    public static final class Point {
        /** Cumulative route distance in metres. */
        public final double distanceM;
        /** Expected moment the rider passes this point. */
        public final Instant eta;
        public final double celsius;

        public Point(double distanceM, Instant eta, double celsius) {
            this.distanceM = distanceM;
            this.eta = eta;
            this.celsius = celsius;
        }
    }

    /** Points with a known temperature, in route order. */
    public final List<Point> points;
    /** Samples left out because the forecast did not cover them (e.g. ride runs past it). */
    public final int missing;
    public final double minCelsius;
    public final double maxCelsius;

    private TemperatureTrend(List<Point> points, int missing) {
        this.points = Collections.unmodifiableList(points);
        this.missing = missing;
        double min = Double.NaN;
        double max = Double.NaN;
        for (Point p : points) {
            if (Double.isNaN(min) || p.celsius < min) min = p.celsius;
            if (Double.isNaN(max) || p.celsius > max) max = p.celsius;
        }
        this.minCelsius = min;
        this.maxCelsius = max;
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /**
     * Sample {@code k} is read from grid location {@code k}. Its arrival time is {@code start}
     * plus {@code rideSeconds} scaled by its distance from the first sample.
     */
    public static TemperatureTrend compute(List<RouteSampler.Sample> samples,
                                           TemperatureGrid grid, Instant start,
                                           long rideSeconds) {
        List<Point> out = new ArrayList<>();
        int missing = 0;
        if (samples == null || samples.isEmpty() || grid == null || start == null) {
            return new TemperatureTrend(out, 0);
        }
        double d0 = samples.get(0).distanceM;
        double span = samples.get(samples.size() - 1).distanceM - d0;
        long secs = Math.max(0L, rideSeconds);
        for (int k = 0; k < samples.size(); k++) {
            RouteSampler.Sample s = samples.get(k);
            double f = span > 0 ? (s.distanceM - d0) / span : 0;
            Instant eta = start.plusSeconds(Math.round(f * secs));
            double c = grid.at(k, eta);
            if (Double.isNaN(c)) {
                missing++;
            } else {
                out.add(new Point(s.distanceM, eta, c));
            }
        }
        return new TemperatureTrend(out, missing);
    }

    /** The planned duration when known ({@code > 0}), else the route length at 25 km/h. */
    public static long rideSeconds(long plannedSeconds, double lengthM) {
        if (plannedSeconds > 0) return plannedSeconds;
        if (!(lengthM > 0)) return 0;
        return Math.round(lengthM / DEFAULT_SPEED_MPS);
    }

    /** The next occurrence of {@code hour:minute} in {@code zone}: today, or tomorrow if past. */
    public static Instant nextStart(Instant now, int hour, int minute, ZoneId zone) {
        ZonedDateTime nowZ = now.atZone(zone).truncatedTo(ChronoUnit.MINUTES);
        LocalDate today = nowZ.toLocalDate();
        ZonedDateTime candidate = today.atTime(hour, minute).atZone(zone);
        if (candidate.isBefore(nowZ)) candidate = today.plusDays(1).atTime(hour, minute).atZone(zone);
        return candidate.toInstant();
    }

    /**
     * Route elevation at each sample's distance, linearly interpolated, so the forecast can be
     * corrected to the real height (matters on climbs). NaN where the route has no elevations.
     */
    public static double[] elevationsAt(StoredRoute r, List<RouteSampler.Sample> samples) {
        double[] out = new double[samples.size()];
        java.util.Arrays.fill(out, Double.NaN);
        if (r == null || r.distances == null || r.elevations == null) return out;
        int n = Math.min(r.distances.length, r.elevations.length);
        if (n == 0) return out;
        int i = 1;
        for (int k = 0; k < samples.size(); k++) {
            double d = samples.get(k).distanceM;
            if (n == 1 || d <= r.distances[0]) {
                out[k] = r.elevations[0];
                continue;
            }
            while (i < n - 1 && r.distances[i] < d) i++;
            double span = r.distances[i] - r.distances[i - 1];
            double t = span > 0 ? (d - r.distances[i - 1]) / span : 0;
            t = Math.max(0, Math.min(1, t));
            out[k] = r.elevations[i - 1] + t * (r.elevations[i] - r.elevations[i - 1]);
        }
        return out;
    }

    /** Dutch multi-line summary: start, finish, warmest and coldest point. */
    public String describe(ZoneId zone) {
        return describe(zone, nl.paree.climbpro.domain.units.UnitPreferences.METRIC);
    }

    /** As {@link #describe(ZoneId)} in the rider's display units (issue #262). */
    public String describe(ZoneId zone, nl.paree.climbpro.domain.units.UnitPreferences units) {
        nl.paree.climbpro.domain.units.UnitFormatter fmt =
                new nl.paree.climbpro.domain.units.UnitFormatter(units, NL);
        if (points.isEmpty()) return "Geen temperatuurverwachting beschikbaar voor dit tijdstip.";
        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        Point warm = first;
        Point cold = first;
        for (Point p : points) {
            if (p.celsius > warm.celsius) warm = p;
            if (p.celsius < cold.celsius) cold = p;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Start ").append(time(first, zone)).append(": ").append(deg(first.celsius, fmt));
        sb.append("\nFinish ").append(time(last, zone)).append(": ").append(deg(last.celsius, fmt));
        sb.append("\nWarmst: ").append(deg(warm.celsius, fmt)).append(" rond ").append(km(warm, fmt))
                .append(" (").append(time(warm, zone)).append(')');
        sb.append("\nKoudst: ").append(deg(cold.celsius, fmt)).append(" rond ").append(km(cold, fmt))
                .append(" (").append(time(cold, zone)).append(')');
        sb.append(" · verschil ").append(degDelta(maxCelsius - minCelsius, fmt));
        if (missing > 0) {
            sb.append("\nLet op: ").append(missing)
                    .append(missing == 1 ? " punt valt" : " punten vallen")
                    .append(" buiten de verwachting en ontbreken in de grafiek.");
        }
        return sb.toString();
    }

    private static String time(Point p, ZoneId zone) {
        return HOUR.format(p.eta.atZone(zone));
    }

    private static String deg(double c, nl.paree.climbpro.domain.units.UnitFormatter fmt) {
        return Math.round(fmt.temperatureValue(c)) + " " + fmt.temperatureUnit();
    }

    /** A temperature difference: °F scales by 9/5 but has no +32 offset. */
    private static String degDelta(double dc, nl.paree.climbpro.domain.units.UnitFormatter fmt) {
        double d = fmt.preferences().fahrenheit ? dc * 9.0 / 5.0 : dc;
        return Math.round(d) + " " + fmt.temperatureUnit();
    }

    private static String km(Point p, nl.paree.climbpro.domain.units.UnitFormatter fmt) {
        return fmt.distanceUnit() + " " + String.format(NL, "%.0f", fmt.distanceValue(p.distanceM));
    }
}
