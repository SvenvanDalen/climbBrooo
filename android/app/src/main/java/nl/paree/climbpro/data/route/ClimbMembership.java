package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * A single climb inside a collection, referenced by its route id + index within
 * {@code StoredRoute#climbs} — climbs have no stable id of their own, so this mirrors
 * how {@link RouteRepository#renameClimb} already addresses a climb.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ClimbMembership {

    public String routeId;
    public int climbIndex;

    public ClimbMembership() {}

    public ClimbMembership(String routeId, int climbIndex) {
        this.routeId = routeId;
        this.climbIndex = climbIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClimbMembership)) return false;
        ClimbMembership other = (ClimbMembership) o;
        return climbIndex == other.climbIndex && Objects.equals(routeId, other.routeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(routeId, climbIndex);
    }
}
