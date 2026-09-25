package nl.paree.climbpro.data.bike;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/** Root object of {@code bike_costs.json} (issue #233). A missing file means no bikes yet. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BikeCostLog {
    public int version = 1;
    public List<Bike> bikes = new ArrayList<>();
}
