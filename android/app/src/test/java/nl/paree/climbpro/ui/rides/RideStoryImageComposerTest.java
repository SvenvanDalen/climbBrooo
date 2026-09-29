package nl.paree.climbpro.ui.rides;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.RideStory;
import nl.paree.climbpro.domain.ride.RideTrack;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class RideStoryImageComposerTest {

    @Test
    public void composesWithAndWithoutTrackAndPhoto() {
        StoredRide ride = new StoredRide();
        ride.activityId = 1;
        ride.name = "Een hele lange ritnaam die zeker niet op één regel past in het plaatje";
        ride.distanceM = 50_000;
        ride.startEpochSec = 1_700_000_000L;
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "c";
        a.activityId = 1;
        a.elapsedSec = 3_725;
        RideStory story = RideStory.build(ride, Collections.singletonList(a), null, 14.0);

        Bitmap withAll = RideStoryImageComposer.compose(story,
                new RideTrack(new double[]{50, 50.1, 50.05}, new double[]{5, 5.1, 5.2}, 14.0),
                Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888));
        Bitmap bare = RideStoryImageComposer.compose(story, null, null);

        assertEquals(RideStoryImageComposer.WIDTH, withAll.getWidth());
        assertEquals(RideStoryImageComposer.HEIGHT, bare.getHeight());
        assertEquals("1:02:05", RideStoryImageComposer.duration(3_725));
        assertEquals("4:40", RideStoryImageComposer.duration(280));
    }
}
