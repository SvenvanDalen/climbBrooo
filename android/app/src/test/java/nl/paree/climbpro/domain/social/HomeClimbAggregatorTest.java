package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.social.FriendFeedEntry;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HomeClimbAggregatorTest {

    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;

    private static StoredClimb climb(String name, String userDisplayName, int gainM, boolean isHome) {
        StoredClimb c = new StoredClimb();
        c.name = name;
        c.userDisplayName = userDisplayName;
        c.elevationGain = gainM;
        c.isHome = isHome;
        return c;
    }

    @Test public void nullAndEmptyInputAggregateToNull() {
        assertNull(HomeClimbAggregator.aggregate(null));
        assertNull(HomeClimbAggregator.aggregate(Collections.<StoredClimb>emptyList()));
    }

    /** Issue #240: isHome is per-route-copy, so a climb marked home on only one of its route
     *  copies must still be treated as home overall — never only the first copy a scan finds. */
    @Test public void isHomeIsOredAcrossAllCopiesRegardlessOfOrder() {
        // Only the second copy is marked home; the first (which a first-copy-wins scan would
        // have used alone) is not.
        List<StoredClimb> copies = Arrays.asList(
                climb("Cauberg", null, 60, false),
                climb("Cauberg", null, 60, true));
        OwnFeedBuilder.ClimbInfo info = HomeClimbAggregator.aggregate(copies);
        assertTrue(info.isHome);

        // Order must not matter.
        List<StoredClimb> reversed = Arrays.asList(copies.get(1), copies.get(0));
        assertTrue(HomeClimbAggregator.aggregate(reversed).isHome);
    }

    @Test public void notHomeWhenNoCopyIsMarkedHome() {
        List<StoredClimb> copies = Arrays.asList(
                climb("Cauberg", null, 60, false),
                climb("Cauberg", null, 60, false));
        assertFalse(HomeClimbAggregator.aggregate(copies).isHome);
    }

    @Test public void prefersNonBlankUserDisplayNameFromAnyCopy() {
        List<StoredClimb> copies = Arrays.asList(
                climb("Cauberg", null, 60, false),
                climb("Cauberg", "Mijn favoriete klim", 60, false));
        OwnFeedBuilder.ClimbInfo info = HomeClimbAggregator.aggregate(copies);
        assertEquals("Mijn favoriete klim", info.name);
    }

    @Test public void fallsBackToDetectedNameWhenNoCopyHasADisplayName() {
        List<StoredClimb> copies = Arrays.asList(
                climb("Cauberg", "  ", 60, false),
                climb("Cauberg", null, 60, false));
        OwnFeedBuilder.ClimbInfo info = HomeClimbAggregator.aggregate(copies);
        assertEquals("Cauberg", info.name);
    }

    /**
     * End-to-end through {@link OwnFeedBuilder#build}: a climb whose only home-marked copy is
     * NOT the first one a catalog scan would return must still be excluded from the share
     * snapshot as a first ascent.
     */
    @Test public void aggregatedHomeClimbNeverBecomesAMilestone() {
        List<StoredClimb> copies = Arrays.asList(
                climb("Mijn straat", null, 30, false),  // route copy #1: not marked home
                climb("Mijn straat", null, 30, true));  // route copy #2: marked home
        OwnFeedBuilder.ClimbInfo info = HomeClimbAggregator.aggregate(copies);
        assertTrue(info.isHome);

        Map<String, OwnFeedBuilder.ClimbInfo> climbs = new HashMap<>();
        climbs.put("thuis", info);
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "thuis";
        a.dateEpochSec = NOW - DAY;
        a.passIndex = 0;

        List<FriendFeedEntry> out = OwnFeedBuilder.build(Collections.emptyList(),
                Collections.singletonList(a), climbs, NOW);
        assertTrue(out.isEmpty());
    }
}
