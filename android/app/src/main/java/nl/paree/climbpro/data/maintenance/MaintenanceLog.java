package nl.paree.climbpro.data.maintenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object of {@code maintenance.json} (issue #154): the user's tracked components.
 * A missing file starts with {@link #withDefaults()}; an empty list is kept as-is (the user
 * deleted everything) and is not re-seeded.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class MaintenanceLog {
    public int version = 1;
    public List<MaintenanceComponent> components = new ArrayList<>();

    /** Starter set with rough, editable intervals; last-serviced dates unknown until set. */
    public static MaintenanceLog withDefaults() {
        MaintenanceLog log = new MaintenanceLog();
        log.components.add(new MaintenanceComponent("chain", "Ketting", 3000, 0));
        log.components.add(new MaintenanceComponent("tires", "Banden", 4000, 0));
        log.components.add(new MaintenanceComponent("brake_pads", "Remblokken", 2000, 0));
        log.components.add(new MaintenanceComponent("service", "Service", 5000, 12));
        return log;
    }
}
