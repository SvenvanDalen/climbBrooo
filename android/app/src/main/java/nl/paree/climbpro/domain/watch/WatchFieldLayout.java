package nl.paree.climbpro.domain.watch;

import java.util.Arrays;

/**
 * Which value each of the five stat slots on the datafield's "HUIDIGE KLIM" page shows
 * (left, middle, right, row 4, bottom line). The codes are the wire values of the optional
 * top-level 'lay' key (protocol/schema.json) and must match garmin/source/FieldLayout.mc.
 * The default reproduces the screen as it was before the layout became configurable.
 */
public final class WatchFieldLayout {

    public static final int SLOT_COUNT = 5;

    public static final int REM_DIST    = 0;
    public static final int REM_ELEV    = 1;
    public static final int CUR_GRAD    = 2;
    public static final int AVG_GRAD    = 3;
    public static final int VAM         = 4;
    public static final int ETA         = 5;
    public static final int GHOST       = 6;
    public static final int BLOCK       = 7;
    public static final int SPEED       = 8;
    public static final int HEART_RATE  = 9;
    public static final int POWER       = 10;
    public static final int CADENCE     = 11;
    public static final int ELAPSED     = 12;
    public static final int EMPTY       = 13;
    /** Interval block when the climb has one, otherwise VAM (the pre-layout row 4). */
    public static final int AUTO_ROW4   = 14;
    /** "vs PR"/"vs plan" when there is a reference, otherwise ETA (the pre-layout bottom line). */
    public static final int AUTO_BOTTOM = 15;
    public static final int MAX_CODE    = AUTO_BOTTOM;

    private static final int[] DEFAULT_CODES = {REM_DIST, REM_ELEV, CUR_GRAD, AUTO_ROW4, AUTO_BOTTOM};

    private static final String[] SLOT_LABELS = {
            "Links", "Midden", "Rechts", "Regel 4", "Onderste regel"};

    private static final String[] METRIC_LABELS = {
            "Rest-afstand klim",
            "Rest-hoogtemeters",
            "Stijging huidig segment",
            "Gemiddelde stijging klim",
            "VAM huidig segment",
            "ETA tot de top",
            "Tijd t.o.v. PR / plan",
            "Intervalblok",
            "Snelheid",
            "Hartslag",
            "Vermogen",
            "Cadans",
            "Verstreken tijd",
            "Leeg",
            "Automatisch: intervalblok, anders VAM",
            "Automatisch: PR/plan, anders ETA"};

    private final int[] codes;

    private WatchFieldLayout(int[] codes) {
        this.codes = codes;
    }

    public static WatchFieldLayout defaults() {
        return new WatchFieldLayout(DEFAULT_CODES.clone());
    }

    /** Null or wrong length → defaults; an out-of-range element → that slot's default. */
    public static WatchFieldLayout of(int[] codes) {
        if (codes == null || codes.length != SLOT_COUNT) return defaults();
        int[] out = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            out[i] = isValidCode(codes[i]) ? codes[i] : DEFAULT_CODES[i];
        }
        return new WatchFieldLayout(out);
    }

    /** Parses {@link #serialize()} output; anything unparseable falls back like {@link #of}. */
    public static WatchFieldLayout parse(String csv) {
        if (csv == null) return defaults();
        String[] parts = csv.split(",", -1);
        if (parts.length != SLOT_COUNT) return defaults();
        int[] codes = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            try {
                codes[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                codes[i] = -1;
            }
        }
        return of(codes);
    }

    public static boolean isValidCode(int code) {
        return code >= 0 && code <= MAX_CODE;
    }

    /** "0,1,2,14,15" — stored in preferences and folded into the sync hash. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(codes[i]);
        }
        return sb.toString();
    }

    public int code(int slot) {
        return codes[slot];
    }

    public int[] codes() {
        return codes.clone();
    }

    public boolean isDefault() {
        return Arrays.equals(codes, DEFAULT_CODES);
    }

    public WatchFieldLayout withCode(int slot, int code) {
        int[] c = codes.clone();
        c[slot] = code;
        return of(c);
    }

    public static String slotLabel(int slot) {
        return SLOT_LABELS[slot];
    }

    public static String metricLabel(int code) {
        return METRIC_LABELS[code];
    }

    public static int metricCount() {
        return MAX_CODE + 1;
    }
}
