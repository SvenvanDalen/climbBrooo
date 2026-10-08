package nl.paree.climbpro.domain.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ImportedActivityDedupeTest {

    private static StoredClimbAttempt a(long activityId, String climbId, long date) {
        StoredClimbAttempt x = new StoredClimbAttempt();
        x.activityId = activityId;
        x.climbId = climbId;
        x.dateEpochSec = date;
        return x;
    }

    @Test
    public void activityId_isNegativeStartAndNeverZero() {
        assertEquals(-1_760_000_000L, ImportedActivityDedupe.activityIdFor(1_760_000_000L));
        assertEquals(-1L, ImportedActivityDedupe.activityIdFor(0));
        assertEquals(-1L, ImportedActivityDedupe.activityIdFor(-50));
    }

    @Test
    public void sameClimbFromStravaWithinFiveMinutes_isDropped_atTheBoundaryToo() {
        List<StoredClimbAttempt> strava = Collections.singletonList(a(123, "k", 1000));
        assertTrue(ImportedActivityDedupe.withoutKnownRides(
                Collections.singletonList(a(-1, "k", 1000 + 300)), strava).isEmpty());
        assertEquals(1, ImportedActivityDedupe.withoutKnownRides(
                Collections.singletonList(a(-1, "k", 1000 + 301)), strava).size());
    }

    @Test
    public void otherClimbOrNullClimbId_isKept() {
        List<StoredClimbAttempt> known = Arrays.asList(a(123, "k", 1000), a(124, null, 1000));
        assertEquals(1, ImportedActivityDedupe.withoutKnownRides(
                Collections.singletonList(a(-1, "andere", 1000)), known).size());
        assertEquals(1, ImportedActivityDedupe.withoutKnownRides(
                Collections.singletonList(a(-1, null, 1000)), known).size());
    }

    @Test
    public void fitAndGpxOfTheSameRideInOneZip_countOnce() {
        List<StoredClimbAttempt> out = ImportedActivityDedupe.withoutKnownRides(
                Arrays.asList(a(-1000, "k", 1000), a(-1003, "k", 1003)),
                Collections.<StoredClimbAttempt>emptyList());
        assertEquals(1, out.size());
        assertEquals(-1000, out.get(0).activityId);
    }

    @Test
    public void sameActivityId_isLeftToTheRepositoryDedupe() {
        // Re-importing the same file keeps its id; append() drops it, not this filter.
        assertEquals(1, ImportedActivityDedupe.withoutKnownRides(
                Collections.singletonList(a(-1000, "k", 1000)),
                Collections.singletonList(a(-1000, "k", 1000))).size());
    }
}
