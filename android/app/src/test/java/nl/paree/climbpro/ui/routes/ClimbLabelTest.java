package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.data.route.StoredClimb;

@RunWith(RobolectricTestRunner.class)
public class ClimbLabelTest {

    @Test
    public void climbLabel() {
        StoredClimb c = new StoredClimb();
        assertEquals("klim 3", RouteDetailActivity.climbLabel(c, 2));
        c.name = "Col";
        assertEquals("Col", RouteDetailActivity.climbLabel(c, 2));
        c.userDisplayName = "Mijn col";
        assertEquals("Mijn col", RouteDetailActivity.climbLabel(c, 2));
    }
}
