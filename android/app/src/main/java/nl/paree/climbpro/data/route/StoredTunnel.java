package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A road tunnel on the route (issue #203), from OpenStreetMap, as integer metres from the route
 * start. Derived from the route geometry, so it is only carried across a resync while the
 * geometry ({@code sourceHash}) is unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredTunnel {
    public int startDistance;
    public int endDistance;

    public StoredTunnel() {}

    public StoredTunnel(int startDistance, int endDistance) {
        this.startDistance = startDistance;
        this.endDistance = endDistance;
    }
}
