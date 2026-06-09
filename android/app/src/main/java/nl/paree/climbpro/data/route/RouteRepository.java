package nl.paree.climbpro.data.route;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.CalibrationPoint;
import nl.paree.climbpro.domain.segment.FlatSegment;
import nl.paree.climbpro.domain.segment.FlatSegmentDetector;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.Segmenter;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-file persistence for routes.
 * Layout:
 *   getFilesDir()/routes/<routeId>.json  – full route document
 *   getFilesDir()/catalog.json           – lightweight index
 *
 * All writes are atomic (temp file + rename).
 */
public final class RouteRepository {

    private static final String TAG = "RouteRepository";
    private static final String CATALOG_FILE = "catalog.json";
    private static final String ROUTES_DIR   = "routes";
    private static final String PREFS_NAME           = "route_repo";
    private static final String PREF_SEGMENT_VERSION = "segment_version";

    private final Context context;
    private final File routesDir;
    private final File catalogFile;
    private final ObjectMapper mapper;

    public RouteRepository(Context context) {
        this.context  = context.getApplicationContext();
        File base     = this.context.getFilesDir();
        this.routesDir    = new File(base, ROUTES_DIR);
        this.catalogFile  = new File(base, CATALOG_FILE);
        this.mapper = new ObjectMapper()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        routesDir.mkdirs();
        migrateIfNeeded();
    }

    // -------------------------------------------------------------------------
    // Catalog operations
    // -------------------------------------------------------------------------

    public List<RouteCatalogEntry> loadCatalog() {
        if (!catalogFile.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(catalogFile)) {
            RouteCatalogEntry[] entries = mapper.readValue(in, RouteCatalogEntry[].class);
            return new ArrayList<>(Arrays.asList(entries));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load catalog", e);
            return new ArrayList<>();
        }
    }

    private void saveCatalog(List<RouteCatalogEntry> catalog) throws IOException {
        writeAtomic(catalogFile, mapper.writeValueAsBytes(catalog));
    }

    // -------------------------------------------------------------------------
    // Route CRUD
    // -------------------------------------------------------------------------

    public void saveRoute(StoredRoute route, List<RoutePoint> points, List<Climb> climbs) throws IOException {
        route.lats       = toDoubleArray(points, "lat");
        route.lons       = toDoubleArray(points, "lon");
        route.elevations = toDoubleArray(points, "ele");
        route.distances  = toDoubleArray(points, "dist");
        route.climbs     = toStoredClimbs(climbs);
        int routeLength = (points != null && !points.isEmpty())
                ? (int) Math.round(points.get(points.size() - 1).distance)
                : 0;
        List<FlatSegment> flatDomain = FlatSegmentDetector.detect(
                routeLength, climbs != null ? climbs : Collections.emptyList());
        List<RoutePoint> pts = points != null ? points : Collections.emptyList();
        route.flatSegments = toStoredFlatSegments(flatDomain, pts,
                loadPreviousFlatSegments(route.routeId));
        route.lastModifiedMs = System.currentTimeMillis();

        File routeFile = routeFile(route.routeId);
        writeAtomic(routeFile, mapper.writeValueAsBytes(route));

        List<RouteCatalogEntry> catalog = loadCatalog();
        catalog.removeIf(e -> e.routeId.equals(route.routeId));
        catalog.add(toCatalogEntry(route, points, climbs));
        saveCatalog(catalog);
    }

    public StoredRoute loadRoute(String routeId) throws IOException {
        File f = routeFile(routeId);
        if (!f.exists()) throw new IOException("Route not found: " + routeId);
        try (FileInputStream in = new FileInputStream(f)) {
            return mapper.readValue(in, StoredRoute.class);
        }
    }

    public void deleteRoute(String routeId) throws IOException {
        routeFile(routeId).delete();
        List<RouteCatalogEntry> catalog = loadCatalog();
        catalog.removeIf(e -> e.routeId.equals(routeId));
        saveCatalog(catalog);
    }

