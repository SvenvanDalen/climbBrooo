package nl.paree.climbpro.ui.privacy;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.intervals.IntervalsIcuRepository;
import nl.paree.climbpro.data.planning.FavoriteStartPoint;
import nl.paree.climbpro.data.planning.FavoriteStartPointStore;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.privacy.PrivacyCategory;
import nl.paree.climbpro.data.privacy.PrivacyInventory;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.social.FriendFeedRepository;
import nl.paree.climbpro.data.social.FriendShareIdentity;
import nl.paree.climbpro.data.social.RideBuddyRepository;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.PlannedClimbWorkScheduler;
import nl.paree.climbpro.service.RouteSyncWorker;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Privacy dashboard (issue #264): lists every {@link PrivacyCategory} with how much is stored,
 * shows what is in it, and deletes it. Deletion goes through the owning repositories where
 * one exists so no dangling references remain (routes leave collections, photos are unlinked
 * from their attempts, planned reminders are cancelled).
 */
public final class PrivacyDashboardViewModel extends AndroidViewModel {

    /** One card on the dashboard. */
    public static final class Row {
        public final PrivacyCategory category;
        public final String summary;
        public final boolean hasData;

        Row(PrivacyCategory category, String summary, boolean hasData) {
            this.category = category;
            this.summary = summary;
            this.hasData = hasData;
        }
    }

    private static final int MAX_LISTED = 40;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<Row>> rows = new MutableLiveData<>();
    private final MutableLiveData<String> details = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();
    private final PrivacyInventory inventory;

    public PrivacyDashboardViewModel(@NonNull Application app) {
        super(app);
        inventory = new PrivacyInventory(app.getFilesDir(), app.getCacheDir());
    }

    public LiveData<List<Row>> rows() { return rows; }
    /** Text for the "Bekijk" dialog; consumed once by the activity. */
    public LiveData<String> details() { return details; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(() -> {
            List<Row> out = new ArrayList<>();
            for (PrivacyCategory c : PrivacyCategory.values()) out.add(rowFor(c));
            rows.postValue(out);
        });
    }

    public void showDetails(PrivacyCategory category) {
        executor.execute(() -> details.postValue(describe(category)));
    }

    public void consumeDetails() { details.setValue(null); }

    public void delete(PrivacyCategory category) {
        executor.execute(() -> {
            try {
                int failed = erase(category);
                message.postValue(failed == 0
                        ? category.label + " verwijderd"
                        : failed + " bestand(en) konden niet worden verwijderd");
            } catch (Exception e) {
                message.postValue("Verwijderen mislukt: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
            load();
        });
    }

    // --- rows -----------------------------------------------------------------------------

    private Row rowFor(PrivacyCategory c) {
        SharedPreferences prefs = defaultPrefs();
        switch (c) {
            case LOCATION:
                return yesNoRow(c, prefs.contains(RouteSyncWorker.PREF_LAST_LAT),
                        "Opgeslagen", "Niets opgeslagen");
            case RIDER_PROFILE:
                return yesNoRow(c, hasAny(prefs, riderKeys()), "Ingevuld", "Niet ingevuld");
            case STRAVA:
                return stravaRow(c);
            case INTERVALS_ICU:
                return yesNoRow(c, new IntervalsIcuRepository(getApplication()).isConfigured(),
                        "Gekoppeld", "Niet gekoppeld");
            case FRIENDS:
                return friendsRow(c, prefs);
            default:
                return fileRow(c);
        }
    }

    private static Row yesNoRow(PrivacyCategory c, boolean has, String yes, String no) {
        return new Row(c, has ? yes : no, has);
    }

    private Row stravaRow(PrivacyCategory c) {
        boolean linked = new StravaAuthRepository(getApplication()).isAuthorised();
        boolean syncState = !stravaPrefs().getAll().isEmpty();
        String status = "Niet gekoppeld";
        if (linked) status = "Gekoppeld";
        else if (syncState) status = "Niet gekoppeld, sync-status bewaard";
        return new Row(c, status, linked || syncState);
    }

    /**
     * The chosen share name lives in prefs, not friend_feed.json, so it must count
     * as data on its own: otherwise a user who shared once but received nothing yet
     * sees "Leeg" with nothing to erase, even though their name is still stored.
     */
    private Row friendsRow(PrivacyCategory c, SharedPreferences prefs) {
        PrivacyInventory.Usage u = inventory.usage(c);
        boolean hasName = !FriendShareIdentity.name(prefs).isEmpty();
        boolean has = u.fileCount > 0 || hasName;
        String summary = hasName ? "Geen feed, deelnaam bewaard" : "Leeg";
        if (u.fileCount > 0) {
            summary = u.fileCount + " bestand(en) · " + PrivacyInventory.formatBytes(u.bytes);
        }
        return new Row(c, summary, has);
    }

    private Row fileRow(PrivacyCategory c) {
        PrivacyInventory.Usage u = inventory.usage(c);
        return new Row(c, u.fileCount == 0 ? "Leeg"
                : u.fileCount + " bestand(en) · " + PrivacyInventory.formatBytes(u.bytes),
                u.fileCount > 0);
    }

    // --- details --------------------------------------------------------------------------

    private String describe(PrivacyCategory c) {
        StringBuilder sb = new StringBuilder(c.description).append("\n\n");
        switch (c) {
            case LOCATION:
                return describeLocation(sb);
            case RIDER_PROFILE:
                return describeRiderProfile(sb);
            case STRAVA:
                return describeStrava(sb);
            case INTERVALS_ICU:
                return describeIntervals(sb);
            case ROUTES:
                appendRoutes(sb);
                break;
            case ATTEMPTS:
                appendAttempts(sb);
                break;
            case COLLECTIONS:
                appendCapped(sb, collectionLines());
                break;
            case PLANNING:
                appendCapped(sb, planningLines());
                break;
            case RIDE_BUDDIES:
                appendCapped(sb, rideBuddyLines());
                break;
            case FAVORITE_START_POINTS:
                appendCapped(sb, startPointLines());
                break;
            default:
                break;
        }
        appendFiles(sb, c);
        return sb.toString().trim();
    }

    private void appendRoutes(StringBuilder sb) {
        List<RouteCatalogEntry> routes = new RouteRepository(getApplication()).loadCatalog();
        sb.append(routes.size()).append(" route(s):\n");
        List<String> names = new ArrayList<>();
        for (RouteCatalogEntry e : routes) {
            String n = e.userDisplayName != null ? e.userDisplayName : e.name;
            names.add("• " + (n != null ? n : e.routeId) + " (" + e.climbCount + " klimmen)");
        }
        appendCapped(sb, names);
    }

    private void appendAttempts(StringBuilder sb) {
        List<StoredClimbAttempt> attempts = new ClimbAttemptRepository(getApplication()).loadAll();
        Set<String> climbs = new HashSet<>();
        long first = Long.MAX_VALUE;
        long last = 0;
        int notes = 0;
        for (StoredClimbAttempt a : attempts) {
            climbs.add(a.climbId);
            if (a.dateEpochSec > 0) {
                first = Math.min(first, a.dateEpochSec);
                last = Math.max(last, a.dateEpochSec);
            }
            if (a.note != null && !a.note.isEmpty()) notes++;
        }
        sb.append(attempts.size()).append(" poging(en) op ").append(climbs.size())
          .append(" klim(men)");
        if (last > 0) sb.append("\nVan ").append(date(first)).append(" t/m ").append(date(last));
        sb.append("\n").append(notes).append(" met notitie");
    }

    private List<String> collectionLines() {
        List<String> names = new ArrayList<>();
        for (RouteCollection col : new RouteCollectionRepository(getApplication()).loadAll()) {
            names.add("• " + col.name + " (" + col.routeIds.size() + " routes, "
                    + col.climbs.size() + " klimmen)");
        }
        return names;
    }

    private List<String> planningLines() {
        List<String> names = new ArrayList<>();
        for (PlannedClimb p : new PlannedClimbRepository(getApplication()).loadAll()) {
            names.add("• " + p.displayName + " op " + date(p.plannedAtEpochSec));
        }
        return names;
    }

    private List<String> rideBuddyLines() {
        List<String> names = new ArrayList<>();
        for (nl.paree.climbpro.domain.social.RideBuddyProfile p
                : new RideBuddyRepository(getApplication()).loadAll()) {
            names.add("• " + p.name);
        }
        return names;
    }

    private List<String> startPointLines() {
        List<String> names = new ArrayList<>();
        for (FavoriteStartPoint f : new FavoriteStartPointStore(new File(
                getApplication().getFilesDir(), FavoriteStartPointStore.FILE_NAME)).loadAll()) {
            names.add("• " + f.name);
        }
        return names;
    }

    private String describeLocation(StringBuilder sb) {
        SharedPreferences prefs = defaultPrefs();
        if (prefs.contains(RouteSyncWorker.PREF_LAST_LAT)) {
            sb.append(String.format(Locale.US, "%.3f, %.3f",
                    Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LAT, 0)),
                    Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LON, 0))));
        } else {
            sb.append("Niets opgeslagen");
        }
        return sb.toString();
    }

    private String describeRiderProfile(StringBuilder sb) {
        RiderProfile p = new RiderProfileRepository(getApplication()).load();
        sb.append("FTP: ").append(p.ftpWatts > 0 ? p.ftpWatts + " W" : "—")
          .append("\nGewicht: ").append(p.riderWeightKg > 0 ? kg(p.riderWeightKg) : "—")
          .append("\nFiets: ").append(p.bikeWeightKg > 0 ? kg(p.bikeWeightKg) : "—")
          .append("\nRit-intensiteit: ").append(p.rideIntensityPct).append("%");
        return sb.toString();
    }

    private String describeStrava(StringBuilder sb) {
        boolean linked = new StravaAuthRepository(getApplication()).isAuthorised();
        sb.append("Gekoppeld: ").append(linked ? "ja" : "nee");
        long lastSync = stravaPrefs().getLong("last_sync_epoch_sec", 0);
        if (lastSync > 0) sb.append("\nLaatste activiteiten-sync: ").append(date(lastSync));
        sb.append("\n\nVerwijderen ontkoppelt Strava; routes en pogingen blijven staan.");
        return sb.toString();
    }

    private String describeIntervals(StringBuilder sb) {
        IntervalsIcuRepository icu = new IntervalsIcuRepository(getApplication());
        boolean linked = icu.isConfigured();
        sb.append("Gekoppeld: ").append(linked ? "ja" : "nee");
        if (linked) sb.append("\nAtleet-id: ").append(icu.athleteId());
        sb.append("""


                Verwijderen wist de API-sleutel; workouts op je intervals.icu-\
                kalender blijven staan.""");
        return sb.toString();
    }

    /** The category's files with their sizes ("Leeg" for photos/cache without any). */
    private void appendFiles(StringBuilder sb, PrivacyCategory c) {
        List<File> files = inventory.filesOf(c);
        if (!files.isEmpty()) {
            sb.append("\n\nBestanden:\n");
            List<String> lines = new ArrayList<>();
            for (File f : files) {
                lines.add("• " + f.getName() + " (" + PrivacyInventory.formatBytes(f.length()) + ")");
            }
            appendCapped(sb, lines);
        } else if (c == PrivacyCategory.PHOTOS || c == PrivacyCategory.CACHE) {
            sb.append("Leeg");
        }
    }

    // --- deletion -------------------------------------------------------------------------

    /** Returns the number of files that could not be deleted. */
    private int erase(PrivacyCategory c) throws java.io.IOException {
        Context app = getApplication();
        SharedPreferences prefs = defaultPrefs();
        switch (c) {
            case ROUTES:
                return eraseRoutes(app, prefs);
            case ATTEMPTS:
                return eraseAttempts(app);
            case RIDES:
                // Through the repository's write lock, like ATTEMPTS.
                return failures(new RideRepository(app).deleteAll())
                        + failures(new RideStreamStatsRepository(app).deleteAll())
                        + failures(new nl.paree.climbpro.data.explore.ExploreMapRepository(app)
                                .deleteAll())
                        + inventory.deleteFiles(c);
            case FRIENDS:
                // Through the repository lock (an import may be writing). The random share id
                // stays: friends who already imported you would otherwise see a second "you".
                prefs.edit().remove(FriendShareIdentity.PREF_NAME).apply();
                return failures(new FriendFeedRepository(app).deleteAll()) + inventory.deleteFiles(c);
            case RIDE_BUDDIES:
                // Through the repository lock (an import may be writing).
                return failures(new RideBuddyRepository(app).deleteAll()) + inventory.deleteFiles(c);
            case PHOTOS:
                new ClimbAttemptRepository(app).clearPhotoReferences();
                return inventory.deleteFiles(c);
            case PLANNING:
                cancelPlannedReminders(app);
                return inventory.deleteFiles(c);
            case LOCATION:
                prefs.edit().remove(RouteSyncWorker.PREF_LAST_LAT)
                        .remove(RouteSyncWorker.PREF_LAST_LON).apply();
                return 0;
            case RIDER_PROFILE:
                removeRiderKeys(prefs);
                return 0;
            case STRAVA:
                new StravaAuthRepository(app).signOut();
                stravaPrefs().edit().clear().apply();
                return 0;
            case INTERVALS_ICU:
                new IntervalsIcuRepository(app).clear();
                return 0;
            default:
                return inventory.deleteFiles(c);
        }
    }

    /** 0 for a successful delete, 1 for a failed one. */
    private static int failures(boolean deleted) {
        return deleted ? 0 : 1;
    }

    private int eraseRoutes(Context app, SharedPreferences prefs) throws java.io.IOException {
        RouteRepository routeRepo = new RouteRepository(app);
        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        for (RouteCatalogEntry e : routeRepo.loadCatalog()) {
            routeRepo.deleteRoute(e.routeId);
            collections.removeRouteEverywhere(e.routeId);
        }
        prefs.edit().remove(RouteSyncWorker.PREF_ROUTE_ID).apply();
        return inventory.deleteFiles(PrivacyCategory.ROUTES);
    }

    private int eraseAttempts(Context app) {
        // Through the repositories' write locks: a raw file delete could be undone by
        // a Strava sync appending at the same moment (it rewrites the list it read).
        int failed = failures(new ClimbAttemptRepository(app).deleteAll());
        failed += failures(new IncompleteClimbAttemptRepository(app).deleteAll());
        failed += failures(new nl.paree.climbpro.data.route.RouteGhostRepository(app).deleteAll());
        // Photos only exist as part of an attempt, so they go with it.
        return failed + inventory.deleteFiles(PrivacyCategory.ATTEMPTS)
                + inventory.deleteFiles(PrivacyCategory.PHOTOS);
    }

    private static void cancelPlannedReminders(Context app) {
        for (PlannedClimb p : new PlannedClimbRepository(app).loadAll()) {
            PlannedClimbWorkScheduler.cancel(app, p.id);
        }
    }

    private static void removeRiderKeys(SharedPreferences prefs) {
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : riderKeys()) ed.remove(k);
        ed.apply();
    }

    // --- helpers --------------------------------------------------------------------------

    private SharedPreferences defaultPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(getApplication());
    }

    private SharedPreferences stravaPrefs() {
        return getApplication().getSharedPreferences(
                StravaActivitiesRepository.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String[] riderKeys() {
        return new String[]{
                RiderProfileRepository.PREF_FTP_WATTS,
                RiderProfileRepository.PREF_RIDER_WEIGHT_KG,
                RiderProfileRepository.PREF_BIKE_WEIGHT_KG,
                RiderProfileRepository.PREF_RIDE_INTENSITY_PCT,
                RiderProfileRepository.PREF_MAX_HEART_RATE,
                RiderProfileRepository.PREF_GHOST_SPEED_KMH,
                RiderProfileRepository.PREF_GHOST_VAM_M_PER_H,
                RiderProfileRepository.PREF_FTP_TEST_EXPORTED_AT,
                RiderProfileRepository.PREF_FTP_TEST_HANDLED_ID};
    }

    private static boolean hasAny(SharedPreferences prefs, String[] keys) {
        for (String k : keys) if (prefs.contains(k)) return true;
        return false;
    }

    private static void appendCapped(StringBuilder sb, List<String> lines) {
        if (lines.isEmpty()) {
            sb.append("Leeg");
            return;
        }
        for (int i = 0; i < Math.min(MAX_LISTED, lines.size()); i++) sb.append(lines.get(i)).append('\n');
        if (lines.size() > MAX_LISTED) sb.append("… en ").append(lines.size() - MAX_LISTED).append(" meer");
    }

    private static String date(long epochSec) {
        return DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("nl"))
                .format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()));
    }

    private static String kg(double v) {
        return String.format(Locale.GERMANY, "%.1f kg", v);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
