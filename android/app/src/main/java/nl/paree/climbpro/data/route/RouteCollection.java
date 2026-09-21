package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * User-defined group of existing routes and/or climbs (e.g. "Alpen 2026").
 * Purely organisational, phone-only metadata — never synced to the watch,
 * same as route notes. Persisted as {@code getFilesDir()/collections.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RouteCollection {

    public String id;
    public String name;
    public long createdAtMs;
    public long lastModifiedMs;

    public List<String> routeIds = new ArrayList<>();
    public List<ClimbMembership> climbs = new ArrayList<>();
}
