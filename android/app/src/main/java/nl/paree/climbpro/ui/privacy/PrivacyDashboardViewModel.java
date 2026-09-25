package nl.paree.climbpro.ui.privacy;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.privacy.PrivacyCategory;
import nl.paree.climbpro.data.privacy.PrivacyInventory;
import nl.paree.climbpro.data.ride.RideRepository;
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
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.PlannedClimbWorkScheduler;
import nl.paree.climbpro.service.RouteSyncWorker;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
            case LOCATION: {
                boolean has = prefs.contains(RouteSyncWorker.PREF_LAST_LAT);
                return new Row(c, has ? "Opgeslagen" : "Niets opgeslagen", has);
            }
            case RIDER_PROFILE: {
                boolean has = hasAny(prefs, riderKeys());
                return new Row(c, has ? "Ingevuld" : "Niet ingevuld", has);
            }
            case STRAVA: {
                boolean linked = new StravaAuthRepository(getApplication()).isAuthorised();
                boolean syncState = !stravaPrefs().getAll().isEmpty();
                return new Row(c, linked ? "Gekoppeld" : (syncState ? "Niet gekoppeld, sync-status bewaard"
                        : "Niet gekoppeld"), linked || syncState);
            }
            default: {
                PrivacyInventory.Usage u = inventory.usage(c);
                return new Row(c, u.fileCount == 0 ? "Leeg"
                        : u.fileCount + " bestand(en) · " + PrivacyInventory.formatBytes(u.bytes),
                        u.fileCount > 0);
            }
        }
    }

    // --- details --------------------------------------------------------------------------

    private String describe(PrivacyCategory c) {
        StringBuilder sb = new StringBuilder(c.description).append("\n\n");
        SharedPreferences prefs = defaultPrefs();
        switch (c) {
            case ROUTES: {
                List<RouteCatalogEntry> routes = new RouteRepository(getApplication()).loadCatalog();
                sb.append(routes.size()).append(" route(s):\n");
                List<String> names = new ArrayList<>();
                for (RouteCatalogEntry e : routes) {
                    String n = e.userDisplayName != null ? e.userDisplayName : e.name;
                    names.add("• " + (n != null ? n : e.routeId) + " (" + e.climbCount + " klimmen)");
                }
                appendCapped(sb, names);
                break;
            }
            case ATTEMPTS: {
                List<StoredClimbAttempt> attempts = new ClimbAttemptRepository(getApplication()).loadAll();
                Set<String> climbs = new HashSet<>();
                long first = Long.MAX_VALUE, last = 0;
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
                break;
            }
            case COLLECTIONS: {
                List<String> names = new ArrayList<>();
                for (RouteCollection col : new RouteCollectionRepository(getApplication()).loadAll()) {
                    names.add("• " + col.name + " (" + col.routeIds.size() + " routes, "
                            + col.climbs.size() + " klimmen)");
                }
                appendCapped(sb, names);
                break;
            }
            case PLANNING: {
                List<String> names = new ArrayList<>();
                for (PlannedClimb p : new PlannedClimbRepository(getApplication()).loadAll()) {
                    names.add("• " + p.displayName + " op " + date(p.plannedAtEpochSec));
                }
                appendCapped(sb, names);
                break;
            }
            case LOCATION:
                if (prefs.contains(RouteSyncWorker.PREF_LAST_LAT)) {
                    sb.append(String.format(Locale.US, "%.3f, %.3f",
                            Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LAT, 0)),
                            Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LON, 0))));
                } else {
                    sb.append("Niets opgeslagen");
                }
                return sb.toString();
            case RIDER_PROFILE: {
                RiderProfile p = new RiderProfileRepository(getApplication()).load();
                sb.append("FTP: ").append(p.ftpWatts > 0 ? p.ftpWatts + " W" : "—")
                  .append("\nGewicht: ").append(p.riderWeightKg > 0 ? kg(p.riderWeightKg) : "—")
                  .append("\nFiets: ").append(p.bikeWeightKg > 0 ? kg(p.bikeWeightKg) : "—")
                  .append("\nRit-intensiteit: ").append(p.rideIntensityPct).append("%");
                return sb.toString();
            }
            case STRAVA: {
                boolean linked = new StravaAuthRepository(getApplication()).isAuthorised();
                sb.append("Gekoppeld: ").append(linked ? "ja" : "nee");
                long lastSync = stravaPrefs().getLong("last_sync_epoch_sec", 0);
                if (lastSync > 0) sb.append("\nLaatste activiteiten-sync: ").append(date(lastSync));
                sb.append("\n\nVerwijderen ontkoppelt Strava; routes en pogingen blijven staan.");
                return sb.toString();
            }
            default:
                break;
        }
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
        return sb.toString().trim();
    }

    // --- deletion -------------------------------------------------------------------------

    /** Returns the number of files that could not be deleted. */
    private int erase(PrivacyCategory c) throws Exception {
        Context app = getApplication();
        SharedPreferences prefs = defaultPrefs();
        switch (c) {
            case ROUTES: {
                RouteRepository routeRepo = new RouteRepository(app);
                RouteCollectionRepository collections = new RouteCollectionRepository(app);
                for (RouteCatalogEntry e : routeRepo.loadCatalog()) {
                    routeRepo.deleteRoute(e.routeId);
                    collections.removeRouteEverywhere(e.routeId);
                }
                prefs.edit().remove(RouteSyncWorker.PREF_ROUTE_ID).apply();
                return inventory.deleteFiles(c);
            }
            case ATTEMPTS: {
                // Through the repositories' write locks: a raw file delete could be undone by
                // a Strava sync appending at the same moment (it rewrites the list it read).
                int failed = new ClimbAttemptRepository(app).deleteAll() ? 0 : 1;
                failed += new IncompleteClimbAttemptRepository(app).deleteAll() ? 0 : 1;
                // Photos only exist as part of an attempt, so they go with it.
                return failed + inventory.deleteFiles(c) + inventory.deleteFiles(PrivacyCategory.PHOTOS);
            }
            case RIDES:
                // Through the repository's write lock, like ATTEMPTS.
                return (new RideRepository(app).deleteAll() ? 0 : 1) + inventory.deleteFiles(c);
            case FRIENDS:
                // Through the repository lock (an import may be writing). The random share id
                // stays: friends who already imported you would otherwise see a second "you".
                prefs.edit().remove(FriendShareIdentity.PREF_NAME).apply();
                return (new FriendFeedRepository(app).deleteAll() ? 0 : 1) + inventory.deleteFiles(c);
            case PHOTOS:
                new ClimbAttemptRepository(app).clearPhotoReferences();
                return inventory.deleteFiles(c);
            case PLANNING:
                for (PlannedClimb p : new PlannedClimbRepository(app).loadAll()) {
                    PlannedClimbWorkScheduler.cancel(app, p.id);
                }
                return inventory.deleteFiles(c);
            case LOCATION:
                prefs.edit().remove(RouteSyncWorker.PREF_LAST_LAT)
                        .remove(RouteSyncWorker.PREF_LAST_LON).apply();
                return 0;
            case RIDER_PROFILE: {
                SharedPreferences.Editor ed = prefs.edit();
                for (String k : riderKeys()) ed.remove(k);
                ed.apply();
                return 0;
            }
            case STRAVA:
                new StravaAuthRepository(app).signOut();
                stravaPrefs().edit().clear().apply();
                return 0;
            default:
                return inventory.deleteFiles(c);
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private SharedPreferences defaultPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(getApplication());
    }

    private SharedPreferences stravaPrefs() {
        return getApplication().getSharedPreferences(
                StravaActivitiesRepository.PREFS, Context.MODE_PRIVATE);
    }

    private static String[] riderKeys() {
        return new String[]{
                RiderProfileRepository.PREF_FTP_WATTS,
                RiderProfileRepository.PREF_RIDER_WEIGHT_KG,
                RiderProfileRepository.PREF_BIKE_WEIGHT_KG,
                RiderProfileRepository.PREF_RIDE_INTENSITY_PCT};
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
        return new SimpleDateFormat("d MMM yyyy", new Locale("nl")).format(new Date(epochSec * 1000L));
    }

    private static String kg(double v) {
        return String.format(Locale.GERMANY, "%.1f kg", v);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
