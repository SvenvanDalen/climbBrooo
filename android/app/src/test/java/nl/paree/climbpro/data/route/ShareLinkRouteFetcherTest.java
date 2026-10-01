package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nl.paree.climbpro.domain.route.ShareLink;

public class ShareLinkRouteFetcherTest {

    @Test
    public void privateKomootTourGetsClearDutchMessage() {
        String msg = ShareLinkRouteFetcher.errorMessage(ShareLink.Provider.KOMOOT, 403);
        assertTrue(msg, msg.contains("privé"));
        assertTrue(msg, msg.contains("Komoot"));
        assertTrue(ShareLinkRouteFetcher.errorMessage(ShareLink.Provider.KOMOOT, 401)
                .contains("privé"));
    }

    @Test
    public void privateRideWithGpsRouteGetsClearDutchMessage() {
        String msg = ShareLinkRouteFetcher.errorMessage(ShareLink.Provider.RIDE_WITH_GPS, 401);
        assertTrue(msg, msg.contains("privé"));
        assertTrue(msg, msg.contains("RideWithGPS"));
    }

    @Test
    public void notFoundAndOtherErrors() {
        assertTrue(ShareLinkRouteFetcher.errorMessage(ShareLink.Provider.KOMOOT, 404)
                .contains("niet gevonden"));
        assertTrue(ShareLinkRouteFetcher.errorMessage(ShareLink.Provider.RIDE_WITH_GPS, 500)
                .contains("HTTP 500"));
    }
}
