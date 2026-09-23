package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import nl.paree.climbpro.data.route.StoredClimb;

import org.junit.Test;

public class ClimbIdentityTest {

    @Test
    public void sameClimbAcrossRoutes_yieldsSameKey() {
        // Two imports of the same climb with tiny GPS/length jitter must collapse.
        String a = ClimbIdentity.of(45.83210, 6.86420, 9000);
        String b = ClimbIdentity.of(45.83225, 6.86411, 9040);
        assertEquals(a, b);
    }

    @Test
    public void differentClimbs_yieldDifferentKeys() {
        String a = ClimbIdentity.of(45.83210, 6.86420, 9000);
        String farAway = ClimbIdentity.of(46.10000, 7.20000, 9000);
        assertNotEquals(a, farAway);

        String muchLonger = ClimbIdentity.of(45.83210, 6.86420, 12000);
        assertNotEquals(a, muchLonger);
    }

    @Test
    public void effectiveLength_prefersStoredLength_overEndMinusStartFallback() {
        StoredClimb c = new StoredClimb();
        c.startLat = 45.83210;
        c.startLon = 6.86420;
        c.startDistance = 1000;
        c.endDistance = 10500; // would yield 9500 via the end-minus-start fallback
        c.length = 9000;       // stored length must win

        assertEquals(9000, ClimbIdentity.effectiveLength(c));
        assertEquals(ClimbIdentity.of(45.83210, 6.86420, 9000), ClimbIdentity.of(c));
    }

    @Test
    public void effectiveLength_fallsBackToEndMinusStart_whenLengthUnset() {
        StoredClimb c = new StoredClimb();
        c.startLat = 45.83210;
        c.startLon = 6.86420;
        c.startDistance = 1000;
        c.endDistance = 10500;
        c.length = 0; // unset — must fall back

        assertEquals(9500, ClimbIdentity.effectiveLength(c));
        assertEquals(ClimbIdentity.of(45.83210, 6.86420, 9500), ClimbIdentity.of(c));
    }

    /**
     * Regression for the divergence risk {@link ClimbIdentity#of(StoredClimb)} and
     * {@link ClimbIdentity#effectiveLength(StoredClimb)} were introduced to close: every
     * caller that needs a climbId or an effective length from a {@link StoredClimb} — both
     * {@code KnownClimbs.fromRoute} and {@code UnfinishedClimbsViewModel.resolveClimbInfo} —
     * MUST route through these shared helpers rather than keeping its own copy of the
     * length-fallback ternary, or the same {@code StoredClimb} can silently resolve to two
     * different climbIds/lengths depending on which call site computed it.
     */
    @Test
    public void of_and_effectiveLength_agreeForSameStoredClimb_regardlessOfCallSite() {
        StoredClimb withStoredLength = new StoredClimb();
        withStoredLength.startLat = 45.83210;
        withStoredLength.startLon = 6.86420;
        withStoredLength.startDistance = 2000;
        withStoredLength.endDistance = 11200;
        withStoredLength.length = 9000;

        StoredClimb withoutStoredLength = new StoredClimb();
        withoutStoredLength.startLat = 45.83210;
        withoutStoredLength.startLon = 6.86420;
        withoutStoredLength.startDistance = 0;
        withoutStoredLength.endDistance = 9000;
        withoutStoredLength.length = 0;

        assertEquals(ClimbIdentity.effectiveLength(withStoredLength),
                ClimbIdentity.effectiveLength(withoutStoredLength));
        assertEquals(ClimbIdentity.of(withStoredLength), ClimbIdentity.of(withoutStoredLength));
    }
}
