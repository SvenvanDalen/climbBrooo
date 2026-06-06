package nl.paree.climbpro.domain.segment;

import java.nio.charset.StandardCharsets;

public final class SurfaceTypeDetector {

    private SurfaceTypeDetector() {}

    /**
     * Scans raw GPX bytes for known sport/surface markers.
     * Uses simple string search — no XML parse needed for these single-value tags.
     * Returns UNKNOWN if no recognised marker found.
     */
    public static int detectFromGpxBytes(byte[] gpxBytes) {
        if (gpxBytes == null || gpxBytes.length == 0) return SurfaceType.UNKNOWN;
        String xml = new String(gpxBytes, StandardCharsets.UTF_8).toLowerCase();

        // Komoot meta sport attribute — check before generic <type> to avoid false matches
        if (xml.contains("sport=\"gravel\""))          return SurfaceType.GRAVEL;
        if (xml.contains("sport=\"mtb\""))             return SurfaceType.DIRT;
        if (xml.contains("sport=\"racebike\""))        return SurfaceType.ASPHALT;
        if (xml.contains("sport=\"touringbicycle\""))  return SurfaceType.ASPHALT;

        // Garmin Connect GPX <type> inside <trk>
        if (xml.contains("<type>road_cycling</type>"))    return SurfaceType.ASPHALT;
        if (xml.contains("<type>mountain_biking</type>")) return SurfaceType.DIRT;
        if (xml.contains("<type>cycling</type>"))         return SurfaceType.ASPHALT;

        // Ride with GPS / generic
        if (xml.contains("<type>gravel</type>"))  return SurfaceType.GRAVEL;

        return SurfaceType.UNKNOWN;
    }

    /**
     * Maps a Strava route sub_type integer to a SurfaceType.
     * Strava sub_type: 1=road, 2=mountain bike, 3=cross, 4=trail, 5=mixed.
     */
    public static int detectFromStravaSubType(int subType) {
        switch (subType) {
            case 1: return SurfaceType.ASPHALT;
            case 2: return SurfaceType.DIRT;
            case 3: return SurfaceType.GRAVEL;
            case 4: return SurfaceType.DIRT;
            case 5: return SurfaceType.MIXED;
            default: return SurfaceType.UNKNOWN;
        }
    }
}
