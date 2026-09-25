package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.StoredClimb;

import org.junit.Test;

public class ClimbRatingTest {

    private static StoredClimb rated(Integer road, Integer traffic, Integer view, String note) {
        StoredClimb c = new StoredClimb();
        ClimbRating.apply(c, road, traffic, view, note);
        return c;
    }

    @Test public void normalizeKeepsOneToFiveOnly() {
        assertNull(ClimbRating.normalize(null));
        assertNull(ClimbRating.normalize(0));
        assertNull(ClimbRating.normalize(6));
        assertNull(ClimbRating.normalize(-1));
        assertEquals(Integer.valueOf(1), ClimbRating.normalize(1));
        assertEquals(Integer.valueOf(5), ClimbRating.normalize(5));
    }

    @Test public void normalizeNoteTrimsAndBlankIsNull() {
        assertNull(ClimbRating.normalizeNote(null));
        assertNull(ClimbRating.normalizeNote("   "));
        assertEquals("Mooi uitzicht", ClimbRating.normalizeNote("  Mooi uitzicht "));
    }

    @Test public void unratedClimbHasNoAverageAndEmptyLabels() {
        StoredClimb c = new StoredClimb();
        assertFalse(ClimbRating.isRated(c));
        assertNull(ClimbRating.average(c));
        assertEquals("", ClimbRating.badge(c));
        assertEquals("", ClimbRating.breakdown(c));
        assertEquals("Nog niet beoordeeld", ClimbRating.detailText(c));
    }

    @Test public void averageIgnoresMissingAspects() {
        StoredClimb c = rated(5, 4, null, null);
        assertTrue(ClimbRating.isRated(c));
        assertEquals(4.5, ClimbRating.average(c), 1e-9);
        assertEquals("★ 4,5", ClimbRating.badge(c));
        assertEquals("Wegdek 5/5 · Verkeer 4/5 · Uitzicht –/5", ClimbRating.breakdown(c));
    }

    @Test public void badgeRoundsToOneDecimalWithComma() {
        assertEquals("★ 4,3", ClimbRating.badge(rated(5, 3, 5, null)));
        assertEquals("", ClimbRating.badge((Double) null));
    }

    @Test public void detailTextIncludesNote() {
        StoredClimb c = rated(3, 2, 5, "Druk in het weekend");
        assertEquals("★ 3,3 · Wegdek 3/5 · Verkeer 2/5 · Uitzicht 5/5\n“Druk in het weekend”",
                ClimbRating.detailText(c));
    }

    @Test public void noteOnlyIsShownButNotRated() {
        StoredClimb c = rated(null, null, null, "Nog eens rijden");
        assertFalse(ClimbRating.isRated(c));
        assertEquals("“Nog eens rijden”", ClimbRating.detailText(c));
    }

    @Test public void applyNormalizesAndClearing() {
        StoredClimb c = rated(0, 9, 4, "  ");
        assertNull(c.ratingRoad);
        assertNull(c.ratingTraffic);
        assertEquals(Integer.valueOf(4), c.ratingView);
        assertNull(c.ratingNote);

        ClimbRating.apply(c, null, null, null, null);
        assertFalse(ClimbRating.isRated(c));
        assertEquals("Nog niet beoordeeld", ClimbRating.detailText(c));
    }

    @Test public void corruptStoredScoresCountAsUnrated() {
        StoredClimb c = new StoredClimb();
        c.ratingRoad = 7;   // hand-edited / corrupt JSON bypasses apply()
        c.ratingView = 0;
        assertFalse(ClimbRating.isRated(c));
        assertNull(ClimbRating.average(c));
        assertEquals("", ClimbRating.badge(c));
    }

    @Test public void copyCopiesAllFields() {
        StoredClimb from = rated(1, 2, 3, "x");
        StoredClimb to = new StoredClimb();
        ClimbRating.copy(from, to);
        assertEquals(Integer.valueOf(1), to.ratingRoad);
        assertEquals(Integer.valueOf(2), to.ratingTraffic);
        assertEquals(Integer.valueOf(3), to.ratingView);
        assertEquals("x", to.ratingNote);
    }

    @Test public void compareBestFirstPutsUnratedLast() {
        assertTrue(ClimbRating.compareBestFirst(4.5, 3.0) < 0);
        assertTrue(ClimbRating.compareBestFirst(3.0, 4.5) > 0);
        assertTrue(ClimbRating.compareBestFirst(1.0, null) < 0);
        assertTrue(ClimbRating.compareBestFirst(null, 1.0) > 0);
        assertEquals(0, ClimbRating.compareBestFirst(null, null));
        assertEquals(0, ClimbRating.compareBestFirst(2.0, 2.0));
    }

    @Test public void jsonRoundTripAndOldJsonWithoutFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StoredClimb back = mapper.readValue(
                mapper.writeValueAsBytes(rated(5, 4, 3, "Top")), StoredClimb.class);
        assertEquals(Integer.valueOf(5), back.ratingRoad);
        assertEquals(Integer.valueOf(4), back.ratingTraffic);
        assertEquals(Integer.valueOf(3), back.ratingView);
        assertEquals("Top", back.ratingNote);

        StoredClimb old = mapper.readValue("{\"length\":1000,\"name\":\"Oud\"}", StoredClimb.class);
        assertFalse(ClimbRating.isRated(old));
        assertNull(old.ratingNote);
    }
}
