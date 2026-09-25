package nl.paree.climbpro.domain.weather;

import java.util.ArrayList;
import java.util.List;

/**
 * Web-Mercator ("slippy map") tiles that cover a route for the rain radar (issue #245). Pure.
 * RainViewer's free API only serves zoom ≤ 7; higher zooms return a "Zoom Level Not Supported"
 * image, so the app downloads z≤7 tiles itself and stretches them over the map.
 */
public final class RadarTiles {

    public static final int MAX_ZOOM = 7;
    public static final int MAX_TILES = 16;
    /** Degrees of padding around the route so approaching rain is visible too. */
    static final double PAD_DEG = 0.3;
    private static final double MAX_LAT = 85.05112878;

    public static final class Tile {
        public final int z;
        public final int x;
        public final int y;
        public final double north;
        public final double south;
        public final double west;
        public final double east;

        Tile(int z, int x, int y) {
            this.z = z;
            this.x = x;
            this.y = y;
            this.north = latOf(y, z);
            this.south = latOf(y + 1, z);
            this.west = lonOf(x, z);
            this.east = lonOf(x + 1, z);
        }
    }

    private RadarTiles() {}

    public static int tileX(double lon, int z) {
        int n = 1 << z;
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        return Math.max(0, Math.min(n - 1, x));
    }

    public static int tileY(double lat, int z) {
        int n = 1 << z;
        double la = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
        int y = (int) Math.floor(
                (1 - Math.log(Math.tan(la) + 1 / Math.cos(la)) / Math.PI) / 2 * n);
        return Math.max(0, Math.min(n - 1, y));
    }

    static double lonOf(int x, int z) { return x / (double) (1 << z) * 360.0 - 180.0; }

    static double latOf(int y, int z) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / (1 << z)))));
    }

    /** All tiles of the highest zoom ≤ maxZoom whose count for the box is ≤ maxTiles. */
    public static List<Tile> covering(double south, double north, double west, double east,
                                      int maxZoom, int maxTiles) {
        int z = Math.max(0, Math.min(MAX_ZOOM, maxZoom));
        while (z > 0 && count(south, north, west, east, z) > maxTiles) z--;
        List<Tile> out = new ArrayList<>();
        for (int y = tileY(north, z); y <= tileY(south, z); y++) {
            for (int x = tileX(west, z); x <= tileX(east, z); x++) out.add(new Tile(z, x, y));
        }
        return out;
    }

    public static List<Tile> forRoute(double[] lats, double[] lons) {
        if (lats == null || lons == null) return new ArrayList<>();
        int n = Math.min(lats.length, lons.length);
        if (n == 0) return new ArrayList<>();
        double s = lats[0], no = lats[0], w = lons[0], e = lons[0];
        for (int i = 1; i < n; i++) {
            s = Math.min(s, lats[i]);
            no = Math.max(no, lats[i]);
            w = Math.min(w, lons[i]);
            e = Math.max(e, lons[i]);
        }
        return covering(s - PAD_DEG, no + PAD_DEG, w - PAD_DEG, e + PAD_DEG, MAX_ZOOM, MAX_TILES);
    }

    private static long count(double south, double north, double west, double east, int z) {
        long cols = tileX(east, z) - tileX(west, z) + 1L;
        long rows = tileY(south, z) - tileY(north, z) + 1L;
        return cols * rows;
    }
}
