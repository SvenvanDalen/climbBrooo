package nl.paree.climbpro.data.route;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.Segment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Atomic rename failed for " + target);
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
        return e;
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

    private void migrateIfNeeded() {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        int stored = prefs.getInt(PREF_SEGMENT_VERSION, 0);
        if (stored == nl.paree.climbpro.domain.climb.ClimbConstants.SEGMENT_VERSION) return;

        File[] files = routesDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        if (catalogFile.exists()) catalogFile.delete();

        prefs.edit()
             .putInt(PREF_SEGMENT_VERSION, nl.paree.climbpro.domain.climb.ClimbConstants.SEGMENT_VERSION)
             .apply();
        Log.i(TAG, "Route migration: cleared all routes (segment format v"
                + nl.paree.climbpro.domain.climb.ClimbConstants.SEGMENT_VERSION + ")");
    }
}
