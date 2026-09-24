package nl.paree.climbpro.ui.planning;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.planning.PlannedClimbScheduler;
import nl.paree.climbpro.service.PlannedClimbCalendarWriter;
import nl.paree.climbpro.service.PlannedClimbWorkScheduler;
import nl.paree.climbpro.widget.WeekWidgetProvider;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlannedClimbListViewModel extends AndroidViewModel {

    /** A route (or a specific climb within it) offered as a plannable target in the add dialog. */
    public static final class Pickable {
        public final String routeId;
        public final int climbIndex; // PlannedClimb.WHOLE_ROUTE for the route itself
        public final String label;
        public Pickable(String routeId, int climbIndex, String label) {
            this.routeId = routeId;
            this.climbIndex = climbIndex;
            this.label = label;
        }
    }

    private final PlannedClimbRepository planRepo;
    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<PlannedClimb>> upcoming = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public PlannedClimbListViewModel(@NonNull Application app) {
        super(app);
        planRepo = new PlannedClimbRepository(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<List<PlannedClimb>> upcoming() { return upcoming; }
    public LiveData<String> error() { return error; }

    public void load() {
        executor.execute(() -> {
            long nowSec = System.currentTimeMillis() / 1000L;
            List<PlannedClimb> all = planRepo.loadAll();
            upcoming.postValue(PlannedClimbScheduler.upcoming(all, nowSec, ZoneId.systemDefault()));
        });
    }

    /** Builds the list of routes/climbs the add dialog can offer, off the main thread. */
    public void loadPickables(PickablesCallback callback) {
        executor.execute(() -> {
            List<Pickable> out = new ArrayList<>();
            for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
                String routeName = entry.userDisplayName != null ? entry.userDisplayName : entry.name;
                out.add(new Pickable(entry.routeId, PlannedClimb.WHOLE_ROUTE, "Route: " + routeName));
                try {
                    StoredRoute route = routeRepo.loadRoute(entry.routeId);
                    if (route.climbs == null) continue;
                    for (int i = 0; i < route.climbs.size(); i++) {
                        StoredClimb c = route.climbs.get(i);
                        String name = c.userDisplayName != null ? c.userDisplayName
                                : (c.name != null ? c.name : ("Klim " + (i + 1)));
                        out.add(new Pickable(entry.routeId, i, "  Klim: " + name + " (" + routeName + ")"));
                    }
                } catch (Exception ignored) {
                    // Route failed to load fully; still offer the whole-route entry above.
                }
            }
            callback.onPickables(out);
        });
    }

    public interface PickablesCallback { void onPickables(List<Pickable> pickables); }

    /**
     * Persists a new plan, schedules its WorkManager reminder, and optionally writes a
     * best-effort calendar event (never blocks on the calendar step).
     */
    public void addPlan(Pickable target, long plannedAtEpochSec, boolean addToCalendar) {
        executor.execute(() -> {
            try {
                PlannedClimb plan = new PlannedClimb(
                        UUID.randomUUID().toString(),
                        target.routeId, target.climbIndex,
                        target.label.trim(),
                        plannedAtEpochSec,
                        System.currentTimeMillis());

                if (addToCalendar) {
                    long eventId = PlannedClimbCalendarWriter.insertEvent(getApplication(), plan);
                    plan.calendarEventId = eventId;
                }

                planRepo.add(plan);
                PlannedClimbWorkScheduler.schedule(getApplication(), plan);
                WeekWidgetProvider.refresh(getApplication());
                load();
            } catch (Exception e) {
                error.postValue("Plannen mislukt: " + e.getMessage());
            }
        });
    }

    public void removePlan(PlannedClimb plan) {
        executor.execute(() -> {
            try {
                PlannedClimbWorkScheduler.cancel(getApplication(), plan.id);
                if (plan.calendarEventId >= 0) {
                    PlannedClimbCalendarWriter.deleteEvent(getApplication(), plan.calendarEventId);
                }
                planRepo.remove(plan.id);
                WeekWidgetProvider.refresh(getApplication());
                load();
            } catch (Exception e) {
                error.postValue("Verwijderen mislukt: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
