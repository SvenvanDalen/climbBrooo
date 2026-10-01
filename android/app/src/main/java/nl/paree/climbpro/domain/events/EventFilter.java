package nl.paree.climbpro.domain.events;

import nl.paree.climbpro.data.events.CyclingEvent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which events to show (issue #241): upcoming (today up to {@link #HORIZON_DAYS} ahead),
 * within the radius of the rider's position when both positions are known, de-duplicated
 * by UID, sorted by date. Events without coordinates are kept (the rider added the feed
 * or event, so it is probably relevant) but sorted after located ones on the same day.
 */
public final class EventFilter {

    public static final int HORIZON_DAYS = 365;

    private EventFilter() {}

    public static List<CyclingEvent> upcomingNearby(List<CyclingEvent> events, LocalDate today,
                                                    double[] home, int radiusKm) {
        List<CyclingEvent> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        LocalDate last = today.plusDays(HORIZON_DAYS);
        if (events == null) return out;
        for (CyclingEvent e : events) {
            if (e == null || e.date == null) continue;
            LocalDate d;
            try {
                d = LocalDate.parse(e.date);
            } catch (RuntimeException ex) {
                continue;
            }
            if (d.isBefore(today) || d.isAfter(last)) continue;
            double km = distanceKm(e, home);
            if (km >= 0 && km > radiusKm) continue;
            String key = e.uid != null ? e.uid : e.date + "|" + e.name;
            if (!seen.add(key)) continue;
            out.add(e);
        }
        out.sort(Comparator.comparing((CyclingEvent e) -> e.date)
                .thenComparing(e -> e.lat == null ? 1 : 0));
        return out;
    }

    /** Great-circle distance from home in km, or -1 when either position is unknown. */
    public static double distanceKm(CyclingEvent e, double[] home) {
        if (e == null || e.lat == null || e.lon == null || home == null) return -1;
        double dLat = Math.toRadians(e.lat - home[0]);
        double dLon = Math.toRadians(e.lon - home[1]);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(home[0])) * Math.cos(Math.toRadians(e.lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
