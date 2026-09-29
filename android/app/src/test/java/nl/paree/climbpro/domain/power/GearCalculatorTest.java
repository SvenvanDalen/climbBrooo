package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.domain.power.GearCalculator.Gear;
import nl.paree.climbpro.domain.power.GearCalculator.Result;

import org.junit.Test;

public class GearCalculatorTest {

    private static final double CRR = 0.004;

    @Test
    public void cadenceFormula() {
        // 34x34 on 2105 mm: 2.105 m per crank rev; 2.105 m/s → 60 rpm.
        assertEquals(60.0, GearCalculator.cadence(2.105, 34, 34, 2105), 1e-9);
        // 50x25 (ratio 2) at 8.42 m/s → 8.42 / 4.21 * 60 = 120 rpm.
        assertEquals(120.0, GearCalculator.cadence(8.42, 50, 25, 2105), 1e-9);
    }

    @Test
    public void neededSprocketInvertsCadence() {
        // At 2.105 m/s on a 34, 80 rpm needs s = 80*2.105*34/(2.105*60) = 45.33 → 46.
        assertEquals(46, GearCalculator.neededSprocket(2.105, 34, 80, 2105));
        // Exact fit is not rounded up: 60 rpm → 34.
        assertEquals(34, GearCalculator.neededSprocket(2.105, 34, 60, 2105));
    }

    @Test
    public void computeSortsEasiestFirstAndUsesPowerModel() {
        Result r = GearCalculator.compute(new int[]{50, 34}, new int[]{11, 34, 28}, 2105, 80,
                0.12, 0.07, 250, 80, CRR);
        assertEquals(6, r.gears.size());
        Gear easiest = r.easiest();
        assertEquals(34, easiest.chainring);
        assertEquals(34, easiest.sprocket);
        assertEquals(50, r.gears.get(5).chainring);
        assertEquals(11, r.gears.get(5).sprocket);
        // Steeper → slower → lower cadence in the same gear.
        assertTrue(r.speedSteepMps < r.speedAvgMps);
        assertTrue(easiest.cadenceSteepRpm < easiest.cadenceAvgRpm);
        double expectedSpeed = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.12, CRR);
        assertEquals(GearCalculator.cadence(expectedSpeed, 34, 34, 2105),
                easiest.cadenceSteepRpm, 1e-9);
    }

    @Test
    public void verdictEnoughVsTooHeavy() {
        // Strong light rider on a mild climb: plenty.
        Result easy = GearCalculator.compute(new int[]{34}, new int[]{34}, 2105, 80,
                0.06, 0.05, 300, 70, CRR);
        assertTrue(easy.easiestIsEnough());
        assertTrue(GearCalculator.verdict(easy).contains("genoeg"));

        // Heavy rider, 39x25 on 15 %: far too heavy.
        Result hard = GearCalculator.compute(new int[]{53, 39}, new int[]{11, 25}, 2105, 80,
                0.15, 0.10, 180, 95, CRR);
        assertFalse(hard.easiestIsEnough());
        String v = GearCalculator.verdict(hard);
        assertTrue(v, v.startsWith("Je lichtste versnelling (39×25) geeft maar"));
        assertTrue(v, v.contains("15%"));
    }

    @Test
    public void parseChainrings() {
        assertArrayEquals(new int[]{50, 34}, GearCalculator.parseChainrings("34/50"));
        assertArrayEquals(new int[]{50, 34}, GearCalculator.parseChainrings(" 50 - 34 "));
        assertArrayEquals(new int[]{42}, GearCalculator.parseChainrings("42"));
        assertRejectedChainrings("");
        assertRejectedChainrings("50/abc");
        assertRejectedChainrings("50/5");
        assertRejectedChainrings("53/46/39/30");
    }

    @Test
    public void parseCassette() {
        assertArrayEquals(GearCalculator.CASSETTES.get("11-34"),
                GearCalculator.parseCassette("11 - 34"));
        assertArrayEquals(new int[]{11, 13, 15, 17},
                GearCalculator.parseCassette("17,11,15,13,13"));
        try {
            GearCalculator.parseCassette("11-99");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Onbekende cassette"));
        }
        try {
            GearCalculator.parseCassette("11,");
            fail();
        } catch (IllegalArgumentException expected) {
            // single sprocket
        }
    }

    @Test
    public void presetCassettesAreAscendingAndEndOnTheirName() {
        for (java.util.Map.Entry<String, int[]> e : GearCalculator.CASSETTES.entrySet()) {
            int[] s = e.getValue();
            String[] ends = e.getKey().split("-");
            assertEquals(e.getKey(), Integer.parseInt(ends[0]), s[0]);
            assertEquals(e.getKey(), Integer.parseInt(ends[1]), s[s.length - 1]);
            for (int i = 1; i < s.length; i++) assertTrue(e.getKey(), s[i] > s[i - 1]);
        }
    }

    private static void assertRejectedChainrings(String text) {
        try {
            GearCalculator.parseChainrings(text);
            fail("accepted " + text);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
