package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Corrupt-file handling, no-op paths and user-data edits of {@link RouteRepository}. */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryEdgeTest {

    private Application app;
    private RouteRepository repo;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app);
    }

    private static List<RoutePoint> points() {
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            pts.add(new RoutePoint(51.0 + i * 0.0009, 5.0 + (i % 2) * 0.0003, i * 5.0, i * 100.0));
        }
        return pts;
    }

    private static Climb climb(int startDistance, double startLat, String name) {
        return Climb.builder()
                .startDistance(startDistance)
                .endDistance(startDistance + 1000)
                .length(1000)
                .elevationGain(50)
                .avgGradient(0.05)
                .startLat(startLat)
                .startLon(5.0)
                .name(name)
                .segments(Arrays.asList(
                        new nl.paree.climbpro.domain.segment.Segment(500, 25, 0.05, 2),
                        new nl.paree.climbpro.domain.segment.Segment(500, 25, 0.05, 2)))
                .build();
    }

    private void save(String routeId, Climb... climbs) throws IOException {
        StoredRoute r = new StoredRoute();
        r.routeId = routeId;
        r.name = "Route " + routeId;
        repo.saveRoute(r, points(), Arrays.asList(climbs));
    }

    private File routeFile(String id) {
        return new File(new File(app.getFilesDir(), "routes"), id + ".json");
    }

    private void writeRouteFileOnly(StoredRoute route) throws IOException {
        routeFile(route.routeId).getParentFile().mkdirs();
        new ObjectMapper().writeValue(routeFile(route.routeId), route);
    }

    // ---- catalog / file corruption ----

    @Test
    public void loadCatalog_corruptFile_returnsEmptyList() throws Exception {
        Files.write(new File(app.getFilesDir(), "catalog.json").toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(repo.loadCatalog().isEmpty());
    }

    @Test
    public void saveRoute_corruptPreviousFile_isTreatedAsFirstImport() throws Exception {
        routeFile("r1").getParentFile().mkdirs();
        Files.write(routeFile("r1").toPath(), "garbage".getBytes(StandardCharsets.UTF_8));

        save("r1", climb(0, 51.0, "A"));

        assertEquals(1, repo.loadRoute("r1").climbs.size());
        assertEquals(1, repo.loadCatalog().size());
    }

    @Test
    public void saveRoute_targetNotWritable_throwsAndLeavesNoTmpFile() throws Exception {
        File target = routeFile("blocked");
        target.mkdirs();
        Files.write(new File(target, "child").toPath(), new byte[]{1});

        StoredRoute r = new StoredRoute();
        r.routeId = "blocked";
        try {
            repo.saveRoute(r, points(), Collections.<Climb>emptyList());
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(new File(target.getParentFile(), "blocked.json.tmp").exists());
    }

    @Test
    public void saveRoute_nullAndEmptyInputs_storeEmptyRoute() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "empty";
        repo.saveRoute(r, Collections.<RoutePoint>emptyList(), null);

        StoredRoute stored = repo.loadRoute("empty");
        assertTrue(stored.climbs.isEmpty());
        assertEquals(0, repo.loadCatalog().get(0).climbCount);
    }

    @Test(expected = IOException.class)
    public void loadRoute_missing_throws() throws Exception {
        repo.loadRoute("nope");
    }

    // ---- climb edits ----

    @Test
    public void setClimbShapeOverride_validNameKept_unknownNameClears() throws Exception {
        save("r1", climb(0, 51.0, "A"));

        repo.setClimbShapeOverride("r1", 0, "STEEP_FINISH");
        assertEquals("STEEP_FINISH", repo.loadRoute("r1").climbs.get(0).shapeOverride);

        repo.setClimbShapeOverride("r1", 0, "ZIGZAG");
        assertNull(repo.loadRoute("r1").climbs.get(0).shapeOverride);

        repo.setClimbShapeOverride("r1", 0, "EASY_START");
        repo.setClimbShapeOverride("r1", 0, null);
        assertNull(repo.loadRoute("r1").climbs.get(0).shapeOverride);
    }

    @Test
    public void setClimbShapeOverride_outOfRange_isNoOp() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.setClimbShapeOverride("r1", 3, "STEADY");
        repo.setClimbShapeOverride("r1", -1, "STEADY");
        assertNull(repo.loadRoute("r1").climbs.get(0).shapeOverride);
    }

    @Test
    public void setClimbRating_skipsUnreadableRoutesWhilePropagating() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        save("r2", climb(0, 51.0, "A"));
        routeFile("r2").delete();

        repo.setClimbRating("r1", 0, 4, 3, 5, "mooi");

        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertEquals(Integer.valueOf(4), c.ratingRoad);
        assertEquals("mooi", c.ratingNote);
    }

    @Test
    public void setClimbRating_outOfRange_isNoOp() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.setClimbRating("r1", 1, 4, 4, 4, null);
        assertNull(repo.loadRoute("r1").climbs.get(0).ratingRoad);
    }

    @Test
    public void saveRoute_inheritRatings_skipsUnreadableRoutes() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.setClimbRating("r1", 0, 5, 5, 5, null);
        save("broken", climb(0, 51.0, "A"));
        routeFile("broken").delete();

        save("r3", climb(0, 51.0, "A"));

        assertEquals(Integer.valueOf(5), repo.loadRoute("r3").climbs.get(0).ratingRoad);
    }

    @Test
    public void removeClimb_outOfRange_isNoOp() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.removeClimb("r1", 1);
        repo.removeClimb("r1", -1);
        assertEquals(1, repo.loadRoute("r1").climbs.size());
    }

    @Test
    public void removeClimb_rebuildsCatalogCoordsAndTombstonesAcrossResync() throws Exception {
        Climb a = climb(0, 51.0, "A");
        Climb b = climb(1500, 51.0135, "B");
        save("r1", a, b);

        repo.removeClimb("r1", 0);

        RouteCatalogEntry entry = repo.loadCatalog().get(0);
        assertEquals(1, entry.climbCount);
        assertArrayEquals(new double[]{51.0135, 5.0}, entry.climbStartCoords, 1e-9);

        // Re-detection brings the same two climbs back: the removed one stays gone.
        save("r1", climb(0, 51.0, "A"), climb(1500, 51.0135, "B"));
        StoredRoute stored = repo.loadRoute("r1");
        assertEquals(1, stored.climbs.size());
        assertEquals("B", stored.climbs.get(0).name);
        assertEquals(1, stored.removedClimbIds.size());
    }

    @Test
    public void removeClimb_lastClimb_clearsCatalogCoords() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.removeClimb("r1", 0);
        RouteCatalogEntry entry = repo.loadCatalog().get(0);
        assertEquals(0, entry.climbCount);
        assertNull(entry.climbStartCoords);
    }

    @Test
    public void setManualRefTime_trimsLabel_blankLabelBecomesNull_andClears() throws Exception {
        save("r1", climb(0, 51.0, "A"));

        repo.setManualRefTime("r1", 0, 600, "  Pogačar 2024 ");
        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertEquals(Integer.valueOf(600), c.manualRefSec);
        assertEquals("Pogačar 2024", c.manualRefLabel);

        repo.setManualRefTime("r1", 0, 700, "   ");
        assertNull(repo.loadRoute("r1").climbs.get(0).manualRefLabel);
        repo.setManualRefTime("r1", 0, 700, null);
        assertNull(repo.loadRoute("r1").climbs.get(0).manualRefLabel);

        repo.setManualRefTime("r1", 0, 0, "x");
        c = repo.loadRoute("r1").climbs.get(0);
        assertNull(c.manualRefSec);
        assertNull(c.manualRefLabel);

        repo.setManualRefTime("r1", 0, 600, "x");
        repo.setManualRefTime("r1", 0, null, "x");
        assertNull(repo.loadRoute("r1").climbs.get(0).manualRefSec);
    }

    @Test
    public void setManualRefTime_survivesResync() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.setManualRefTime("r1", 0, 600, "Pro");

        save("r1", climb(0, 51.0, "A"));

        StoredClimb c = repo.loadRoute("r1").climbs.get(0);
        assertEquals(Integer.valueOf(600), c.manualRefSec);
        assertEquals("Pro", c.manualRefLabel);
    }

    @Test
    public void setManualRefTime_outOfRange_isNoOp() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.setManualRefTime("r1", 2, 600, "x");
        repo.setManualRefTime("r1", -1, 600, "x");
        assertNull(repo.loadRoute("r1").climbs.get(0).manualRefSec);
    }

    // ---- route without catalog entry ----

    @Test
    public void notesAndRideStatus_routeMissingFromCatalog_stillWriteRouteFile() throws Exception {
        save("other", climb(0, 51.0, "A"));
        StoredRoute orphan = new StoredRoute();
        orphan.routeId = "orphan";
        writeRouteFileOnly(orphan);

        repo.saveNotes("orphan", "n");
        repo.setRideStatus("orphan", RouteRideStatus.WANT_TO_RIDE);

        StoredRoute stored = repo.loadRoute("orphan");
        assertEquals("n", stored.notes);
        assertEquals(RouteRideStatus.WANT_TO_RIDE, stored.rideStatus);
        assertEquals(1, repo.loadCatalog().size());
    }

    @Test
    public void surfaceEdit_routeMissingFromCatalog_addsStubEntry() throws Exception {
        save("first", climb(0, 51.0, "A"));
        StoredRoute orphan = new StoredRoute();
        orphan.routeId = "orphan";
        orphan.name = "Wees";
        orphan.lats = new double[]{51.0, 51.01};
        orphan.lons = new double[]{5.0, 5.0};
        orphan.distances = new double[]{0, 1000};
        orphan.elevations = new double[]{0, 0};
        writeRouteFileOnly(orphan);

        repo.addSurfaceSection("orphan", 0, 500, SurfaceType.GRAVEL);

        assertEquals(2, repo.loadCatalog().size());
        RouteCatalogEntry stub = repo.loadCatalog().get(1);
        assertEquals("orphan", stub.routeId);
        assertEquals("Wees", stub.name);
    }

    @Test
    public void findNearby_matchesAnyClimbStartWithinRadius() throws Exception {
        save("r1", climb(0, 51.0, "A"), climb(1500, 51.0135, "B"));
        StoredRoute noClimbs = new StoredRoute();
        noClimbs.routeId = "flat";
        repo.saveRoute(noClimbs, points(), Collections.<Climb>emptyList());

        assertEquals(1, repo.findNearby(51.0135, 5.0, 50).size());
        assertEquals("r1", repo.findNearby(51.0135, 5.0, 50).get(0).routeId);
        assertTrue(repo.findNearby(52.0, 5.0, 1000).isEmpty());
    }

    // ---- segments / flats / starred / sections ----

    @Test
    public void setSegmentManualTargetSec_setsAndClears_andRejectsBadIndices() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        assertFalse(repo.loadRoute("r1").climbs.get(0).segments.isEmpty());

        repo.setSegmentManualTargetSec("r1", 0, 0, 42);
        assertEquals(Integer.valueOf(42),
                repo.loadRoute("r1").climbs.get(0).segments.get(0).manualTargetSec);
        repo.setSegmentManualTargetSec("r1", 0, 0, null);
        assertNull(repo.loadRoute("r1").climbs.get(0).segments.get(0).manualTargetSec);

        assertThrows(IOException.class, () -> repo.setSegmentManualTargetSec("r1", 1, 0, 1));
        assertThrows(IOException.class, () -> repo.setSegmentManualTargetSec("r1", 0, 99, 1));
        assertThrows(IOException.class, () -> repo.setSegmentManualTargetSec("r1", 0, -1, 1));
    }

    @Test
    public void setBulkClimbSurfaceType_climbWithoutSegments_isNoOp() throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        StoredClimb c = new StoredClimb();
        c.segments = null;
        route.climbs = new ArrayList<>(Collections.singletonList(c));
        writeRouteFileOnly(route);

        repo.setBulkClimbSurfaceType("r1", 0, SurfaceType.GRAVEL);

        assertNull(repo.loadRoute("r1").climbs.get(0).segments);
    }

    @Test
    public void setBulkClimbSurfaceType_setsEverySegment() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.setBulkClimbSurfaceType("r1", 0, SurfaceType.COBBLESTONE);
        for (StoredSegment s : repo.loadRoute("r1").climbs.get(0).segments) {
            assertEquals(SurfaceType.COBBLESTONE, s.surfaceType);
        }
    }

    @Test
    public void flatSegmentEdits_unknownStart_areNoOps() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        StoredRoute before = repo.loadRoute("r1");
        assertFalse(before.flatSegments.isEmpty());
        int realStart = before.flatSegments.get(0).startDistance;

        repo.setFlatSegmentSurfaceType("r1", 123_456, SurfaceType.GRAVEL);
        repo.updateFlatSegment("r1", 123_456, SurfaceType.GRAVEL, "x");
        assertEquals(SurfaceType.UNKNOWN, repo.loadRoute("r1").flatSegments.get(0).surfaceType);

        repo.updateFlatSegment("r1", realStart, SurfaceType.GRAVEL, "  Dijk  ");
        StoredFlatSegment f = repo.loadRoute("r1").flatSegments.get(0);
        assertEquals(SurfaceType.GRAVEL, f.surfaceType);
        assertEquals("Dijk", f.name);
        repo.updateFlatSegment("r1", realStart, SurfaceType.GRAVEL, " ");
        assertNull(repo.loadRoute("r1").flatSegments.get(0).name);
    }

    @Test
    public void flatSegmentEdits_routeWithoutFlats_areNoOps() throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        writeRouteFileOnly(route);
        repo.setFlatSegmentSurfaceType("r1", 0, SurfaceType.GRAVEL);
        repo.updateFlatSegment("r1", 0, SurfaceType.GRAVEL, null);
        repo.updateStarredSegment("r1", 1L, SurfaceType.GRAVEL, null);
        assertNull(repo.loadRoute("r1").flatSegments);
    }

    @Test
    public void starredSegmentEdits_byStravaId() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        StoredStarredSegment s = new StoredStarredSegment();
        s.stravaId = 77L;
        s.name = "Ster";
        repo.saveRoute(r, points(), Collections.<Climb>emptyList(), Collections.singletonList(s));

        repo.setStarredSegmentSurface("r1", 77L, SurfaceType.GRAVEL);
        StoredStarredSegment stored = repo.loadRoute("r1").starredSegments.get(0);
        assertEquals(SurfaceType.GRAVEL, stored.surfaceType);
        assertNull(stored.userDisplayName);

        repo.updateStarredSegment("r1", 77L, SurfaceType.ASPHALT, "  Mijn ster ");
        assertEquals("Mijn ster", repo.loadRoute("r1").starredSegments.get(0).userDisplayName);

        repo.updateStarredSegment("r1", 999L, SurfaceType.COBBLESTONE, "x");
        assertEquals(SurfaceType.ASPHALT, repo.loadRoute("r1").starredSegments.get(0).surfaceType);
    }

    @Test
    public void addSurfaceSection_validatesBounds() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        assertThrows(IllegalArgumentException.class,
                () -> repo.addSurfaceSection("r1", -1, 100, SurfaceType.GRAVEL));
        assertThrows(IllegalArgumentException.class,
                () -> repo.addSurfaceSection("r1", 100, 100, SurfaceType.GRAVEL));
        assertThrows(IllegalArgumentException.class,
                () -> repo.addSurfaceSection("r1", 0, 3001, SurfaceType.GRAVEL));
    }

    @Test
    public void surfaceSections_sortedNamedAndEditable() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.addSurfaceSection("r1", 2000, 2500, SurfaceType.GRAVEL, " Grind ");
        repo.addSurfaceSection("r1", 100, 500, SurfaceType.COBBLESTONE);

        List<StoredSurfaceSection> s = repo.loadRoute("r1").surfaceSections;
        assertEquals(100, s.get(0).startDistance);
        assertNull(s.get(0).name);
        assertEquals("Grind", s.get(1).name);

        repo.setSurfaceSectionName("r1", 0, "Kasseien");
        repo.setSurfaceSectionName("r1", 5, "x");
        repo.setSurfaceSectionName("r1", -1, "x");
        assertEquals("Kasseien", repo.loadRoute("r1").surfaceSections.get(0).name);

        repo.updateSurfaceSection("r1", 1, SurfaceType.ASPHALT, "");
        StoredSurfaceSection second = repo.loadRoute("r1").surfaceSections.get(1);
        assertEquals(SurfaceType.ASPHALT, second.surfaceType);
        assertNull(second.name);

        repo.deleteSurfaceSection("r1", 7);
        repo.deleteSurfaceSection("r1", 0);
        assertEquals(1, repo.loadRoute("r1").surfaceSections.size());
        assertEquals(2000, repo.loadRoute("r1").surfaceSections.get(0).startDistance);
    }

    @Test
    public void surfaceSections_surviveResync() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        repo.addSurfaceSection("r1", 100, 500, SurfaceType.GRAVEL, "Grind");
        save("r1", climb(0, 51.0, "A"));
        assertEquals("Grind", repo.loadRoute("r1").surfaceSections.get(0).name);
    }

    @Test
    public void surfaceEdits_onSecondCatalogRoute_updateThatEntryOnly() throws Exception {
        save("r1", climb(0, 51.0, "A"));
        save("r2", climb(0, 51.0, "A"));

        repo.setBulkClimbSurfaceType("r2", 0, SurfaceType.GRAVEL);

        List<RouteCatalogEntry> catalog = repo.loadCatalog();
        assertEquals(2, catalog.size());
        assertFalse(Arrays.equals(catalog.get(0).surfaceTypes, catalog.get(1).surfaceTypes));
    }
}
