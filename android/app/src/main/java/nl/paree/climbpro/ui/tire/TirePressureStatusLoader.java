package nl.paree.climbpro.ui.tire;

import android.content.Context;

import nl.paree.climbpro.data.bike.BikeCostLog;
import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.tire.TirePressureLog;
import nl.paree.climbpro.data.tire.TirePressureLogRepository;
import nl.paree.climbpro.domain.bike.BikeGarage;
import nl.paree.climbpro.domain.tire.TirePressureReminderCalculator;
import nl.paree.climbpro.domain.tire.TirePressureReminderCalculator.Status;

import java.util.List;

/**
 * Reads the tire-pressure log and the ride archive and evaluates the reminder (issue #155).
 * Does file IO — call off the main thread. Shared by the main-screen banner and the log screen
 * so both always agree on "due". When the log is linked to a garage bike (issue #187), only
 * that bike's rides count towards the km reminder.
 */
public final class TirePressureStatusLoader {

    private TirePressureStatusLoader() {}

    /** The log as loaded plus its evaluated reminder status. */
    public static final class Snapshot {
        public final TirePressureLog log;
        public final Status status;
        /** The bike garage, for the bike picker and label. */
        public final BikeCostLog garage;

        Snapshot(TirePressureLog log, Status status, BikeCostLog garage) {
            this.log = log;
            this.status = status;
            this.garage = garage;
        }

        /** Banner text for the main screen, or null when no check is due. */
        public String bannerText() {
            return TirePressureReminderCalculator.bannerText(status);
        }
    }

    public static Snapshot load(Context context, long nowEpochSec) {
        TirePressureLog log = new TirePressureLogRepository(context).load();
        BikeCostLog garage = new BikeCostRepository(context).load();
        // The ride archive is only needed for the km criterion and once there is a check to
        // count from; skip reading it otherwise.
        boolean needRides = log.reminderKm > 0 && !log.entries.isEmpty();
        List<StoredRide> rides = needRides ? new RideRepository(context).loadAll() : null;
        if (rides != null && BikeGarage.find(garage, log.bikeId) != null) {
            rides = BikeGarage.ridesForBike(rides, garage, log.bikeId);
        }
        Status status = TirePressureReminderCalculator.evaluate(log.entries, rides,
                log.reminderDays, log.reminderKm, nowEpochSec);
        return new Snapshot(log, status, garage);
    }
}
