package nl.paree.climbpro.data.strava;

import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.climb.KnownClimbs;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.domain.climb.VamCalculator;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;
import nl.paree.climbpro.domain.strava.StravaTitleTemplateRenderer;
import nl.paree.climbpro.domain.strava.StravaTitleUpdateDecision;

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
    private static final long   ONE_YEAR_SEC = 365L * 24 * 60 * 60;
    private static final String STREAM_KEYS  = "latlng,time";

    /**
     * Default-shared-prefs key for the user's Strava title template (issue #60), edited from
     * {@code ui.settings.StravaTitleTemplateActivity}. Blank/absent = feature off.
     */
    public static final String PREF_TITLE_TEMPLATE = "strava_title_template";

    private final Context                context;
    private final StravaAuthRepository   auth;
    private final RouteRepository        routeRepo;
    private final ClimbAttemptRepository attemptRepo;
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

        long lastSync = prefs.getLong(PREF_LAST, 0L);
        long nowSec   = System.currentTimeMillis() / 1000L;
        long after    = lastSync > 0 ? lastSync : nowSec - ONE_YEAR_SEC;

        List<KnownClimb> climbs = enumerateKnownClimbs();
        if (climbs.isEmpty()) return 0;

        Set<Long> known = attemptRepo.knownActivityIds();

        // Title-update inputs (issue #60): resolved once per sync so per-activity title
        // rendering doesn't re-scan routes/attempts. priorPrByClimb reflects state BEFORE this
        // sync's own new attempts, since Strava's title should read "here's your new time vs.
        // your old best" — comparing against attempts created earlier in the same batch is a
        // v1 simplification left for a follow-up if it proves confusing in practice.
        String titleTemplate = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_TITLE_TEMPLATE, null);
        Map<String, StoredClimb> climbsById = titleTemplate != null && !titleTemplate.trim().isEmpty()
                ? enumerateStoredClimbsById()
                : Collections.emptyMap();
        Map<String, LogbookCalculator.Summary> priorSummaries =
                LogbookCalculator.summaries(attemptRepo.loadAll());

        List<StoredClimbAttempt> created = new ArrayList<>();
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
                List<StoredClimbAttempt> matched = matchActivity(token, act, climbs);
                created.addAll(matched);
                // Attempts are always recorded above regardless of auth state; only the
                // title-update HTTP call is skipped once we know the scope is missing —
                // every remaining activity in this sync run would fail with the same
                // 401/403 (it's an account-level scope problem, not per-activity), so
                // calling it again would just waste a doomed request and spam the logs.
                if (!titleUpdateAuthExpired
                        && StravaTitleUpdateDecision.shouldUpdateTitle(matched, titleTemplate)) {
                    updateActivityTitle(token, act, matched, titleTemplate,
                            climbsById, priorSummaries);
                }
            }
            page++;
        }

        if (!created.isEmpty()) attemptRepo.append(created);
        if (paginationComplete) {
            prefs.edit().putLong(PREF_LAST, nowSec).apply();
        }
        Log.i(TAG, "Activity sync: " + created.size() + " new attempt(s)"
                + (paginationComplete ? "" : " (incomplete — cursor not advanced)"));
        return created.size();
    }

    /**
     * Renders the title template for the genuinely first-encountered climb attempt of
     * {@code act} — the match with the lowest {@link StoredClimbAttempt#entryTimeSec}, i.e.
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
            if (a.entryTimeSec < best.entryTimeSec) best = a;
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
                StravaTitleTemplateRenderer.TitleContext.of(climbName, best.elapsedSec, delta, vam);
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

    private List<StoredClimbAttempt> matchActivity(
            String token, StravaActivityDto act, List<KnownClimb> climbs) {
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
                // matchAllWithEntryTime finds every valid ascent in the track, not just the
                // first — an out-and-back or loop route can pass over the same climb more
                // than once in a single activity, and each pass should be logged separately.
                // It also carries each pass's entry time so callers can determine true
                // ride-encounter order across DIFFERENT climbs (see StoredClimbAttempt#entryTimeSec).
                List<ClimbAttemptMatcher.PassResult> passes = ClimbAttemptMatcher.matchAllWithEntryTime(
                        track, k.startLat, k.startLon, k.endLat, k.endLon, k.lengthM);
                List<int[]> segPasses = ClimbAttemptMatcher.matchAllSegments(
                        track, k.startLat, k.startLon, k.endLat, k.endLon,
                        k.lengthM, k.segLengthsM);
                for (int i = 0; i < passes.size(); i++) {
                    StoredClimbAttempt a = new StoredClimbAttempt();
                    a.climbId      = k.climbId;
                    a.activityId   = act.id;
                    a.dateEpochSec = dateSec;
                    a.elapsedSec   = passes.get(i).elapsedSec;
                    a.entryTimeSec = passes.get(i).entryTimeSec;
                    a.passIndex    = i;
                    a.segSplitSec  = i < segPasses.size() ? segPasses.get(i) : null;
                    out.add(a);
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
