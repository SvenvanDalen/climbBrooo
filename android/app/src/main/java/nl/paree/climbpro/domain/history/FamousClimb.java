package nl.paree.climbpro.domain.history;

import java.util.Collections;
import java.util.List;

/**
 * One entry of the bundled famous-climb dataset ({@code assets/climb_facts.json}, issue #212):
 * a well-known climb with its top, the usual sides (start points) and a few short facts.
 * Immutable; built by {@link ClimbFactsParser}.
 */
public final class FamousClimb {

    /** One way up: a named start point. */
    public static final class Side {
        public final String label;
        public final double lat;
        public final double lon;

        public Side(String label, double lat, double lon) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public final String id;
    public final String name;
    /** Normalised name fragments (see {@link FamousClimbMatcher#normalize}) used for the name fallback. */
    public final List<String> aliases;
    public final double topLat;
    public final double topLon;
    public final List<Side> sides;
    public final List<String> facts;

    public FamousClimb(String id, String name, List<String> aliases, double topLat, double topLon,
                       List<Side> sides, List<String> facts) {
        this.id = id;
        this.name = name;
        this.aliases = Collections.unmodifiableList(aliases);
        this.topLat = topLat;
        this.topLon = topLon;
        this.sides = Collections.unmodifiableList(sides);
        this.facts = Collections.unmodifiableList(facts);
    }
}
