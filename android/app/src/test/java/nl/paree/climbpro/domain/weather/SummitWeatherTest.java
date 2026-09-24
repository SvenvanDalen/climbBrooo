package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class SummitWeatherTest {

    private static final String FOOT = "{\"hourly\":{\"time\":[\"2026-09-24T10:00\"],"
            + "\"temperature_2m\":[14.2],\"apparent_temperature\":[13.0],"
            + "\"wind_speed_10m\":[10.0],\"precipitation_probability\":[5]}}";

    @Test public void describesFootAndTopWithTip() throws IOException {
        String text = SummitWeather.describe(HourlyForecast.parse(FOOT),
                HourlyForecast.parse(HourlyForecastTest.JSON),
                Instant.parse("2026-09-24T10:15:00Z"), 125, 250);
        assertEquals("Dal (125 m): 14,2 °C\n"
                + "Top (250 m): 12,4 °C, voelt als 9,8 °C\n"
                + "Wind op de top 22 km/u · regenkans 10%\n"
                + "Tip: Arm- en beenstukken plus een windjack", text);
    }

    @Test public void unknownRainAndElevation() throws IOException {
        String text = SummitWeather.describe(HourlyForecast.parse(FOOT),
                HourlyForecast.parse(HourlyForecastTest.JSON),
                Instant.parse("2026-09-24T11:00:00Z"), Double.NaN, Double.NaN);
        assertNull(text); // foot forecast has no 11:00 hour
        String top = SummitWeather.describe(HourlyForecast.parse(HourlyForecastTest.JSON),
                HourlyForecast.parse(HourlyForecastTest.JSON),
                Instant.parse("2026-09-24T11:00:00Z"), Double.NaN, Double.NaN);
        assertEquals("Dal: 13,0 °C\nTop: 13,0 °C, voelt als 10,5 °C\n"
                + "Wind op de top 32 km/u · regenkans onbekend\n"
                + "Tip: Neem een windvestje mee voor de afdaling. Let op: harde wind op de top", top);
    }

    @Test public void clothingThresholds() {
        assertEquals("Winterkleding: lange broek, winterjack en handschoenen", SummitWeather.clothingTip(4.9, 0));
        assertEquals("Arm- en beenstukken plus een windjack", SummitWeather.clothingTip(5, 0));
        assertEquals("Neem een windvestje mee voor de afdaling", SummitWeather.clothingTip(10, 0));
        assertEquals("Zomertenue volstaat", SummitWeather.clothingTip(15, 0));
        assertEquals("Zomertenue volstaat. Let op: harde wind op de top", SummitWeather.clothingTip(25, 30));
    }
}
