package nl.paree.climbpro.domain.poi;

import java.util.Map;

/**
 * The OpenStreetMap feature kinds shown as "Bezienswaardigheden" along a route (issue #208).
 * Declared in match priority: a castle that is also tagged {@code tourism=attraction} reads
 * better as "Kasteel" than as the generic "Attractie".
 */
public enum PoiType {
    CASTLE("historic", "castle", "Kasteel"),
    RUINS("historic", "ruins", "Ruïne"),
    MONUMENT("historic", "monument", "Monument"),
    MEMORIAL("historic", "memorial", "Gedenkteken"),
    VIEWPOINT("tourism", "viewpoint", "Uitzichtpunt"),
    ARTWORK("tourism", "artwork", "Kunstwerk"),
    ATTRACTION("tourism", "attraction", "Attractie");

    public final String osmKey;
    public final String osmValue;
    public final String label;

    PoiType(String osmKey, String osmValue, String label) {
        this.osmKey = osmKey;
        this.osmValue = osmValue;
        this.label = label;
    }

    /** The highest-priority type these OSM tags match, or null for none. */
    public static PoiType fromTags(Map<String, String> tags) {
        if (tags == null) return null;
        for (PoiType t : values()) {
            if (t.osmValue.equals(tags.get(t.osmKey))) return t;
        }
        return null;
    }

    /** Lenient lookup by {@link #name()} for cached data; null when unknown. */
    public static PoiType fromName(String name) {
        if (name == null) return null;
        for (PoiType t : values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }
}
