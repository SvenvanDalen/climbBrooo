package nl.paree.climbpro.domain.pain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.pain.PainLogEntry;
import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PainPatternAnalyzerTest {

    private static PainLogEntry entry(long rideId, int severity, String bike, String setup,
                                      String... areas) {
        PainLogEntry e = new PainLogEntry();
        e.id = "e" + rideId + severity;
        e.rideActivityId = rideId;
        e.severity = severity;
        e.bike = bike;
        e.setup = setup;
        e.areas = new ArrayList<>(Arrays.asList(areas));
        return e;
    }

    private static StoredRide ride(long id, double km, String type) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.distanceM = (float) (km * 1000);
        r.type = type;
        r.startEpochSec = 1_700_000_000L + id;
        return r;
    }

    @Test
    public void emptyLog_promptsToLog() {
        PainPatternAnalyzer.Report r = PainPatternAnalyzer.analyze(null, null);
        assertEquals(0, r.entryCount);
        assertTrue(PainPatternAnalyzer.summaryText(r).startsWith("Nog geen klachten"));
    }

    @Test
    public void areas_countedAndSortedByFrequency_duplicatesAndUnknownIgnored() {
        List<PainLogEntry> log = Arrays.asList(
                entry(0, 2, null, null, "KNEE", "KNEE", "BACK"),
                entry(0, 4, null, null, "KNEE"),
                entry(0, 3, null, null, "bogus"));
        PainPatternAnalyzer.Report r = PainPatternAnalyzer.analyze(log, null);
        assertEquals(3, r.entryCount);
        assertEquals(2, r.areas.size());
        assertEquals("Knie", r.areas.get(0).label);
        assertEquals(2, r.areas.get(0).count);
        assertEquals(3.0, r.areas.get(0).avgSeverity, 1e-9);
        assertEquals("Onderrug", r.areas.get(1).label);
        assertEquals("Knie: 2× (gem. 3,0/5)", PainPatternAnalyzer.statLine(r.areas.get(0)));
    }

    @Test
    public void bikesAndSetups_onlyShownWhenThereIsSomethingToCompare() {
        PainPatternAnalyzer.Report one = PainPatternAnalyzer.analyze(Arrays.asList(
                entry(0, 3, "Racefiets", "Oud zadel", "SADDLE"),
                entry(0, 3, "Racefiets", "Oud zadel", "SADDLE")), null);
        assertTrue(one.bikes.isEmpty());
        assertTrue(one.setups.isEmpty());

        PainPatternAnalyzer.Report two = PainPatternAnalyzer.analyze(Arrays.asList(
                entry(0, 4, "Racefiets", "Oud zadel", "SADDLE"),
                entry(0, 4, "Racefiets", "Oud zadel", "SADDLE"),
                entry(0, 1, "Gravel", "Nieuw zadel", "SADDLE")), null);
        assertEquals("Racefiets", two.bikes.get(0).label);
        assertEquals(2, two.bikes.get(0).count);
        assertEquals(2, two.setups.size());
        String text = PainPatternAnalyzer.summaryText(two);
        assertTrue(text, text.contains("Per fiets:"));
        assertTrue(text, text.contains("Nieuw zadel: 1× (gem. 1,0/5)"));
    }

    @Test
    public void longRidePattern_needsEnoughLinkedRidesAndExcludesVirtual() {
        List<StoredRide> rides = Arrays.asList(
                ride(1, 150, "Ride"), ride(2, 140, "Ride"), ride(3, 160, "Ride"),
                ride(4, 40, "Ride"), ride(5, 30, "Ride"), ride(6, 35, "Ride"),
                ride(7, 300, "VirtualRide"));
        // Two linked rides: not enough evidence.
        PainPatternAnalyzer.Report few = PainPatternAnalyzer.analyze(Arrays.asList(
                entry(1, 3, null, null, "BACK"), entry(2, 3, null, null, "BACK")), rides);
        assertFalse(few.longRidePattern());

        PainPatternAnalyzer.Report r = PainPatternAnalyzer.analyze(Arrays.asList(
                entry(1, 3, null, null, "BACK"), entry(2, 3, null, null, "BACK"),
                entry(3, 3, null, null, "BACK"), entry(7, 3, null, null, "BACK")), rides);
        assertEquals(150.0, r.complaintRideKm, 1e-3);
        assertEquals(92.5, r.allRideKm, 1e-3);
        assertTrue(r.longRidePattern());
        assertTrue(PainPatternAnalyzer.summaryText(r).contains("Klachten vooral na lange ritten"));
    }

    @Test
    public void shortComplaintRides_noLongRidePattern() {
        List<StoredRide> rides = Arrays.asList(
                ride(1, 30, "Ride"), ride(2, 30, "Ride"), ride(3, 30, "Ride"),
                ride(4, 100, "Ride"), ride(5, 100, "Ride"));
        PainPatternAnalyzer.Report r = PainPatternAnalyzer.analyze(Arrays.asList(
                entry(1, 2, null, null, "HANDS"), entry(2, 2, null, null, "HANDS"),
                entry(3, 2, null, null, "HANDS")), rides);
        assertFalse(r.longRidePattern());
        assertTrue(PainPatternAnalyzer.analyze(Collections.emptyList(), rides).areas.isEmpty());
    }
}
