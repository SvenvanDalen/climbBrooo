package nl.paree.climbpro.testsupport;

import android.content.Context;
import android.content.SharedPreferences;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * A realistic data set for screen tests: two routes with detected climbs, a year of climb
 * attempts, outdoor and MyWhoosh rides with stream statistics, a complete rider profile and a
 * collection. Written through the real repositories so every screen reads it the normal way.
 */
public final class UiTestData {

    public static final String ROUTE_ID = "r1";
    public static final String ROUTE_ID_2 = "r2";
    public static final long RIDE_OUTDOOR = 1001L;
    public static final long RIDE_OUTDOOR_2 = 1002L;
    public static final long RIDE_MYWHOOSH = 2001L;
    public static final long RIDE_MYWHOOSH_2 = 2002L;

    /** Collection created by {@link #seed}. */
    public static String collectionId;

    private UiTestData() {}

    public static void seed(Context context) throws Exception {
        Context app = context.getApplicationContext();
        SharedPreferences repoPrefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        repoPrefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        RouteRepository routes = new RouteRepository(app);
        saveRoute(routes, ROUTE_ID, "Ardennen rondje", 50.40, 5.80);
        saveRoute(routes, ROUTE_ID_2, "Limburgse heuvels", 50.80, 5.90);

        RiderProfileRepository rider = new RiderProfileRepository(app);
        rider.save(new RiderProfile(260, 74, 8.5));
        rider.saveMaxHeartRate(188);

        long now = System.currentTimeMillis() / 1000L;
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(RIDE_OUTDOOR, "Ochtendrit Ardennen", "Ride", now - 3 * 86_400L, 50.40, 5.80));
        rides.add(ride(RIDE_OUTDOOR_2, "Ardennen opnieuw", "Ride", now - 20 * 86_400L, 50.40, 5.80));
        rides.add(ride(RIDE_MYWHOOSH, "MyWhoosh - Hautacam Summit", "VirtualRide",
                now - 5 * 86_400L, null, null));
        rides.add(ride(RIDE_MYWHOOSH_2, "MyWhoosh - Hautacam Summit", "VirtualRide",
                now - 40 * 86_400L, null, null));
        for (int i = 0; i < 12; i++) {
            rides.add(ride(3000L + i, "Rit " + i, i % 4 == 0 ? "GravelRide" : "Ride",
                    now - (i * 9L + 2) * 86_400L, 50.40 + i * 0.01, 5.80));
        }
        new RideRepository(app).upsertAll(rides);

        List<StoredRideStreamStats> stats = new ArrayList<>();
        for (StoredRide r : rides) stats.add(stats(r.activityId));
        new RideStreamStatsRepository(app).upsertAll(stats);

        List<StoredClimbAttempt> attempts = new ArrayList<>();
        StoredRoute route = routes.loadRoute(ROUTE_ID);
        for (int c = 0; c < route.climbs.size(); c++) {
            String climbId = climbId(route.climbs.get(c));
            int segs = route.climbs.get(c).segments != null ? route.climbs.get(c).segments.size() : 0;
            long[] ids = {RIDE_OUTDOOR, RIDE_OUTDOOR_2, RIDE_MYWHOOSH, 3001L, 3002L, 3003L};
            for (int i = 0; i < ids.length; i++) {
                StoredClimbAttempt a = new StoredClimbAttempt();
                a.climbId = climbId;
                a.activityId = ids[i];
                a.dateEpochSec = now - (i * 30L + 3) * 86_400L;
                a.elapsedSec = 900 + i * 25 + c * 60;
                a.startOffsetSec = 600 + c * 1200;
                a.avgTempC = 14.0 + i;
                if (segs > 0) {
                    a.segSplitSec = new int[segs];
                    for (int s = 0; s < segs; s++) a.segSplitSec[s] = a.elapsedSec / segs;
                }
                attempts.add(a);
            }
        }
        new ClimbAttemptRepository(app).append(attempts);

        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        RouteCollection col = collections.create("Favorieten");
        collections.addRoute(col.id, ROUTE_ID);
        collections.addClimb(col.id, ROUTE_ID_2, 0);
        collectionId = col.id;
    }

    public static String climbId(StoredClimb c) {
        int len = c.length > 0 ? c.length : c.endDistance - c.startDistance;
        return ClimbIdentity.of(c.startLat, c.startLon, len);
    }

    /** 25 km northwards: flat, a 5 km climb at ~6 %, flat, 2 km at ~9 %, then down. */
    private static void saveRoute(RouteRepository routes, String id, String name, double lat0,
                                  double lon0) throws Exception {
        List<RoutePoint> raw = new ArrayList<>();
        double metersPerDegree = 111_195.0;
        for (int m = 0; m <= 25_000; m += 50) {
            double ele;
            if (m < 5_000) ele = 100 + (m % 400 == 0 ? 0.5 : 0);
            else if (m < 10_000) ele = 100 + (m - 5_000) * 0.06;
            else if (m < 13_000) ele = 400;
            else if (m < 15_000) ele = 400 + (m - 13_000) * 0.09;
            else ele = Math.max(100, 580 - (m - 15_000) * 0.05);
            raw.add(new RoutePoint(lat0 + m / metersPerDegree, lon0, ele, 0));
        }
        List<RoutePoint> smoothed = ElevationSmoother.smooth(CumulativeDistance.compute(raw), 5);
        // No Douglas-Peucker: it is 2D-only and would collapse this straight line to its ends.
        List<RoutePoint> simple = smoothed;
        List<Climb> climbs = ClimbDetector.detect(simple);
        StoredRoute stored = new StoredRoute();
        stored.routeId = id;
        stored.name = name;
        stored.notes = "Testroute met twee klimmen";
        stored.importedAtMs = System.currentTimeMillis();
        stored.sourceHash = "hash-" + id;
        routes.saveRoute(stored, simple, climbs);
    }

    private static StoredRide ride(long id, String name, String type, long start, Double lat,
                                   Double lon) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = name;
        r.type = type;
        r.sportType = type;
        r.startEpochSec = start;
        r.distanceM = 42_000;
        r.movingTimeSec = 5_400;
        r.elapsedTimeSec = 6_000;
        r.elevationGainM = 650;
        r.avgSpeedMps = 7.8f;
        r.maxSpeedMps = 17f;
        r.avgWatts = 205f;
        r.weightedAvgWatts = 225;
        r.deviceWatts = true;
        r.gearId = "b123";
        r.startLat = lat;
        r.startLon = lon;
        r.endLat = lat;
        r.endLon = lon;
        return r;
    }

    private static StoredRideStreamStats stats(long id) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = RideStreamAnalyzer.VERSION;
        s.hasStreams = true;
        s.best10kSec = 1_150;
        s.best40kSec = 4_900;
        s.sprint5sWatts = 820;
        s.sprint5sAtSec = 1_200;
        s.sprint15sWatts = 640;
        s.sprint10sSpeedMps = 15.2;
        s.sprint10sSpeedAtSec = 1_500;
        s.avgHeartrate = 142;
        s.hrDriftPct = 4.2;
        s.hrDriftBasis = "power";
        s.hrDriftMinutes = 75;
        s.powerCurve = new int[]{820, 410, 300, 262, 238};
        s.hrSecondsPerBpm = new int[160];
        for (int i = 60; i < 140; i++) s.hrSecondsPerBpm[i] = 40;
        s.powerSecondsPer10W = new int[60];
        for (int i = 10; i < 35; i++) s.powerSecondsPer10W[i] = 120;
        return s;
    }
}
