package nl.paree.climbpro.data.weather;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.domain.weather.RouteSampler;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class OpenMeteoClientTest {

    @Test public void precipitationUrlListsAllSamplesAndOneExtraHour() {
        List<RouteSampler.Sample> pts = new ArrayList<>();
        pts.add(new RouteSampler.Sample(0, 50.85, 5.69));
        pts.add(new RouteSampler.Sample(5_000, 50.9, 5.8));
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=50.8500,50.9000"
                + "&longitude=5.6900,5.8000&hourly=precipitation&timezone=UTC&forecast_hours=7",
                OpenMeteoClient.precipitationUrl(pts, 6));
    }

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
