package nl.paree.climbpro.domain.watch;

import org.junit.Test;

import static org.junit.Assert.*;

public class WatchFieldLayoutTest {

    @Test
    public void defaults_matchCurrentScreen() {
        WatchFieldLayout d = WatchFieldLayout.defaults();
        assertArrayEquals(new int[]{0, 1, 2, 14, 15}, d.codes());
        assertTrue(d.isDefault());
        assertEquals("0,1,2,14,15", d.serialize());
    }

    @Test
    public void serializeParse_roundTrips() {
        WatchFieldLayout l = WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5});
        assertFalse(l.isDefault());
        assertArrayEquals(new int[]{8, 9, 10, 6, 5}, WatchFieldLayout.parse(l.serialize()).codes());
    }

    @Test
    public void parse_nullOrWrongLengthGivesDefaults() {
        assertTrue(WatchFieldLayout.parse(null).isDefault());
        assertTrue(WatchFieldLayout.parse("").isDefault());
        assertTrue(WatchFieldLayout.parse("1,2").isDefault());
        assertTrue(WatchFieldLayout.parse("1,2,3,4,5,6").isDefault());
    }

    @Test
    public void parse_badElementFallsBackPerSlot() {
        assertArrayEquals(new int[]{8, 1, 2, 9, 15},
                WatchFieldLayout.parse("8,x,99,9,-1").codes());
    }

    @Test
    public void of_nullOrWrongLengthGivesDefaults() {
        assertTrue(WatchFieldLayout.of(null).isDefault());
        assertTrue(WatchFieldLayout.of(new int[]{1, 2, 3}).isDefault());
    }

    @Test
    public void codes_isDefensiveCopy() {
        WatchFieldLayout l = WatchFieldLayout.defaults();
        l.codes()[0] = 13;
        assertEquals(0, l.code(0));
    }

    @Test
    public void withCode_replacesOneSlotAndValidates() {
        WatchFieldLayout l = WatchFieldLayout.defaults().withCode(4, WatchFieldLayout.ELAPSED);
        assertArrayEquals(new int[]{0, 1, 2, 14, 12}, l.codes());
        assertEquals(15, l.withCode(4, 42).code(4));
    }

    @Test
    public void labels_coverEverySlotAndCode() {
        assertEquals(16, WatchFieldLayout.metricCount());
        for (int c = 0; c < WatchFieldLayout.metricCount(); c++) {
            assertNotNull(WatchFieldLayout.metricLabel(c));
        }
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            assertNotNull(WatchFieldLayout.slotLabel(s));
        }
    }
}
