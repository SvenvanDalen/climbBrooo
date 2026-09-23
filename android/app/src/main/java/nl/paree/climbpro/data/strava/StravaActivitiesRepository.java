package nl.paree.climbpro.data.strava;

import android.content.Context;
import android.util.Log;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.climb.KnownClimbs;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;
import nl.paree.climbpro.domain.matching.ClimbEntryOnlyDetector;
import nl.paree.climbpro.domain.matching.ClimbRouteDeviationDetector;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Fetches Strava activities (last 12 months on first run, incremental after),
 * matches each against every known climb, and persists matched attempts.
 * Call {@link #syncActivities()} from a background thread.
 */
public final class StravaActivitiesRepository {

    private static final String TAG       = "StravaActivitiesRepo";
    private static final String PREFS     = "strava_activities";
    private static final String PREF_LAST = "last_sync_epoch_sec";
    /**
     * The set of {@link KnownClimb#climbId}s that were known the last time we let
     * incomplete-only activities be skipped as "known". See the comment above
     * {@link #syncActivities()}'s use of this for why a plain permanent skip-list is wrong.
     */
    private static final String PREF_KNOWN_CLIMB_IDS = "known_climb_ids_for_incomplete_skip";
    private static final long   ONE_YEAR_SEC = 365L * 24 * 60 * 60;
    private static final String STREAM_KEYS  = "latlng,time";

    private final StravaAuthRepository   auth;
    private final RouteRepository        routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final IncompleteClimbAttemptRepository incompleteAttemptRepo;
    private final StravaApiClient        api;
    private final android.content.SharedPreferences prefs;

    public StravaActivitiesRepository(Context context,
                                      StravaAuthRepository auth,
                                      RouteRepository routeRepo,
                                      ClimbAttemptRepository attemptRepo) {
        this(context, auth, routeRepo, attemptRepo,
                buildRetrofit().create(StravaApiClient.class));
    }

    /** Test-injectable variant — pass a (mock) StravaApiClient. */
    StravaActivitiesRepository(Context context,
                               StravaAuthRepository auth,
                               RouteRepository routeRepo,
                               ClimbAttemptRepository attemptRepo,
                               StravaApiClient api) {
        this.auth = auth;
        this.routeRepo = routeRepo;
        this.attemptRepo = attemptRepo;
        this.incompleteAttemptRepo = new IncompleteClimbAttemptRepository(context);
        this.api = api;
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return number of new attempts persisted. */
    public int syncActivities() throws IOException {
        String token = "Bearer " + auth.getAccessToken();

        long lastSync = prefs.getLong(PREF_LAST, 0L);
        long nowSec   = System.currentTimeMillis() / 1000L;
        long after    = lastSync > 0 ? lastSync : nowSec - ONE_YEAR_SEC;

        List<KnownClimb> climbs = enumerateKnownClimbs();
        if (climbs.isEmpty()) return 0;

        // An activity that only ever produced incomplete passes must NOT be treated as
        // permanently "known" the way a fully-successful activity is: a later sync may add a
        // brand-new route/climb that this same old activity's track actually rode in full, and
        // that legitimate successful attempt would never be detected if the activity is never
        // re-fetched/re-matched again. So we only let the incomplete-only skip-list apply when
        // the set of known climbs hasn't grown since the last time we evaluated it — if new
        // climbs showed up, incomplete-only activities are re-checked against them this run
        // (some redundant re-fetching of long-incomplete activities is an acceptable trade-off
        // for not getting permanently stuck). A fully successful activity (attemptRepo) is
        // unaffected by this — it's a pre-existing, out-of-scope skip-list.
        Set<String> currentClimbIds = new HashSet<>();
        for (KnownClimb k : climbs) currentClimbIds.add(k.climbId);
        Set<String> priorClimbIds = prefs.getStringSet(PREF_KNOWN_CLIMB_IDS, java.util.Collections.<String>emptySet());
        boolean knownClimbSetGrew = !priorClimbIds.containsAll(currentClimbIds);

        Set<Long> known = new HashSet<>(attemptRepo.knownActivityIds());
        if (!knownClimbSetGrew) {
            known.addAll(incompleteAttemptRepo.knownActivityIds());
        }

        List<StoredClimbAttempt> created = new ArrayList<>();
        List<StoredIncompleteClimbAttempt> incompleteCreated = new ArrayList<>();
        boolean paginationComplete = false;
        int page = 1;
        while (true) {
            Response<List<StravaActivityDto>> resp =
                    api.listActivities(token, after, page, 50).execute();
            if (!resp.isSuccessful()) {
                Log.w(TAG, "Activity page " + page + " failed (HTTP "
                        + resp.code() + "); keeping sync cursor for retry");
                break; // aborted — do NOT mark complete
            }
            if (resp.body() == null || resp.body().isEmpty()) {
                paginationComplete = true; // reached the end cleanly
                break;
            }
            for (StravaActivityDto act : resp.body()) {
                if (known.contains(act.id)) continue;
                created.addAll(matchActivity(token, act, climbs, incompleteCreated));
            }
            page++;
        }

        if (!created.isEmpty()) attemptRepo.append(created);
        if (!incompleteCreated.isEmpty()) incompleteAttemptRepo.append(incompleteCreated);
        if (paginationComplete) {
            prefs.edit().putLong(PREF_LAST, nowSec).apply();
            // Record the climb set this run evaluated incomplete-only activities against, so
            // the NEXT run can tell whether new climbs have shown up in the meantime (see
            // above). Guarded by paginationComplete exactly like PREF_LAST: if the sync aborted
            // early, we must not commit a currentClimbIds snapshot that may already be bigger
            // than priorClimbIds (e.g. a climb was added elsewhere just before this aborted
            // run) — doing so would make the NEXT (successful) sync see knownClimbSetGrew ==
            // false and incorrectly fold previously-recorded incomplete-only activities back
            // into the permanent skip-list, defeating the re-check guarantee.
            prefs.edit().putStringSet(PREF_KNOWN_CLIMB_IDS, currentClimbIds).apply();
        }
        Log.i(TAG, "Activity sync: " + created.size() + " new attempt(s)"
                + (paginationComplete ? "" : " (incomplete — cursor not advanced)"));
        return created.size();
    }

    /**
     * @param incompleteOut ADDITIONAL, separate output: never-completed passes are appended
     *                      here for climbs that had zero successful passes matched in this
     *                      activity — see {@link ClimbEntryOnlyDetector}. The returned list
     *                      of successful {@link StoredClimbAttempt}s is unaffected.
     */
    private List<StoredClimbAttempt> matchActivity(
            String token, StravaActivityDto act, List<KnownClimb> climbs,
            List<StoredIncompleteClimbAttempt> incompleteOut) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        try {
            Response<StravaStreamsDto> sresp =
                    api.getStreams(token, act.id, STREAM_KEYS).execute();
            if (!sresp.isSuccessful() || sresp.body() == null) return out;
            StravaStreamsDto s = sresp.body();
            if (s.latlng == null || s.time == null
                    || s.latlng.data == null || s.time.data == null) return out;

            List<TrackSample> track = toTrack(s);
            if (track.size() < 2) return out;

            long dateSec = parseStartDate(act.startDate);
            for (KnownClimb k : climbs) {
                // matchAllPasses finds every valid ascent in the track, not just the first —
                // an out-and-back or loop route can pass over the same climb more than
                // once in a single activity, and each pass should be logged separately.
                List<ClimbAttemptMatcher.PassResult> passes = ClimbAttemptMatcher.matchAllPasses(
                        track, k.startLat, k.startLon, k.endLat, k.endLon,
                        k.lengthM, k.segLengthsM);
                for (int i = 0; i < passes.size(); i++) {
                    ClimbAttemptMatcher.PassResult p = passes.get(i);
                    StoredClimbAttempt a = new StoredClimbAttempt();
                    a.climbId      = k.climbId;
                    a.activityId   = act.id;
                    a.dateEpochSec = dateSec;
                    a.elapsedSec   = p.elapsedSec;
                    a.startOffsetSec = (int) track.get(p.entryIdx).timeSec;
                    a.passIndex    = i;
                    a.segSplitSec  = p.segSplitSec;
                    a.routeDeviation = ClimbRouteDeviationDetector.isDeviated(
                            track, p.entryIdx, p.exitIdx, k.calibLats, k.calibLons);
                    out.add(a);
                }

                // Only run entry-only detection when this climb had zero successful passes
                // in this activity — a climb ridden successfully isn't "never completed",
                // even if the rider also looped back over the start gate afterwards.
                if (passes.isEmpty()) {
                    int distanceCovered = ClimbEntryOnlyDetector.detectIncomplete(
                            track, k.startLat, k.startLon, k.endLat, k.endLon, k.lengthM);
                    if (distanceCovered >= 0) {
                        StoredIncompleteClimbAttempt ia = new StoredIncompleteClimbAttempt();
                        ia.climbId          = k.climbId;
                        ia.activityId       = act.id;
                        ia.dateEpochSec     = dateSec;
                        ia.distanceCoveredM = distanceCovered;
                        incompleteOut.add(ia);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Stream fetch failed for activity " + act.id, e);
        }
        return out;
    }

    private List<KnownClimb> enumerateKnownClimbs() {
        Map<String, KnownClimb> byId = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                for (KnownClimb k : KnownClimbs.fromRoute(route)) {
                    byId.put(k.climbId, k); // dedupe same climb appearing on multiple routes
                }
            } catch (IOException e) {
                Log.w(TAG, "Skipping route " + entry.routeId + " in climb enumeration", e);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static List<TrackSample> toTrack(StravaStreamsDto s) {
        int n = Math.min(s.latlng.data.size(), s.time.data.size());
        List<TrackSample> track = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Double> ll = s.latlng.data.get(i);
            if (ll == null || ll.size() < 2) continue;
            track.add(new TrackSample(ll.get(0), ll.get(1), s.time.data.get(i)));
        }
        return track;
    }

    private static long parseStartDate(String iso) {
        if (iso == null) return 0L;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt.parse(iso).getTime() / 1000L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Retrofit buildRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(logging).build();
        return new Retrofit.Builder()
                .baseUrl(StravaApiClient.BASE_URL)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
    }
}
