package nl.paree.climbpro.domain.power;

import java.util.Locale;

/** Formats a duration in seconds as "m:ss" or "h:mm:ss". */
public final class DurationFormat {

    private DurationFormat() {}

    public static String format(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
