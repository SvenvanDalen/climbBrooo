package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Open-Meteo hourly precipitation (requested with timezone=UTC) for one location (issue #234).
 * Open-Meteo reports {@code precipitation} as the sum of the <em>preceding</em> hour, so the
 * value stamped 10:00 covers 09:00–10:00; {@link #hourEnds} is named for that. Pure.
 */
public final class HourlyPrecipitation {

    private static final long HOUR_SEC = 3600L;

    /** End of each hourly interval (UTC). */
    public final Instant[] hourEnds;
    /** Precipitation in mm over the hour ending at {@code hourEnds[i]}; NaN when unknown. */
    public final double[] mm;

    public HourlyPrecipitation(Instant[] hourEnds, double[] mm) {
        this.hourEnds = hourEnds;
        this.mm = mm;
    }

    public static HourlyPrecipitation parse(String json) throws IOException {
        JsonNode hourly = new ObjectMapper().readTree(json).get("hourly");
        if (hourly == null || !hourly.has("time") || !hourly.has("precipitation")) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        JsonNode time = hourly.get("time");
        JsonNode precip = hourly.get("precipitation");
        int n = time.size();
        Instant[] ends = new Instant[n];
        double[] mm = new double[n];
        for (int i = 0; i < n; i++) {
            ends[i] = LocalDateTime.parse(time.get(i).asText()).toInstant(ZoneOffset.UTC);
            JsonNode v = precip.get(i);
            mm[i] = v == null || v.isNull() ? Double.NaN : v.asDouble();
        }
        return new HourlyPrecipitation(ends, mm);
    }

    /**
     * Total precipitation of every hour that overlaps {@code [fromSec, toSec)}, skipping
     * unknown hours. NaN when no overlapping hour has a value — "unknown", not "dry".
     */
    public double sumBetween(long fromSec, long toSec) {
        double sum = 0;
        boolean any = false;
        for (int i = 0; i < hourEnds.length; i++) {
            long end = hourEnds[i].getEpochSecond();
            long start = end - HOUR_SEC;
            if (end <= fromSec || start >= toSec) continue;
            if (Double.isNaN(mm[i])) continue;
            sum += mm[i];
            any = true;
        }
        return any ? sum : Double.NaN;
    }
}
