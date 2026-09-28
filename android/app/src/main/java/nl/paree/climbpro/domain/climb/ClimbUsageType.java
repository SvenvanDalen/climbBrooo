package nl.paree.climbpro.domain.climb;

/**
 * Auto-computed classification of how a climb is actually ridden, derived purely from ride
 * frequency and location signals already in the logbook ({@link ClimbUsageClassifier}) — issue
 * #44. This is distinct from user-supplied tags/notes (which stay phone-side per CLAUDE.md) and
 * is never sent to the watch: it exists for phone-side filtering/statistics (e.g. climb
 * collections) and to scope down which climbs get "disturbing" notifications.
 */
public enum ClimbUsageType {
    /** Ridden often (see {@link ClimbConstants#USAGE_TRAINING_MIN_ATTEMPTS}) — a repeat, local climb. */
    TRAINING,
    /** Ridden exactly once, and not near any other climb the rider rides often — a one-off, far climb. */
    RECREATIONAL,
    /** Not enough signal (no attempts, or a single ascent near a frequently-ridden cluster) to call it either way. */
    UNKNOWN
}
