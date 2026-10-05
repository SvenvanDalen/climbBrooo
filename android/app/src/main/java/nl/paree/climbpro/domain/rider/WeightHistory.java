package nl.paree.climbpro.domain.rider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rider's weight over time (issue #408), so the W/kg of an old attempt uses the weight of
 * that day instead of today's. The weight on a date is the latest measurement on or before it;
 * a date before the first measurement takes the first one (closer than the profile weight),
 * and without any measurement the profile weight is the fallback. Pure; phone-only.
 */
public final class WeightHistory {

    public static final double MIN_KG = 30;
    public static final double MAX_KG = 250;

    /** One measurement: the day it was taken and the weight in kg. */
    public static final class Point {
        public final LocalDate date;
        public final double kg;

        public Point(LocalDate date, double kg) {
            this.date = date;
            this.kg = kg;
        }
    }

    private final List<Point> points;
    private final double fallbackKg;
    private final ZoneId zone;

    /**
     * @param points     measurements in any order; implausible weights are ignored
     * @param fallbackKg profile weight for when there are no measurements; 0 = unknown
     */
    public WeightHistory(List<Point> points, double fallbackKg, ZoneId zone) {
        List<Point> valid = new ArrayList<>();
        if (points != null) {
            for (Point p : points) {
                if (p != null && p.date != null && isPlausible(p.kg)) valid.add(p);
            }
        }
        valid.sort((a, b) -> a.date.compareTo(b.date));
        this.points = Collections.unmodifiableList(valid);
        this.fallbackKg = fallbackKg;
        this.zone = zone;
    }

    public static boolean isPlausible(double kg) {
        return kg >= MIN_KG && kg <= MAX_KG;
    }

    /** Measurements, oldest first. */
    public List<Point> points() {
        return points;
    }

    /** Weight in kg on {@code date}; 0 when nothing is known. */
    public double weightOn(LocalDate date) {
        if (points.isEmpty()) return Math.max(0, fallbackKg);
        Point chosen = points.get(0);
        for (Point p : points) {
            if (p.date.isAfter(date)) break;
            chosen = p;
        }
        return chosen.kg;
    }

    /** Weight in kg at an epoch-second timestamp, in this history's time zone. */
    public double weightAt(long epochSec) {
        return weightOn(Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate());
    }

    /** Watts per kg at a timestamp, or null when the power or the weight is unknown. */
    public Double wattsPerKg(Integer watts, long epochSec) {
        if (watts == null || watts <= 0) return null;
        double kg = weightAt(epochSec);
        return kg > 0 ? watts / kg : null;
    }
}
