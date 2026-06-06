package nl.paree.climbpro.domain.segment;

public final class SurfaceType {

    private SurfaceType() {}

    public static final int ASPHALT     = 0;
    public static final int GRAVEL      = 1;
    public static final int DIRT        = 2;
    public static final int COBBLESTONE = 3;
    public static final int MIXED       = 4;
    public static final int UNKNOWN     = 5;

    private static final String[] LABELS = {"A", "G", "D", "K", "M", null};

    /** Returns UNKNOWN for any value outside 0–5. */
    public static int fromInt(int v) {
        return (v >= 0 && v <= 5) ? v : UNKNOWN;
    }

    /** Returns the single-character watch label, or null for UNKNOWN. */
    public static String label(int v) {
        if (v < 0 || v >= LABELS.length) return null;
        return LABELS[v];
    }
}
