package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RideBuddyMatcherTest {

    private static RideBuddyProfile rider(String name, int dkmh, int km, int types, int days,
                                          double lat, double lon) {
        RideBuddyProfile p = new RideBuddyProfile();
        p.riderId = name;
        p.name = name;
        p.flatSpeedDkmh = dkmh;
        p.typicalDistanceKm = km;
        p.rideTypes = types;
        p.weekdays = days;
        double[] c = RideBuddyProfile.snapToCell(lat, lon);
        p.areaLat = c[0];
        p.areaLon = c[1];
        return p;
    }

    private static final int ROAD = RideBuddyProfile.TYPE_ROAD;
    private static final int GRAVEL = RideBuddyProfile.TYPE_GRAVEL;
    private static final int MTB = RideBuddyProfile.TYPE_MTB;
    private static final int WEEKEND = 0b1100000;

    private final RideBuddyProfile me = rider("Ik", 280, 70, ROAD | GRAVEL, WEEKEND, 52.09, 5.12);

    @Test
    public void identicalProfile_scoresHigh() {
        RideBuddyProfile twin = me.copy();
        twin.name = "Tweeling";
        RideBuddyMatcher.Match m = RideBuddyMatcher.score(me, twin);
        assertTrue("score " + m.score, m.score >= 85);
        assertTrue(m.explanation, m.explanation.startsWith("vergelijkbaar tempo, in je buurt"));
    }

    @Test
    public void explanation_mentionsDistanceBetweenAreas() {
        // ~0.11° latitude north is ~12 km.
        RideBuddyProfile other = rider("Joost", 285, 75, ROAD, WEEKEND, 52.20, 5.12);
        RideBuddyMatcher.Match m = RideBuddyMatcher.score(me, other);
        assertTrue(m.explanation, m.explanation.matches("vergelijkbaar tempo, 1\\d km verderop, .*"));
        assertTrue(m.explanation, m.explanation.contains("rijdt ook weg"));
        assertTrue(m.explanation, m.explanation.contains("vergelijkbare ritlengte"));
        assertTrue(m.explanation, m.explanation.contains("rijdt ook op za, zo"));
        assertTrue(m.distanceKm > 8 && m.distanceKm < 16);
    }

    @Test
    public void rank_ordersBySimilarity() {
        List<RideBuddyProfile> others = new ArrayList<>();
        others.add(rider("Ver en snel", 360, 160, MTB, 0b0000001, 50.85, 4.35));
        others.add(rider("Buurman", 275, 65, ROAD, WEEKEND, 52.10, 5.13));
        others.add(rider("Middel", 310, 100, GRAVEL, 0b0100000, 52.30, 5.40));
        List<RideBuddyMatcher.Match> ranked = RideBuddyMatcher.rank(me, others);
        assertEquals("Buurman", ranked.get(0).buddy.name);
        assertEquals("Middel", ranked.get(1).buddy.name);
        assertEquals("Ver en snel", ranked.get(2).buddy.name);
        assertTrue(ranked.get(0).score > ranked.get(1).score);
        assertTrue(ranked.get(1).score > ranked.get(2).score);
    }

    @Test
    public void farAwayFastMtbRider_explainsTheDifferences() {
        RideBuddyProfile o = rider("Ver", 360, 160, MTB, 0b0000001, 50.85, 4.35);
        RideBuddyMatcher.Match m = RideBuddyMatcher.score(me, o);
        assertTrue(m.explanation, m.explanation.contains("rijdt 8,0 km/u sneller"));
        assertTrue(m.explanation, m.explanation.contains("rijdt vooral MTB"));
        assertTrue(m.explanation, m.explanation.contains("ritten van ~160 km"));
        assertTrue(m.explanation, m.explanation.contains("rijdt op andere dagen"));
        assertTrue("score " + m.score, m.score < 20);
    }

    @Test
    public void missingFieldsAreSkippedNotPenalisedAsMismatch() {
        RideBuddyProfile paceOnly = new RideBuddyProfile();
        paceOnly.riderId = "p";
        paceOnly.name = "Alleen tempo";
        paceOnly.flatSpeedDkmh = 280;
        RideBuddyMatcher.Match m = RideBuddyMatcher.score(me, paceOnly);
        assertEquals("vergelijkbaar tempo", m.explanation);
        assertNull(m.distanceKm);
        // Perfect on the one shared aspect, but scaled down for low coverage.
        assertTrue("score " + m.score, m.score >= 60 && m.score < 80);
    }

    @Test
    public void nothingInCommon_scoresZero() {
        RideBuddyProfile empty = new RideBuddyProfile();
        empty.riderId = "e";
        empty.name = "Leeg";
        RideBuddyMatcher.Match m = RideBuddyMatcher.score(me, empty);
        assertEquals(0, m.score);
        assertEquals("Te weinig gegevens om te vergelijken", m.explanation);
        assertEquals(0, RideBuddyMatcher.score(null, me).score);
    }

    @Test
    public void climbAndDaypartsAreCompared() {
        RideBuddyProfile a = new RideBuddyProfile();
        a.vamMph = 800;
        a.dayparts = RideBuddyProfile.PART_EVENING;
        RideBuddyProfile b = new RideBuddyProfile();
        b.name = "B";
        b.vamMph = 1_100;
        b.dayparts = RideBuddyProfile.PART_EVENING | RideBuddyProfile.PART_MORNING;
        RideBuddyMatcher.Match m = RideBuddyMatcher.score(a, b);
        assertEquals("klimt sneller, rijdt ook in de avond", m.explanation);
    }

    @Test
    public void rank_handlesNullAndTiesByName() {
        List<RideBuddyProfile> others = new ArrayList<>();
        RideBuddyProfile b = me.copy();
        b.name = "b";
        RideBuddyProfile a = me.copy();
        a.name = "A";
        others.add(b);
        others.add(null);
        others.add(a);
        List<RideBuddyMatcher.Match> ranked = RideBuddyMatcher.rank(me, others);
        assertEquals(2, ranked.size());
        assertEquals("A", ranked.get(0).buddy.name);
        assertTrue(RideBuddyMatcher.rank(me, null).isEmpty());
    }
}
