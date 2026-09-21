package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredSegment;

/**
 * TDD for issue #33 (share climb as image): {@link ClimbShareImageComposer} must compose a
 * fixed-size bitmap containing the header stats + the existing {@link ClimbProfileView} chart,
 * without crashing on missing segments/duration data.
 */
@RunWith(RobolectricTestRunner.class)
public class ClimbShareImageComposerTest {

    private Application ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static StoredClimb climbWithSegments() {
        StoredClimb climb = new StoredClimb();
        climb.name = "La Redoute";
        climb.length = 2000;
        climb.avgGradient = 0.085;
        climb.elevationGain = 170;

        List<StoredSegment> segs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 400;
            s.elevationGain = 34;
            s.gradient = 0.085;
            s.colorIndex = 3;
            segs.add(s);
        }
        climb.segments = segs;
        return climb;
    }

    @Test
    public void compose_returnsFixedSizeBitmap() {
        Bitmap bitmap = ClimbShareImageComposer.compose(ctx(), "La Redoute", climbWithSegments(),
                "Geschatte tijd: 12:30 · 250 W");

        assertNotNull(bitmap);
        assertEquals(ClimbShareImageComposer.WIDTH, bitmap.getWidth());
        assertEquals(ClimbShareImageComposer.TOTAL_HEIGHT, bitmap.getHeight());
    }

    @Test
    public void compose_nullTimeEstimate_stillProducesBitmap() {
        Bitmap bitmap = ClimbShareImageComposer.compose(ctx(), "La Redoute", climbWithSegments(), null);

        assertNotNull(bitmap);
        assertEquals(ClimbShareImageComposer.WIDTH, bitmap.getWidth());
        assertEquals(ClimbShareImageComposer.TOTAL_HEIGHT, bitmap.getHeight());
    }

    @Test
    public void compose_climbWithoutSegments_doesNotCrash() {
        StoredClimb climb = new StoredClimb();
        climb.name = "Empty";
        climb.length = 900;
        climb.avgGradient = 0.04;
        climb.elevationGain = 36;
        climb.segments = null;

        Bitmap bitmap = ClimbShareImageComposer.compose(ctx(), "Empty", climb, null);

        assertNotNull(bitmap);
        assertEquals(ClimbShareImageComposer.WIDTH, bitmap.getWidth());
        assertEquals(ClimbShareImageComposer.TOTAL_HEIGHT, bitmap.getHeight());
    }

    @Test
    public void compose_nullClimb_stillProducesHeaderAndFooterOnlyBitmap() {
        Bitmap bitmap = ClimbShareImageComposer.compose(ctx(), "Onbekende klim", null, null);

        assertNotNull(bitmap);
        assertEquals(ClimbShareImageComposer.WIDTH, bitmap.getWidth());
        assertEquals(ClimbShareImageComposer.TOTAL_HEIGHT, bitmap.getHeight());
    }
}
