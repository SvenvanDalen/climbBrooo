package nl.paree.climbpro.domain.climb;

/**
 * Suggests a display name for a newly detected climb from its start coordinate
 * (e.g. via reverse geocoding). Backlog #109.
 */
public interface ClimbNameSuggester {

    /**
     * Returns a suggested name for the climb starting at {@code (lat, lon)}, or {@code null}
     * when no suggestion is available (no network, no result, lookup failure). Must never
     * throw — offline / lookup failures are the caller's expected, non-exceptional case.
     */
    String suggestName(double lat, double lon);
}
