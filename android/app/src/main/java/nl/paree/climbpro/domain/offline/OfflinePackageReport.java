package nl.paree.climbpro.domain.offline;

import nl.paree.climbpro.domain.weather.HourlyForecast;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a stored {@link OfflinePackage} into readable text without any network (issue #200):
 * its age, the forecast at each point for the coming hours (or for the forecast's own first
 * hours when the package is from before now), and the POIs along the route by type.
 * Pure Java; Dutch text like the rest of the UI.
 */
public final class OfflinePackageReport {

    /** Hours of forecast summarised per point. */
    public static final int WINDOW_HOURS = 8;
    /** Older packages are flagged as stale. */
    public static final long STALE_MS = 24L * 60 * 60 * 1000;
    /** At most this many POIs listed per type. */
    static final int MAX_POIS_PER_TYPE = 15;

    private static final Map<String, String> TYPE_LABELS = new LinkedHashMap<>();
    static {
        TYPE_LABELS.put(OfflinePackage.Poi.WATER, "Water");
        TYPE_LABELS.put(OfflinePackage.Poi.FOOD, "Eten en drinken");
        TYPE_LABELS.put(OfflinePackage.Poi.TOILET, "Toiletten");
        TYPE_LABELS.put(OfflinePackage.Poi.BIKE, "Fietsenmaker / reparatiepunt");
    }

    private OfflinePackageReport() {}

    public static String build(OfflinePackage pkg, long nowMs) {
        StringBuilder sb = new StringBuilder(ageLine(pkg.createdAtMs, nowMs));
        sb.append("\n\nWEER LANGS DE ROUTE");
        if (pkg.weatherError != null) {
            sb.append("\nNiet opgehaald: ").append(pkg.weatherError);
        } else if (pkg.weather == null || pkg.weather.isEmpty()) {
            sb.append("\nGeen weerpunten.");
        } else {
            for (OfflinePackage.WeatherPoint w : pkg.weather) {
                sb.append('\n').append(weatherLine(w, nowMs));
            }
        }

        sb.append("\n\nLANGS DE ROUTE (≤ 300 m)");
        if (pkg.poiError != null) {
            sb.append("\nNiet opgehaald: ").append(pkg.poiError);
        } else if (pkg.pois == null || pkg.pois.isEmpty()) {
            sb.append("\nGeen water-, eet-, toilet- of fietspunten gevonden.");
        } else {
            for (Map.Entry<String, String> type : TYPE_LABELS.entrySet()) {
                appendType(sb, pkg, type.getKey(), type.getValue());
            }
        }
        return sb.toString();
    }

    static String ageLine(long createdAtMs, long nowMs) {
        long ageMin = Math.max(0, (nowMs - createdAtMs) / 60_000);
        String age = ageMin < 60 ? ageMin + " min geleden"
                : ageMin < 48 * 60 ? (ageMin / 60) + " uur geleden"
                : (ageMin / (24 * 60)) + " dagen geleden";
        String line = "Opgeslagen " + age + ".";
        if (nowMs - createdAtMs > STALE_MS) line += " Verouderd — vernieuw als je bereik hebt.";
        return line;
    }

    /**
     * "km 40: 12–17 °C, wind tot 25 km/u, regen tot 60 %" over the next {@link #WINDOW_HOURS}
     * from now, or from the forecast's first hour when now lies outside it.
     */
    static String weatherLine(OfflinePackage.WeatherPoint w, long nowMs) {
        String prefix = String.format(Locale.US, "km %.0f: ", w.distanceM / 1000.0);
        HourlyForecast f;
        try {
            f = HourlyForecast.parse(w.forecastJson);
        } catch (Exception e) {
            return prefix + "onleesbare voorspelling";
        }
        if (f.times.length == 0) return prefix + "geen uren";
        int start = f.indexAt(Instant.ofEpochMilli(nowMs));
        boolean outdated = start < 0;
        if (outdated) {
            if (Instant.ofEpochMilli(nowMs).isAfter(f.times[f.times.length - 1])) {
                return prefix + "voorspelling verlopen";
            }
            start = 0;
        }
        int end = Math.min(f.times.length, start + WINDOW_HOURS);
        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE, wind = 0;
        int rain = -1;
        for (int i = start; i < end; i++) {
            if (!Double.isNaN(f.temperature[i])) {
                tMin = Math.min(tMin, f.temperature[i]);
                tMax = Math.max(tMax, f.temperature[i]);
            }
            if (!Double.isNaN(f.windKmh[i])) wind = Math.max(wind, f.windKmh[i]);
            if (f.rainPct[i] != null) rain = Math.max(rain, f.rainPct[i]);
        }
        StringBuilder sb = new StringBuilder(prefix);
        if (tMin <= tMax) {
            sb.append(String.format(Locale.US, "%.0f–%.0f °C", tMin, tMax));
        } else {
            sb.append("temperatuur onbekend");
        }
        sb.append(String.format(Locale.US, ", wind tot %.0f km/u", wind));
        if (rain >= 0) sb.append(", regen tot ").append(rain).append(" %");
        sb.append(" (").append(end - start).append(" u");
        if (outdated) sb.append(" vanaf begin voorspelling");
        return sb.append(')').toString();
    }

    private static void appendType(StringBuilder sb, OfflinePackage pkg, String type,
                                   String label) {
        int count = 0;
        for (OfflinePackage.Poi p : pkg.pois) if (type.equals(p.type)) count++;
        if (count == 0) return;
        sb.append('\n').append(label).append(" (").append(count).append(')');
        int shown = 0;
        for (OfflinePackage.Poi p : pkg.pois) {
            if (!type.equals(p.type)) continue;
            if (shown++ == MAX_POIS_PER_TYPE) {
                sb.append("\n  … en ").append(count - MAX_POIS_PER_TYPE).append(" meer");
                break;
            }
            sb.append(String.format(Locale.US, "\n  km %.1f", p.distanceM / 1000.0));
            if (p.name != null && !p.name.isEmpty()) sb.append(" · ").append(p.name);
            if (p.offsetM >= 50) sb.append(String.format(Locale.US, " (%.0f m van route)", p.offsetM));
        }
    }
}
