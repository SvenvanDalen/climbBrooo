package nl.paree.climbpro.ui.events;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.events.CyclingEvent;
import nl.paree.climbpro.data.events.EventCalendar;
import nl.paree.climbpro.data.events.EventCalendarRepository;
import nl.paree.climbpro.data.goal.GoalEvent;
import nl.paree.climbpro.data.goal.GoalEventStore;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.domain.events.EventFilter;
import nl.paree.climbpro.domain.events.EventLevel;
import nl.paree.climbpro.service.RadiusLocation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Event calendar screen state (issue #241): nearby upcoming events with a level fit. */
public final class EventCalendarViewModel extends AndroidViewModel {

    /** One list row. */
    public static final class Row {
        public final CyclingEvent event;
        /** Distance from the rider in km; -1 when unknown. */
        public final double distanceKm;
        public final EventLevel.Result level;

        Row(CyclingEvent event, double distanceKm, EventLevel.Result level) {
            this.event = event;
            this.distanceKm = distanceKm;
            this.level = level;
        }
    }

    public static final class State {
        public final List<Row> rows;
        public final List<EventCalendar.Feed> feeds;
        public final int radiusKm;
        public final boolean hasLocation;
        public final EventLevel.Capacity capacity;
        public final long lastFetchMs;
        public final boolean loading;

        State(List<Row> rows, List<EventCalendar.Feed> feeds, int radiusKm, boolean hasLocation,
              EventLevel.Capacity capacity, long lastFetchMs, boolean loading) {
            this.rows = rows;
            this.feeds = feeds;
            this.radiusKm = radiusKm;
            this.hasLocation = hasLocation;
            this.capacity = capacity;
            this.lastFetchMs = lastFetchMs;
            this.loading = loading;
        }
    }

    private final EventCalendarRepository repo;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public EventCalendarViewModel(@NonNull Application app) {
        super(app);
        repo = new EventCalendarRepository(app);
    }

    public LiveData<State> state() { return state; }
    public LiveData<String> message() { return message; }

    public void load() {
        io.execute(() -> publish(repo.load(), false));
    }

    public void refresh() {
        io.execute(() -> {
            EventCalendar c = repo.load();
            publish(c, true);
            if (c.feeds.isEmpty()) {
                publish(c, false);
                message.postValue("Voeg eerst een agenda-link (iCal) toe");
                return;
            }
            try {
                c = repo.refresh();
                int failed = 0;
                for (EventCalendar.Feed f : c.feeds) if (f.lastError != null) failed++;
                if (failed > 0) message.postValue(failed + " agenda('s) niet bijgewerkt");
            } catch (Exception e) {
                message.postValue("Vernieuwen mislukt: " + e.getMessage());
            }
            publish(repo.load(), false);
        });
    }

    public void addFeed(String url, String name) {
        io.execute(() -> {
            EventCalendar c = repo.load();
            String u = EventCalendarRepository.normalise(url);
            for (EventCalendar.Feed f : c.feeds) {
                if (f.url.equalsIgnoreCase(u)) {
                    message.postValue("Deze agenda staat er al");
                    return;
                }
            }
            EventCalendar.Feed f = new EventCalendar.Feed();
            f.url = u;
            f.name = name != null && !name.trim().isEmpty() ? name.trim() : u;
            c.feeds.add(f);
            if (!write(c)) return;
            refresh();
        });
    }

    public void removeFeed(String url) {
        io.execute(() -> {
            EventCalendar c = repo.load();
            c.feeds.removeIf(f -> f.url.equals(url));
            c.feedEvents.removeIf(e -> url.equals(e.feedUrl));
            if (write(c)) publish(c, false);
        });
    }

    public void addManual(CyclingEvent e) {
        io.execute(() -> {
            EventCalendar c = repo.load();
            e.uid = "manual-" + UUID.randomUUID();
            e.feedUrl = null;
            repo.geocode(e, new HashMap<>());
            c.manualEvents.add(e);
            if (write(c)) publish(c, false);
        });
    }

    public void removeManual(String uid) {
        io.execute(() -> {
            EventCalendar c = repo.load();
            c.manualEvents.removeIf(e -> uid.equals(e.uid));
            if (write(c)) publish(c, false);
        });
    }

    public void setRadius(int km) {
        io.execute(() -> {
            EventCalendar c = repo.load();
            c.radiusKm = km;
            if (write(c)) publish(c, false);
        });
    }

    /** Makes the event the rider's goal event (issue #221), using its longest option. */
    public void setAsGoal(CyclingEvent e) {
        io.execute(() -> {
            GoalEvent g = new GoalEvent();
            g.name = e.name;
            g.date = e.date;
            g.distanceKm = e.distancesKm == null || e.distancesKm.isEmpty()
                    ? 0 : e.distancesKm.get(e.distancesKm.size() - 1);
            g.elevationM = e.elevationM != null ? e.elevationM : 0;
            try {
                new GoalEventStore(getApplication()).save(g);
                message.postValue("Ingesteld als doelevenement");
            } catch (Exception ex) {
                message.postValue("Opslaan mislukt: " + ex.getMessage());
            }
        });
    }

    private boolean write(EventCalendar c) {
        try {
            repo.save(c);
            return true;
        } catch (Exception e) {
            message.postValue("Opslaan mislukt: " + e.getMessage());
            return false;
        }
    }

    private void publish(EventCalendar c, boolean loading) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
        double[] home = RadiusLocation.current(getApplication(), prefs);
        EventLevel.Capacity cap = EventLevel.capacity(
                new RideRepository(getApplication()).loadAll(), System.currentTimeMillis() / 1000);
        List<CyclingEvent> all = new ArrayList<>(c.manualEvents);
        all.addAll(c.feedEvents);
        List<Row> rows = new ArrayList<>();
        for (CyclingEvent e : EventFilter.upcomingNearby(all, LocalDate.now(), home, c.radiusKm)) {
            rows.add(new Row(e, EventFilter.distanceKm(e, home), EventLevel.judge(e, cap)));
        }
        state.postValue(new State(rows, Collections.unmodifiableList(new ArrayList<>(c.feeds)),
                c.radiusKm, home != null, cap, c.lastFetchMs, loading));
    }

    @Override
    protected void onCleared() {
        io.shutdown();
    }
}
