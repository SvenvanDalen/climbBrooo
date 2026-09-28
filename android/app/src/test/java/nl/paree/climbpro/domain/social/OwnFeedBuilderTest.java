package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.social.FriendFeedEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OwnFeedBuilderTest {

    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;

    private static StoredRide ride(long start, String name) {
        StoredRide r = new StoredRide();
        r.startEpochSec = start;
        r.name = name;
        r.distanceM = 42_349.6f;
        r.elevationGainM = 350.4f;
        r.movingTimeSec = 5_520;
        r.startLat = 50.85;   // must never end up in the snapshot
        r.startLon = 5.69;
        return r;
    }

    private static StoredClimbAttempt attempt(String climbId, long date, int pass) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.dateEpochSec = date;
        a.passIndex = pass;
        return a;
    }

    private static Map<String, OwnFeedBuilder.ClimbInfo> climbs() {
        Map<String, OwnFeedBuilder.ClimbInfo> m = new HashMap<>();
        m.put("cauberg", new OwnFeedBuilder.ClimbInfo("Cauberg", 60, false));
        m.put("thuis", new OwnFeedBuilder.ClimbInfo("Mijn straat", 30, true));
        m.put("oud", new OwnFeedBuilder.ClimbInfo("Keutenberg", 90, false));
        m.put("nodate", new OwnFeedBuilder.ClimbInfo("Eyserbosweg", 100, false));
        return m;
    }

    @Test public void ridesInWindowNewestFirstRoundedAndNamed() {
        List<FriendFeedEntry> out = OwnFeedBuilder.build(Arrays.asList(
                ride(NOW - 2 * DAY, "Ochtendrit"),
                ride(NOW - DAY, "  "),
                ride(NOW - 31 * DAY, "Te oud"),
                ride(0, "Geen datum"),
                ride(NOW + 3 * DAY, "Toekomst")),
                Collections.<StoredClimbAttempt>emptyList(), climbs(), NOW);
        assertEquals(2, out.size());
        assertEquals("Rit", out.get(0).title);
        assertEquals("Ochtendrit", out.get(1).title);
        FriendFeedEntry e = out.get(1);
        assertEquals(FriendFeedEntry.KIND_RIDE, e.kind);
        assertEquals(42_350, e.distanceM);
        assertEquals(350, e.gainM);
        assertEquals(5_520, e.movingSec);
    }

    @Test public void ridesAreCappedAtFifteen() {
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 25; i++) rides.add(ride(NOW - i * 3_600L, "R" + i));
        List<FriendFeedEntry> out = OwnFeedBuilder.build(rides,
                Collections.<StoredClimbAttempt>emptyList(), climbs(), NOW);
        assertEquals(OwnFeedBuilder.MAX_RIDES, out.size());
        assertEquals("R0", out.get(0).title);
    }

    @Test public void firstAscentInWindowBecomesMilestoneOnlyOnce() {
        List<FriendFeedEntry> out = OwnFeedBuilder.build(Collections.<StoredRide>emptyList(),
                Arrays.asList(attempt("cauberg", NOW - DAY, 0),
                        attempt("cauberg", NOW - 5 * DAY, 1),
                        attempt("cauberg", NOW - 5 * DAY, 0)),
                climbs(), NOW);
        assertEquals(1, out.size());
        assertEquals(FriendFeedEntry.KIND_MILESTONE, out.get(0).kind);
        assertEquals("Eerste beklimming: Cauberg", out.get(0).title);
        assertEquals(NOW - 5 * DAY, out.get(0).epochSec);
        assertEquals(60, out.get(0).gainM);
    }

    @Test public void homeUnknownOldAndUndatedClimbsNeverBecomeMilestones() {
        List<FriendFeedEntry> out = OwnFeedBuilder.build(Collections.<StoredRide>emptyList(),
                Arrays.asList(
                        attempt("thuis", NOW - DAY, 0),              // thuisklim: private
                        attempt("onbekend", NOW - DAY, 0),           // no name known
                        attempt("oud", NOW - 90 * DAY, 0),           // first ascent long ago
                        attempt("oud", NOW - DAY, 0),
                        attempt("nodate", 0, 0),                     // undated earlier attempt
                        attempt("nodate", NOW - DAY, 0)),
                climbs(), NOW);
        assertTrue(out.isEmpty());
    }

    @Test public void milestonesCappedAtFiveAndTotalWithinCodeLimit() {
        Map<String, OwnFeedBuilder.ClimbInfo> many = new HashMap<>();
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            many.put("c" + i, new OwnFeedBuilder.ClimbInfo("Klim " + i, 50, false));
            attempts.add(attempt("c" + i, NOW - i * 3_600L, 0));
        }
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 25; i++) rides.add(ride(NOW - i * 60L, "R" + i));
        List<FriendFeedEntry> out = OwnFeedBuilder.build(rides, attempts, many, NOW);
        int milestones = 0;
        for (FriendFeedEntry e : out) if (!e.isRide()) milestones++;
        assertEquals(OwnFeedBuilder.MAX_MILESTONES, milestones);
        assertEquals(OwnFeedBuilder.MAX_RIDES + OwnFeedBuilder.MAX_MILESTONES, out.size());
        assertTrue(out.size() <= FriendShareCode.MAX_ENTRIES);
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i - 1).epochSec >= out.get(i).epochSec);
        }
    }
}
