package nl.paree.climbpro.domain.bike;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostEntry;
import nl.paree.climbpro.data.ride.StoredRide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cost per kilometre per bike (issue #233).
 *
 * <p>Km = archived rides ({@code rides.json}, issue #160) that started in
 * {@code [sinceEpochSec, retiredEpochSec)} — only when the bike counts archive rides and has a
 * since-date — plus the manual {@link Bike#extraKm}. {@code VirtualRide} is excluded unless
 * opted in; rides with an unknown start are skipped. Costs are integer cents; the rate is
 * {@code round_half_up(totalCents * 1000 / meters)} cents per km and is not given below
 * {@link #MIN_METERS_FOR_RATE}, so a bike without km never divides by zero.
 *
 * <p>Pure and static, so it is unit-testable on the JVM.
 */
public final class BikeCostCalculator {

    private BikeCostCalculator() {}

    static final String VIRTUAL_RIDE_TYPE = "VirtualRide";
    public static final long MIN_METERS_FOR_RATE = 1000L;
    public static final long NO_RATE = -1L;

    /** Evaluated cost overview of one bike. */
    public static final class Summary {
        public final Bike bike;
        public final long archiveMeters;
        public final long totalMeters;
        public final long purchaseCents;
        public final long partsCents;
        public final long totalCents;
        /** Cents per km, or {@link #NO_RATE} below 1 km. */
        public final long costPerKmCents;

        Summary(Bike bike, long archiveMeters, long totalMeters, long purchaseCents,
                long partsCents, long costPerKmCents) {
            this.bike           = bike;
            this.archiveMeters  = archiveMeters;
            this.totalMeters    = totalMeters;
            this.purchaseCents  = purchaseCents;
            this.partsCents     = partsCents;
            this.totalCents     = purchaseCents + partsCents;
            this.costPerKmCents = costPerKmCents;
        }

        public boolean hasRate() { return costPerKmCents != NO_RATE; }
    }

    public static Summary evaluate(Bike bike, List<StoredRide> rides) {
        long archive = 0;
        if (bike.countArchiveRides && bike.sinceEpochSec > 0) {
            archive = archiveMeters(rides, bike.sinceEpochSec, bike.retiredEpochSec,
                    bike.includeVirtualRides);
        }
        long total = archive + Math.max(0, bike.extraKm) * 1000L;

        long purchase = 0;
        long parts = 0;
        if (bike.costs != null) {
            for (BikeCostEntry c : bike.costs) {
                if (c == null || c.amountCents <= 0) continue;
                if (BikeCostEntry.KIND_PURCHASE.equals(c.kind)) purchase += c.amountCents;
                else parts += c.amountCents;
            }
        }
        return new Summary(bike, archive, total, purchase, parts,
                costPerKmCents(purchase + parts, total));
    }

    public static List<Summary> evaluateAll(List<Bike> bikes, List<StoredRide> rides) {
        if (bikes == null) return Collections.emptyList();
        List<Summary> out = new ArrayList<>(bikes.size());
        for (Bike b : bikes) {
            if (b != null) out.add(evaluate(b, rides));
        }
        return out;
    }

    /** Metres of rides started in {@code [since, until)}; {@code until == 0} = open-ended. */
    public static long archiveMeters(List<StoredRide> rides, long sinceEpochSec,
                                     long untilEpochSec, boolean includeVirtual) {
        if (rides == null || sinceEpochSec <= 0) return 0;
        double meters = 0;
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0 || r.startEpochSec < sinceEpochSec) continue;
            if (untilEpochSec > 0 && r.startEpochSec >= untilEpochSec) continue;
            if (!includeVirtual && VIRTUAL_RIDE_TYPE.equalsIgnoreCase(r.type)) continue;
            if (r.distanceM > 0) meters += r.distanceM;
        }
        return Math.round(meters);
    }

    /** Cents per km rounded half up, or {@link #NO_RATE} below {@link #MIN_METERS_FOR_RATE}. */
    public static long costPerKmCents(long totalCents, long meters) {
        if (meters < MIN_METERS_FOR_RATE || totalCents < 0) return NO_RATE;
        return (totalCents * 1000L + meters / 2) / meters;
    }

    /** Whole km, truncated (not rounded) so it agrees with {@link #rateText}: {@code "1.234 km"}. */
    public static String kmText(long meters) {
        return EuroAmount.groupThousands(Math.max(0, meters) / 1000) + " km";
    }

    public static String rateText(Summary s) {
        if (!s.hasRate()) return "Nog geen km: kosten per km onbekend";
        if (s.totalCents == 0) return "Nog geen kosten ingevoerd";
        // Costs exist but the rate rounds to 0 cents/km: say so rather than showing € 0,00.
        if (s.costPerKmCents == 0) return "< € 0,01 per km";
        return EuroAmount.format(s.costPerKmCents) + " per km";
    }

    /** {@code "Aankoop € 1.999,00  •  Onderdelen € 74,50"}. */
    public static String breakdownText(Summary s) {
        return "Aankoop " + EuroAmount.format(s.purchaseCents)
                + "  •  Onderdelen " + EuroAmount.format(s.partsCents);
    }
}
