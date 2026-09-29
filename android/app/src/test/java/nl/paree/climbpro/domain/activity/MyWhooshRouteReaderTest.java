package nl.paree.climbpro.domain.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MyWhooshRouteReaderTest {

    private static final long FIT_TS = 1_100_000_000L;

    private static FitTrackDecoder.Record virtual(double distanceM, double altitudeM) {
        return new FitTrackDecoder.Record(Double.NaN, Double.NaN, altitudeM, distanceM, 0);
    }

    private static FitTrackDecoder.Record gps(double lat, double lon, double altitudeM) {
        return new FitTrackDecoder.Record(lat, lon, altitudeM, Double.NaN, 0);
    }

    @Test
    public void recognisesMyWhooshStravaUploadsOnly() {
        assertTrue(MyWhooshRouteReader.isMyWhooshActivity(
                "MyWhoosh - Hautacam Summit", "VirtualRide", "VirtualRide"));
        assertTrue(MyWhooshRouteReader.isMyWhooshActivity("mywhoosh: Alula", "VirtualRide", null));
        assertFalse(MyWhooshRouteReader.isMyWhooshActivity(
                "Zwift - Watopia", "VirtualRide", "VirtualRide"));
        assertFalse(MyWhooshRouteReader.isMyWhooshActivity("MyWhoosh - x", "Ride", "Ride"));
        assertFalse(MyWhooshRouteReader.isMyWhooshActivity(null, "VirtualRide", "VirtualRide"));
    }

    @Test
    public void routeTitleDropsTheMyWhooshPrefix() {
        assertEquals("Hautacam Summit", MyWhooshRouteReader.routeTitle("MyWhoosh - Hautacam Summit"));
        assertEquals("Jabel Hafeet", MyWhooshRouteReader.routeTitle("MyWhoosh: Jabel Hafeet"));
        assertEquals("MyWhoosh", MyWhooshRouteReader.routeTitle("MyWhoosh"));
    }

    @Test
    public void positionedRideKeepsItsGpsTrack() throws IOException {
        List<FitTrackDecoder.Record> records = new ArrayList<>();
        records.add(gps(24.0, 55.0, 10));
        records.add(virtual(5, Double.NaN));   // no altitude: skipped
        records.add(gps(24.001, 55.0, 12));
        records.add(virtual(20, 13));           // lone position-less sample in a GPS ride: dropped

        MyWhooshRouteReader.Result r = MyWhooshRouteReader.fromRecords(records);

        assertFalse(r.virtual);
        assertEquals(2, r.points.size());
        assertEquals(24.001, r.points.get(1).lat, 1e-9);
        assertEquals(12, r.points.get(1).elevation, 1e-9);
    }

    @Test
    public void virtualRideIsLaidOutByDistanceSkippingStandstill() throws IOException {
        List<FitTrackDecoder.Record> records = new ArrayList<>();
        records.add(virtual(0, 100));
        records.add(virtual(0, 100));           // standing still at the start
        records.add(virtual(500, 110));
        records.add(virtual(1000, 130));

        MyWhooshRouteReader.Result r = MyWhooshRouteReader.fromRecords(records);

        assertTrue(r.virtual);
        assertEquals(3, r.points.size());
        List<RoutePoint> withDist = CumulativeDistance.compute(r.points);
        assertEquals(500, withDist.get(1).distance, 0.01);
        assertEquals(1000, withDist.get(2).distance, 0.01);
        assertEquals(130, withDist.get(2).elevation, 1e-9);
    }

    @Test
    public void rejectsRidesWithoutAltitudeOrDistance() {
        List<FitTrackDecoder.Record> noAltitude = new ArrayList<>();
        noAltitude.add(gps(24.0, 55.0, Double.NaN));
        noAltitude.add(gps(24.1, 55.0, Double.NaN));
        List<FitTrackDecoder.Record> noDistance = new ArrayList<>();
        noDistance.add(virtual(Double.NaN, 100));
        noDistance.add(virtual(Double.NaN, 110));
        for (List<FitTrackDecoder.Record> bad : java.util.Arrays.asList(noAltitude, noDistance)) {
            try {
                MyWhooshRouteReader.fromRecords(bad);
                fail("expected IOException");
            } catch (IOException expected) {
                // ok
            }
        }
    }

    /** A 2 km virtual climb at 6 % between 2 km flats, as MyWhoosh writes it: no GPS at all. */
    @Test
    public void virtualFitClimbIsDetectedByTheClimbPipeline() throws IOException {
        FitTrackDecoderTest.Fit f = new FitTrackDecoderTest.Fit();
        // record: timestamp, altitude, distance — no lat/lon fields.
        f.def(0, false, 20, 253, 4, 0x86, 2, 2, 0x84, 5, 4, 0x86);
        for (int d = 0; d <= 6000; d += 10) {
            double alt = 50 + (d <= 2000 ? 0 : Math.min(d - 2000, 2000) * 0.06);
            f.u8(0).u32(FIT_TS + d / 10).u16((int) Math.round((alt + 500) * 5)).u32(d * 100L);
        }

        MyWhooshRouteReader.Result r = MyWhooshRouteReader.read(f.build());

        assertTrue(r.virtual);
        List<RoutePoint> simple = MyWhooshRouteReader.simplify(
                ElevationSmoother.smooth(CumulativeDistance.compute(r.points), 5), r.virtual);
        assertTrue(simple.size() > 100); // the plain 2D simplifier would keep only 2 points
        List<Climb> climbs = ClimbDetector.detect(simple);
        assertEquals(1, climbs.size());
        assertEquals(2000, climbs.get(0).length, 100);
        assertEquals(0.06, climbs.get(0).avgGradient, 0.005);
    }
}
