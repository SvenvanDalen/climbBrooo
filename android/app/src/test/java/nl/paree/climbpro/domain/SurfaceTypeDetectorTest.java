package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.domain.segment.SurfaceTypeDetector;
import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceTypeDetectorTest {

    // ---- GPX detection ----

    @Test
    public void detectsGarminRoadCycling() {
        byte[] gpx = "<trk><type>road_cycling</type></trk>".getBytes();
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsGarminMountainBiking() {
        byte[] gpx = "<trk><type>mountain_biking</type></trk>".getBytes();
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootGravel() {
        byte[] gpx = "<komoot:meta sport=\"gravel\"/>".getBytes();
        assertEquals(SurfaceType.GRAVEL, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootMtb() {
        byte[] gpx = "<komoot:meta sport=\"mtb\"/>".getBytes();
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootRacebike() {
        byte[] gpx = "<komoot:meta sport=\"racebike\"/>".getBytes();
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootTouringbicycle() {
        byte[] gpx = "<komoot:meta sport=\"touringbicycle\"/>".getBytes();
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsRideWithGpsGravel() {
        byte[] gpx = "<type>gravel</type>".getBytes();
        assertEquals(SurfaceType.GRAVEL, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void unknownGpxReturnsUnknown() {
        byte[] gpx = "<gpx><trk><name>My route</name></trk></gpx>".getBytes();
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void nullGpxBytesReturnsUnknown() {
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromGpxBytes(null));
    }

    // ---- Strava sub_type detection ----

    @Test
    public void stravaSubType1Road() {
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromStravaSubType(1));
    }

    @Test
    public void stravaSubType2MountainBike() {
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromStravaSubType(2));
    }

    @Test
    public void stravaSubType3Cross() {
        assertEquals(SurfaceType.GRAVEL, SurfaceTypeDetector.detectFromStravaSubType(3));
    }

    @Test
    public void stravaSubType4Trail() {
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromStravaSubType(4));
    }

    @Test
    public void stravaSubType5Mixed() {
        assertEquals(SurfaceType.MIXED, SurfaceTypeDetector.detectFromStravaSubType(5));
    }

    @Test
    public void stravaSubType0AndUnknownReturnUnknown() {
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromStravaSubType(0));
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromStravaSubType(99));
    }
}
