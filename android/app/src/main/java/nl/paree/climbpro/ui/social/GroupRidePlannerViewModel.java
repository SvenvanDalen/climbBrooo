package nl.paree.climbpro.ui.social;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.social.FriendShareIdentity;
import nl.paree.climbpro.data.social.RideBuddyRepository;
import nl.paree.climbpro.domain.social.GroupRideParticipant;
import nl.paree.climbpro.domain.social.GroupRidePlanner;
import nl.paree.climbpro.domain.social.RideBuddyProfile;
import nl.paree.climbpro.domain.social.RideBuddyProfileBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Group-ride planner (issue #195). Holds the plan being built — route, riders, first possible
 * date — and recomputes the estimate, date options and share text on every change. Nothing is
 * persisted: riders come from the imported ride-buddy profiles (#242), your own derived profile
 * and manual entries for this session; the result leaves the phone only as shared text.
 */
public final class GroupRidePlannerViewModel extends AndroidViewModel {

    private static final String TAG = "GroupRidePlannerVM";

    /** A route the user can plan on, from the catalog. */
    public static final class RouteChoice {
        public final String routeId;
        public final String name;

        RouteChoice(String routeId, String name) {
            this.routeId = routeId;
            this.name = name;
        }
    }

    /** A rider that can be ticked in the participants dialog. */
    public static final class Candidate {
        /** Rider id of an imported profile, or {@link #SELF_ID} for yourself. */
        public final String id;
        public final String label;

        Candidate(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Everything the screen shows for the current plan. */
    public static final class Plan {
        public final String routeName;
        public final double distanceM;
        public final int ascentM;
        public final List<GroupRideParticipant> participants;
        public final GroupRidePlanner.Estimate estimate;
        public final List<GroupRidePlanner.DateOption> dates;
        public final String shareText;
        public final LocalDate firstDay;
        /** The last {@code manualCount} participants are manual entries (removable). */
        public final int manualCount;

        Plan(String routeName, double distanceM, int ascentM,
             List<GroupRideParticipant> participants, GroupRidePlanner.Estimate estimate,
             List<GroupRidePlanner.DateOption> dates, String shareText, LocalDate firstDay,
             int manualCount) {
            this.routeName = routeName;
            this.distanceM = distanceM;
            this.ascentM = ascentM;
            this.participants = participants;
            this.estimate = estimate;
            this.dates = dates;
            this.shareText = shareText;
            this.firstDay = firstDay;
            this.manualCount = manualCount;
        }

        /** Index among the manual riders of participant {@code i}, or -1 when not manual. */
        public int manualIndexOf(int i) {
            int m = i - (participants.size() - manualCount);
            return m >= 0 && m < manualCount ? m : -1;
        }
    }

    public static final String SELF_ID = "__self__";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<RouteChoice>> routes = new MutableLiveData<>();
    private final MutableLiveData<Plan> plan = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    // Plan state, only touched on the executor thread.
    private RideBuddyProfile own;
    private final List<RideBuddyProfile> buddies = new ArrayList<>();
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private final List<GroupRideParticipant> manual = new ArrayList<>();
    private String routeName;
    private double distanceM;
    private int ascentM;
    private LocalDate firstDay = LocalDate.now().plusDays(1);
    /** Snapshot for the participants dialog (read on the main thread). */
    private volatile List<Candidate> candidates = new ArrayList<>();
    private volatile Set<String> selectedSnapshot = new LinkedHashSet<>();
    private boolean loaded;

    public GroupRidePlannerViewModel(@NonNull Application app) {
        super(app);
    }

    public LiveData<List<RouteChoice>> routes() { return routes; }
    public LiveData<Plan> plan() { return plan; }
    public LiveData<String> message() { return message; }
    public void consumeMessage() { message.setValue(null); }

    public List<Candidate> candidates() { return candidates; }
    public Set<String> selectedIds() { return selectedSnapshot; }

    public void load() {
        if (loaded) return;
        loaded = true;
        executor.execute(() -> {
            try {
                List<RouteChoice> out = new ArrayList<>();
                for (RouteCatalogEntry e : new RouteRepository(getApplication()).loadCatalog()) {
                    if (e == null || e.routeId == null) continue;
                    out.add(new RouteChoice(e.routeId, displayName(e.userDisplayName, e.name)));
                }
                routes.postValue(out);
                own = OwnRideBuddyProfile.build(getApplication());
                buddies.clear();
                buddies.addAll(new RideBuddyRepository(getApplication()).loadAll());
                selectedIds.add(SELF_ID);
                rebuildCandidates();
                recompute();
            } catch (Exception e) {
                Log.e(TAG, "Load failed", e);
                message.postValue("Gegevens laden mislukt.");
            }
        });
    }

    public void selectRoute(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute r = new RouteRepository(getApplication()).loadRoute(routeId);
                if (r == null || r.distances == null || r.distances.length == 0) {
                    message.postValue("Deze route heeft geen afstandsgegevens.");
                    return;
                }
                routeName = displayName(r.userDisplayName, r.name);
                distanceM = r.distances[r.distances.length - 1];
                ascentM = GroupRidePlanner.ascentMeters(r.elevations);
                recompute();
            } catch (Exception e) {
                Log.e(TAG, "Route load failed", e);
                message.postValue("Route laden mislukt.");
            }
        });
    }

    public void setSelected(Set<String> ids) {
        Set<String> copy = new LinkedHashSet<>(ids);
        executor.execute(() -> {
            selectedIds.clear();
            selectedIds.addAll(copy);
            selectedSnapshot = new LinkedHashSet<>(selectedIds);
            recompute();
        });
    }

    public void addManual(String name, double avgSpeedKmh) {
        executor.execute(() -> {
            manual.add(GroupRideParticipant.manual(name, avgSpeedKmh));
            recompute();
        });
    }

    public void removeManual(int index) {
        executor.execute(() -> {
            if (index >= 0 && index < manual.size()) manual.remove(index);
            recompute();
        });
    }

    public void setFirstDay(LocalDate day) {
        executor.execute(() -> {
            firstDay = day;
            recompute();
        });
    }

    private void rebuildCandidates() {
        List<Candidate> out = new ArrayList<>();
        out.add(new Candidate(SELF_ID, selfLabel()));
        for (RideBuddyProfile b : buddies) out.add(new Candidate(b.riderId, b.name));
        candidates = out;
        selectedSnapshot = new LinkedHashSet<>(selectedIds);
    }

    private String selfName() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
        String n = FriendShareIdentity.name(prefs);
        return n == null || n.trim().isEmpty() ? "Ik" : n.trim();
    }

    private String selfLabel() {
        if (own == null || own.rideCount < RideBuddyProfileBuilder.MIN_RIDES) {
            return selfName() + " (te weinig ritten, standaardtempo)";
        }
        return selfName() + " (jij)";
    }

    private List<GroupRideParticipant> participants() {
        List<GroupRideParticipant> out = new ArrayList<>();
        if (selectedIds.contains(SELF_ID)) {
            if (own != null && own.rideCount >= RideBuddyProfileBuilder.MIN_RIDES) {
                out.add(GroupRideParticipant.fromProfile(own, selfName()));
            } else {
                out.add(new GroupRideParticipant(selfName(), 0, 0, 0, 0));
            }
        }
        for (RideBuddyProfile b : buddies) {
            if (selectedIds.contains(b.riderId)) out.add(GroupRideParticipant.fromProfile(b, null));
        }
        out.addAll(manual);
        return out;
    }

    private void recompute() {
        List<GroupRideParticipant> ps = participants();
        GroupRidePlanner.Estimate est = GroupRidePlanner.estimate(ps, distanceM, ascentM);
        List<GroupRidePlanner.DateOption> dates = GroupRidePlanner.proposeDates(ps, firstDay,
                GroupRidePlanner.DEFAULT_HORIZON_DAYS, GroupRidePlanner.DEFAULT_DATE_OPTIONS);
        String text = routeName == null ? null
                : GroupRidePlanner.shareText(routeName, distanceM, ascentM, ps, est, dates);
        plan.postValue(new Plan(routeName, distanceM, ascentM, ps, est, dates, text, firstDay,
                manual.size()));
    }

    private static String displayName(String userName, String name) {
        if (userName != null && !userName.trim().isEmpty()) return userName.trim();
        return name == null || name.trim().isEmpty() ? "Naamloze route" : name.trim();
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
