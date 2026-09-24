package nl.paree.climbpro.data.weather;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OpenMeteoClientTest {

    @Test public void urlWithElevationUsesDotDecimals() {
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=50.85000&longitude=5.69000"
                + "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,precipitation_probability"
                + "&wind_speed_unit=kmh&timezone=UTC&forecast_days=2&elevation=312",
                OpenMeteoClient.url(50.85, 5.69, 312.4));
    }

    @Test public void unknownElevationIsLeftOut() {
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=50.85000&longitude=5.69000"
                + "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,precipitation_probability"
                + "&wind_speed_unit=kmh&timezone=UTC&forecast_days=2",
                OpenMeteoClient.url(50.85, 5.69, Double.NaN));
    }
}
