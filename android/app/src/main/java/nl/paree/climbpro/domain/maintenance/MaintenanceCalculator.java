package nl.paree.climbpro.domain.maintenance;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.ride.StoredRide;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Wear status of maintenance components (issue #154), independent of any climb logic.
 *
 * <p>Km since service = the sum of {@link StoredRide#distanceM} of archived rides (issue #160
 * ride archive) that started after the component's last-serviced date. {@code VirtualRide} is
 * excluded unless the component opts in ({@link MaintenanceComponent#includeVirtualRides}):
 * trainer km don't wear a road chain, tyres or brake pads the same way. Rides with an unknown
 * start ({@code startEpochSec == 0}) are skipped because they cannot be placed before or after
 * the service. The archive only grows on a Strava sync, so km lag behind until the next sync.
 *
 * <p>A component is <b>due</b> when an enabled criterion is reached: km since service ≥
 * interval km, or ≥ interval calendar months since the service. It is <b>soon</b> due from
 * {@link #SOON_FRACTION} of either interval. A component without a known last-serviced date is
 * never due — otherwise every new user would get a permanent banner — and the tracker screen
 * prompts for a date instead.
 *
 * <p>Pure and static with an explicit {@code nowEpochSec} and zone, so it is unit-testable.
 */
public final class MaintenanceCalculator {

    private MaintenanceCalculator() {}

    static final String VIRTUAL_RIDE_TYPE = "VirtualRide";
    /** Fraction of an interval from which a component shows as "bijna aan de beurt". */
    public static final double SOON_FRACTION = 0.9;
    /** Service dates kept per component; older ones are dropped. */
    public static final int MAX_HISTORY = 10;

    /** Evaluated status of one component. */
    public static final class Status {
        public final MaintenanceComponent component;
        /** False when the last-serviced date is unknown; all other fields are then zero. */
        public final boolean configured;
        public final double  kmSince;
        /** Whole calendar months since the service. */
        public final int     monthsSince;
        /** kmSince / intervalKm; 0 when the km criterion is off. */
        public final double  kmFraction;
        /** Elapsed share of the month interval; 0 when the time criterion is off. */
        public final double  timeFraction;
        public final boolean dueByKm;
        public final boolean dueByTime;
        public final boolean due;
        public final boolean soon;

        Status(MaintenanceComponent component, boolean configured, double kmSince,
               int monthsSince, double kmFraction, double timeFraction,
               boolean dueByKm, boolean dueByTime) {
            this.component    = component;
            this.configured   = configured;
            this.kmSince      = kmSince;
            this.monthsSince  = monthsSince;
            this.kmFraction   = kmFraction;
            this.timeFraction = timeFraction;
            this.dueByKm      = dueByKm;
            this.dueByTime    = dueByTime;
            this.due          = dueByKm || dueByTime;
            this.soon         = !due && Math.max(kmFraction, timeFraction) >= SOON_FRACTION;
        }

        /**
         * Worn percentage: the larger of the km and time shares, rounded down (so 100 only
         * shows once due) and possibly above 100 when overdue.
         */
        public int wornPercent() {
            return (int) Math.floor(Math.max(kmFraction, timeFraction) * 100 + 1e-9);
        }
    }

    public static Status evaluate(MaintenanceComponent c, List<StoredRide> rides,
                                  long nowEpochSec, ZoneId zone) {
        if (c == null || c.lastServicedEpochSec <= 0) {
            return new Status(c, false, 0, 0, 0, 0, false, false);
        }
        long last = c.lastServicedEpochSec;
        double km = kmSince(rides, last, c.includeVirtualRides);

        boolean byKm = false;
        double kmFraction = 0;
        if (c.intervalKm > 0) {
            kmFraction = km / c.intervalKm;
            byKm = km >= c.intervalKm;
        }

        ZonedDateTime lastZ = Instant.ofEpochSecond(last).atZone(zone);
        ZonedDateTime nowZ  = Instant.ofEpochSecond(nowEpochSec).atZone(zone);
        int months = (int) Math.max(0, ChronoUnit.MONTHS.between(lastZ, nowZ));

        boolean byTime = false;
        double timeFraction = 0;
        if (c.intervalMonths > 0) {
            long dueAt = lastZ.plusMonths(c.intervalMonths).toEpochSecond();
            timeFraction = Math.max(0, (double) (nowEpochSec - last) / (dueAt - last));
            byTime = nowEpochSec >= dueAt;
        }
        return new Status(c, true, km, months, kmFraction, timeFraction, byKm, byTime);
    }

    public static List<Status> evaluateAll(List<MaintenanceComponent> components,
                                           List<StoredRide> rides, long nowEpochSec,
                                           ZoneId zone) {
        if (components == null) return Collections.emptyList();
        List<Status> out = new ArrayList<>(components.size());
        for (MaintenanceComponent c : components) {
            if (c != null) out.add(evaluate(c, rides, nowEpochSec, zone));
        }
        return out;
    }

    /** Km ridden after {@code sinceEpochSec}; VirtualRide only when {@code includeVirtual}. */
    public static double kmSince(List<StoredRide> rides, long sinceEpochSec,
                                 boolean includeVirtual) {
        if (rides == null) return 0;
        double meters = 0;
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0 || r.startEpochSec <= sinceEpochSec) continue;
            if (!includeVirtual && VIRTUAL_RIDE_TYPE.equalsIgnoreCase(r.type)) continue;
            if (r.distanceM > 0) meters += r.distanceM;
        }
        return meters / 1000.0;
    }

    /**
     * "Gedaan": appends a service at {@code epochSec} to the history and makes the newest
     * recorded service the last-serviced date. Mutates {@code c}.
     */
    public static void recordService(MaintenanceComponent c, long epochSec) {
        if (c == null || epochSec <= 0) return;
        if (c.serviceHistory == null) c.serviceHistory = new ArrayList<>();
        normalizeHistory(c); // keeps a legacy date without history as an entry
        c.serviceHistory.add(epochSec);
        normalizeHistory(c);
    }

    /**
     * Corrects the latest service date (a date picked in the edit dialog): replaces the newest
     * history entry, or records one when there is none yet. Mutates {@code c}.
     */
    public static void correctLastService(MaintenanceComponent c, long epochSec) {
        if (c == null || epochSec <= 0) return;
        if (c.serviceHistory == null) c.serviceHistory = new ArrayList<>();
        normalizeHistory(c);
        if (!c.serviceHistory.isEmpty()) c.serviceHistory.remove(c.serviceHistory.size() - 1);
        c.serviceHistory.add(epochSec);
        normalizeHistory(c);
    }

    /** Sorts oldest first, drops duplicates, caps to {@link #MAX_HISTORY}, syncs the date. */
    private static void normalizeHistory(MaintenanceComponent c) {
        List<Long> h = c.serviceHistory;
        h.removeIf(ts -> ts == null || ts <= 0);
        Collections.sort(h);
        for (int i = h.size() - 1; i > 0; i--) {
            if (h.get(i).equals(h.get(i - 1))) h.remove(i);
        }
        while (h.size() > MAX_HISTORY) h.remove(0);
        if (!h.isEmpty()) {
            c.lastServicedEpochSec = h.get(h.size() - 1);
        } else if (c.lastServicedEpochSec > 0) {
            // Legacy / hand-set date without history: keep it as the only entry.
            h.add(c.lastServicedEpochSec);
        }
    }

    /** The components that are due, in list order. */
    public static List<Status> due(List<Status> statuses) {
        List<Status> out = new ArrayList<>();
        if (statuses == null) return out;
        for (Status s : statuses) {
            if (s != null && s.due) out.add(s);
        }
        return out;
    }

    /** Route-list banner text, e.g. "Onderhoud nodig: Ketting, Banden"; null when none due. */
    public static String bannerText(List<Status> statuses) {
        List<Status> due = due(statuses);
        if (due.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Onderhoud nodig: ");
        for (int i = 0; i < due.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(due.get(i).component.name);
        }
        return sb.toString();
    }

    /** One-line usage summary, e.g. "2710 / 3000 km (90%)  •  5 / 12 mnd". */
    public static String usageText(Status s) {
        if (s == null || !s.configured) {
            return "Laatste onderhoud onbekend: stel een datum in of tik op Gedaan";
        }
        MaintenanceComponent c = s.component;
        List<String> parts = new ArrayList<>(2);
        if (c.intervalKm > 0) {
            parts.add(String.format(Locale.getDefault(), "%d / %d km",
                    Math.round(s.kmSince), c.intervalKm));
        } else {
            parts.add(String.format(Locale.getDefault(), "%d km", Math.round(s.kmSince)));
        }
        if (c.intervalMonths > 0) {
            parts.add(String.format(Locale.getDefault(), "%d / %d mnd",
                    s.monthsSince, c.intervalMonths));
        }
        String text = String.join("  •  ", parts);
        if (c.intervalKm > 0 || c.intervalMonths > 0) text += "  (" + s.wornPercent() + "%)";
        return text;
    }

    /** Short state label, or null when nothing needs attention. */
    public static String stateLabel(Status s) {
        if (s == null || !s.configured) return null;
        if (s.due) return "Vervangen/servicen nodig";
        if (s.soon) return "Bijna aan de beurt";
        return null;
    }
}
