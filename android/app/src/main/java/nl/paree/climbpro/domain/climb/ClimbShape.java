package nl.paree.climbpro.domain.climb;

/**
 * Auto-computed classification of a climb's gradient profile, derived purely from its
 * segment gradients ({@link ClimbShapeClassifier}). This is distinct from user-supplied
 * tags/notes (which stay phone-side per CLAUDE.md) — it is a phone-computed label, not
 * free text, and today it is never sent to the watch (see {@link ClimbShapeClassifier}
 * doc comment for the payload-budget reasoning).
 */
public enum ClimbShape {
    /** Roughly constant gradient throughout — no strong trend, low segment-to-segment variance. */
    STEADY,
    /** Gradient increases notably toward the top compared to the start. */
    STEEP_FINISH,
    /** Gradient decreases notably toward the top compared to the start (starts steep, eases off). */
    EASY_START,
    /** High variance between segments with no clear steady/ramping trend — "grillig". */
    IRREGULAR
}
