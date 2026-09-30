package nl.paree.climbpro.data.strava;

import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.KnownClimbCatalog;
import nl.paree.climbpro.data.route.MyWhooshRouteStore;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.activity.MyWhooshRouteReader;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.DuplicateClimbMatcher;
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
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;
import nl.paree.climbpro.domain.ride.RideStreams;
import nl.paree.climbpro.domain.ride.RideTrack;

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
     * #220; 3: gear id for the bike garage, issue #187), the next archive sync re-lists the whole past year once so older rides get the new
     * fields too; that's the cheap list endpoint only, and upsert replaces the old entries.
     */
    static final String PREF_RIDES_SCHEMA = "rides_schema_version";
    static final int RIDES_SCHEMA_VERSION = 3;
    /**
     * Strava's {@code after} filters on activity START time, so a ride uploaded after the last
     * archive sync but started before it would be skipped forever. Re-listing a few days of
     * overlap catches late uploads; {@code RideRepository#upsertAll} makes the overlap harmless.
     */
    private static final long   RIDE_CURSOR_OVERLAP_SEC = 3L * 24 * 60 * 60;
    private static final String STREAM_KEYS  = "latlng,time,temp"; // temp: optional, same request
    /**
     * Streams the ride-archive analysis needs (issues #225, #224, #222); Strava omits keys a
     * ride doesn't have, such as watts without a power meter.
     */
    static final String RIDE_STREAM_KEYS = "time,distance,watts,altitude,heartrate";
    /**
     * Stream requests per {@link #analyzeRideStreams()} run. Strava allows 100 reads per 15
     * minutes, shared with climb matching; a year of rides fills in over a few syncs instead.
     */
    static final int MAX_STREAM_ANALYSES_PER_RUN = 20;

    /**
     * Automatic MyWhoosh import (issue #344): archived rides already turned into a route, or
     * found unusable, so their streams are never fetched again.
     */
    static final String PREF_MYWHOOSH_DONE = "mywhoosh_imported_activity_ids";
    /** Stream requests per run; shares Strava's 100-per-15-minutes budget with the rest. */
    static final int MAX_MYWHOOSH_IMPORTS_PER_RUN = 10;
    static final String MYWHOOSH_STREAM_KEYS = "latlng,distance,altitude,time";

    /**
     * One-off history backfill (issue #312). Walks newest to oldest from the moment it was
     * started down to {@link #BACKFILL_YEARS} years before it, moving {@code before} down after
     * every activity, so a run that stops halfway (rate limit, app killed) resumes exactly
     * there. The floor and start are pinned on the first run so a resume weeks later still
     * covers the same window.
     */
    static final String PREF_BACKFILL_CURSOR = "history_backfill_before_epoch_sec";
    static final String PREF_BACKFILL_FLOOR  = "history_backfill_after_epoch_sec";
    public static final String PREF_BACKFILL_DONE = "history_backfill_done";
    static final int    BACKFILL_YEARS = 10;
    private static final long BACKFILL_WINDOW_SEC = BACKFILL_YEARS * ONE_YEAR_SEC;
    private static final int  BACKFILL_PAGE_SIZE  = 50;

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
    private final RideStreamStatsRepository streamStatsRepo;
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
        this.streamStatsRepo = new RideStreamStatsRepository(context);
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
        // Before the known climbs are enumerated, so a fresh MyWhoosh ride is matched against
        // the climbs it just created and shows up in the logbook right away.
        try {
            importMyWhooshRides(token);
        } catch (Exception e) {
            Log.w(TAG, "MyWhoosh import failed; climb sync continues", e);
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

    public enum BackfillStatus {
        /** The whole window is processed; the backfill never runs again. */
        DONE,
        /** Strava said 429, or the budget reserve for the regular sync was reached. */
        PAUSED_RATE_LIMIT,
        /** Network error or 5xx; try again later from the same cursor. */
        RETRY
    }

    public static final class BackfillResult {
        public final BackfillStatus status;
        public final int ridesArchived;
        public final int attemptsCreated;
        /** Oldest activity start reached so far (epoch seconds). */
        public final long cursorEpochSec;
        public final long floorEpochSec;

        BackfillResult(BackfillStatus status, int ridesArchived, int attemptsCreated,
                       long cursorEpochSec, long floorEpochSec) {
            this.status = status;
            this.ridesArchived = ridesArchived;
            this.attemptsCreated = attemptsCreated;
            this.cursorEpochSec = cursorEpochSec;
            this.floorEpochSec = floorEpochSec;
        }
    }

    /** Called after every page, so the UI can show how far back the backfill got. */
    public interface BackfillProgress {
        void onProgress(long cursorEpochSec, long floorEpochSec);
    }

    public boolean isHistoryBackfillDone() {
        return prefs.getBoolean(PREF_BACKFILL_DONE, false);
    }

    /**
     * One-off import of every activity from the last {@link #BACKFILL_YEARS} years (issue #312):
     * every ride goes into the ride archive, and every cycling ride with GPS (virtual rides
     * included) is matched against the known climbs. Resumable: progress is saved after every
     * activity, and the run stops early on a 429, a network error, or when Strava's rate-limit
     * headers say the budget reserved for the regular sync is next. Never renames activities
     * on Strava. Call from a background thread.
     */
    public BackfillResult backfillHistory(BackfillProgress progress) throws IOException {
        long nowSec = clock.getAsLong();
        long floor  = prefs.getLong(PREF_BACKFILL_FLOOR, 0L);
        long cursor = prefs.getLong(PREF_BACKFILL_CURSOR, 0L);
        if (isHistoryBackfillDone()) {
            return new BackfillResult(BackfillStatus.DONE, 0, 0, cursor, floor);
        }
        if (floor <= 0 || cursor <= 0) {
            floor  = nowSec - BACKFILL_WINDOW_SEC;
            cursor = nowSec;
            prefs.edit().putLong(PREF_BACKFILL_FLOOR, floor)
                    .putLong(PREF_BACKFILL_CURSOR, cursor).apply();
        }

        String token = "Bearer " + auth.getAccessToken();
        List<KnownClimb> climbs = enumerateKnownClimbs();
        Set<Long> known = new HashSet<>(attemptRepo.knownActivityIds());
        known.addAll(incompleteAttemptRepo.knownActivityIds());

        int ridesArchived = 0;
        int attemptsCreated = 0;
        while (true) {
            Response<List<StravaActivityDto>> resp =
                    api.listActivitiesBefore(token, cursor, floor, BACKFILL_PAGE_SIZE).execute();
            if (!resp.isSuccessful()) {
                Log.w(TAG, "Backfill page before " + cursor + " failed (HTTP " + resp.code() + ")");
                BackfillStatus s = resp.code() == 429
                        ? BackfillStatus.PAUSED_RATE_LIMIT : BackfillStatus.RETRY;
                return new BackfillResult(s, ridesArchived, attemptsCreated, cursor, floor);
            }
            List<StravaActivityDto> page = resp.body();
            if (page == null || page.isEmpty()) {
                prefs.edit().putBoolean(PREF_BACKFILL_DONE, true).apply();
                Log.i(TAG, "History backfill done");
                return new BackfillResult(BackfillStatus.DONE, ridesArchived, attemptsCreated,
                        cursor, floor);
            }

            // Newest first, so moving the cursor to each processed activity's start keeps
            // everything not yet processed strictly before it.
            List<StravaActivityDto> sorted = new ArrayList<>(page);
            Collections.sort(sorted, (a, b) ->
                    Long.compare(parseStartDate(b.startDate), parseStartDate(a.startDate)));

            List<StoredRide> rides = new ArrayList<>();
            for (StravaActivityDto act : sorted) {
                if (isCycling(act.type)) rides.add(toStoredRide(act));
            }
            rideRepo.upsertAll(rides); // list data only, idempotent: safe to redo on resume
            ridesArchived += rides.size();

            boolean budgetLow = StravaRateLimit.nearLimit(resp.headers());
            long pageStartCursor = cursor;
            for (StravaActivityDto act : sorted) {
                if (budgetLow) break;
                long start = parseStartDate(act.startDate);
                if (shouldMatchInBackfill(act, climbs, known)) {
                    List<StoredIncompleteClimbAttempt> incomplete = new ArrayList<>();
                    MatchResult m = matchActivityChecked(token, act, climbs, incomplete);
                    if (m.outcome == StreamOutcome.RATE_LIMITED
                            || m.outcome == StreamOutcome.TRANSIENT_FAILURE) {
                        // Cursor stays above this activity: it is fetched again on resume.
                        BackfillStatus s = m.outcome == StreamOutcome.RATE_LIMITED
                                ? BackfillStatus.PAUSED_RATE_LIMIT : BackfillStatus.RETRY;
                        return new BackfillResult(s, ridesArchived, attemptsCreated, cursor, floor);
                    }
                    if (!m.attempts.isEmpty()) attemptRepo.append(m.attempts);
                    incompleteAttemptRepo.append(incomplete);
                    attemptsCreated += m.attempts.size();
                    known.add(act.id);
                    budgetLow = StravaRateLimit.nearLimit(m.headers);
                }
                if (start > 0 && start < cursor) {
                    cursor = start;
                    prefs.edit().putLong(PREF_BACKFILL_CURSOR, cursor).apply();
                }
            }
            if (progress != null) progress.onProgress(cursor, floor);
            if (budgetLow) {
                Log.i(TAG, "Backfill pausing: Strava rate-limit reserve reached");
                return new BackfillResult(BackfillStatus.PAUSED_RATE_LIMIT, ridesArchived,
                        attemptsCreated, cursor, floor);
            }
            if (cursor >= pageStartCursor) {
                // A full page without a parseable start date can't move the window down;
                // stop instead of re-requesting the same page forever.
                Log.w(TAG, "Backfill cursor did not move; stopping");
                prefs.edit().putBoolean(PREF_BACKFILL_DONE, true).apply();
                return new BackfillResult(BackfillStatus.DONE, ridesArchived, attemptsCreated,
                        cursor, floor);
            }
        }
    }

    /**
     * Only cycling activities with GPS (virtual rides included) cost a streams request;
     * runs, walks and indoor trainer rides without a track are archived but never matched.
     */
    static boolean shouldMatchInBackfill(StravaActivityDto act, List<KnownClimb> climbs,
                                         Set<Long> known) {
        return !climbs.isEmpty()
                && isCycling(act.type)
                && act.startLatLng != null && act.startLatLng.size() >= 2
                && !known.contains(act.id);
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
     * Cycling activities started after {@code afterEpochSec} as ride summaries ("veilig thuis",
     * issue #231). List-only like {@link #listActivitiesSince}; nothing is stored.
     */
    public List<StoredRide> listRecentRides(long afterEpochSec) throws IOException {
        List<StoredRide> out = new ArrayList<>();
        for (StravaActivityDto act : listActivitiesSince(afterEpochSec)) {
            if (isCycling(act.type)) out.add(toStoredRide(act));
        }
        return out;
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
        return matchActivityChecked(token, act, climbs, incompleteOut).attempts;
    }

    /** Why a stream fetch ended, so the backfill can tell "retry later" from "skip for good". */
    enum StreamOutcome { OK, SKIPPED, RATE_LIMITED, TRANSIENT_FAILURE }

    static final class MatchResult {
        final StreamOutcome outcome;
        final List<StoredClimbAttempt> attempts;
        final okhttp3.Headers headers;

        MatchResult(StreamOutcome outcome, List<StoredClimbAttempt> attempts,
                    okhttp3.Headers headers) {
            this.outcome = outcome;
            this.attempts = attempts;
            this.headers = headers;
        }
    }

    /**
     * {@link #matchActivity} plus the reason a fetch failed: 429 is {@code RATE_LIMITED}, 5xx
     * and network errors are {@code TRANSIENT_FAILURE}, and any other error (404 for a deleted
     * or private activity) is {@code SKIPPED} because retrying would never help.
     */
    private MatchResult matchActivityChecked(
            String token, StravaActivityDto act, List<KnownClimb> climbs,
            List<StoredIncompleteClimbAttempt> incompleteOut) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        try {
            Response<StravaStreamsDto> sresp =
                    api.getStreams(token, act.id, STREAM_KEYS).execute();
            okhttp3.Headers headers = sresp.headers();
            if (!sresp.isSuccessful()) {
                StreamOutcome o = sresp.code() == 429 ? StreamOutcome.RATE_LIMITED
                        : sresp.code() >= 500 ? StreamOutcome.TRANSIENT_FAILURE
                        : StreamOutcome.SKIPPED;
                return new MatchResult(o, out, headers);
            }
            StravaStreamsDto s = sresp.body();
            if (s == null || s.latlng == null || s.time == null
                    || s.latlng.data == null || s.time.data == null) {
                return new MatchResult(StreamOutcome.SKIPPED, out, headers);
            }

            List<Double> trackTemps = new ArrayList<>();
            List<TrackSample> track = toTrack(s, trackTemps);
            if (track.size() < 2) return new MatchResult(StreamOutcome.SKIPPED, out, headers);

            out.addAll(ActivityClimbMatcher.match(track, trackTemps, climbs, act.id,
                    parseStartDate(act.startDate), incompleteOut));
            return new MatchResult(StreamOutcome.OK, out, headers);
        } catch (IOException e) {
            Log.w(TAG, "Stream fetch failed for activity " + act.id, e);
            return new MatchResult(StreamOutcome.TRANSIENT_FAILURE, out, null);
        }
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

    /**
     * Fetches streams for archived rides that have no (current) stream analysis yet, newest
     * first, and stores what {@link RideStreamAnalyzer} derives from them (issue #225). At most
     * {@link #MAX_STREAM_ANALYSES_PER_RUN} requests per run; stops early on rate limiting,
     * an auth error or a network failure, keeping everything analyzed so far.
     *
     * @return number of rides analyzed in this run
     */
    public int analyzeRideStreams() throws IOException {
        return analyzeRideStreams("Bearer " + auth.getAccessToken());
    }

    private int analyzeRideStreams(String token) throws IOException {
        Map<Long, StoredRideStreamStats> done = streamStatsRepo.loadById();
        List<StoredRide> todo = new ArrayList<>();
        for (StoredRide r : rideRepo.loadAll()) {
            StoredRideStreamStats s = done.get(r.activityId);
            if (s == null || s.version < RideStreamAnalyzer.VERSION) todo.add(r);
        }
        todo.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));

        List<StoredRideStreamStats> out = new ArrayList<>();
        try {
            for (StoredRide r : todo) {
                if (out.size() >= MAX_STREAM_ANALYSES_PER_RUN) break;
                Response<StravaStreamsDto> resp =
                        api.getStreams(token, r.activityId, RIDE_STREAM_KEYS).execute();
                if (resp.code() == 404) {
                    // Deleted on Strava or a manual entry: nothing to analyze, don't ask again.
                    out.add(RideStreamAnalyzer.analyze(r.activityId, null));
                    continue;
                }
                if (resp.code() == 429 || resp.code() == 401 || resp.code() == 403) {
                    Log.w(TAG, "Ride stream analysis paused (HTTP " + resp.code() + ")");
                    break;
                }
                if (!resp.isSuccessful()) continue; // transient; retried next run
                out.add(RideStreamAnalyzer.analyze(r.activityId, toRideStreams(resp.body())));
            }
        } catch (IOException e) {
            Log.w(TAG, "Ride stream fetch failed; keeping " + out.size() + " analyzed ride(s)", e);
        }
        streamStatsRepo.upsertAll(out);
        return out.size();
    }

    /**
     * Turns archived MyWhoosh rides into routes (issue #344). MyWhoosh has no public API but
     * uploads every ride to Strava as a "MyWhoosh - &lt;route&gt;" {@code VirtualRide}, which
     * the ride archive already holds. For each ride not handled yet (newest first, at most
     * {@link #MAX_MYWHOOSH_IMPORTS_PER_RUN}) the streams go through the same pipeline as the
     * FIT import. A ride is saved when it has a climb that isn't known yet: a ride without
     * climbs (a flat circuit) or a repeat of an imported MyWhoosh route adds nothing. MyWhoosh
     * uses fixed virtual coordinates per world, so a repeat is caught by the duplicate-climb
     * check and matched as a logbook attempt instead.
     *
     * @return number of routes created in this run
     */
    public int importMyWhooshRides() throws IOException {
        return importMyWhooshRides("Bearer " + auth.getAccessToken());
    }

    private int importMyWhooshRides(String token) {
        Set<String> done = new HashSet<>(
                prefs.getStringSet(PREF_MYWHOOSH_DONE, Collections.<String>emptySet()));
        List<StoredRide> todo = new ArrayList<>();
        for (StoredRide r : rideRepo.loadAll()) {
            if (MyWhooshRouteReader.isMyWhooshActivity(r.name, r.type, r.sportType)
                    && !done.contains(String.valueOf(r.activityId))) {
                todo.add(r);
            }
        }
        if (todo.isEmpty()) return 0;
        todo.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));

        MyWhooshRouteStore store = new MyWhooshRouteStore(context);
        int fetched = 0;
        int saved = 0;
        try {
            for (StoredRide r : todo) {
                if (fetched >= MAX_MYWHOOSH_IMPORTS_PER_RUN) break;
                fetched++;
                Response<StravaStreamsDto> resp =
                        api.getStreams(token, r.activityId, MYWHOOSH_STREAM_KEYS).execute();
                if (resp.code() == 429 || resp.code() == 401 || resp.code() == 403) {
                    Log.w(TAG, "MyWhoosh import paused (HTTP " + resp.code() + ")");
                    break;
                }
                if (resp.code() >= 500) continue; // transient; retried next run
                String id = String.valueOf(r.activityId);
                if (resp.isSuccessful() && saveMyWhooshRide(store, r, resp.body())) saved++;
                done.add(id); // saved, no new climbs, or unusable (404, no altitude): never again
            }
        } catch (IOException e) {
            Log.w(TAG, "MyWhoosh stream fetch failed; " + saved + " route(s) imported", e);
        }
        prefs.edit().putStringSet(PREF_MYWHOOSH_DONE, done).apply();
        Log.i(TAG, "MyWhoosh import: " + saved + " new route(s)");
        return saved;
    }

    /** @return true when a route was saved */
    private boolean saveMyWhooshRide(MyWhooshRouteStore store, StoredRide ride,
                                     StravaStreamsDto streams) {
        try {
            MyWhooshRouteReader.Result read = toMyWhooshRoute(streams);
            MyWhooshRouteReader.Detected detected = MyWhooshRouteReader.detectClimbs(read);
            if (detected.climbs.isEmpty()) return false;
            if (!read.virtual && DuplicateClimbMatcher.findDuplicates(detected.climbs,
                    routeRepo.loadCatalog(), ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M)
                    .size() == detected.climbs.size()) {
                return false; // every climb is already known: a repeat of an imported route
            }
            store.save(MYWHOOSH_ROUTE_PREFIX + ride.activityId,
                    MyWhooshRouteReader.routeTitle(ride.name),
                    "strava:" + ride.activityId, read.virtual, detected.points, detected.climbs);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "MyWhoosh ride " + ride.activityId + " not imported: " + e.getMessage());
            return false;
        }
    }

    /** Route-id prefix of automatically imported MyWhoosh rides; the Strava id follows. */
    static final String MYWHOOSH_ROUTE_PREFIX = "mywhoosh_strava_";

    /**
     * Index-aligned stream arrays for {@link MyWhooshRouteReader#fromStreams}; a missing or
     * misaligned latlng stream means "no GPS".
     */
    static MyWhooshRouteReader.Result toMyWhooshRoute(StravaStreamsDto s) throws IOException {
        if (s == null || s.distance == null || s.distance.data == null
                || s.altitude == null || s.altitude.data == null) {
            throw new IOException("Geen hoogte- of afstandsstream");
        }
        double[] dist = toNaN(s.distance.data);
        double[] alt = toNaN(s.altitude.data);
        double[] lat = null;
        double[] lon = null;
        if (s.latlng != null && s.latlng.data != null && s.latlng.data.size() == dist.length) {
            lat = new double[dist.length];
            lon = new double[dist.length];
            for (int i = 0; i < dist.length; i++) {
                List<Double> p = s.latlng.data.get(i);
                boolean ok = p != null && p.size() >= 2 && p.get(0) != null && p.get(1) != null;
                lat[i] = ok ? p.get(0) : Double.NaN;
                lon[i] = ok ? p.get(1) : Double.NaN;
            }
        }
        return MyWhooshRouteReader.fromStreams(lat, lon, alt, dist);
    }

    private static double[] toNaN(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            Double v = values.get(i);
            out[i] = v != null ? v : Double.NaN;
        }
        return out;
    }

    /**
     * One ride's streams fetched on demand, e.g. for the ride comparer (issue #199). Null when
     * Strava has none (manual entry, deleted) or the request is refused.
     */
    public RideStreams fetchRideStreams(long activityId) throws IOException {
        Response<StravaStreamsDto> resp = api.getStreams(
                "Bearer " + auth.getAccessToken(), activityId, RIDE_STREAM_KEYS).execute();
        if (!resp.isSuccessful()) {
            Log.w(TAG, "Streams for " + activityId + " failed (HTTP " + resp.code() + ")");
            return null;
        }
        return toRideStreams(resp.body());
    }

    /**
     * GPS track and average device temperature of one ride, fetched on demand for the ride
     * story (issue #193). Null when Strava has no track or the request is refused.
     */
    public RideTrack fetchRideTrack(long activityId) throws IOException {
        Response<StravaStreamsDto> resp = api.getStreams(
                "Bearer " + auth.getAccessToken(), activityId, "latlng,temp").execute();
        if (!resp.isSuccessful()) {
            Log.w(TAG, "Track for " + activityId + " failed (HTTP " + resp.code() + ")");
            return null;
        }
        return toRideTrack(resp.body());
    }

    /** Null without at least two valid lat/lon samples; null temperature samples are skipped. */
    static RideTrack toRideTrack(StravaStreamsDto s) {
        if (s == null || s.latlng == null || s.latlng.data == null) return null;
        List<List<Double>> ll = s.latlng.data;
        double[] lat = new double[ll.size()];
        double[] lon = new double[ll.size()];
        int n = 0;
        for (List<Double> p : ll) {
            if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null) continue;
            lat[n] = p.get(0);
            lon[n] = p.get(1);
            n++;
        }
        if (n < 2) return null;
        Double avgTemp = null;
        if (s.temp != null && s.temp.data != null) {
            double sum = 0;
            int count = 0;
            for (Double t : s.temp.data) {
                if (t == null) continue;
                sum += t;
                count++;
            }
            if (count > 0) avgTemp = sum / count;
        }
        return new RideTrack(java.util.Arrays.copyOf(lat, n), java.util.Arrays.copyOf(lon, n),
                avgTemp);
    }

    /**
     * Null when the time or distance stream is missing. Null distance and altitude samples carry
     * the previous value forward; null power and heart-rate samples become NaN (not recorded).
     */
    static RideStreams toRideStreams(StravaStreamsDto s) {
        if (s == null || s.time == null || s.time.data == null
                || s.distance == null || s.distance.data == null) return null;
        List<Integer> time = s.time.data;
        List<Double> dist = s.distance.data;
        if (time.size() != dist.size()) return null;
        int[] t = new int[time.size()];
        double[] d = new double[dist.size()];
        double last = 0;
        int lastT = 0;
        for (int i = 0; i < t.length; i++) {
            Integer ti = time.get(i);
            Double di = dist.get(i);
            lastT = ti != null ? ti : lastT;
            last = di != null ? di : last;
            t[i] = lastT;
            d[i] = last;
        }
        return new RideStreams(t, d, toNaNGaps(s.watts, t.length),
                toAltitude(s.altitude, t.length), toNaNGaps(s.heartrate, t.length));
    }

    private static double[] toNaNGaps(StravaStreamsDto.NumberStream s, int n) {
        if (s == null || s.data == null || s.data.size() != n) return null;
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            Double v = s.data.get(i);
            w[i] = v != null ? v : Double.NaN;
        }
        return w;
    }

    private static double[] toAltitude(StravaStreamsDto.NumberStream s, int n) {
        if (s == null || s.data == null || s.data.size() != n) return null;
        double[] a = new double[n];
        Double last = null;
        for (int i = 0; i < n; i++) {
            Double v = s.data.get(i);
            if (v != null) last = v;
            a[i] = last != null ? last : Double.NaN;
        }
        return a;
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
        r.sportType      = act.sportType;
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
        r.gearId           = act.gearId != null && !act.gearId.trim().isEmpty()
                ? act.gearId.trim() : null;
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
