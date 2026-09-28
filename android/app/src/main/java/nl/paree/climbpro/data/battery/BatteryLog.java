package nl.paree.climbpro.data.battery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/** Root object of {@code battery_status.json} (issue #238): the tracked devices. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatteryLog {
    /** Devices in insertion order. */
    public List<BatteryDevice> devices = new ArrayList<>();
}
