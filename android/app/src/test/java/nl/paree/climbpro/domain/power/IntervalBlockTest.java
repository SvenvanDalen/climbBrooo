package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredIntervalBlock;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.ArrayList;

public class IntervalBlockTest {

    @Test
    public void presetsCarryTheirFtpBands() {
        assertBand(IntervalBlock.of(IntervalBlock.Preset.DREMPEL), 95, 100);
        assertBand(IntervalBlock.of(IntervalBlock.Preset.SWEET_SPOT), 88, 93);
        assertBand(IntervalBlock.of(IntervalBlock.Preset.VO2MAX), 110, 120);
        assertBand(IntervalBlock.of(IntervalBlock.Preset.TEMPO), 80, 85);
    }

    @Test(expected = IllegalArgumentException.class)
    public void customIsNotAPresetBand() {
        IntervalBlock.of(IntervalBlock.Preset.CUSTOM);
    }

    @Test
    public void customTargetGetsASymmetricBand() {
        IntervalBlock b = IntervalBlock.custom(105);
        assertEquals(IntervalBlock.Preset.CUSTOM, b.preset);
        assertBand(b, 105 - IntervalBlock.CUSTOM_HALF_BAND_PCT, 105 + IntervalBlock.CUSTOM_HALF_BAND_PCT);
        assertEquals(1.05, b.targetFraction(), 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void customBelowMinimumIsRejected() {
        IntervalBlock.custom(IntervalBlock.MIN_TARGET_PCT - 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void customAboveMaximumIsRejected() {
        IntervalBlock.custom(IntervalBlock.MAX_TARGET_PCT + 1);
    }

    @Test
    public void wireWattsAreTargetLowHighFromFtp() {
        // Drempel at FTP 280: target 97.5 % = 273, low 95 % = 266, high 100 % = 280.
        assertArrayEquals(new int[]{273, 266, 280},
                IntervalBlock.of(IntervalBlock.Preset.DREMPEL).wireWatts(280));
    }

    @Test
    public void wireWattsNullWithoutFtp() {
        assertNull(IntervalBlock.of(IntervalBlock.Preset.TEMPO).wireWatts(0));
    }

    @Test
    public void storedRoundTrip() {
        IntervalBlock b = IntervalBlock.of(IntervalBlock.Preset.VO2MAX);
        IntervalBlock back = IntervalBlock.fromStored(b.toStored());
        assertNotNull(back);
        assertEquals(IntervalBlock.Preset.VO2MAX, back.preset);
        assertBand(back, 110, 120);
    }

    @Test
    public void fromStoredRejectsInvalidData() {
        assertNull(IntervalBlock.fromStored(null));
        StoredIntervalBlock inverted = new StoredIntervalBlock();
        inverted.preset = "CUSTOM";
        inverted.lowPct = 120;
        inverted.highPct = 100;
        assertNull(IntervalBlock.fromStored(inverted));
        StoredIntervalBlock absurd = new StoredIntervalBlock();
        absurd.preset = "CUSTOM";
        absurd.lowPct = 0;
        absurd.highPct = 5;
        assertNull(IntervalBlock.fromStored(absurd));
    }

    @Test
    public void fromStoredUnknownPresetFallsBackToCustom() {
        StoredIntervalBlock s = new StoredIntervalBlock();
        s.preset = "SOMETHING_NEW";
        s.lowPct = 90;
        s.highPct = 96;
        IntervalBlock b = IntervalBlock.fromStored(s);
        assertNotNull(b);
        assertEquals(IntervalBlock.Preset.CUSTOM, b.preset);
        assertBand(b, 90, 96);
    }

    @Test
    public void labelIsDutch() {
        assertEquals("Drempel · 95–100 % FTP",
                IntervalBlock.of(IntervalBlock.Preset.DREMPEL).label());
        assertEquals("Eigen doel · 102–108 % FTP", IntervalBlock.custom(105).label());
    }

    @Test
    public void routeSignatureChangesWithABlock() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        StoredClimb c = new StoredClimb();
        r.climbs.add(c);
        String before = IntervalBlock.signature(r);
        c.intervalBlock = IntervalBlock.of(IntervalBlock.Preset.TEMPO).toStored();
        String after = IntervalBlock.signature(r);
        assertEquals(false, before.equals(after));
        assertEquals("", IntervalBlock.signature(null));
    }

    private static void assertBand(IntervalBlock b, int low, int high) {
        assertEquals(low, b.lowPct);
        assertEquals(high, b.highPct);
    }
}
