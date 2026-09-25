package nl.paree.climbpro.data.maintenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/** Root object of {@code torque_values.json} (issue #237): the rider's own torque values. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TorqueValueLog {
    /** Insertion order; the UI sorts with {@code TorqueReference.sorted}. */
    public List<TorqueValue> values = new ArrayList<>();
}
