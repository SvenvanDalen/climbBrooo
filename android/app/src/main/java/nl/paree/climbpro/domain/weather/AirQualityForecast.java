package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Open-Meteo air-quality forecast (issue #197, requested with timezone=UTC) for one location:
 * particulate matter, the European AQI and pollen. Missing values are NaN — pollen is only
 * modelled for Europe and in season. Pure.
 */
public final class AirQualityForecast {

    /** Pollen types Open-Meteo models, with the Dutch name shown to the rider. */
    public enum Pollen {
        GRASS("grass_pollen", "grassen", 10, 50),
        BIRCH("birch_pollen", "berk", 10, 100),
        ALDER("alder_pollen", "els", 10, 100),
        MUGWORT("mugwort_pollen", "bijvoet", 10, 50),
        RAGWEED("ragweed_pollen", "ambrosia", 5, 20),
        OLIVE("olive_pollen", "olijf", 10, 100);

        public final String field;
        public final String label;
        /** Grains/m³ from which hay-fever riders typically notice it. */
        public final double moderateFrom;
        /** Grains/m³ from which most hay-fever riders get symptoms. */
        public final double highFrom;

        Pollen(String field, String label, double moderateFrom, double highFrom) {
            this.field = field;
            this.label = label;
            this.moderateFrom = moderateFrom;
            this.highFrom = highFrom;
        }
    }

    public final Instant[] times;
    /** µg/m³ */
    public final double[] pm25;
    /** µg/m³ */
    public final double[] pm10;
    /** European Air Quality Index (0 = clean, above 100 = extremely poor). */
    public final double[] europeanAqi;
    /** Grains/m³ per hour, indexed by {@link Pollen#ordinal()}. */
    public final double[][] pollen;

    private AirQualityForecast(Instant[] times, double[] pm25, double[] pm10,
                               double[] europeanAqi, double[][] pollen) {
        this.times = times;
        this.pm25 = pm25;
        this.pm10 = pm10;
        this.europeanAqi = europeanAqi;
        this.pollen = pollen;
    }

    public static AirQualityForecast parse(String json) throws IOException {
        JsonNode hourly = new ObjectMapper().readTree(json).get("hourly");
        if (hourly == null || !hourly.has("time")) {
            throw new IOException("Onverwacht antwoord van de luchtkwaliteitsdienst");
        }
        JsonNode time = hourly.get("time");
        int n = time.size();
        Instant[] times = new Instant[n];
        double[] pm25 = new double[n], pm10 = new double[n], aqi = new double[n];
        double[][] pollen = new double[Pollen.values().length][n];
        for (int i = 0; i < n; i++) {
            times[i] = LocalDateTime.parse(time.get(i).asText()).toInstant(ZoneOffset.UTC);
            pm25[i] = number(hourly, "pm2_5", i);
            pm10[i] = number(hourly, "pm10", i);
            aqi[i] = number(hourly, "european_aqi", i);
            for (Pollen p : Pollen.values()) pollen[p.ordinal()][i] = number(hourly, p.field, i);
        }
        return new AirQualityForecast(times, pm25, pm10, aqi, pollen);
    }

    private static double number(JsonNode hourly, String field, int i) {
        JsonNode v = hourly.path(field).get(i);
        return v == null || v.isNull() ? Double.NaN : v.asDouble();
    }
}
