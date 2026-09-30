package nl.paree.climbpro.domain.bike;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostLog;
import nl.paree.climbpro.data.ride.StoredRide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bike garage rules (issue #187): which bike is active, which one takes indoor rides, which
 * bike an archived ride belongs to, and the one-time migration of the single-bike data.
 *
 * <p><b>Ride → bike</b>, first match wins:
 * <ol>
 *   <li>the ride's Strava gear id equals a bike's {@link Bike#stravaGearId};</li>
 *   <li>a {@code VirtualRide} goes to the indoor bike ({@link #indoorBike});</li>
 *   <li>otherwise the active bike ({@link #activeBike}) — for an outdoor ride a trainer-type
 *       active bike is skipped in favour of the first other bike in use;</li>
 *   <li>an empty garage assigns nothing ({@code null}).</li>
 * </ol>
 *
 * <p>Pure and static, so it is unit-testable on the JVM.
 */
public final class BikeGarage {

    private BikeGarage() {}

    static final String VIRTUAL_RIDE_TYPE = "VirtualRide";
    /** Id prefix of bikes the migration creates; deterministic so a re-run finds them. */
    static final String MIGRATED_ID_PREFIX = "garage-";

    // --- lookups --------------------------------------------------------------------------

    public static Bike find(BikeCostLog log, String id) {
        if (log == null || log.bikes == null || id == null) return null;
        for (Bike b : log.bikes) {
            if (b != null && id.equals(b.id)) return b;
        }
        return null;
    }

    /** The explicitly chosen active bike, else the first bike in use, else the first bike. */
    public static Bike activeBike(BikeCostLog log) {
        Bike chosen = find(log, log != null ? log.activeBikeId : null);
        if (chosen != null) return chosen;
        return firstBike(log, false);
    }

    /** The explicitly chosen indoor bike, else the first trainer-type bike in use, else null. */
    public static Bike indoorBike(BikeCostLog log) {
        Bike chosen = find(log, log != null ? log.indoorBikeId : null);
        if (chosen != null) return chosen;
        if (log == null || log.bikes == null) return null;
        for (Bike b : log.bikes) {
            if (b != null && b.retiredEpochSec <= 0 && isTrainer(b)) return b;
        }
        return null;
    }

    public static boolean isTrainer(Bike b) {
        return b != null && Bike.TYPE_TRAINER.equals(b.type);
    }

    /** Trainer-type bikes always count indoor rides; others only when opted in. */
    public static boolean countsVirtualRides(Bike b, boolean includeVirtualRides) {
        return b != null && (includeVirtualRides || isTrainer(b));
    }

    private static boolean isVirtual(StoredRide r) {
        return VIRTUAL_RIDE_TYPE.equalsIgnoreCase(r.type);
    }

    /** First bike in use (optionally skipping trainers), else the first bike at all. */
    private static Bike firstBike(BikeCostLog log, boolean skipTrainer) {
        if (log == null || log.bikes == null) return null;
        for (Bike b : log.bikes) {
            if (b != null && b.retiredEpochSec <= 0 && !(skipTrainer && isTrainer(b))) return b;
        }
        for (Bike b : log.bikes) {
            if (b != null && !(skipTrainer && isTrainer(b))) return b;
        }
        return null;
    }

    // --- ride assignment ------------------------------------------------------------------

    /** Id of the garage bike this ride was on (see the class doc), or null. */
    public static String bikeIdForRide(StoredRide ride, BikeCostLog log) {
        if (ride == null || log == null || log.bikes == null || log.bikes.isEmpty()) return null;
        if (ride.gearId != null) {
            for (Bike b : log.bikes) {
                if (b != null && ride.gearId.equals(b.stravaGearId)) return b.id;
            }
        }
        if (isVirtual(ride)) {
            Bike indoor = indoorBike(log);
            if (indoor != null) return indoor.id;
            Bike active = activeBike(log);
            return active != null ? active.id : null;
        }
        Bike active = activeBike(log);
        if (active != null && isTrainer(active)) {
            Bike outdoor = firstBike(log, true);
            if (outdoor != null) return outdoor.id;
        }
        return active != null ? active.id : null;
    }

    /** The archived rides assigned to {@code bikeId}, in archive order. */
    public static List<StoredRide> ridesForBike(List<StoredRide> rides, BikeCostLog log,
                                                String bikeId) {
        if (rides == null || bikeId == null) return Collections.emptyList();
        List<StoredRide> out = new ArrayList<>();
        for (StoredRide r : rides) {
            if (r != null && bikeId.equals(bikeIdForRide(r, log))) out.add(r);
        }
        return out;
    }

    /** Per bike id: {@code {rideCount, roundedMeters}} of all assigned rides. */
    public static Map<String, long[]> totalsPerBike(List<StoredRide> rides, BikeCostLog log) {
        Map<String, double[]> acc = new LinkedHashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                String id = bikeIdForRide(r, log);
                if (id == null) continue;
                double[] t = acc.get(id);
                if (t == null) {
                    t = new double[2];
                    acc.put(id, t);
                }
                t[0] += 1;
                if (r.distanceM > 0) t[1] += r.distanceM;
            }
        }
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            out.put(e.getKey(), new long[]{(long) e.getValue()[0], Math.round(e.getValue()[1])});
        }
        return out;
    }

    /** A Strava gear id seen in the ride archive, with how much it was ridden. */
    public static final class GearUsage {
        public final String gearId;
        public final int rides;
        public final long meters;

        GearUsage(String gearId, int rides, long meters) {
            this.gearId = gearId;
            this.rides = rides;
            this.meters = meters;
        }
    }

    /** Distinct gear ids in the archive, most-ridden first — the choices for linking a bike. */
    public static List<GearUsage> gearIdsInArchive(List<StoredRide> rides) {
        Map<String, double[]> acc = new LinkedHashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r == null || r.gearId == null) continue;
                double[] t = acc.get(r.gearId);
                if (t == null) {
                    t = new double[2];
                    acc.put(r.gearId, t);
                }
                t[0] += 1;
                if (r.distanceM > 0) t[1] += r.distanceM;
            }
        }
        List<GearUsage> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            out.add(new GearUsage(e.getKey(), (int) e.getValue()[0],
                    Math.round(e.getValue()[1])));
        }
        out.sort((a, b) -> Integer.compare(b.rides, a.rides));
        return out;
    }

    // --- migration ------------------------------------------------------------------------

    /**
     * One-time move of the single-bike data into the garage; a no-op (false) once
     * {@code log.version >= GARAGE_VERSION}. Lossless: existing cost bikes stay as they are
     * (only a missing type is filled in), passport bikes whose name is not yet in the garage
     * are added, and when the garage is still empty a "Mijn fiets" is created from the rider
     * profile's bike weight / the gear calculator's gearing if either is known. The active
     * bike (first non-trainer bike) takes over that weight and gearing where it has none.
     *
     * @param profileBikeWeightKg the rider profile's bike weight; 0 = not set
     * @param passportNames       names of the bike passports (issue #190)
     * @param chainrings          the gear calculator's saved chainrings; null = never saved
     * @param cassette            the gear calculator's saved cassette; null = never saved
     * @return whether anything besides the version changed (the caller then persists)
     */
    public static boolean migrate(BikeCostLog log, double profileBikeWeightKg,
                                  List<String> passportNames, String chainrings,
                                  String cassette) {
        if (log == null || log.version >= BikeCostLog.GARAGE_VERSION) return false;
        if (log.bikes == null) log.bikes = new ArrayList<>();
        boolean changed = false;

        for (Bike b : log.bikes) {
            if (b != null && (b.type == null || !b.type.equals(cleanType(b.type)))) {
                b.type = cleanType(guessType(b.name));
                changed = true;
            }
        }

        Set<String> names = new HashSet<>();
        for (Bike b : log.bikes) {
            if (b != null && b.name != null) names.add(b.name.trim().toLowerCase(Locale.ROOT));
        }
        int index = 0;
        if (passportNames != null) {
            for (String raw : passportNames) {
                if (raw == null || raw.trim().isEmpty()) continue;
                String name = raw.trim();
                if (!names.add(name.toLowerCase(Locale.ROOT))) continue;
                log.bikes.add(newBike(MIGRATED_ID_PREFIX + "passport-" + index++, name));
                changed = true;
            }
        }

        String rings = clean(chainrings);
        String cogs = clean(cassette);
        if (log.bikes.isEmpty() && (profileBikeWeightKg > 0 || rings != null || cogs != null)) {
            log.bikes.add(newBike(MIGRATED_ID_PREFIX + "profile", "Mijn fiets"));
            changed = true;
        }

        if (!log.bikes.isEmpty() && find(log, log.activeBikeId) == null) {
            Bike first = firstBike(log, true);
            if (first == null) first = firstBike(log, false);
            log.activeBikeId = first.id;
            changed = true;
        }
        Bike active = find(log, log.activeBikeId);
        if (active != null) {
            if (active.weightKg <= 0 && profileBikeWeightKg > 0) {
                active.weightKg = profileBikeWeightKg;
                changed = true;
            }
            if (active.chainrings == null && rings != null) {
                active.chainrings = rings;
                changed = true;
            }
            if (active.cassette == null && cogs != null) {
                active.cassette = cogs;
                changed = true;
            }
        }
        log.version = BikeCostLog.GARAGE_VERSION;
        return changed;
    }

    private static Bike newBike(String id, String name) {
        Bike b = new Bike();
        b.id = id;
        b.name = name;
        b.type = guessType(name);
        return b;
    }

    /** Best guess from a bike's own name; falls back to a road bike. */
    static String guessType(String name) {
        if (name == null) return Bike.TYPE_ROAD;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("gravel")) return Bike.TYPE_GRAVEL;
        if (n.contains("mtb") || n.contains("mountain")) return Bike.TYPE_MTB;
        if (n.contains("trainer") || n.contains("indoor") || n.contains("tacx")
                || n.contains("wahoo kickr")) return Bike.TYPE_TRAINER;
        return Bike.TYPE_ROAD;
    }

    // --- labels ---------------------------------------------------------------------------

    /** Known {@code TYPE_*} value, or {@link Bike#TYPE_ROAD} for anything else. */
    public static String cleanType(String type) {
        if (Bike.TYPE_GRAVEL.equals(type) || Bike.TYPE_MTB.equals(type)
                || Bike.TYPE_TRAINER.equals(type)) {
            return type;
        }
        return Bike.TYPE_ROAD;
    }

    /** The types in the order the UI offers them. */
    public static final String[] TYPES = {
            Bike.TYPE_ROAD, Bike.TYPE_GRAVEL, Bike.TYPE_MTB, Bike.TYPE_TRAINER};

    public static String typeLabel(String type) {
        switch (cleanType(type)) {
            case Bike.TYPE_GRAVEL:  return "Gravel";
            case Bike.TYPE_MTB:     return "Mountainbike";
            case Bike.TYPE_TRAINER: return "Trainer";
            default:                return "Racefiets";
        }
    }

    /** {@code "Gravel · 8,8 kg · 40 mm banden · 40 × 10-44"}; unknown fields are left out. */
    public static String specLine(Bike b) {
        StringBuilder sb = new StringBuilder(typeLabel(b.type));
        if (b.weightKg > 0) sb.append(" · ").append(formatKg(b.weightKg)).append(" kg");
        if (b.tyreWidthMm > 0) sb.append(" · ").append(b.tyreWidthMm).append(" mm banden");
        if (b.chainrings != null || b.cassette != null) {
            sb.append(" · ");
            if (b.chainrings != null) sb.append(b.chainrings);
            if (b.chainrings != null && b.cassette != null) sb.append(" × ");
            if (b.cassette != null) sb.append(b.cassette);
        }
        return sb.toString();
    }

    /** One decimal, Dutch comma, no trailing ",0". */
    public static String formatKg(double kg) {
        String s = String.format(new Locale("nl", "NL"), "%.1f", kg);
        return s.endsWith(",0") ? s.substring(0, s.length() - 2) : s;
    }

    static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
