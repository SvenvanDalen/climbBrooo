package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ClimbUsageClassifierTest {

    private static final double AMS_LAT = 52.370216, AMS_LON = 4.895168;
    // ~10 km away, well outside USAGE_LOCAL_CLUSTER_RADIUS_M (5 km).
    private static final double FAR_LAT = 52.460216, FAR_LON = 4.895168;

    @Test
    public void riddenThreeOrMoreTimes_classifiesTraining() {
        assertEquals(ClimbUsageType.TRAINING,
                ClimbUsageClassifier.classify(3, AMS_LAT, AMS_LON, null));
        assertEquals(ClimbUsageType.TRAINING,
                ClimbUsageClassifier.classify(5, AMS_LAT, AMS_LON, Collections.emptyList()));
    }

    @Test
    public void boundary_twoAttempts_isUnknown_threeAttempts_isTraining() {
        assertEquals(ClimbUsageType.UNKNOWN,
                ClimbUsageClassifier.classify(2, AMS_LAT, AMS_LON, null));
        assertEquals(ClimbUsageType.TRAINING,
                ClimbUsageClassifier.classify(3, AMS_LAT, AMS_LON, null));
    }

    @Test
    public void riddenOnceAndIsolated_classifiesRecreational() {
        assertEquals(ClimbUsageType.RECREATIONAL,
                ClimbUsageClassifier.classify(1, AMS_LAT, AMS_LON, Collections.emptyList()));

        List<double[]> farClusters = Arrays.asList(new double[]{FAR_LAT, FAR_LON});
        assertEquals(ClimbUsageType.RECREATIONAL,
                ClimbUsageClassifier.classify(1, AMS_LAT, AMS_LON, farClusters));
    }

    @Test
    public void riddenOnceButNearFrequentCluster_classifiesUnknown() {
        // Same coordinate as a frequently-ridden climb (0 m away) — well within the radius.
        List<double[]> nearCluster = Arrays.asList(new double[]{AMS_LAT, AMS_LON});
        assertEquals(ClimbUsageType.UNKNOWN,
                ClimbUsageClassifier.classify(1, AMS_LAT, AMS_LON, nearCluster));
    }

    @Test
    public void insufficientData_zeroAttempts_classifiesUnknown() {
        assertEquals(ClimbUsageType.UNKNOWN,
                ClimbUsageClassifier.classify(0, AMS_LAT, AMS_LON, null));
    }

    @Test
    public void classifyAll_combinesFrequencyAndClusterSignals() {
        // Climb A: ridden 4x (TRAINING). Climb B: ridden once, right next to A -> UNKNOWN
        // (near a frequent cluster). Climb C: ridden once, far from both -> RECREATIONAL.
        StoredClimb a = climb(AMS_LAT, AMS_LON, 1000);
        StoredClimb b = climb(AMS_LAT + 0.0005, AMS_LON, 1000); // a few hundred metres from A
        StoredClimb c = climb(FAR_LAT, FAR_LON, 1000);

        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(attempt(ClimbIdentity.of(a.startLat, a.startLon, a.length), i));
        }
        attempts.add(attempt(ClimbIdentity.of(b.startLat, b.startLon, b.length), 0));
        attempts.add(attempt(ClimbIdentity.of(c.startLat, c.startLon, c.length), 0));

        ClimbUsageType[] result =
                ClimbUsageClassifier.classifyAll(Arrays.asList(a, b, c), attempts);

        assertEquals(ClimbUsageType.TRAINING, result[0]);
        assertEquals(ClimbUsageType.UNKNOWN, result[1]);
        assertEquals(ClimbUsageType.RECREATIONAL, result[2]);
    }

    @Test
    public void classifyAll_multiplePassesInOneRide_countAsOneRide() {
        StoredClimb a = climb(FAR_LAT, FAR_LON, 1000);
        String id = ClimbIdentity.of(a.startLat, a.startLon, a.length);
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (int pass = 0; pass < 4; pass++) {  // hill repeats within one activity
            StoredClimbAttempt at = attempt(id, 0);
            at.passIndex = pass;
            attempts.add(at);
        }

        ClimbUsageType[] result =
                ClimbUsageClassifier.classifyAll(Collections.singletonList(a), attempts);

        assertEquals(ClimbUsageType.RECREATIONAL, result[0]);
    }

    @Test
    public void classifyAll_frequentClimbOnAnotherRoute_countsAsLocalCluster() {
        // The frequently-ridden climb is not part of this route at all, only its attempts
        // are in the logbook. A once-ridden climb next to it must not read as a one-off.
        StoredClimb elsewhere = climb(AMS_LAT, AMS_LON, 1500);
        StoredClimb onThisRoute = climb(AMS_LAT + 0.01, AMS_LON, 1000); // ~1.1 km away
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            attempts.add(attempt(
                    ClimbIdentity.of(elsewhere.startLat, elsewhere.startLon, elsewhere.length), i));
        }
        attempts.add(attempt(
                ClimbIdentity.of(onThisRoute.startLat, onThisRoute.startLon, onThisRoute.length), 9));

        ClimbUsageType[] result =
                ClimbUsageClassifier.classifyAll(Collections.singletonList(onThisRoute), attempts);

        assertEquals(ClimbUsageType.UNKNOWN, result[0]);
    }

    @Test
    public void classifyAll_emptyOrNullClimbs_returnsEmptyArray() {
        assertEquals(0, ClimbUsageClassifier.classifyAll(null, null).length);
        assertEquals(0, ClimbUsageClassifier.classifyAll(Collections.emptyList(), null).length);
    }

    private static StoredClimb climb(double lat, double lon, int length) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.length = length;
        c.startDistance = 0;
        c.endDistance = length;
        return c;
    }

    private static StoredClimbAttempt attempt(String climbId, int passIndex) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = 1000 + passIndex;
        a.passIndex = 0;
        return a;
    }
}
