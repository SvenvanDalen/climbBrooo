package nl.paree.climbpro.data.strava;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.GpxParseException;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteSimplifier;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates Strava auth + API + domain processing + persistence.
 * Call {@link #syncRoutes()} from a background thread.
 */
public final class StravaRoutesRepository {

    private static final String TAG             = "StravaRoutesRepo";
    private static final int    ELEVATION_WINDOW = 5;
    private static final double SIMPLIFY_EPSILON = 5.0; // metres

    private final StravaAuthRepository auth;
    private final RouteRepository      routeRepo;
    private final StravaApiClient      api;

    public StravaRoutesRepository(StravaAuthRepository auth, RouteRepository routeRepo) {
        this(auth, routeRepo, buildRetrofit().create(StravaApiClient.class));
    }

    /** Package-private, test-injectable variant — pass a (mock) StravaApiClient. */
    StravaRoutesRepository(StravaAuthRepository auth, RouteRepository routeRepo, StravaApiClient api) {
        this.auth      = auth;
        this.routeRepo = routeRepo;
        this.api       = api;
    }

    /**
     * Fetches all Strava routes, downloads GPX for new/changed ones, processes,
     * and persists them. Safe to call repeatedly (idempotent per source hash).
     *
     * @throws IOException if the network is unreachable or auth fails
     */
    public int syncRoutes() throws IOException {
        String token = "Bearer " + auth.getAccessToken();
        List<StravaRouteDto> routes = new ArrayList<>();
        int page = 1;
        while (true) {
            Response<List<StravaRouteDto>> resp =
                    api.listRoutes(token, page, 50).execute();
            if (!resp.isSuccessful() || resp.body() == null || resp.body().isEmpty()) break;
            routes.addAll(resp.body());
            page++;
        }
        Log.i(TAG, "Found " + routes.size() + " Strava routes");

        int changed = 0;
        for (StravaRouteDto dto : routes) {
            if (processRoute(token, dto)) changed++;
        }
        Log.i(TAG, "Strava sync: " + changed + " route(s) created/updated");
        return changed;
    }

    private boolean processRoute(String token, StravaRouteDto dto) {
        String routeId = "strava_" + dto.id;
        String hash    = sha256(dto.updatedAt + "_" + dto.distance);

        try {
            StoredRoute existing = null;
            try { existing = routeRepo.loadRoute(routeId); } catch (IOException ignored) {}

            if (existing != null && hash.equals(existing.sourceHash)) {
                Log.d(TAG, "Route " + routeId + " unchanged, skipping");
                return false;
            }

            Response<okhttp3.ResponseBody> gpxResp =
                    api.exportGpx(token, dto.id).execute();
            if (!gpxResp.isSuccessful() || gpxResp.body() == null) {
                Log.e(TAG, "Failed to download GPX for " + routeId);
                return false;
            }

            byte[] gpxBytes = gpxResp.body().bytes();
            List<RoutePoint> raw = GpxParser.parse(
                    new ByteArrayInputStream(gpxBytes));

            List<RoutePoint> withDist   = CumulativeDistance.compute(raw);
            List<RoutePoint> smoothed   = ElevationSmoother.smooth(withDist, ELEVATION_WINDOW);
            List<RoutePoint> simplified = RouteSimplifier.simplify(smoothed, SIMPLIFY_EPSILON);
            List<Climb>      climbs     = ClimbDetector.detect(simplified);

            StoredRoute stored = new StoredRoute();
            stored.routeId      = routeId;
            stored.sourceHash   = hash;
            stored.name         = dto.name;
            stored.importedAtMs = System.currentTimeMillis();

            // Preserve user display name across re-imports
            if (existing != null && existing.userDisplayName != null) {
                stored.userDisplayName = existing.userDisplayName;
                stored.notes           = existing.notes;
            }

            routeRepo.saveRoute(stored, simplified, climbs);
            Log.i(TAG, "Saved route " + routeId + " with " + climbs.size() + " climbs");

            // Seed surface type from the Strava sub_type on FIRST import only. On a
            // re-sync (existing != null) saveRoute has already preserved the user's
            // per-segment surface edits, and a blanket bulk-set here would clobber them.
            int detectedSurface = nl.paree.climbpro.domain.segment.SurfaceTypeDetector
                    .detectFromStravaSubType(dto.subType);
            if (existing == null
                    && detectedSurface != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
                try {
                    StoredRoute saved = routeRepo.loadRoute(routeId);
                    if (saved.climbs != null) {
                        for (int ci = 0; ci < saved.climbs.size(); ci++) {
                            routeRepo.setBulkClimbSurfaceType(routeId, ci, detectedSurface);
                        }
                    }
                } catch (IOException e2) {
                    Log.w(TAG, "Surface type wiring failed for " + routeId, e2);
                }
            }

            return true;

        } catch (GpxParseException e) {
            Log.e(TAG, "GPX parse error for route " + routeId + ": " + e.getMessage());
            return false;
        } catch (IOException e) {
            Log.e(TAG, "I/O error processing route " + routeId, e);
            return false;
        }
    }

    private static Retrofit buildRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();
        return new Retrofit.Builder()
                .baseUrl(StravaApiClient.BASE_URL)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}
