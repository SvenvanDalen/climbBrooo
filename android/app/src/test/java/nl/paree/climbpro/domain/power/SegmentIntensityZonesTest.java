package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.export.ClimbWorkoutWriter;
import nl.paree.climbpro.domain.ride.ZoneCalculator;
import nl.paree.climbpro.domain.segment.GradientColor;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SegmentIntensityZonesTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 75, 8);

    // ---- zone -> color mapping (protocol/colors.md) ----------------------

    @Test public void powerZoneColorMapping() {
        assertEquals(0, GradientColor.forPowerZone(0)); // Z1
        assertEquals(1, GradientColor.forPowerZone(1)); // Z2
        assertEquals(2, GradientColor.forPowerZone(2)); // Z3
        assertEquals(3, GradientColor.forPowerZone(3)); // Z4
        assertEquals(4, GradientColor.forPowerZone(4)); // Z5
        assertEquals(5, GradientColor.forPowerZone(5)); // Z6
        assertEquals(5, GradientColor.forPowerZone(6)); // Z7 shares red
    }

    @Test public void powerZoneColorClampsOutOfRange() {
        assertEquals(0, GradientColor.forPowerZone(-3));
        assertEquals(5, GradientColor.forPowerZone(42));
    }

    // ---- Coggan zone boundaries ------------------------------------------

    @Test public void cogganZoneBoundariesAreInclusiveLower() {
        assertEquals(0, ZoneCalculator.powerZoneIndex(0.0));
        assertEquals(0, ZoneCalculator.powerZoneIndex(0.549));
        assertEquals(1, ZoneCalculator.powerZoneIndex(0.55));
        assertEquals(2, ZoneCalculator.powerZoneIndex(0.75));
        assertEquals(3, ZoneCalculator.powerZoneIndex(0.90));
        assertEquals(3, ZoneCalculator.powerZoneIndex(1.0));
        assertEquals(4, ZoneCalculator.powerZoneIndex(1.05));
        assertEquals(5, ZoneCalculator.powerZoneIndex(1.20));
        assertEquals(6, ZoneCalculator.powerZoneIndex(1.50));
        assertEquals(6, ZoneCalculator.powerZoneIndex(3.0));
    }

    // ---- missing input ----------------------------------------------------

    @Test public void nullWithoutFtp() {
        assertNull(SegmentIntensityZones.zones(climb(2000, 0.06), new RiderProfile(0, 75, 8)));
    }

    @Test public void nullWithoutWeights() {
        assertNull(SegmentIntensityZones.zones(climb(2000, 0.06), new RiderProfile(250, 0, 8)));
        assertNull(SegmentIntensityZones.zones(climb(2000, 0.06), new RiderProfile(250, 75, 0)));
    }

    @Test public void nullWithoutProfile() {
        assertNull(SegmentIntensityZones.zones(climb(2000, 0.06), null));
        assertNull(SegmentIntensityZones.colorIndices(climb(2000, 0.06), null));
    }

    @Test public void nullWithoutSegments() {
        assertNull(SegmentIntensityZones.zones((List<StoredSegment>) null, RIDER));
        assertNull(SegmentIntensityZones.zones(Collections.<StoredSegment>emptyList(), RIDER));
        assertNull(SegmentIntensityZones.colorIndices(Collections.<StoredSegment>emptyList(), RIDER));
    }

    @Test public void nullOnMismatchedArrays() {
        assertNull(SegmentIntensityZones.zones(new int[]{100, 100}, new double[]{0.05},
                new int[]{SurfaceType.UNKNOWN, SurfaceType.UNKNOWN}, RIDER));
        assertNull(SegmentIntensityZones.zones(new int[]{100}, new double[]{0.05},
                new int[]{}, RIDER));
        assertNull(SegmentIntensityZones.zones(null, null, null, RIDER));
    }

    @Test public void nullSegmentEntryGivesNull() {
        List<StoredSegment> segs = climb(2000, 0.06);
        segs.set(1, null);
        assertNull(SegmentIntensityZones.zones(segs, RIDER));
    }

    // ---- the zones themselves ---------------------------------------------

    @Test public void oneZonePerSegment() {
        List<StoredSegment> segs = climb(2000, 0.06);
        int[] zones = SegmentIntensityZones.zones(segs, RIDER);
        assertNotNull(zones);
        assertEquals(segs.size(), zones.length);
        for (int z : zones) assertTrue("zone in 0..6: " + z, z >= 0 && z <= 6);
    }

    @Test public void zonesFollowTheWorkoutPlanPower() {
        List<StoredSegment> segs = varied();
        int[] zones = SegmentIntensityZones.zones(segs, RIDER);
        int n = segs.size();
        int[] dist = new int[n];
        double[] grad = new double[n];
        int[] surf = new int[n];
        for (int i = 0; i < n; i++) {
            dist[i] = segs.get(i).distance;
            grad[i] = segs.get(i).gradient;
            surf[i] = segs.get(i).surfaceType;
        }
        ClimbWorkoutWriter.Plan plan = ClimbWorkoutWriter.plan(dist, grad, surf, RIDER);
        for (int i = 0; i < n; i++) {
            assertEquals("segment " + i,
                    ZoneCalculator.powerZoneIndex(plan.steps.get(i).ftpFraction), zones[i]);
        }
    }

    @Test public void steeperSegmentNeverInALowerZone() {
        List<StoredSegment> segs = varied();
        int[] zones = SegmentIntensityZones.zones(segs, RIDER);
        for (int i = 0; i < segs.size(); i++) {
            for (int j = 0; j < segs.size(); j++) {
                if (segs.get(i).gradient > segs.get(j).gradient) {
                    assertTrue("seg " + i + " steeper than " + j, zones[i] >= zones[j]);
                }
            }
        }
    }

    @Test public void steepAndFlatSegmentsLandInDifferentZones() {
        int[] zones = SegmentIntensityZones.zones(varied(), RIDER);
        int min = 7, max = -1;
        for (int z : zones) { min = Math.min(min, z); max = Math.max(max, z); }
        assertTrue("a 2 % vs 12 % climb spans more than one zone", max > min);
    }

    @Test public void shortClimbIsHarderThanLongClimbOfSameGradient() {
        // A short effort allows more power above FTP (W'), so its zone is at least as high.
        int[] shortZones = SegmentIntensityZones.zones(climb(1000, 0.06), RIDER);
        int[] longZones = SegmentIntensityZones.zones(climb(15000, 0.06), RIDER);
        assertTrue(shortZones[0] > longZones[0]);
    }

    @Test public void heavierRiderRidesTheSameClimbAtALowerOrEqualZone() {
        // Same FTP, more mass: the climb takes longer, so less of W' per second is available.
        int[] light = SegmentIntensityZones.zones(climb(3000, 0.07), new RiderProfile(250, 60, 7));
        int[] heavy = SegmentIntensityZones.zones(climb(3000, 0.07), new RiderProfile(250, 100, 9));
        for (int i = 0; i < light.length; i++) assertTrue(light[i] >= heavy[i]);
    }

    @Test public void typicalClimbIsAroundThreshold() {
        // 2 km at 6 % for a 250 W / 83 kg rider takes roughly 8-9 minutes: Z4-Z6.
        for (int z : SegmentIntensityZones.zones(climb(2000, 0.06), RIDER)) {
            assertTrue("zone " + z, z >= 3 && z <= 5);
        }
    }

    @Test public void colorIndicesMapEachZone() {
        List<StoredSegment> segs = varied();
        int[] zones = SegmentIntensityZones.zones(segs, RIDER);
        int[] colors = SegmentIntensityZones.colorIndices(segs, RIDER);
        int[] expected = new int[zones.length];
        for (int i = 0; i < zones.length; i++) expected[i] = GradientColor.forPowerZone(zones[i]);
        assertArrayEquals(expected, colors);
    }

    @Test public void labels() {
        assertEquals("Z1", SegmentIntensityZones.label(0));
        assertEquals("Z4", SegmentIntensityZones.label(3));
        assertEquals("Z7", SegmentIntensityZones.label(6));
        assertEquals("Z7", SegmentIntensityZones.label(9));
        assertEquals("Z1", SegmentIntensityZones.label(-1));
    }

    // ---- fixtures ----------------------------------------------------------

    /** Climb of the given length and constant gradient, split into 12 segments. */
    private static List<StoredSegment> climb(int lengthM, double gradient) {
        List<StoredSegment> segs = new ArrayList<>();
        for (int i = 0; i < 12; i++) segs.add(seg(lengthM / 12, gradient));
        return segs;
    }

    /** 2.4 km climb that alternates easy and very steep ramps. */
    private static List<StoredSegment> varied() {
        double[] grads = {0.02, 0.04, 0.06, 0.08, 0.10, 0.12, 0.02, 0.05, 0.09, 0.03, 0.11, 0.07};
        List<StoredSegment> segs = new ArrayList<>();
        for (double g : grads) segs.add(seg(200, g));
        return segs;
    }

    private static StoredSegment seg(int distance, double gradient) {
        StoredSegment s = new StoredSegment();
        s.distance = distance;
        s.gradient = gradient;
        s.elevationGain = (int) Math.round(distance * gradient);
        s.colorIndex = GradientColor.forGradient(gradient);
        return s;
    }
}
