package nl.paree.climbpro.domain.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShareLinkTest {

    @Test
    public void komootTourLink() {
        ShareLink link = ShareLink.find("https://www.komoot.com/tour/123456789");
        assertEquals(ShareLink.Provider.KOMOOT, link.provider);
        assertEquals("123456789", link.id);
        assertNull(link.shareToken);
        assertEquals("https://api.komoot.de/v007/tours/123456789/coordinates",
                link.komootCoordinatesUrl());
        assertEquals("https://api.komoot.de/v007/tours/123456789", link.komootTourUrl());
    }

    @Test
    public void komootCountryDomainsAndLocalePrefix() {
        assertEquals("42", ShareLink.find("https://www.komoot.de/tour/42").id);
        assertEquals("42", ShareLink.find("https://www.komoot.nl/tour/42").id);
        assertEquals("77", ShareLink.find("https://www.komoot.com/nl-nl/tour/77").id);
        assertEquals("88", ShareLink.find("komoot.com/tour/88").id);
    }

    @Test
    public void komootShareTokenIsKeptAndPassedToApi() {
        ShareLink link = ShareLink.find(
                "https://www.komoot.com/tour/555?ref=wtd&share_token=aBc-12_x&utm=1");
        assertEquals("aBc-12_x", link.shareToken);
        assertEquals("https://api.komoot.de/v007/tours/555/coordinates?share_token=aBc-12_x",
                link.komootCoordinatesUrl());
        assertEquals("https://api.komoot.de/v007/tours/555?share_token=aBc-12_x",
                link.komootTourUrl());
    }

    @Test
    public void komootShareTokenWithOddCharactersIsDropped() {
        assertNull(ShareLink.find("https://www.komoot.com/tour/555?share_token=a%26b").shareToken);
    }

    @Test
    public void linkInsideSharedMessageText() {
        ShareLink link = ShareLink.find(
                "Bekijk mijn tour op Komoot! (https://www.komoot.com/tour/999?ref=wtd).");
        assertEquals("999", link.id);
    }

    @Test
    public void rideWithGpsRouteAndTrip() {
        ShareLink route = ShareLink.find("https://ridewithgps.com/routes/12345");
        assertEquals(ShareLink.Provider.RIDE_WITH_GPS, route.provider);
        assertEquals("12345", route.id);
        assertFalse(route.trip);
        assertEquals("https://ridewithgps.com/routes/12345.gpx?sub_format=track", route.gpxUrl());

        ShareLink trip = ShareLink.find("https://www.ridewithgps.com/trips/678?privacy_code=x");
        assertTrue(trip.trip);
        assertEquals("https://ridewithgps.com/trips/678.gpx?sub_format=track", trip.gpxUrl());
    }

    @Test
    public void rideWithGpsGpxLinkIsAccepted() {
        assertEquals("12345", ShareLink.find("https://ridewithgps.com/routes/12345.gpx").id);
    }

    @Test
    public void unsupportedLinksAreIgnored() {
        assertNull(ShareLink.find(null));
        assertNull(ShareLink.find(""));
        assertNull(ShareLink.find("geen link hier"));
        assertNull(ShareLink.find("https://www.strava.com/routes/123"));
        assertNull(ShareLink.find("https://www.komoot.com/collection/123"));
        assertNull(ShareLink.find("https://ridewithgps.com/users/123"));
        assertNull(ShareLink.find("https://komoot.com.evil.example/tour/1"));
        assertNull(ShareLink.find("https://notridewithgps.com/routes/1"));
    }

    @Test
    public void defaultNames() {
        assertEquals("Komoot-tour 1", ShareLink.find("https://www.komoot.com/tour/1").defaultName());
        assertEquals("RideWithGPS-route 2",
                ShareLink.find("https://ridewithgps.com/routes/2").defaultName());
        assertEquals("RideWithGPS-rit 3",
                ShareLink.find("https://ridewithgps.com/trips/3").defaultName());
    }
}