    public void renameRoute(String routeId, String displayName) throws IOException {
        StoredRoute route = loadRoute(routeId);
        route.userDisplayName = displayName;
        route.lastModifiedMs  = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));

        List<RouteCatalogEntry> catalog = loadCatalog();
        for (RouteCatalogEntry e : catalog) {
            if (e.routeId.equals(routeId)) {
                e.userDisplayName = displayName;
                e.lastModifiedMs  = System.currentTimeMillis();
                break;
            }
        }
        saveCatalog(catalog);
    }

    public void renameClimb(String routeId, int climbIndex, String displayName) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.climbs != null && climbIndex < route.climbs.size()) {
            route.climbs.get(climbIndex).userDisplayName = displayName;
            route.lastModifiedMs = System.currentTimeMillis();
            writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        }
    }

    public void saveNotes(String routeId, String notes) throws IOException {
        StoredRoute route = loadRoute(routeId);
        route.notes = notes;
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));

        List<RouteCatalogEntry> catalog = loadCatalog();
        for (RouteCatalogEntry e : catalog) {
            if (e.routeId.equals(routeId)) {
                e.notes = notes;
                e.lastModifiedMs = System.currentTimeMillis();
                break;
            }
        }
        saveCatalog(catalog);
    }

    /** Return catalog entries for routes whose start coordinates are within radiusM of lat/lon. */
    public List<RouteCatalogEntry> findNearby(double lat, double lon, double radiusM) {
        List<RouteCatalogEntry> catalog = loadCatalog();
        List<RouteCatalogEntry> result  = new ArrayList<>();
        for (RouteCatalogEntry entry : catalog) {
            if (entry.climbStartCoords == null) continue;
            double[] coords = entry.climbStartCoords;
            for (int i = 0; i + 1 < coords.length; i += 2) {
                double d = haversine(lat, lon, coords[i], coords[i + 1]);
                if (d <= radiusM) {
                    result.add(entry);
                    break;
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File routeFile(String routeId) {
        return new File(routesDir, routeId + ".json");
    }

    private List<StoredFlatSegment> loadPreviousFlatSegments(String routeId) {
        File f = routeFile(routeId);
        if (!f.exists()) return Collections.emptyList();
        try (FileInputStream in = new FileInputStream(f)) {
            StoredRoute existing = mapper.readValue(in, StoredRoute.class);
            return existing.flatSegments != null ? existing.flatSegments : Collections.emptyList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Fall back to non-atomic replace when ATOMIC_MOVE is not supported by the filesystem.
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static double[] toDoubleArray(List<RoutePoint> pts, String field) {
        double[] arr = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            RoutePoint p = pts.get(i);
            switch (field) {
                case "lat":  arr[i] = p.lat;       break;
                case "lon":  arr[i] = p.lon;       break;
                case "ele":  arr[i] = p.elevation; break;
                case "dist": arr[i] = p.distance;  break;
            }
        }
        return arr;
    }

    private static List<StoredClimb> toStoredClimbs(List<Climb> climbs) {
        if (climbs == null) return Collections.emptyList();
        List<StoredClimb> out = new ArrayList<>(climbs.size());
        for (Climb c : climbs) {
            StoredClimb sc  = new StoredClimb();
            sc.startDistance = c.startDistance;
            sc.endDistance   = c.endDistance;
            sc.length        = c.length;
            sc.elevationGain = c.elevationGain;
            sc.avgGradient   = c.avgGradient;
            sc.startLat      = c.startLat;
            sc.startLon      = c.startLon;
            sc.name          = c.name;
            sc.segments      = new ArrayList<>();
            for (Segment seg : c.segments) {
                StoredSegment ss = new StoredSegment();
                ss.distance     = seg.distance;
                ss.elevationGain = seg.elevationGain;
                ss.gradient     = seg.gradient;
                ss.colorIndex   = seg.colorIndex;
                sc.segments.add(ss);
            }
            out.add(sc);
        }
        return out;
    }

    private static List<StoredFlatSegment> toStoredFlatSegments(
            List<FlatSegment> flat, List<RoutePoint> points,
            List<StoredFlatSegment> previous) {
        Map<Integer, Integer> prevSurface = new HashMap<>();
        for (StoredFlatSegment prev : previous) {
            if (prev.surfaceType != SurfaceType.UNKNOWN) {
                prevSurface.put(prev.startDistance, prev.surfaceType);
            }
        }
        List<StoredFlatSegment> result = new ArrayList<>(flat.size());
        for (FlatSegment fs : flat) {
            StoredFlatSegment sfs = new StoredFlatSegment();
            sfs.startDistance = fs.startDistance;
            sfs.endDistance   = fs.endDistance;
            sfs.length        = fs.length;
            sfs.surfaceType   = prevSurface.getOrDefault(fs.startDistance, SurfaceType.UNKNOWN);
            double[] startCoord = nearestCoord(points, fs.startDistance);
            double[] endCoord   = nearestCoord(points, fs.endDistance);
            sfs.startLat = startCoord[0];
            sfs.startLon = startCoord[1];
            sfs.endLat   = endCoord[0];
            sfs.endLon   = endCoord[1];
            result.add(sfs);
        }
        return result;
    }

    /** Total route length in metres = the largest cumulative distance, or 0 if unknown. */
    private static int routeLengthMeters(StoredRoute route) {
        if (route.distances == null || route.distances.length == 0) return 0;
        return (int) Math.round(route.distances[route.distances.length - 1]);
    }

    /** Returns {lat, lon} of the point in pts whose distance is closest to targetM. */
    private static double[] nearestCoord(List<RoutePoint> pts, int targetM) {
        if (pts.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        RoutePoint best = pts.get(0);
        double bestDiff = Math.abs(best.distance - targetM);
        for (RoutePoint p : pts) {
            double diff = Math.abs(p.distance - targetM);
            if (diff < bestDiff) { bestDiff = diff; best = p; }
        }
        return new double[]{best.lat, best.lon};
    }

    private static RouteCatalogEntry toCatalogEntry(
            StoredRoute route, List<RoutePoint> points, List<Climb> climbs) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId         = route.routeId;
        e.name            = route.name;
        e.userDisplayName = route.userDisplayName;
        e.sourceHash      = route.sourceHash;
        e.climbCount      = climbs != null ? climbs.size() : 0;
        e.notes           = route.notes;
        e.importedAtMs    = route.importedAtMs;
        e.lastModifiedMs  = route.lastModifiedMs;

        if (points != null && !points.isEmpty()) {
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (RoutePoint p : points) {
                if (p.lat < minLat) minLat = p.lat;
                if (p.lat > maxLat) maxLat = p.lat;
                if (p.lon < minLon) minLon = p.lon;
                if (p.lon > maxLon) maxLon = p.lon;
            }
            e.bboxMinLat = minLat; e.bboxMaxLat = maxLat;
            e.bboxMinLon = minLon; e.bboxMaxLon = maxLon;
        }

        if (climbs != null) {
            double[] coords = new double[climbs.size() * 2];
            for (int i = 0; i < climbs.size(); i++) {
                coords[i * 2]     = climbs.get(i).startLat;
                coords[i * 2 + 1] = climbs.get(i).startLon;
            }
            e.climbStartCoords = coords;
        }

        e.surfaceTypes = computeSurfaceTypes(route);

        return e;
    }

    // -------------------------------------------------------------------------
    // Re-segmentation
    // -------------------------------------------------------------------------

    /**
     * Re-segments one climb in a stored route using the given segment count.
     * Pass {@code newSegmentCount <= 0} to fall back to {@link ClimbConstants#SEGMENT_COUNT}.
     */
    public void reSegmentClimb(String routeId, int climbIndex, int newSegmentCount) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.climbs == null || climbIndex >= route.climbs.size()) {
            throw new IOException("Climb index out of range: " + climbIndex);
        }

        StoredClimb sc = route.climbs.get(climbIndex);
        List<RoutePoint> climbPts = extractClimbPoints(route, sc);

        int count = newSegmentCount > 0 ? newSegmentCount
                                        : ClimbConstants.SEGMENT_COUNT;

        sc.segments          = toStoredSegments(
                Segmenter.segment(climbPts, count));
        sc.calibrationPoints = toStoredCalibPoints(
                Segmenter.calibrationPoints(climbPts)); // uses SEGMENT_COUNT spacing; acceptable for custom counts
        sc.segmentCount      = count;
        route.lastModifiedMs = System.currentTimeMillis();

        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /**
     * Sets the surface type of a single segment and updates the catalog's surfaceTypes index.
     */
    public void setSegmentSurfaceType(String routeId, int climbIndex, int segmentIndex,
                                       int surfaceType) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.climbs == null || climbIndex >= route.climbs.size()) {
            throw new IOException("Climb index out of range: " + climbIndex);
        }
        StoredClimb sc = route.climbs.get(climbIndex);
        if (sc.segments == null || segmentIndex >= sc.segments.size()) {
            throw new IOException("Segment index out of range: " + segmentIndex);
        }
        sc.segments.get(segmentIndex).surfaceType = surfaceType;
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /**
     * Sets the surface type of every segment in one climb, then updates the catalog.
     */
    public void setBulkClimbSurfaceType(String routeId, int climbIndex,
                                         int surfaceType) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.climbs == null || climbIndex >= route.climbs.size()) {
            throw new IOException("Climb index out of range: " + climbIndex);
        }
        StoredClimb sc = route.climbs.get(climbIndex);
        if (sc.segments == null) {
            Log.w(TAG, "setBulkClimbSurfaceType: climb " + climbIndex + " has no segments");
            return;
        }
        for (StoredSegment seg : sc.segments) {
            seg.surfaceType = surfaceType;
        }
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /**
     * Sets the surface type of a flat segment identified by its startDistance,
     * then updates the catalog.
     */
    public void setFlatSegmentSurfaceType(String routeId, int startDistance,
                                           int surfaceType) throws IOException {
        StoredRoute route = loadRoute(routeId);
        boolean found = false;
        if (route.flatSegments != null) {
            for (StoredFlatSegment sf : route.flatSegments) {
                if (sf.startDistance == startDistance) {
                    sf.surfaceType = surfaceType;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            Log.w(TAG, "setFlatSegmentSurfaceType: no flat segment at startDistance " + startDistance);
            return;
        }
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /**
     * Adds a user-defined surface override for an arbitrary route stretch.
     * Distances are integer metres. The surface type is clamped to a legal value.
     * The list is kept sorted by startDistance. Overlaps with existing sections are
     * allowed (phone-only display); the most-recently-added section wins visually.
     *
     * @throws IllegalArgumentException if start &lt; 0, end &lt;= start, or end &gt; route length.
     */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType) throws IOException {
        StoredRoute route = loadRoute(routeId);
        int routeLength = routeLengthMeters(route);
        if (startDistance < 0) {
            throw new IllegalArgumentException("startDistance must be >= 0: " + startDistance);
        }
        if (endDistance <= startDistance) {
            throw new IllegalArgumentException(
                    "endDistance must be > startDistance: " + startDistance + ".." + endDistance);
        }
        if (routeLength > 0 && endDistance > routeLength) {
            throw new IllegalArgumentException(
                    "endDistance " + endDistance + " exceeds route length " + routeLength);
        }

        StoredSurfaceSection section = new StoredSurfaceSection();
        section.startDistance = startDistance;
        section.endDistance   = endDistance;
        section.surfaceType   = SurfaceType.fromInt(surfaceType);

        if (route.surfaceSections == null) {
            route.surfaceSections = new ArrayList<>();
        }
        route.surfaceSections.add(section);
        route.surfaceSections.sort((a, b) -> Integer.compare(a.startDistance, b.startDistance));

        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /**
     * Extracts the sub-list of RoutePoints that belong to the given climb,
     * using the route's parallel arrays and the climb's startDistance/endDistance.
     */
    private static List<RoutePoint> extractClimbPoints(StoredRoute route, StoredClimb sc) {
        List<RoutePoint> pts = new ArrayList<>();
        if (route.lats == null || route.lats.length == 0) return pts;

        int n = route.lats.length;
        for (int i = 0; i < n; i++) {
            double d = route.distances[i];
            if (d >= sc.startDistance && d <= sc.endDistance) {
                pts.add(new RoutePoint(route.lats[i], route.lons[i],
                        route.elevations[i], route.distances[i]));
            }
        }
        return pts;
    }

    private static List<StoredSegment> toStoredSegments(List<Segment> segs) {
        List<StoredSegment> out = new ArrayList<>(segs.size());
        for (Segment s : segs) {
            StoredSegment ss = new StoredSegment();
            ss.distance      = s.distance;
            ss.elevationGain = s.elevationGain;
            ss.gradient      = s.gradient;
            ss.colorIndex    = s.colorIndex;
            out.add(ss);
        }
        return out;
    }

    private static List<StoredCalibrationPoint> toStoredCalibPoints(List<CalibrationPoint> cps) {
        List<StoredCalibrationPoint> out = new ArrayList<>(cps.size());
        for (CalibrationPoint cp : cps) {
            StoredCalibrationPoint scp = new StoredCalibrationPoint();
            scp.distanceFromClimbStart = cp.distanceFromClimbStart;
            scp.lat                    = cp.lat;
            scp.lon                    = cp.lon;
            out.add(scp);
        }
        return out;
    }

    private static int[] computeSurfaceTypes(StoredRoute route) {
        java.util.TreeSet<Integer> surfaceSet = new java.util.TreeSet<>();
        if (route.climbs != null) {
            for (StoredClimb sc : route.climbs) {
                if (sc.segments != null) {
                    for (StoredSegment ss : sc.segments) {
                        if (ss.surfaceType != SurfaceType.UNKNOWN) {
                            surfaceSet.add(ss.surfaceType);
                        }
                    }
                }
            }
        }
        if (route.flatSegments != null) {
            for (StoredFlatSegment sf : route.flatSegments) {
                if (sf.surfaceType != SurfaceType.UNKNOWN) {
                    surfaceSet.add(sf.surfaceType);
                }
            }
        }
        if (route.surfaceSections != null) {
            for (StoredSurfaceSection ss : route.surfaceSections) {
                if (ss.surfaceType != SurfaceType.UNKNOWN) {
                    surfaceSet.add(ss.surfaceType);
                }
            }
        }
        return surfaceSet.isEmpty() ? null
                : surfaceSet.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void rebuildCatalogSurfaceTypes(String routeId, StoredRoute route) throws IOException {
        int[] types = computeSurfaceTypes(route);

        List<RouteCatalogEntry> catalog = loadCatalog();
        boolean found = false;
        for (RouteCatalogEntry e : catalog) {
            if (e.routeId.equals(routeId)) {
                e.surfaceTypes   = types;
                e.lastModifiedMs = route.lastModifiedMs;
                found = true;
                break;
            }
        }
        if (!found) {
            // Route exists on disk but has no catalog entry — create a minimal one so that
            // surface-type queries work correctly without requiring a full saveRoute() call.
            RouteCatalogEntry stub = new RouteCatalogEntry();
            stub.routeId        = routeId;
            stub.name           = route.name;
            stub.userDisplayName = route.userDisplayName;
            stub.sourceHash     = route.sourceHash;
            stub.climbCount     = route.climbs != null ? route.climbs.size() : 0;
            stub.notes          = route.notes;
            stub.importedAtMs   = route.importedAtMs;
            stub.lastModifiedMs = route.lastModifiedMs;
            stub.surfaceTypes   = types;
            catalog.add(stub);
        }
        saveCatalog(catalog);
    }

    private void migrateIfNeeded() {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        int stored = prefs.getInt(PREF_SEGMENT_VERSION, 0);
        if (stored == ClimbConstants.SEGMENT_VERSION) return;

        File[] files = routesDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        if (catalogFile.exists()) catalogFile.delete();

        prefs.edit()
             .putInt(PREF_SEGMENT_VERSION, ClimbConstants.SEGMENT_VERSION)
             .apply();
        Log.i(TAG, "Route migration: cleared all routes (segment format v"
                + ClimbConstants.SEGMENT_VERSION + ")");
    }
}
