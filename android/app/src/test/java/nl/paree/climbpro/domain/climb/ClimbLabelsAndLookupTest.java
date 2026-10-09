package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.Segment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Dutch labels for climb properties, descent text, identity parsing and catalog lookup. */
@RunWith(RobolectricTestRunner.class)
public class ClimbLabelsAndLookupTest {

    @Test
    public void usageLabels() {
        assertEquals("", ClimbUsageLabel.forType(null));
        assertEquals("trainingsklim", ClimbUsageLabel.forType(ClimbUsageType.TRAINING));
        assertEquals("eenmalige klim", ClimbUsageLabel.forType(ClimbUsageType.RECREATIONAL));
        assertEquals("", ClimbUsageLabel.forType(ClimbUsageType.UNKNOWN));
    }

    @Test
    public void surfaceLabels() {
        assertEquals("", ClimbSurfaceLabel.forComposition(null));
        assertEquals("asfalt", ClimbSurfaceLabel.forComposition(ClimbSurfaceComposition.PAVED));
        assertEquals("gravel", ClimbSurfaceLabel.forComposition(ClimbSurfaceComposition.GRAVEL));
        assertEquals("gemengd", ClimbSurfaceLabel.forComposition(ClimbSurfaceComposition.MIXED));
        assertEquals("", ClimbSurfaceLabel.forComposition(ClimbSurfaceComposition.UNKNOWN));
        assertEquals("", ClimbSurfaceLabel.forStoredClimb(null));
    }

    @Test
    public void shapeLabels() {
        assertEquals("", ClimbShapeLabel.forShape(null));
        assertEquals("gelijkmatig", ClimbShapeLabel.forShape(ClimbShape.STEADY));
        assertEquals("steil einde", ClimbShapeLabel.forShape(ClimbShape.STEEP_FINISH));
        assertEquals("rustige start", ClimbShapeLabel.forShape(ClimbShape.EASY_START));
        assertEquals("grillig", ClimbShapeLabel.forShape(ClimbShape.IRREGULAR));
        assertEquals("", ClimbShapeLabel.forStoredClimb(null));
    }

    @Test
    public void descentLabel_coversHairpinsEndsAndTwistiness() {
        assertTrue(DescentLabel.format(null).contains("geen noemenswaardige afdaling"));

        String straight = DescentLabel.format(new DescentAnalyzer.Descent(
                4_200, 300, 0.071, 0.12, 0, 0, DescentAnalyzer.End.RISE));
        assertEquals("Afdaling na de top: 4,2 km · 300 m omlaag · gem. 7,1 % · max 12,0 % · vrij recht",
                straight);

        String oneHairpin = DescentLabel.format(new DescentAnalyzer.Descent(
                2_000, 100, 0.05, 0.08, 10_000, 1, DescentAnalyzer.End.NEXT_CLIMB));
        assertTrue(oneHairpin, oneHairpin.contains("zeer bochtig · 1 haarspeldbocht (tot de volgende klim)"));

        String many = DescentLabel.format(new DescentAnalyzer.Descent(
                2_000, 100, 0.05, 0.08, 0, 7, DescentAnalyzer.End.ROUTE_END));
        assertTrue(many, many.endsWith("7 haarspeldbochten (tot het einde van de route)"));

        assertEquals("bochtig", DescentLabel.twistiness(DescentAnalyzer.Twistiness.TWISTY));
    }

    @Test
    public void approxStart_rejectsMalformedIds() {
        assertNull(ClimbIdentity.approxStart(null));
        assertNull(ClimbIdentity.approxStart("a:b"));
        assertNull(ClimbIdentity.approxStart("x:1:1000"));
        String id = ClimbIdentity.of(45.1234, 6.5678, 1000);
        double[] start = ClimbIdentity.approxStart(id);
        assertEquals(45.1234, start[0], 0.01);
        assertEquals(6.5678, start[1], 0.01);
    }

    @Test
    public void knownClimb_shortConstructorsLeaveOptionalGeometryEmpty() {
        KnownClimb a = new KnownClimb("id", 1, 2, 3, 4, 1000);
        assertNull(a.segLengthsM);
        assertNull(a.calibLats);
        KnownClimb b = new KnownClimb("id", 1, 2, 3, 4, 1000, new int[]{500, 500});
        assertArrayEquals(new int[]{500, 500}, b.segLengthsM);
        assertNull(b.calibLons);
    }

    @Test
    public void toStrings_areReadable() {
        Climb c = Climb.builder().startDistance(100).length(1000).avgGradient(0.065)
                .segments(Collections.<Segment>emptyList()).build();
        assertTrue(c.toString(), c.toString().contains("grad=6.5%"));
        assertTrue(new Segment(80, 5, 0.0625, 3).toString().contains("grad=6.3%")
                || new Segment(80, 5, 0.0625, 3).toString().contains("grad=6.2%"));
    }

    @Test
    public void catalogIndex_resolvesFirstCopyAndAllCopies_skippingBrokenRoutes() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        RouteRepository repo = new RouteRepository(app);
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i <= 10; i++) pts.add(new RoutePoint(45 + i * 0.0009, 6, i * 6, i * 100));
        Climb climb = Climb.builder().startDistance(0).endDistance(1000).length(1000)
                .elevationGain(60).avgGradient(0.06).startLat(45).startLon(6)
                .segments(Collections.singletonList(new Segment(1000, 60, 0.06, 3))).build();
        for (String id : new String[]{"a", "b", "kapot"}) {
            StoredRoute r = new StoredRoute();
            r.routeId = id;
            repo.saveRoute(r, pts, Collections.singletonList(climb));
        }
        new File(new File(app.getFilesDir(), "routes"), "kapot.json").delete();
        String climbId = ClimbIdentity.of(repo.loadRoute("a").climbs.get(0));

        Map<String, ClimbCatalogIndex.Entry> one = ClimbCatalogIndex.resolve(repo,
                new HashSet<>(Arrays.asList(climbId, "missing")));
        Map<String, List<StoredClimb>> all = ClimbCatalogIndex.resolveAllCopies(repo,
                Collections.singleton(climbId));

        assertEquals(1, one.size());
        assertEquals("a", one.get(climbId).routeId);
        assertEquals(0, one.get(climbId).index);
        assertEquals(2, all.get(climbId).size());
        assertTrue(ClimbCatalogIndex.resolve(repo, null).isEmpty());
        assertTrue(ClimbCatalogIndex.resolveAllCopies(repo, Collections.<String>emptySet()).isEmpty());
    }
}
