package nl.paree.climbpro.domain.pain;

import nl.paree.climbpro.data.pain.PainLogEntry;
import nl.paree.climbpro.data.ride.StoredRide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds patterns in the pain log (issue #232): which areas hurt most often and how badly,
 * whether one bike or setup stands out, and whether complaints follow the longer rides.
 * Pure and static, so it is unit-testable.
 */
public final class PainPatternAnalyzer {

    private PainPatternAnalyzer() {}

    /** Complaint rides must average this much longer than all rides to call it a pattern. */
    static final double LONG_RIDE_FACTOR = 1.2;
    static final int MIN_LINKED_FOR_DISTANCE = 3;
    static final int MIN_RIDES_FOR_DISTANCE = 5;
    static final String VIRTUAL_RIDE_TYPE = "VirtualRide";

    /** Count and average severity for one area / bike / setup. */
    public static final class Stat {
        public final String label;
        public final int count;
        public final double avgSeverity;

        Stat(String label, int count, double avgSeverity) {
            this.label = label;
            this.count = count;
            this.avgSeverity = avgSeverity;
        }
    }

    public static final class Report {
        public final int entryCount;
        /** Most frequent first. */
        public final List<Stat> areas;
        /** Only filled when at least two different bikes were logged. */
        public final List<Stat> bikes;
        /** Only filled when at least two different setups were logged. */
        public final List<Stat> setups;
        /** Average km of rides with a complaint, or 0 when too little data. */
        public final double complaintRideKm;
        /** Average km of all outdoor archived rides, or 0 when too little data. */
        public final double allRideKm;

        Report(int entryCount, List<Stat> areas, List<Stat> bikes, List<Stat> setups,
               double complaintRideKm, double allRideKm) {
            this.entryCount = entryCount;
            this.areas = areas;
            this.bikes = bikes;
            this.setups = setups;
            this.complaintRideKm = complaintRideKm;
            this.allRideKm = allRideKm;
        }

        public boolean longRidePattern() {
            return complaintRideKm > 0 && allRideKm > 0
                    && complaintRideKm >= allRideKm * LONG_RIDE_FACTOR;
        }
    }

    public static Report analyze(List<PainLogEntry> entries, List<StoredRide> rides) {
        List<PainLogEntry> list = new ArrayList<>();
        if (entries != null) for (PainLogEntry e : entries) if (e != null) list.add(e);

        Map<String, int[]> areaAgg = new LinkedHashMap<>();
        for (PainArea a : PainArea.values()) areaAgg.put(a.label, new int[2]);
        Map<String, int[]> bikeAgg = new LinkedHashMap<>();
        Map<String, int[]> setupAgg = new LinkedHashMap<>();
        for (PainLogEntry e : list) {
            if (e.areas != null) {
                for (String name : new java.util.LinkedHashSet<>(e.areas)) {
                    PainArea a = PainArea.fromName(name);
                    if (a != null) add(areaAgg, a.label, e.severity);
                }
            }
            if (e.bike != null) add(bikeAgg, e.bike, e.severity);
            if (e.setup != null) add(setupAgg, e.setup, e.severity);
        }

        double complaintKm = 0;
        double allKm = 0;
        Map<Long, StoredRide> byId = new HashMap<>();
        List<StoredRide> outdoor = new ArrayList<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r == null || r.distanceM <= 0 || VIRTUAL_RIDE_TYPE.equalsIgnoreCase(r.type)) {
                    continue;
                }
                outdoor.add(r);
                byId.put(r.activityId, r);
            }
        }
        List<StoredRide> linked = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (PainLogEntry e : list) {
            StoredRide r = e.rideActivityId > 0 ? byId.get(e.rideActivityId) : null;
            if (r != null && seen.add(r.activityId)) linked.add(r);
        }
        if (linked.size() >= MIN_LINKED_FOR_DISTANCE && outdoor.size() >= MIN_RIDES_FOR_DISTANCE) {
            complaintKm = avgKm(linked);
            allKm = avgKm(outdoor);
        }

        return new Report(list.size(), toStats(areaAgg), bikeAgg.size() >= 2
                ? toStats(bikeAgg) : new ArrayList<>(), setupAgg.size() >= 2
                ? toStats(setupAgg) : new ArrayList<>(), complaintKm, allKm);
    }

    private static void add(Map<String, int[]> agg, String key, int severity) {
        int[] v = agg.get(key);
        if (v == null) {
            v = new int[2];
            agg.put(key, v);
        }
        v[0]++;
        v[1] += severity;
    }

    private static List<Stat> toStats(Map<String, int[]> agg) {
        List<Stat> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            int count = e.getValue()[0];
            if (count == 0) continue;
            out.add(new Stat(e.getKey(), count, e.getValue()[1] / (double) count));
        }
        // Stable sort keeps insertion order for ties.
        out.sort((a, b) -> Integer.compare(b.count, a.count));
        return out;
    }

    private static double avgKm(List<StoredRide> rides) {
        double m = 0;
        for (StoredRide r : rides) m += r.distanceM;
        return m / rides.size() / 1000.0;
    }

    static String statLine(Stat s) {
        return String.format(Locale.GERMANY, "%s: %d× (gem. %.1f/5)",
                s.label, s.count, s.avgSeverity);
    }

    /** Multi-line Dutch summary for the top of the log screen. */
    public static String summaryText(Report r) {
        if (r.entryCount == 0) {
            return "Nog geen klachten gelogd. Log na een rit waar het pijn deed, dan zie je "
                    + "hier na een paar ritten patronen.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.entryCount == 1 ? "1 klacht gelogd" : r.entryCount + " klachten gelogd");
        for (Stat s : r.areas) sb.append("\n• ").append(statLine(s));
        if (!r.bikes.isEmpty()) {
            sb.append("\n\nPer fiets:");
            for (Stat s : r.bikes) sb.append("\n• ").append(statLine(s));
        }
        if (!r.setups.isEmpty()) {
            sb.append("\n\nPer afstelling:");
            for (Stat s : r.setups) sb.append("\n• ").append(statLine(s));
        }
        if (r.longRidePattern()) {
            sb.append(String.format(Locale.GERMANY,
                    "\n\nKlachten vooral na lange ritten: gemiddeld %.0f km, tegen %.0f km "
                            + "voor al je ritten.", r.complaintRideKm, r.allRideKm));
        }
        return sb.toString();
    }
}
