package nl.paree.climbpro.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.segment.CalibrationPoint;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class CalibrationPointTest {

    @Test
    public void constructorStoresFields() {
        CalibrationPoint cp = new CalibrationPoint(500, 51.1234, 5.5678);
        assertEquals(500, cp.distanceFromClimbStart);
        assertEquals(51.1234, cp.lat, 1e-6);
        assertEquals(5.5678, cp.lon, 1e-6);
    }

    @Test
    public void storedCalibrationPointRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StoredCalibrationPoint p = new StoredCalibrationPoint();
        p.distanceFromClimbStart = 300;
        p.lat = 51.9876;
        p.lon = 5.4321;

        String json = mapper.writeValueAsString(p);
        StoredCalibrationPoint back = mapper.readValue(json, StoredCalibrationPoint.class);

        assertEquals(300, back.distanceFromClimbStart);
        assertEquals(51.9876, back.lat, 1e-6);
        assertEquals(5.4321, back.lon, 1e-6);
    }

    @Test
    public void storedCalibrationPointIgnoresUnknownFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"distanceFromClimbStart\":100,\"lat\":52.0,\"lon\":4.0,\"future\":\"ignored\"}";
        StoredCalibrationPoint p = mapper.readValue(json, StoredCalibrationPoint.class);
        assertEquals(100, p.distanceFromClimbStart);
    }

    @Test
    public void climbBuilderAcceptsCalibrationPoints() {
        CalibrationPoint cp = new CalibrationPoint(500, 51.5, 5.0);
        Climb c = Climb.builder()
                .length(1000)
                .elevationGain(50)
                .avgGradient(0.05)
                .segments(Collections.emptyList())
                .calibrationPoints(Collections.singletonList(cp))
                .build();
        assertEquals(1, c.calibrationPoints.size());
        assertEquals(500, c.calibrationPoints.get(0).distanceFromClimbStart);
    }
}
