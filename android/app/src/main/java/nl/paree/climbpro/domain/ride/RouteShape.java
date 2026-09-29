package nl.paree.climbpro.domain.ride;

/**
 * Fits a GPS track into a box for drawing its shape without map tiles (issue #193 ride story).
 * Equirectangular with the longitude scaled by cos(latitude), aspect ratio kept, centred. Pure.
 */
public final class RouteShape {

    /** Tracks are thinned to at most this many points; plenty for a share image. */
    static final int MAX_POINTS = 600;

    private RouteShape() {}

    /**
     * Track {@code lat}/{@code lon} in degrees, index-aligned.
     * @return x0,y0,x1,y1,... in box pixels (y down, north up); empty when fewer than 2 points
     */
    public static float[] fit(double[] lat, double[] lon, float width, float height) {
        if (lat == null || lon == null || lat.length != lon.length || lat.length < 2) {
            return new float[0];
        }
        int step = Math.max(1, (int) Math.ceil(lat.length / (double) MAX_POINTS));
        int n = (lat.length - 1) / step + 1;
        boolean lastIncluded = (lat.length - 1) % step == 0;
        if (!lastIncluded) n++;

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (int i = 0; i < lat.length; i++) {
            minLat = Math.min(minLat, lat[i]);
            maxLat = Math.max(maxLat, lat[i]);
            minLon = Math.min(minLon, lon[i]);
            maxLon = Math.max(maxLon, lon[i]);
        }
        double cos = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        double spanX = Math.max((maxLon - minLon) * cos, 1e-9);
        double spanY = Math.max(maxLat - minLat, 1e-9);
        double scale = Math.min(width / spanX, height / spanY);
        double offX = (width - spanX * scale) / 2;
        double offY = (height - spanY * scale) / 2;

        float[] out = new float[n * 2];
        int k = 0;
        for (int i = 0; i < lat.length; i += step) {
            out[k++] = (float) (offX + (lon[i] - minLon) * cos * scale);
            out[k++] = (float) (offY + (maxLat - lat[i]) * scale);
        }
        if (!lastIncluded) {
            int i = lat.length - 1;
            out[k++] = (float) (offX + (lon[i] - minLon) * cos * scale);
            out[k] = (float) (offY + (maxLat - lat[i]) * scale);
        }
        return out;
    }
}
