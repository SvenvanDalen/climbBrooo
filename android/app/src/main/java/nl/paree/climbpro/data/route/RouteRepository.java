package nl.paree.climbpro.data.route;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbNameSuggester;
import nl.paree.climbpro.domain.climb.ClimbShapeClassifier;
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
    private final ClimbNameSuggester nameSuggester;

    public RouteRepository(Context context) {
        this(context, new GeocoderClimbNameSuggester(context));
    }

    /** Visible for tests: injects a fake {@link ClimbNameSuggester}. */
    RouteRepository(Context context, ClimbNameSuggester nameSuggester) {
        this.context  = context.getApplicationContext();
        File base     = this.context.getFilesDir();
        this.routesDir    = new File(base, ROUTES_DIR);
        this.catalogFile  = new File(base, CATALOG_FILE);
        this.mapper = new ObjectMapper()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        this.nameSuggester = nameSuggester;
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
        saveRoute(route, points, climbs, java.util.Collections.<StoredStarredSegment>emptyList());
    }

    public void saveRoute(StoredRoute route, List<RoutePoint> points, List<Climb> climbs,
                          List<StoredStarredSegment> starredSegments) throws IOException {
        route.lats       = toDoubleArray(points, "lat");
        route.lons       = toDoubleArray(points, "lon");
        route.elevations = toDoubleArray(points, "ele");
        route.distances  = toDoubleArray(points, "dist");
        route.climbs     = toStoredClimbs(climbs);
        // Load existing stored data once to preserve user customisation (renames, surface types).
        // A single read avoids the TOCTOU race that three separate reads created.
        StoredRoute prev = loadPreviousRoute(route.routeId);
        List<StoredClimb>          prevClimbs   = prev != null && prev.climbs != null
                ? prev.climbs : Collections.emptyList();
        List<StoredFlatSegment>    prevFlats    = prev != null && prev.flatSegments != null
                ? prev.flatSegments : Collections.emptyList();
        List<StoredSurfaceSection> prevSections = prev != null && prev.surfaceSections != null
                ? prev.surfaceSections : Collections.emptyList();
        List<StoredStarredSegment> prevStarred  = prev != null && prev.starredSegments != null
                ? prev.starredSegments : Collections.emptyList();
        mergePreviousClimbUserData(route.climbs, prevClimbs);
        fillMissingClimbNames(route.climbs);
        int routeLength = (points != null && !points.isEmpty())
                ? (int) Math.round(points.get(points.size() - 1).distance)
                : 0;
        List<FlatSegment> flatDomain = FlatSegmentDetector.detect(
                routeLength, climbs != null ? climbs : Collections.emptyList());
        List<RoutePoint> pts = points != null ? points : Collections.emptyList();
        route.flatSegments    = toStoredFlatSegments(flatDomain, pts, prevFlats);
        route.surfaceSections = new ArrayList<>(prevSections);
        route.starredSegments = mergePreviousStarredSegmentUserData(starredSegments, prevStarred);
        route.lastModifiedMs = System.currentTimeMillis();

        File routeFile = routeFile(route.routeId);
        writeAtomic(routeFile, mapper.writeValueAsBytes(route));

        List<RouteCatalogEntry> catalog = loadCatalog();
        catalog.removeIf(e -> e.routeId.equals(route.routeId));
        catalog.add(toCatalogEntry(route, points, climbs));
        saveCatalog(catalog);

        // A climb's elevationGain/avgGradient (and thus its historic difficulty score) can
        // only change here — a fresh import or resync — never via the rename/notes/surface
        // mutators below, so this is the one place that needs to invalidate the cache.
        invalidateHistoricScoreCache();
    }

    /** No-op when {@code context} isn't a {@link ClimbProApplication} (e.g. an unusual test setup). */
    private void invalidateHistoricScoreCache() {
        if (context instanceof ClimbProApplication) {
            ((ClimbProApplication) context).historicClimbScoreCache().invalidate();
        }
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
        if (route.climbs != null && climbIndex >= 0 && climbIndex < route.climbs.size()) {
            route.climbs.get(climbIndex).userDisplayName = displayName;
            route.lastModifiedMs = System.currentTimeMillis();
            writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        }
    }

    /**
     * Renames multiple climbs in one load/write cycle, for the bulk rename screen. Out-of-range
     * indices (including negatives) are silently skipped, matching {@link #renameClimb}. A blank
     * name clears {@code userDisplayName} back to null so the climb falls back to its auto name.
     */
    public void renameClimbs(String routeId, java.util.Map<Integer, String> namesByIndex)
            throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.climbs == null || namesByIndex.isEmpty()) return;

        boolean changed = false;
        for (java.util.Map.Entry<Integer, String> entry : namesByIndex.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= route.climbs.size()) continue;
            String trimmed = entry.getValue() != null ? entry.getValue().trim() : "";
            route.climbs.get(index).userDisplayName = trimmed.isEmpty() ? null : trimmed;
            changed = true;
        }
        if (changed) {
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

    /** Reads the existing stored route for {@code routeId}, or null on first import or read error. */
    private StoredRoute loadPreviousRoute(String routeId) {
        File f = routeFile(routeId);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return mapper.readValue(in, StoredRoute.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Copies user-supplied climb data (display-name rename + per-segment surface type)
     * from a route's previous climbs onto the freshly detected ones, matching climbs by
     * start distance. Per-segment surface is copied by index — segment counts are stable
     * for unchanged geometry — and only non-UNKNOWN values overwrite, so re-detection
     * never erases a user's customisation.
     */
    private static void mergePreviousClimbUserData(List<StoredClimb> fresh,
                                                   List<StoredClimb> previous) {
        if (fresh == null || previous == null || previous.isEmpty()) return;
        Map<Integer, StoredClimb> prevByStart = new HashMap<>();
        for (StoredClimb p : previous) prevByStart.put(p.startDistance, p);
        for (StoredClimb f : fresh) {
            StoredClimb p = prevByStart.get(f.startDistance);
            if (p == null) continue;
            if (p.userDisplayName != null) f.userDisplayName = p.userDisplayName;
            if (f.name == null && p.name != null) f.name = p.name;
            if (f.segments != null && p.segments != null) {
                int n = Math.min(f.segments.size(), p.segments.size());
                for (int i = 0; i < n; i++) {
                    int prevSurface = p.segments.get(i).surfaceType;
                    if (prevSurface != SurfaceType.UNKNOWN) {
                        f.segments.get(i).surfaceType = prevSurface;
                    }
                }
            }
        }
    }


    /**
     * Fills in a suggested {@link StoredClimb#name} (backlog #109) for climbs that don't have
     * one yet and that the user hasn't renamed — i.e. first detection only, since a name
     * carried over by {@link #mergePreviousClimbUserData} is already non-null here. Suggester
     * failures (no network, no geocoder backend, ...) are swallowed: offline-first means a
     * missing suggestion is an expected outcome, not a save failure.
     */
    private void fillMissingClimbNames(List<StoredClimb> climbs) {
        if (climbs == null || nameSuggester == null) return;
        for (StoredClimb c : climbs) {
            if (c.name != null || c.userDisplayName != null) continue;
            try {
                String suggestion = nameSuggester.suggestName(c.startLat, c.startLon);
                if (suggestion != null && !suggestion.trim().isEmpty()) {
                    c.name = suggestion.trim();
                }
            } catch (Exception e) {
                Log.w(TAG, "Climb name suggestion failed, leaving name unset", e);
            }
        }
    }

    private static List<StoredStarredSegment> mergePreviousStarredSegmentUserData(
            List<StoredStarredSegment> fresh, List<StoredStarredSegment> previous) {
        List<StoredStarredSegment> result = fresh != null ? new ArrayList<>(fresh) : new ArrayList<>();
        if (previous == null || previous.isEmpty()) return result;
        java.util.Map<Long, Integer> prevSurface = new java.util.HashMap<>();
        java.util.Map<Long, String>  prevName    = new java.util.HashMap<>();
        for (StoredStarredSegment p : previous) {
            if (p.surfaceType != SurfaceType.UNKNOWN) prevSurface.put(p.stravaId, p.surfaceType);
            if (p.userDisplayName != null)             prevName.put(p.stravaId, p.userDisplayName);
        }
        for (StoredStarredSegment s : result) {
            Integer su = prevSurface.get(s.stravaId);
            if (su != null) s.surfaceType = su;
            String nm = prevName.get(s.stravaId);
            if (nm != null) s.userDisplayName = nm;
        }
        return result;
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        try {
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Fall back to non-atomic replace when ATOMIC_MOVE is not supported by the filesystem.
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            tmp.delete(); // best-effort cleanup so a failed write leaves no orphan .tmp
            throw moveFailed;
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
            sc.shape         = c.shape.name();
            sc.segments      = new ArrayList<>();
            for (Segment seg : c.segments) {
                StoredSegment ss = new StoredSegment();
                ss.distance     = seg.distance;
                ss.elevationGain = seg.elevationGain;
                ss.gradient     = seg.gradient;
                ss.colorIndex   = seg.colorIndex;
                ss.avgVamMPerH  = seg.avgVamMPerH;
                ss.peakVamMPerH = seg.peakVamMPerH;
                sc.segments.add(ss);
            }
            if (c.calibrationPoints != null && !c.calibrationPoints.isEmpty()) {
                sc.calibrationPoints = toStoredCalibPoints(c.calibrationPoints);
            }
            out.add(sc);
        }
        return out;
    }

    private static List<StoredFlatSegment> toStoredFlatSegments(
            List<FlatSegment> flat, List<RoutePoint> points,
            List<StoredFlatSegment> previous) {
        Map<Integer, Integer> prevSurface = new HashMap<>();
        Map<Integer, String>  prevName    = new HashMap<>();
        for (StoredFlatSegment prev : previous) {
            if (prev.surfaceType != SurfaceType.UNKNOWN) {
                prevSurface.put(prev.startDistance, prev.surfaceType);
            }
            if (prev.name != null) {
                prevName.put(prev.startDistance, prev.name);
            }
        }
        List<StoredFlatSegment> result = new ArrayList<>(flat.size());
        for (FlatSegment fs : flat) {
            StoredFlatSegment sfs = new StoredFlatSegment();
            sfs.startDistance = fs.startDistance;
            sfs.endDistance   = fs.endDistance;
            sfs.length        = fs.length;
            sfs.surfaceType   = prevSurface.getOrDefault(fs.startDistance, SurfaceType.UNKNOWN);
            sfs.name          = prevName.get(fs.startDistance);
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
     * Pass {@code newSegmentCount <= 0} to fall back to {@link ClimbConstants#defaultSegmentCount()}.
     */
    public void reSegmentClimb(String routeId, int climbIndex, int newSegmentCount) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            throw new IOException("Climb index out of range: " + climbIndex);
        }

        StoredClimb sc = route.climbs.get(climbIndex);
        List<RoutePoint> climbPts = extractClimbPoints(route, sc);

        int count = newSegmentCount > 0 ? newSegmentCount
                                        : ClimbConstants.defaultSegmentCount();

        sc.segments          = toStoredSegments(
                Segmenter.segment(climbPts, count));
        sc.calibrationPoints = toStoredCalibPoints(
                Segmenter.calibrationPoints(climbPts, count));
        sc.segmentCount      = count;
        sc.shape             = ClimbShapeClassifier.classifyStored(sc.segments).name();
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
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            throw new IOException("Climb index out of range: " + climbIndex);
        }
        StoredClimb sc = route.climbs.get(climbIndex);
        if (sc.segments == null || segmentIndex < 0 || segmentIndex >= sc.segments.size()) {
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
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
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
     * Sets both the surface type and the optional display name of a flat segment
     * identified by its startDistance, in a single atomic write. A blank/empty name
     * is stored as null. Updates the catalog surface index.
     */
    public void updateFlatSegment(String routeId, int startDistance,
                                  int surfaceType, String name) throws IOException {
        StoredRoute route = loadRoute(routeId);
        boolean found = false;
        if (route.flatSegments != null) {
            for (StoredFlatSegment sf : route.flatSegments) {
                if (sf.startDistance == startDistance) {
                    sf.surfaceType = SurfaceType.fromInt(surfaceType);
                    sf.name = (name == null || name.trim().isEmpty()) ? null : name.trim();
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            Log.w(TAG, "updateFlatSegment: no flat segment at startDistance " + startDistance);
            return;
        }
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /** Sets a starred segment's surface type (phone + watch). */
    public void setStarredSegmentSurface(String routeId, long stravaId, int surfaceType) throws IOException {
        updateStarredSegment(routeId, stravaId, surfaceType, null);
    }

    /**
     * Sets a starred segment's surface type and optional display name in a single atomic
     * write, identified by Strava id. A blank/empty name is stored as null. Updates the
     * catalog surface index.
     */
    public void updateStarredSegment(String routeId, long stravaId,
                                     int surfaceType, String name) throws IOException {
        StoredRoute route = loadRoute(routeId);
        boolean found = false;
        if (route.starredSegments != null) {
            for (StoredStarredSegment s : route.starredSegments) {
                if (s.stravaId == stravaId) {
                    s.surfaceType = SurfaceType.fromInt(surfaceType);
                    s.userDisplayName = (name == null || name.trim().isEmpty()) ? null : name.trim();
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            Log.w(TAG, "updateStarredSegment: no starred segment with id " + stravaId);
            return;
        }
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /** Backwards-compatible overload: adds a section with no name. */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType) throws IOException {
        addSurfaceSection(routeId, startDistance, endDistance, surfaceType, null);
    }

    /**
     * Adds a user-defined surface override for an arbitrary route stretch.
     * Distances are integer metres. The surface type is clamped to a legal value.
     * A blank/empty name is stored as null. The list is kept sorted by startDistance.
     *
     * @throws IllegalArgumentException if start &lt; 0, end &lt;= start, or end &gt; route length.
     */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType, String name) throws IOException {
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
        section.name          = (name == null || name.trim().isEmpty()) ? null : name.trim();

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
     * Removes the surface section at the given index (after sorting by startDistance).
     * Out-of-range indices are ignored.
     */
    public void deleteSurfaceSection(String routeId, int index) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.surfaceSections == null
                || index < 0 || index >= route.surfaceSections.size()) {
            Log.w(TAG, "deleteSurfaceSection: index out of range: " + index);
            return;
        }
        route.surfaceSections.remove(index);
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }

    /**
     * Renames the surface section at the given index (after sorting by startDistance).
     * A blank/empty name clears it (stored as null). Out-of-range indices are ignored.
     */
    public void setSurfaceSectionName(String routeId, int index, String name) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.surfaceSections == null
                || index < 0 || index >= route.surfaceSections.size()) {
            Log.w(TAG, "setSurfaceSectionName: index out of range: " + index);
            return;
        }
        route.surfaceSections.get(index).name =
                (name == null || name.trim().isEmpty()) ? null : name.trim();
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
    }

    /**
     * Sets both the surface type and the optional name of the surface section at the given
     * index, in a single atomic write. Surface is clamped via {@link SurfaceType#fromInt};
     * a blank/empty name is stored as null. Updates the catalog surface index. Out-of-range
     * indices are ignored.
     */
    public void updateSurfaceSection(String routeId, int index,
                                     int surfaceType, String name) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.surfaceSections == null
                || index < 0 || index >= route.surfaceSections.size()) {
            Log.w(TAG, "updateSurfaceSection: index out of range: " + index);
            return;
        }
        StoredSurfaceSection section = route.surfaceSections.get(index);
        section.surfaceType = SurfaceType.fromInt(surfaceType);
        section.name = (name == null || name.trim().isEmpty()) ? null : name.trim();
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
            ss.avgVamMPerH   = s.avgVamMPerH;
            ss.peakVamMPerH  = s.peakVamMPerH;
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
        if (route.starredSegments != null) {
            for (StoredStarredSegment s : route.starredSegments) {
                if (s.surfaceType != SurfaceType.UNKNOWN) surfaceSet.add(s.surfaceType);
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
