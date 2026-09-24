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
