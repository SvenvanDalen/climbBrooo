package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.power.GhostTarget;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.GradientPalette;
import nl.paree.climbpro.domain.units.UnitPreferences;
import nl.paree.climbpro.domain.watch.WatchFieldLayout;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** A layout change must change the sync hash, or the watch never receives the new layout. */
public class RouteSyncWorkerWantHashTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.sourceHash = "abc";
        r.climbs = new ArrayList<>();
        return r;
    }

    private static String hash(RiderProfile p, WatchFieldLayout layout) {
        return RouteSyncWorker.wantHash(route(), p, GhostTarget.NONE, GradientPalette.DEFAULT,
                new UnitPreferences(false, false, false), layout);
    }

    @Test
    public void layoutChangeChangesHash() {
        RiderProfile p = new RiderProfile(250, 75, 8);
        String a = hash(p, WatchFieldLayout.defaults());
        String b = hash(p, WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5}));
        assertNotEquals(a, b);
    }

    @Test
    public void sameLayoutSameHash() {
        RiderProfile p = new RiderProfile(250, 75, 8);
        assertEquals(hash(p, WatchFieldLayout.defaults()), hash(p, WatchFieldLayout.defaults()));
    }
}
