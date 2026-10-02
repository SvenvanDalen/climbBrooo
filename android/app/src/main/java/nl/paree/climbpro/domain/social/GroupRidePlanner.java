package nl.paree.climbpro.domain.social;

import nl.paree.climbpro.domain.power.DurationFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Group-ride planner (issue #195): estimates the pace of a group on a saved route, proposes
 * date options from the riders' habitual riding days and dayparts, and renders a shareable
 * Dutch text proposal. Backend-free like the ride-buddy matcher (#242): the riders' habits come
 * from imported profile codes or manual entries, and the proposal travels as plain text through
 * any chat app. Pure; no Android dependencies.
 *
 * <p>Pace model (deliberately simple, documented so the numbers can be explained):
 * <ul>
 *   <li>On the flat a group rides at the <em>slowest</em> rider's speed plus a
 *       {@link #DRAFT_BONUS} for sitting in the wheels, capped at the second-slowest rider's
 *       speed (the draft can't make the group faster than its next-weakest member).</li>
 *   <li>On climbs riders go at their own pace and regroup at the top, so the group loses the
 *       <em>slowest</em> VAM: {@code ascent × CLIMB_FACTOR / slowestVam} on top of the flat
 *       time. {@link #CLIMB_FACTOR} &lt; 1 because the climbing distance is already counted
 *       once in the flat part.</li>
 *   <li>One {@link #PAUSE_SEC} regroup/coffee stop per full {@link #PAUSE_EVERY_SEC} of riding.</li>
 * </ul>
 * Unknown speeds fall back to {@link #DEFAULT_FLAT_DKMH} / {@link #DEFAULT_VAM_MPH}.
 */
public final class GroupRidePlanner {

    public static final int DEFAULT_FLAT_DKMH = 250;
    public static final int DEFAULT_VAM_MPH = 700;
    static final double DRAFT_BONUS = 1.05;
    static final double CLIMB_FACTOR = 0.7;
    public static final int PAUSE_EVERY_SEC = 9_000;
    public static final int PAUSE_SEC = 15 * 60;
    /** The fastest rider solo needing less than this share of the group time = big spread. */
    static final double BIG_SPREAD_RATIO = 0.8;
    /** Elevation changes smaller than this are treated as GPS/DEM noise in the ascent. */
    static final double ASCENT_HYSTERESIS_M = 3.0;

    public static final int DEFAULT_HORIZON_DAYS = 14;
    public static final int DEFAULT_DATE_OPTIONS = 3;

    private static final Locale NL = new Locale("nl");
    private static final String[] DAY_SHORT = {"ma", "di", "wo", "do", "vr", "za", "zo"};
    private static final String[] MONTH_SHORT = {"jan", "feb", "mrt", "apr", "mei", "jun",
            "jul", "aug", "sep", "okt", "nov", "dec"};
    private static final int[] PARTS = {RideBuddyProfile.PART_MORNING,
            RideBuddyProfile.PART_AFTERNOON, RideBuddyProfile.PART_EVENING};

    private GroupRidePlanner() {}

    /** The group's expected pace on one route. */
    public static final class Estimate {
        public final int groupFlatDkmh;
        public final int groupVamMph;
        public final int movingSec;
        public final int pauseSec;
        public final double avgSpeedKmh;
        /** Moving time of the fastest rider riding alone (for the spread warning). */
        public final int fastestSoloSec;
        /** Name of the rider who sets the pace (slowest on the flat); "" without riders. */
        public final String slowestName;
        /** Riders whose flat speed was unknown and got the default. */
        public final int unknownSpeedCount;
        public final boolean bigSpread;

        Estimate(int groupFlatDkmh, int groupVamMph, int movingSec, int pauseSec,
                 double avgSpeedKmh, int fastestSoloSec, String slowestName,
                 int unknownSpeedCount, boolean bigSpread) {
            this.groupFlatDkmh = groupFlatDkmh;
            this.groupVamMph = groupVamMph;
            this.movingSec = movingSec;
            this.pauseSec = pauseSec;
            this.avgSpeedKmh = avgSpeedKmh;
            this.fastestSoloSec = fastestSoloSec;
            this.slowestName = slowestName;
            this.unknownSpeedCount = unknownSpeedCount;
            this.bigSpread = bigSpread;
        }

        public int totalSec() {
            return movingSec + pauseSec;
        }
    }

    /** One proposed date: the best daypart of that day and who can make it. */
    public static final class DateOption {
        public final LocalDate date;
        /** One of the {@code RideBuddyProfile.PART_*} bits. */
        public final int daypart;
        public final int available;
        public final int total;
        public final List<String> unavailable;

        DateOption(LocalDate date, int daypart, int available, int total, List<String> unavailable) {
            this.date = date;
            this.daypart = daypart;
            this.available = available;
            this.total = total;
            this.unavailable = Collections.unmodifiableList(unavailable);
        }
    }

    public static Estimate estimate(List<GroupRideParticipant> participants, double distanceM,
                                    int ascentM) {
        List<GroupRideParticipant> ps = new ArrayList<>();
        if (participants != null) {
            for (GroupRideParticipant p : participants) if (p != null) ps.add(p);
        }
        if (ps.isEmpty()) ps.add(new GroupRideParticipant("", 0, 0, 0, 0));
        double km = Math.max(0, distanceM) / 1000.0;
        int ascent = Math.max(0, ascentM);

        List<Integer> flats = new ArrayList<>();
        int unknown = 0;
        int minVam = Integer.MAX_VALUE;
        int slowestFlat = Integer.MAX_VALUE;
        String slowestName = "";
        int fastestSolo = Integer.MAX_VALUE;
        for (GroupRideParticipant p : ps) {
            int flat = p.flatSpeedDkmh > 0 ? p.flatSpeedDkmh : DEFAULT_FLAT_DKMH;
            if (p.flatSpeedDkmh <= 0) unknown++;
            int vam = p.vamMph > 0 ? p.vamMph : DEFAULT_VAM_MPH;
            flats.add(flat);
            minVam = Math.min(minVam, vam);
            if (flat < slowestFlat) {
                slowestFlat = flat;
                slowestName = p.name;
            }
            fastestSolo = Math.min(fastestSolo, movingSec(km, ascent, flat, vam));
        }
        Collections.sort(flats);
        int groupFlat = flats.get(0);
        if (flats.size() >= 2) {
            groupFlat = (int) Math.min(Math.round(flats.get(0) * DRAFT_BONUS), flats.get(1));
        }
        int moving = movingSec(km, ascent, groupFlat, minVam);
        int pause = (moving / PAUSE_EVERY_SEC) * PAUSE_SEC;
        double avg = moving > 0 ? km / (moving / 3600.0) : groupFlat / 10.0;
        boolean spread = ps.size() >= 2 && fastestSolo < BIG_SPREAD_RATIO * moving;
        if (participants == null || participants.isEmpty()) {
            unknown = 0;
            slowestName = "";
        }
        return new Estimate(groupFlat, minVam, moving, pause, avg, fastestSolo, slowestName,
                unknown, spread);
    }

    private static int movingSec(double km, int ascentM, int flatDkmh, int vamMph) {
        double hours = km / (flatDkmh / 10.0) + ascentM * CLIMB_FACTOR / vamMph;
        return (int) Math.round(hours * 3600);
    }

    /** Total ascent of an elevation series, ignoring wiggles below the noise hysteresis. */
    public static int ascentMeters(double[] elevations) {
        if (elevations == null) return 0;
        double gain = 0;
        double ref = Double.NaN;
        for (double e : elevations) {
            if (Double.isNaN(e) || Double.isInfinite(e)) continue;
            if (Double.isNaN(ref)) {
                ref = e;
            } else if (e - ref >= ASCENT_HYSTERESIS_M) {
                gain += e - ref;
                ref = e;
            } else if (ref - e >= ASCENT_HYSTERESIS_M) {
                ref = e;
            }
        }
        return (int) Math.round(gain);
    }

    /**
     * The {@code count} best days in {@code [firstDay, firstDay + horizonDays)}: most riders
     * available first, earliest date on ties. Per day the daypart with the most riders wins
     * (morning before afternoon before evening on ties). Riders without a known schedule count
     * as always available.
     */
    public static List<DateOption> proposeDates(List<GroupRideParticipant> participants,
                                                LocalDate firstDay, int horizonDays, int count) {
        List<GroupRideParticipant> ps = participants == null
                ? new ArrayList<>() : participants;
        List<DateOption> days = new ArrayList<>();
        for (int i = 0; i < Math.max(0, horizonDays); i++) {
            LocalDate d = firstDay.plusDays(i);
            int dow = d.getDayOfWeek().getValue() - 1;
            DateOption best = null;
            for (int part : PARTS) {
                List<String> missing = new ArrayList<>();
                int ok = 0;
                for (GroupRideParticipant p : ps) {
                    if (p.ridesOn(dow) && p.ridesIn(part)) ok++;
                    else missing.add(p.name);
                }
                if (best == null || ok > best.available) {
                    best = new DateOption(d, part, ok, ps.size(), missing);
                }
            }
            days.add(best);
        }
        Collections.sort(days, Comparator.comparingInt((DateOption o) -> -o.available)
                .thenComparing(o -> o.date));
        return new ArrayList<>(days.subList(0, Math.min(Math.max(0, count), days.size())));
    }

    /** "za 10 okt" — fixed Dutch abbreviations, independent of the JVM's locale data. */
    public static String formatDate(LocalDate d) {
        return DAY_SHORT[d.getDayOfWeek().getValue() - 1] + " " + d.getDayOfMonth() + " "
                + MONTH_SHORT[d.getMonthValue() - 1];
    }

    public static String daypartName(int part) {
        if (part == RideBuddyProfile.PART_AFTERNOON) return "middag";
        if (part == RideBuddyProfile.PART_EVENING) return "avond";
        return "ochtend";
    }

    public static String kmh(double kmh) {
        return String.format(NL, "%.1f", kmh);
    }

    /** The proposal as plain Dutch text, ready for {@code ACTION_SEND}. */
    public static String shareText(String routeName, double distanceM, int ascentM,
                                   List<GroupRideParticipant> participants, Estimate e,
                                   List<DateOption> dates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Groepsrit: ").append(routeName == null || routeName.trim().isEmpty()
                ? "route" : routeName.trim()).append('\n');
        sb.append(String.format(NL, "%.1f km", distanceM / 1000.0)).append(" · ")
          .append(Math.max(0, ascentM)).append(" hm\n");
        sb.append("Verwacht groepstempo: ").append(kmh(e.avgSpeedKmh)).append(" km/u gemiddeld (")
          .append(kmh(e.groupFlatDkmh / 10.0)).append(" km/u op vlak)\n");
        sb.append("Rijtijd: ").append(hm(e.movingSec));
        if (e.pauseSec > 0) sb.append(" — met pauzes ca. ").append(hm(e.totalSec()));
        sb.append('\n');
        if (participants != null && !participants.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (GroupRideParticipant p : participants) names.add(p.name);
            sb.append("Deelnemers: ").append(String.join(", ", names)).append('\n');
        }
        if (dates != null && !dates.isEmpty()) {
            sb.append("\nDatumvoorstellen:\n");
            int i = 1;
            for (DateOption o : dates) {
                sb.append(i++).append(". ").append(formatDate(o.date)).append(", ")
                  .append(daypartName(o.daypart));
                if (o.total > 0) {
                    sb.append(" (").append(o.available).append('/').append(o.total)
                      .append(" kunnen)");
                }
                sb.append('\n');
            }
            sb.append("\nLaat weten welke datum jou past!\n");
        }
        sb.append("Gepland met ClimbPro");
        return sb.toString();
    }

    /** "3:07 u" — hours and minutes, rounded to the minute. */
    public static String hm(int sec) {
        int minutes = Math.round(Math.max(0, sec) / 60f);
        String hms = DurationFormat.format(minutes * 60);
        // DurationFormat gives h:mm:ss (or m:ss under an hour); keep hours:minutes.
        if (minutes >= 60) return hms.substring(0, hms.length() - 3) + " u";
        return minutes + " min";
    }
}
