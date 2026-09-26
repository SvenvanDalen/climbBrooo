package nl.paree.climbpro.data.strava;

import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.KnownClimbCatalog;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.climb.KnownClimbs;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.domain.climb.VamCalculator;
import nl.paree.climbpro.domain.matching.ActivityClimbMatcher;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;
import nl.paree.climbpro.domain.strava.StravaTitleTemplateRenderer;
import nl.paree.climbpro.domain.strava.StravaTitleUpdateDecision;
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
import java.util.Collections;
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
    public  static final String PREFS     = "strava_activities";
    private static final String PREF_LAST = "last_sync_epoch_sec";
    /**
     * The set of {@link KnownClimb#climbId}s that were known the last time we let
     * incomplete-only activities be skipped as "known". See the comment above
     * {@link #syncActivities()}'s use of this for why a plain permanent skip-list is wrong.
     */
    private static final String PREF_KNOWN_CLIMB_IDS = "known_climb_ids_for_incomplete_skip";
    private static final long   ONE_YEAR_SEC = 365L * 24 * 60 * 60;
    /** Separate cursor for the ride archive (issue #160), independent of climb matching. */
    private static final String PREF_RIDES_LAST = "rides_last_sync_epoch_sec";
    /**
     * Version of the fields {@link #toStoredRide} fills. When it grows (2: power summary, issue
     * #220), the next archive sync re-lists the whole past year once so older rides get the new
     * fields too; that's the cheap list endpoint only, and upsert replaces the old entries.
     */
    static final String PREF_RIDES_SCHEMA = "rides_schema_version";
    static final int RIDES_SCHEMA_VERSION = 2;
    /**
     * Strava's {@code after} filters on activity START time, so a ride uploaded after the last
     * archive sync but started before it would be skipped forever. Re-listing a few days of
     * overlap catches late uploads; {@code RideRepository#upsertAll} makes the overlap harmless.
     */
    private static final long   RIDE_CURSOR_OVERLAP_SEC = 3L * 24 * 60 * 60;
    private static final String STREAM_KEYS  = "latlng,time,temp"; // temp: optional, same request

    /**
     * Default-shared-prefs key for the user's Strava title template (issue #60), edited from
     * {@code ui.settings.StravaTitleTemplateActivity}. Blank/absent = feature off.
     */
    public static final String PREF_TITLE_TEMPLATE = "strava_title_template";
    /**
     * Default-shared-prefs key: epoch seconds at which the current title template was
     * configured. Only activities started after this get retitled (see
     * {@link StravaTitleUpdateDecision#isEligibleActivity}).
     */
    public static final String PREF_TITLE_TEMPLATE_SINCE = "strava_title_template_since";

    /** Clock in epoch seconds; package-private so tests can pin "now". */
    java.util.function.LongSupplier clock = () -> System.currentTimeMillis() / 1000L;

    private final Context                context;
    private final StravaAuthRepository   auth;
    private final RouteRepository        routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final IncompleteClimbAttemptRepository incompleteAttemptRepo;
    private final RideRepository         rideRepo;
    private final StravaApiClient        api;
    private final android.content.SharedPreferences prefs;

    /**
     * True after {@link #syncActivities()} if a title-update call was rejected for a reason
     * consistent with the {@code activity:write} scope being missing (HTTP 401/403) — i.e. the
     * user authorised before issue #60 added the scope and must reconnect. Matching/attempt
     * persistence still succeeds in this case; only the title-update side effect is skipped.
     */
    private boolean titleUpdateAuthExpired = false;

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
        this.context = context.getApplicationContext();
        this.auth = auth;
        this.routeRepo = routeRepo;
        this.attemptRepo = attemptRepo;
        this.incompleteAttemptRepo = new IncompleteClimbAttemptRepository(context);
        this.rideRepo = new RideRepository(context);
        this.api = api;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @see #titleUpdateAuthExpired */
    public boolean titleUpdateAuthExpired() {
        return titleUpdateAuthExpired;
    }

    /** @return number of new attempts persisted. */
    public int syncActivities() throws IOException {
        String token = "Bearer " + auth.getAccessToken();
        titleUpdateAuthExpired = false;

        // The ride archive is a side feature: it must never block or fail climb matching.
        try {
            syncRideArchive(token);
        } catch (Exception e) {
            Log.w(TAG, "Ride archive sync failed; climb sync continues", e);
        }

        long lastSync = prefs.getLong(PREF_LAST, 0L);
        long nowSec   = clock.getAsLong();
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

        // Title-update inputs (issue #60): resolved once per sync so per-activity title
        // rendering doesn't re-scan routes/attempts. priorPrByClimb reflects state BEFORE this
        // sync's own new attempts, since Strava's title should read "here's your new time vs.
        // your old best" — comparing against attempts created earlier in the same batch is a
        // v1 simplification left for a follow-up if it proves confusing in practice.
        android.content.SharedPreferences defaultPrefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        String titleTemplate = defaultPrefs.getString(PREF_TITLE_TEMPLATE, null);
        long templateSinceSec = defaultPrefs.getLong(PREF_TITLE_TEMPLATE_SINCE, 0L);
        if (titleTemplate != null && !titleTemplate.trim().isEmpty() && templateSinceSec <= 0) {
            // Template present without an opt-in timestamp (set outside the editor): fail
            // safe by treating "now" as the opt-in moment, so no past activity is renamed.
            templateSinceSec = nowSec;
            defaultPrefs.edit().putLong(PREF_TITLE_TEMPLATE_SINCE, nowSec).apply();
        }
        Map<String, StoredClimb> climbsById = titleTemplate != null && !titleTemplate.trim().isEmpty()
                ? enumerateStoredClimbsById()
                : Collections.emptyMap();
        Map<String, LogbookCalculator.Summary> priorSummaries =
                LogbookCalculator.summaries(attemptRepo.loadAll());

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
                List<StoredClimbAttempt> matched = matchActivity(token, act, climbs, incompleteCreated);
                created.addAll(matched);
                // Attempts are always recorded above regardless of auth state; only the
                // title-update HTTP call is skipped once we know the scope is missing —
                // every remaining activity in this sync run would fail with the same
                // 401/403 (it's an account-level scope problem, not per-activity), so
                // calling it again would just waste a doomed request and spam the logs.
                if (!titleUpdateAuthExpired
                        && StravaTitleUpdateDecision.shouldUpdateTitle(matched, titleTemplate)
                        && StravaTitleUpdateDecision.isEligibleActivity(
                                parseStartDate(act.startDate), templateSinceSec, nowSec)) {
                    updateActivityTitle(token, act, matched, titleTemplate,
                            climbsById, priorSummaries);
                }
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
     * Renders the title template for the genuinely first-encountered climb attempt of
     * {@code act} — the match with the lowest {@link StoredClimbAttempt#startOffsetSec}, i.e.
     * whichever climb the rider actually reached first on this ride, not whatever order
     * {@code matched} happens to be in (that order ultimately traces back to
     * {@code enumerateKnownClimbs()}'s {@code HashMap.values()}, which is arbitrary hash-bucket
     * order and unrelated to ride-encounter order) — and PUTs it to Strava. Never throws — a
     * failure here must not undo the already-computed attempt matches for this activity.
     */
    private void updateActivityTitle(String token, StravaActivityDto act,
            List<StoredClimbAttempt> matched, String template,
            Map<String, StoredClimb> climbsById,
            Map<String, LogbookCalculator.Summary> priorSummaries) {
        StoredClimbAttempt best = matched.get(0);
        for (StoredClimbAttempt a : matched) {
            if (a.startOffsetSec < best.startOffsetSec) best = a;
        }
        StoredClimb climb = climbsById.get(best.climbId);
        String climbName = best.climbId;
        if (climb != null) {
            if (climb.userDisplayName != null && !climb.userDisplayName.isEmpty()) {
                climbName = climb.userDisplayName;
            } else if (climb.name != null && !climb.name.isEmpty()) {
                climbName = climb.name;
            }
        }
        Integer vam = climb != null ? VamCalculator.averageVam(climb.avgGradient) : null;
        LogbookCalculator.Summary prior = priorSummaries.get(best.climbId);
        Integer delta = prior != null ? best.elapsedSec - prior.prSec : null;

        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of(climbName, best.elapsedSec, delta, vam,
                        !best.routeDeviation);
        String title = StravaTitleTemplateRenderer.render(template, ctx);
        if (title.isEmpty()) return;

        try {
            Response<StravaActivityDto> resp = api.updateActivity(
                    token, act.id, new StravaUpdateActivityDto(title)).execute();
            if (!resp.isSuccessful()) {
                if (resp.code() == 401 || resp.code() == 403) {
                    titleUpdateAuthExpired = true;
                    Log.w(TAG, "Title update rejected (HTTP " + resp.code() + ") for activity "
                            + act.id + " — activity:write scope likely missing; "
                            + "user must reconnect Strava");
                } else {
                    Log.w(TAG, "Title update failed for activity " + act.id
                            + ": HTTP " + resp.code());
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Title update failed for activity " + act.id, e);
        }
    }

    /**
     * All activities started after {@code afterEpochSec}, oldest pages first (Health Connect
     * export, issue #255). Unlike {@link #syncActivities()} this only lists; no streams are
     * fetched and nothing is matched or stored.
     *
     * @throws IOException when a page fails, so the caller does not advance its cursor
     */
    public List<StravaActivityDto> listActivitiesSince(long afterEpochSec) throws IOException {
        String token = "Bearer " + auth.getAccessToken();
        List<StravaActivityDto> out = new ArrayList<>();
        for (int page = 1; ; page++) {
            Response<List<StravaActivityDto>> resp =
                    api.listActivities(token, afterEpochSec, page, 50).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("Strava activities page " + page + " failed (HTTP "
                        + resp.code() + ")");
            }
            if (resp.body() == null || resp.body().isEmpty()) return out;
            out.addAll(resp.body());
        }
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

            List<Double> trackTemps = new ArrayList<>();
            List<TrackSample> track = toTrack(s, trackTemps);
            if (track.size() < 2) return out;

            out.addAll(ActivityClimbMatcher.match(track, trackTemps, climbs, act.id,
                    parseStartDate(act.startDate), incompleteOut));
        } catch (IOException e) {
            Log.w(TAG, "Stream fetch failed for activity " + act.id, e);
        }
        return out;
    }

    private List<KnownClimb> enumerateKnownClimbs() {
        return KnownClimbCatalog.load(routeRepo);
    }

    /**
     * climbId -> {@link StoredClimb}, for title-template rendering (issue #60): unlike
     * {@link KnownClimb}, {@link StoredClimb} carries the display name and gradient needed for
     * the {@code {climb}}/{@code {vam}} placeholders. Only built when a title template is
     * actually configured.
     */
    private Map<String, StoredClimb> enumerateStoredClimbsById() {
        Map<String, StoredClimb> byId = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (StoredClimb c : route.climbs) {
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String climbId = nl.paree.climbpro.domain.climb.ClimbIdentity.of(
                            c.startLat, c.startLon, len);
                    byId.put(climbId, c); // dedupe same climb appearing on multiple routes
                }
            } catch (IOException e) {
                Log.w(TAG, "Skipping route " + entry.routeId + " in climb name lookup", e);
            }
        }
        return byId;
    }

    /**
     * @param tempsOut filled index-aligned with the returned track: one entry per kept sample,
     *                 the {@code temp} reading for that raw stream index, or null when the temp
     *                 stream is absent or shorter. Built in the same loop so skipped (malformed)
     *                 latlng samples can never shift temperatures onto the wrong track index.
     */
    static List<TrackSample> toTrack(StravaStreamsDto s, List<Double> tempsOut) {
        int n = Math.min(s.latlng.data.size(), s.time.data.size());
        List<Double> rawTemps = s.temp != null ? s.temp.data : null;
        List<TrackSample> track = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Double> ll = s.latlng.data.get(i);
            if (ll == null || ll.size() < 2) continue;
            track.add(new TrackSample(ll.get(0), ll.get(1), s.time.data.get(i)));
            tempsOut.add(rawTemps != null && i < rawTemps.size() ? rawTemps.get(i) : null);
        }
        return track;
    }

    /**
     * Archives a summary of every cycling activity since the last archive sync (issue #160).
     * Uses only the activity list (no streams) and its own cursor, so it also works when no
     * climbs are known yet, and the first run backfills the past year for existing users.
     *
     * @return number of rides archived this run.
     */
    public int syncRideArchive() throws IOException {
        return syncRideArchive("Bearer " + auth.getAccessToken());
    }

    private int syncRideArchive(String token) throws IOException {
        long nowSec = System.currentTimeMillis() / 1000L;
        long last   = prefs.getInt(PREF_RIDES_SCHEMA, 1) < RIDES_SCHEMA_VERSION
                ? 0L : prefs.getLong(PREF_RIDES_LAST, 0L);
        long after  = last > 0 ? last - RIDE_CURSOR_OVERLAP_SEC : nowSec - ONE_YEAR_SEC;

        List<StoredRide> rides = new ArrayList<>();
        boolean complete = false;
        for (int page = 1; ; page++) {
            Response<List<StravaActivityDto>> resp =
                    api.listActivities(token, after, page, 50).execute();
            if (!resp.isSuccessful()) {
                Log.w(TAG, "Ride archive page " + page + " failed (HTTP " + resp.code() + ")");
                break;
            }
            if (resp.body() == null || resp.body().isEmpty()) {
                complete = true;
                break;
            }
            for (StravaActivityDto act : resp.body()) {
                if (isCycling(act.type)) rides.add(toStoredRide(act));
            }
        }
        // Rides gathered before an aborted page are still valid; upsert is idempotent.
        rideRepo.upsertAll(rides);
        if (complete) {
            prefs.edit().putLong(PREF_RIDES_LAST, nowSec)
                    .putInt(PREF_RIDES_SCHEMA, RIDES_SCHEMA_VERSION).apply();
        }
        return rides.size();
    }

    /** Ride, VirtualRide, EBikeRide, GravelRide, MountainBikeRide, ... — not runs/walks. */
    static boolean isCycling(String type) {
        return type != null && type.endsWith("Ride");
    }

    static StoredRide toStoredRide(StravaActivityDto act) {
        StoredRide r = new StoredRide();
        r.activityId     = act.id;
        r.name           = act.name;
        r.type           = act.type;
        r.startEpochSec  = parseStartDate(act.startDate);
        r.distanceM      = act.distance;
        r.movingTimeSec  = act.movingTime;
        r.elapsedTimeSec = act.elapsedTime;
        r.elevationGainM = act.totalElevationGain;
        r.avgSpeedMps    = act.averageSpeed;
        r.maxSpeedMps    = act.maxSpeed;
        r.commute        = act.commute;
        r.avgWatts         = act.averageWatts != null && act.averageWatts > 0 ? act.averageWatts : null;
        r.weightedAvgWatts = act.weightedAverageWatts != null && act.weightedAverageWatts > 0
                ? act.weightedAverageWatts : null;
        r.deviceWatts      = act.deviceWatts;
        if (act.startLatLng != null && act.startLatLng.size() >= 2) {
            r.startLat = act.startLatLng.get(0);
            r.startLon = act.startLatLng.get(1);
        }
        if (act.endLatLng != null && act.endLatLng.size() >= 2) {
            r.endLat = act.endLatLng.get(0);
            r.endLon = act.endLatLng.get(1);
        }
        return r;
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
