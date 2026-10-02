package nl.paree.climbpro.data.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.explore.ExploreMap;
import nl.paree.climbpro.domain.ride.RideTrack;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExploreMapRepositoryTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file;
    private ExploreMapRepository repo;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), ExploreMapRepository.FILE);
        repo = new ExploreMapRepository(file);
    }

    private static RideTrack track(double startLat) {
        double[] lat = new double[10];
        double[] lon = new double[10];
        for (int i = 0; i < 10; i++) {
            lat[i] = startLat + i * 0.001; // ~111 m per sample
            lon[i] = 5.0;
        }
        return new RideTrack(lat, lon, null);
    }

    @Test
    public void missingFileLoadsEmpty() {
        assertEquals(0, repo.load().tileCount());
    }

    @Test
    public void addRidesPersistsTilesAndRideIds() throws Exception {
        Map<Long, RideTrack> tracks = new LinkedHashMap<>();
        tracks.put(1L, track(50.0));
        tracks.put(2L, null); // indoor ride: processed, no cells
        int added = repo.addRides(tracks);

        ExploreMap back = new ExploreMapRepository(file).load();
        assertTrue(added > 0);
        assertEquals(added, back.tileCount());
        assertEquals(2, back.rideCount());
        assertTrue(back.containsRide(2L));
    }

    @Test
    public void addingTheSameRideAgainAddsNothing() throws Exception {
        Map<Long, RideTrack> tracks = new LinkedHashMap<>();
        tracks.put(1L, track(50.0));
        repo.addRides(tracks);
        int before = repo.load().tileCount();

        Map<Long, RideTrack> again = new LinkedHashMap<>();
        again.put(1L, track(51.0));
        assertEquals(0, repo.addRides(again));
        assertEquals(before, repo.load().tileCount());
    }

    @Test
    public void corruptFileLoadsEmpty() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(0, repo.load().tileCount());
    }

    @Test
    public void deleteAllRemovesTheFile() throws Exception {
        Map<Long, RideTrack> tracks = new LinkedHashMap<>();
        tracks.put(1L, track(50.0));
        repo.addRides(tracks);
        assertTrue(repo.deleteAll());
        assertFalse(file.exists());
    }
}
