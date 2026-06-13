package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
}
