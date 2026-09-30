package nl.paree.climbpro.domain.climb;

/**
 * Auto-computed aggregation of a climb's per-segment surface types
 * ({@link nl.paree.climbpro.domain.segment.SurfaceType}) into a single overview label
 * (issue #74 — "Onderscheid asfalt vs. gravel klimmen"). Derived purely from data that
 * already exists per segment; this is a phone-side display aggregation, not new data
 * collection, and is never sent to the watch.
 */
public enum ClimbSurfaceComposition {
    /** Segments are, by distance, overwhelmingly asphalt/cobblestone. */
    PAVED,
    /** Segments are, by distance, overwhelmingly gravel/dirt. */
    GRAVEL,
    /** Neither paved nor unpaved dominates, or segments are explicitly tagged MIXED. */
    MIXED,
    /** No segment carries a known surface type. */
    UNKNOWN
}
