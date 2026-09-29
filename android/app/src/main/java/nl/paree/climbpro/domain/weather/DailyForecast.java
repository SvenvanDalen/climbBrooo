package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Open-Meteo daily forecast for the coming week at one location (issue #40, "klim van de
 * week"). Requested with {@code timezone=auto}, so every date is the local calendar day.
 * Pure; the HTTP call lives in {@link nl.paree.climbpro.data.weather.OpenMeteoClient}.
 */
public final class DailyForecast {

    private DailyForecast() {}

    /** One forecast day. */
    public static final class Day {
        public final LocalDate date;
        /** Maximum precipitation probability (0–100); null when the service left it out. */
        public final Integer rainPct;
        /** Maximum 10 m wind speed in km/h; NaN when unknown. */
        public final double windMaxKmh;
        /** Maximum 2 m temperature in °C; NaN when unknown. */
        public final double tempMaxC;

        public Day(LocalDate date, Integer rainPct, double windMaxKmh, double tempMaxC) {
            this.date = date;
            this.rainPct = rainPct;
            this.windMaxKmh = windMaxKmh;
            this.tempMaxC = tempMaxC;
        }
    }

    public static String url(double lat, double lon) {
        return String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&daily=precipitation_probability_max,wind_speed_10m_max,temperature_2m_max"
                        + "&wind_speed_unit=kmh&timezone=auto&forecast_days=7", lat, lon);
    }

    public static List<Day> parse(String json) throws IOException {
        JsonNode daily = new ObjectMapper().readTree(json).get("daily");
        if (daily == null || !daily.has("time")) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        JsonNode time = daily.get("time");
        List<Day> out = new ArrayList<>(time.size());
        for (int i = 0; i < time.size(); i++) {
            LocalDate date;
            try {
                date = LocalDate.parse(time.get(i).asText());
            } catch (RuntimeException e) {
                throw new IOException("Onverwachte datum van de weerdienst", e);
            }
            JsonNode r = daily.path("precipitation_probability_max").get(i);
            Integer rain = r == null || r.isNull() ? null : r.asInt();
            out.add(new Day(date, rain, number(daily, "wind_speed_10m_max", i),
                    number(daily, "temperature_2m_max", i)));
        }
        return Collections.unmodifiableList(out);
    }

    private static double number(JsonNode daily, String field, int i) {
        JsonNode n = daily.path(field).get(i);
        return n == null || n.isNull() || !n.isNumber() ? Double.NaN : n.asDouble();
    }
}
