package nl.paree.climbpro.ui.maintenance;

import android.content.Context;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.maintenance.MaintenanceLog;
import nl.paree.climbpro.data.maintenance.MaintenanceRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.domain.maintenance.MaintenanceCalculator;
import nl.paree.climbpro.domain.maintenance.MaintenanceCalculator.Status;

import java.time.ZoneId;
import java.util.List;

/**
 * Reads the maintenance log and the ride archive and evaluates every component (issue #154).
 * Does file IO — call off the main thread. Shared by the route-list banner and the tracker
 * screen so both always agree on "due".
 */
public final class MaintenanceStatusLoader {

    private MaintenanceStatusLoader() {}

    /** The log as loaded plus the evaluated status of each component, in list order. */
    public static final class Snapshot {
        public final MaintenanceLog log;
        public final List<Status> statuses;

        Snapshot(MaintenanceLog log, List<Status> statuses) {
            this.log = log;
            this.statuses = statuses;
        }

        /** Banner text for the route list, or null when nothing is due. */
        public String bannerText() {
            return MaintenanceCalculator.bannerText(statuses);
        }
    }

    public static Snapshot load(Context context, long nowEpochSec) {
        MaintenanceLog log = new MaintenanceRepository(context).load();
        // Km only matter once a component has a service date to count from (they are shown
        // even for month-only components, so don't also require a km interval).
        boolean needRides = false;
        for (MaintenanceComponent c : log.components) {
            if (c.lastServicedEpochSec > 0) {
                needRides = true;
                break;
            }
        }
        List<Status> statuses = MaintenanceCalculator.evaluateAll(log.components,
                needRides ? new RideRepository(context).loadAll() : null,
                nowEpochSec, ZoneId.systemDefault());
        return new Snapshot(log, statuses);
    }
}
