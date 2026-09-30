package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import nl.paree.climbpro.data.route.RouteCatalogEntry;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class QuickStartTest {

    private static RouteCatalogEntry entry(String id, String name, String userName) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = id;
        e.name = name;
        e.userDisplayName = userName;
        return e;
    }

    @Test
    public void resolve_returnsActiveRouteWhenStillInCatalog() {
        RouteCatalogEntry a = entry("a", "A", null);
        RouteCatalogEntry b = entry("b", "B", null);
        assertSame(b, QuickStart.resolve("b", Arrays.asList(a, b)));
    }

    @Test
    public void resolve_nullWhenNoActiveRouteOrItWasDeleted() {
        List<RouteCatalogEntry> catalog = Collections.singletonList(entry("a", "A", null));
        assertNull(QuickStart.resolve(null, catalog));
        assertNull(QuickStart.resolve("gone", catalog));
        assertNull(QuickStart.resolve("a", null));
    }

    @Test
    public void buttonLabel_namesTheRoutePreferringUserName() {
        assertEquals("Rit nu starten", QuickStart.buttonLabel(null));
        assertEquals("Rit nu starten: Rondje Vaals",
                QuickStart.buttonLabel(entry("a", "strava_123.gpx", "Rondje Vaals")));
        assertEquals("Rit nu starten: strava",
                QuickStart.buttonLabel(entry("a", "strava", "")));
        assertEquals("Rit nu starten: a", QuickStart.buttonLabel(entry("a", null, null)));
    }
}
