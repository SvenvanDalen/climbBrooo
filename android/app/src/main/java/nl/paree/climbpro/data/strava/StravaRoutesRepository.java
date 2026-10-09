package nl.paree.climbpro.data.strava;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.climb.ClimbMerger;
import nl.paree.climbpro.domain.climb.SegmentExploreTiler;
import nl.paree.climbpro.domain.climb.StarredSegmentLocator;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.GpxParseException;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteSimplifier;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Coordinates Strava auth + API + domain processing + persistence.
 * Call {@link #syncRoutes()} from a background thread.
 */
public final class StravaRoutesRepository {

    private static final String TAG             = "StravaRoutesRepo";
    private static final int    ELEVATION_WINDOW = 5;
    private static final double SIMPLIFY_EPSILON = 5.0; // metres

    /** Max number of automatic retries after an HTTP 429 before giving up on a call. */
    private static final int  MAX_RETRY_ATTEMPTS   = 2;
    /** Backoff used when a 429 carries no (parseable) Retry-After header. */
    private static final long DEFAULT_RETRY_WAIT_MS = 5_000L;
    /** Upper bound we are willing to block a sync; longer Retry-After -> give up, retry next sync. */
    private static final long MAX_RETRY_WAIT_MS     = 60_000L;

    /** Pauses the current thread. Injectable so tests don't actually sleep. */
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    /** Produces a fresh single-shot {@link Call}; called again for each retry attempt. */
    private interface CallFactory<T> { Call<T> create(); }

    private final StravaAuthRepository auth;
    private final RouteRepository      routeRepo;
    private final StravaApiClient      api;
    private final Sleeper              sleeper;

    /**
     * False once a {@code segments/explore} call in this sync hit (or neared) Strava's rate
     * limit (issue #35). From then on no route is explored, and unchanged routes that are only
     * waiting to be explored are left for a later sync instead of re-downloading their GPX.
     */
    private boolean exploreBudgetLeft = true;

    public StravaRoutesRepository(StravaAuthRepository auth, RouteRepository routeRepo) {
        this(auth, routeRepo, buildRetrofit().create(StravaApiClient.class));
    }

    /** Package-private, test-injectable variant — pass a (mock) StravaApiClient. */
    StravaRoutesRepository(StravaAuthRepository auth, RouteRepository routeRepo, StravaApiClient api) {
        this(auth, routeRepo, api, Thread::sleep);
    }

    /** Package-private, fully-injectable variant — used by tests to capture backoff timing. */
    StravaRoutesRepository(StravaAuthRepository auth, RouteRepository routeRepo,
                           StravaApiClient api, Sleeper sleeper) {
        this.auth      = auth;
        this.routeRepo = routeRepo;
        this.api       = api;
        this.sleeper   = sleeper;
    }

    /**
     * Fetches all Strava routes, downloads GPX for new/changed ones, processes,
     * and persists them. Safe to call repeatedly (idempotent per source hash).
     *
     * @throws IOException if the network is unreachable or auth fails
     */
    public int syncRoutes() throws IOException {
        String token = "Bearer " + auth.getAccessToken();
        exploreBudgetLeft = true;
        List<StravaRouteDto> routes = new ArrayList<>();
        for (int page = 1; ; page++) {
            final int p = page;
            Response<List<StravaRouteDto>> resp =
                    executeWithRetry(() -> api.listRoutes(token, p, 50));
            if (resp == null || !resp.isSuccessful() || resp.body() == null || resp.body().isEmpty()) break;
            routes.addAll(resp.body());
        }
        Log.i(TAG, "Found " + routes.size() + " Strava routes");

        // Fetch the athlete's starred segments ONCE per sync (best-effort). Each segment
        // carries its own geometry + grade, so routes are matched locally — we never make
        // a per-route detail call, which previously doubled API usage and tripped Strava's
        // rate limit during a full re-sync (e.g. on a fresh phone), starving the essential
        // GPX downloads and dropping routes.
        List<StravaSegmentDto> starredSegments;
        try {
            starredSegments = fetchStarredSegments(token);
        } catch (IOException e) {
            Log.w(TAG, "Starred-segment list fetch failed; skipping promotion this sync", e);
            starredSegments = Collections.emptyList();
        }

        int changed = 0;
        for (StravaRouteDto dto : routes) {
            if (processRoute(token, dto, starredSegments)) changed++;
        }
        Log.i(TAG, "Strava sync: " + changed + " route(s) created/updated");
        return changed;
    }

    private boolean processRoute(String token, StravaRouteDto dto,
                                 List<StravaSegmentDto> starredSegments) {
        String routeId = "strava_" + dto.id;
        String hash    = sha256(dto.updatedAt + "_" + dto.distance);

        try {
            StoredRoute existing = null;
            try { existing = routeRepo.loadRoute(routeId); } catch (IOException ignored) {}

            // Skip unchanged routes — UNLESS the stored doc predates the starred-segment
            // feature (starredSegments == null). Such routes were saved before flat starred
            // segments were matched, so reprocess them once to populate the field even when
            // the Strava sourceHash is unchanged. After that first reprocess the field is
            // non-null (possibly empty) and the route skips normally again.
            // Likewise for the public-segment match (issue #35): a route that hasn't been
            // explored yet is reprocessed once, but only while this sync has budget for it.
            if (existing != null && hash.equals(existing.sourceHash)
                    && existing.starredSegments != null
                    && (Boolean.TRUE.equals(existing.stravaSegmentsExplored) || !exploreBudgetLeft)) {
                Log.d(TAG, "Route " + routeId + " unchanged, skipping");
                return false;
            }

            Response<okhttp3.ResponseBody> gpxResp =
                    executeWithRetry(() -> api.exportGpx(token, dto.id));
            if (gpxResp == null || !gpxResp.isSuccessful() || gpxResp.body() == null) {
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

            // Known public Strava segments along the route replace the detected bounds and
            // name the climb (issue #35). Starred segments are merged after, so they still win.
            ExploreResult explored = exploreSegments(token, simplified);
            List<Climb> segmentClimbs = ClimbMerger.longestNonOverlapping(matchSegmentClimbs(
                    simplified, withoutIds(explored.segments, starredSegments),
                    ClimbConstants.MIN_CLIMB_LENGTH_M));
            if (!segmentClimbs.isEmpty()) {
                climbs = ClimbMerger.merge(climbs, segmentClimbs);
                Log.i(TAG, "Matched " + segmentClimbs.size()
                        + " Strava segment(s) to climbs on " + routeId);
            }

            List<Climb> starredClimbs = matchSegmentClimbs(simplified, starredSegments, 0);
            if (!starredClimbs.isEmpty()) {
                climbs = ClimbMerger.merge(climbs, starredClimbs);
                Log.i(TAG, "Promoted " + starredClimbs.size()
                        + " starred segment(s) to climbs on " + routeId);
            }

            List<StoredStarredSegment> starredFlats = matchStarredFlatSegments(simplified, starredSegments);

            StoredRoute stored = new StoredRoute();
            stored.routeId      = routeId;
            stored.sourceHash   = hash;
            stored.name         = dto.name;
            stored.importedAtMs = System.currentTimeMillis();
            stored.stravaSegmentsExplored = explored.complete;

            // Preserve user-owned route fields across re-imports. Each field is copied on its
            // own: notes on a never-renamed route must survive too. rideStatus, surface
            // sections, removed-climb tombstones and per-climb/segment edits are carried
            // forward by RouteRepository.saveRoute itself.
            if (existing != null) {
                stored.userDisplayName = existing.userDisplayName;
                stored.notes           = existing.notes;
            }

            routeRepo.saveRoute(stored, simplified, climbs, starredFlats);
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

    /**
     * Builds a {@link Climb} for every segment that lies on {@code route}, has an average
     * gradient >= {@link ClimbConstants#MIN_AVG_GRADIENT} and is at least {@code minLengthM}
     * long. Starred segments pass 0 (keep the >= 3% rule, drop the 800 m minimum — product
     * decision 2026-06-20: the user picked them); public segments found by explore (issue #35)
     * pass the full 800 m climb minimum. Pure CPU, no network: a segment counts as "on the
     * route" when {@link StarredSegmentLocator} can place its start/end on the route geometry.
     * Returns an empty list (never null) when nothing qualifies.
     */
    private static List<Climb> matchSegmentClimbs(List<RoutePoint> route,
                                                  List<StravaSegmentDto> segments,
                                                  int minLengthM) {
        if (segments.isEmpty()) return Collections.emptyList();
        List<Climb> result = new ArrayList<>();
        for (StravaSegmentDto seg : segments) {
            if (seg == null) continue;
            if (seg.averageGrade / 100.0 < ClimbConstants.MIN_AVG_GRADIENT) continue;
            if (seg.startLatlng == null || seg.startLatlng.length < 2
                    || seg.endLatlng == null || seg.endLatlng.length < 2) continue;

            Climb c = StarredSegmentLocator.locate(
                    route,
                    seg.startLatlng[0], seg.startLatlng[1],
                    seg.endLatlng[0], seg.endLatlng[1],
                    seg.name,
                    ClimbConstants.STARRED_SEGMENT_MATCH_MAX_M);
            if (c != null && c.endDistance - c.startDistance >= minLengthM) result.add(c);
        }
        return result;
    }

    /**
     * Builds a {@link StoredStarredSegment} for every starred segment that lies on the route
     * and is too flat to be a climb (avg gradient < {@link ClimbConstants#MIN_AVG_GRADIENT}).
     * Pure CPU; returns an empty list (never null) when nothing qualifies.
     */
    private static List<StoredStarredSegment> matchStarredFlatSegments(
            List<RoutePoint> route, List<StravaSegmentDto> starredSegments) {
        if (starredSegments.isEmpty()) return Collections.emptyList();
        List<StoredStarredSegment> result = new ArrayList<>();
        for (StravaSegmentDto seg : starredSegments) {
            if (seg == null) continue;
            if (seg.averageGrade / 100.0 >= ClimbConstants.MIN_AVG_GRADIENT) continue; // climbs handled separately
            if (seg.startLatlng == null || seg.startLatlng.length < 2
                    || seg.endLatlng == null || seg.endLatlng.length < 2) continue;

            StarredSegmentLocator.Span span = StarredSegmentLocator.locateSpan(
                    route,
                    seg.startLatlng[0], seg.startLatlng[1],
                    seg.endLatlng[0], seg.endLatlng[1],
                    ClimbConstants.STARRED_SEGMENT_MATCH_MAX_M);
            if (span == null) continue;

            StoredStarredSegment s = new StoredStarredSegment();
            s.stravaId = seg.id;
            s.startDistance = span.startDistance;
            s.endDistance = span.endDistance;
            s.length = span.length;
            s.startLat = span.startLat; s.startLon = span.startLon;
            s.endLat = span.endLat;     s.endLon = span.endLon;
            s.avgGradient = span.avgGradient;
            s.name = seg.name;
            result.add(s);
        }
        return result;
    }

    /** Public segments found along a route, and whether every tile was actually asked. */
    static final class ExploreResult {
        final List<StravaSegmentDto> segments;
        final boolean complete;

        ExploreResult(List<StravaSegmentDto> segments, boolean complete) {
            this.segments = segments;
            this.complete = complete;
        }
    }

    /**
     * Asks {@code segments/explore} for each tile of the route (issue #35) and collects the
     * segments, de-duplicated by id. Stops early, marking the result incomplete so a later sync
     * retries, when Strava answers 429 or its rate-limit headers say the budget is nearly used
     * up; that leaves the rest for the essential GPX downloads. Any other failure (network, a
     * 4xx because the segment API is unavailable) counts as done, so a route isn't downloaded
     * again every sync for an endpoint that won't answer.
     */
    private ExploreResult exploreSegments(String token, List<RoutePoint> route) {
        List<double[]> tiles = SegmentExploreTiler.tiles(route);
        List<StravaSegmentDto> found = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int t = 0; t < tiles.size(); t++) {
            if (!exploreBudgetLeft) return new ExploreResult(found, false);
            String bounds = SegmentExploreTiler.toBoundsParam(tiles.get(t));
            Response<StravaSegmentExploreDto> resp;
            try {
                resp = executeWithRetry(() -> api.exploreSegments(token, bounds, "riding"));
            } catch (IOException e) {
                Log.w(TAG, "Segment explore failed; keeping what was found", e);
                return new ExploreResult(found, true);
            }
            if (resp == null) return new ExploreResult(found, true);
            if (resp.code() == 429) {
                exploreBudgetLeft = false;
                return new ExploreResult(found, false);
            }
            if (!resp.isSuccessful()) return new ExploreResult(found, true);
            if (resp.body() != null && resp.body().segments != null) {
                for (StravaSegmentExploreDto.Entry e : resp.body().segments) {
                    if (e != null && seen.add(e.id)) found.add(e.toSegment());
                }
            }
            if (StravaRateLimit.nearLimit(resp.headers())) {
                exploreBudgetLeft = false;
                return new ExploreResult(found, t == tiles.size() - 1);
            }
        }
        return new ExploreResult(found, true);
    }

    /** {@code segments} without the ones whose id is in {@code exclude}. */
    private static List<StravaSegmentDto> withoutIds(List<StravaSegmentDto> segments,
                                                     List<StravaSegmentDto> exclude) {
        if (segments.isEmpty() || exclude.isEmpty()) return segments;
        Set<Long> ids = new HashSet<>();
        for (StravaSegmentDto s : exclude) if (s != null) ids.add(s.id);
        List<StravaSegmentDto> out = new ArrayList<>();
        for (StravaSegmentDto s : segments) if (!ids.contains(s.id)) out.add(s);
        return out;
    }

    /** Hard cap on starred-segment pages — guards against a misbehaving API looping forever. */
    private static final int MAX_STARRED_PAGES = 50;

    /** Fetches all of the athlete's starred segments (paginated), once per sync. */
    private List<StravaSegmentDto> fetchStarredSegments(String token) throws IOException {
        List<StravaSegmentDto> segments = new ArrayList<>();
        for (int page = 1; page <= MAX_STARRED_PAGES; page++) {
            final int p = page;
            Response<List<StravaSegmentDto>> resp =
                    executeWithRetry(() -> api.listStarredSegments(token, p, 50));
            if (resp == null || !resp.isSuccessful() || resp.body() == null || resp.body().isEmpty()) break;
            for (StravaSegmentDto s : resp.body()) {
                if (s != null) segments.add(s);
            }
        }
        return segments;
    }

    /**
     * Executes a Strava call, retrying on HTTP 429 ("Too Many Requests") after honoring the
     * {@code Retry-After} header (delta-seconds). Retries up to {@link #MAX_RETRY_ATTEMPTS}
     * times; if the server asks us to wait longer than {@link #MAX_RETRY_WAIT_MS} we give up
     * and return the 429 response so the next scheduled sync can pick the work back up.
     *
     * @return the response, or {@code null} if the factory produced no call (e.g. an
     *         unstubbed endpoint) — callers treat null as "stop, nothing to read".
     */
    private <T> Response<T> executeWithRetry(CallFactory<T> factory) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Call<T> call = factory.create();
            if (call == null) return null;
            Response<T> resp = call.execute();
            if (resp.code() != 429 || attempt >= MAX_RETRY_ATTEMPTS) return resp;

            long waitMs = retryAfterMillis(resp);
            if (waitMs > MAX_RETRY_WAIT_MS) {
                Log.w(TAG, "Strava 429: Retry-After " + waitMs + " ms exceeds cap; giving up this sync");
                return resp;
            }
            Log.w(TAG, "Strava rate-limited (429); retrying in " + waitMs + " ms");
            try {
                sleeper.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during Strava rate-limit backoff", e);
            }
        }
    }

    /** Parses the {@code Retry-After} header (delta-seconds); falls back to a default backoff. */
    private static long retryAfterMillis(Response<?> resp) {
        String header = resp.headers().get("Retry-After");
        if (header != null) {
            try {
                long secs = Long.parseLong(header.trim());
                if (secs >= 0) return secs * 1000L;
            } catch (NumberFormatException ignored) {
                // Retry-After may be an HTTP-date; we don't parse those — use the default.
            }
        }
        return DEFAULT_RETRY_WAIT_MS;
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
