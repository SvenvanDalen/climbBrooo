package nl.paree.climbpro.domain.weather;

import java.time.Instant;

/**
 * Weather filter for radius mode (issue #12): which climb starts are dry right now. A start
 * counts as wet when the current hour or one of the next {@link #HOURS_AHEAD} hours has at
 * least {@link #WET_MM_PER_HOUR} of precipitation in the Open-Meteo forecast. Unknown data
 * (a missing value, or "now" outside the forecast) counts as dry: the filter may only ever
 * leave climbs out on evidence, never because the weather service was vague. Pure.
 */
public final class DryClimbFilter {

    private DryClimbFilter() {}

    /** Hours after the current one that must stay dry too (the time to get there). */
    public static final int HOURS_AHEAD = 2;
    /** Less than this per hour is drizzle at most; the road stays rideable. */
    public static final double WET_MM_PER_HOUR = 0.3;
    /** Starts checked per sync (closest first); one Open-Meteo request covers them all. */
    public static final int MAX_LOCATIONS = 40;

    /** One flag per location in {@code grid}: true = dry or unknown. */
    public static boolean[] dryFlags(PrecipitationGrid grid, Instant now) {
        boolean[] dry = new boolean[grid.mm.length];
        int from = grid.indexAt(now);
        for (int l = 0; l < dry.length; l++) {
            dry[l] = true;
            if (from < 0) continue;
            for (int h = from; h <= from + HOURS_AHEAD && h < grid.mm[l].length; h++) {
                double mm = grid.mm[l][h];
                if (!Double.isNaN(mm) && mm >= WET_MM_PER_HOUR) {
                    dry[l] = false;
                    break;
                }
            }
        }
        return dry;
    }
}
