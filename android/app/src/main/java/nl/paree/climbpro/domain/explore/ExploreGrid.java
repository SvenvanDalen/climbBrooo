package nl.paree.climbpro.domain.explore;

import java.util.Set;

/**
 * The fixed grid behind the explore-the-region map (issue #194): the world is cut into cells
 * of roughly {@link #TILE_SIZE_M} by {@link #TILE_SIZE_M} metres, and a ride "explores" every
 * cell its GPS track passes through. Rows are equal slices of latitude; each row's longitude
 * span is widened by {@code 1/cos(lat)} so cells stay roughly square from the equator to
 * Scandinavia. A cell is packed into one {@code long} (row in the high 32 bits, column in the
 * low 32 bits) so a whole region fits in a compact JSON array. Pure Java, phone-only.
 */
public final class ExploreGrid {

    /** Edge of one cell in metres; a stored map with another size is rebuilt. */
    public static final double TILE_SIZE_M = 150.0;

    static final double M_PER_DEG_LAT = 111_320.0;
    static final double TILE_DEG_LAT = TILE_SIZE_M / M_PER_DEG_LAT;

    /**
     * Gaps between consecutive samples longer than this are a pause, a car transfer or a GPS
     * glitch: both ends count, but the straight line between them is not filled in.
     */
    static final double MAX_GAP_M = 1000.0;
    /** Interpolation step, a third of a cell so a diagonal never skips a corner cell. */
    private static final double STEP_M = TILE_SIZE_M / 3.0;

    private ExploreGrid() {}

    /** The cell containing a coordinate. */
    public static long tileOf(double lat, double lon) {
        int row = (int) Math.floor(lat / TILE_DEG_LAT);
        int col = (int) Math.floor(lon / lonSpan(row));
        return pack(row, col);
    }

    /** {south, west, north, east} of a cell in degrees. */
    public static double[] bounds(long tile) {
        int row = (int) (tile >> 32);
        int col = (int) tile;
        double span = lonSpan(row);
        return new double[]{row * TILE_DEG_LAT, col * span,
                (row + 1) * TILE_DEG_LAT, (col + 1) * span};
    }

    /**
     * Adds every cell a track passes through to {@code tiles}, interpolating between samples
     * that are further apart than a third of a cell. Invalid samples (NaN, out of range, the
     * 0/0 placeholder some devices write before a fix) are skipped.
     *
     * @return number of cells that were not in {@code tiles} yet
     */
    public static int addTrack(double[] lat, double[] lon, Set<Long> tiles) {
        if (lat == null || lon == null || lat.length != lon.length) return 0;
        int before = tiles.size();
        double prevLat = Double.NaN;
        double prevLon = Double.NaN;
        for (int i = 0; i < lat.length; i++) {
            double la = lat[i];
            double lo = lon[i];
            if (!isValid(la, lo)) continue;
            if (!Double.isNaN(prevLat)) {
                double d = distanceM(prevLat, prevLon, la, lo);
                if (d <= MAX_GAP_M && d > STEP_M) {
                    int steps = (int) Math.ceil(d / STEP_M);
                    for (int s = 1; s < steps; s++) {
                        double f = (double) s / steps;
                        tiles.add(tileOf(prevLat + (la - prevLat) * f,
                                prevLon + (lo - prevLon) * f));
                    }
                }
            }
            tiles.add(tileOf(la, lo));
            prevLat = la;
            prevLon = lo;
        }
        return tiles.size() - before;
    }

    /**
     * Road length explored, approximated as one cell edge per cell: a road crossing a cell
     * covers about that much of it. Good enough for a "how much did I explore" counter.
     */
    public static double exploredKm(int tileCount) {
        return tileCount * TILE_SIZE_M / 1000.0;
    }

    private static boolean isValid(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (lat < -85 || lat > 85 || lon < -180 || lon > 180) return false;
        return !(lat == 0 && lon == 0);
    }

    private static double lonSpan(int row) {
        double centreLat = (row + 0.5) * TILE_DEG_LAT;
        return TILE_DEG_LAT / Math.max(Math.cos(Math.toRadians(centreLat)), 0.05);
    }

    private static long pack(int row, int col) {
        return ((long) row << 32) | (col & 0xFFFFFFFFL);
    }

    /** Equirectangular distance; exact enough for gaps of at most a few kilometres. */
    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double x = (lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = lat2 - lat1;
        return Math.sqrt(x * x + y * y) * M_PER_DEG_LAT;
    }
}
