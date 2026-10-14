package nl.paree.climbpro.domain.poi;

import java.util.Map;

/**
 * The OpenStreetMap feature kinds shown as "Bezienswaardigheden" along a route (issue #208).
 * Declared in match priority: a castle that is also tagged {@code tourism=attraction} reads
 * better as "Kasteel" than as the generic "Attractie".
 */
public enum PoiType {
    CASTLE(OsmKey.HISTORIC, "castle", "Kasteel"),
    RUINS(OsmKey.HISTORIC, "ruins", "Ruïne"),
    MONUMENT(OsmKey.HISTORIC, "monument", "Monument"),
    MEMORIAL(OsmKey.HISTORIC, "memorial", "Gedenkteken"),
    VIEWPOINT(OsmKey.TOURISM, "viewpoint", "Uitzichtpunt"),
    ARTWORK(OsmKey.TOURISM, "artwork", "Kunstwerk"),
    ATTRACTION(OsmKey.TOURISM, "attraction", "Attractie");

    /** OSM tag keys; a holder class because enum constants can't use the enum's own statics. */
    private static final class OsmKey {
        static final String HISTORIC = "historic";
        static final String TOURISM = "tourism";
    }

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
