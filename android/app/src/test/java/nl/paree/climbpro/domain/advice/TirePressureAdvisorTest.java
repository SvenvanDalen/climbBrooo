package nl.paree.climbpro.domain.advice;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TirePressureAdvisorTest {

    @Test
    public void allAsphalt_suggestsHigherPressure() {
        StoredRoute route = routeWithOneClimb(
                segment(1000, SurfaceType.ASPHALT), segment(1000, SurfaceType.ASPHALT));

        TirePressureAdvice advice = TirePressureAdvisor.advise(route);

        assertEquals(TirePressureConstants.ASPHALT_MIN_PSI, advice.minPsi);
        assertEquals(TirePressureConstants.ASPHALT_MAX_PSI, advice.maxPsi);
        assertTrue(advice.rationale.toLowerCase().contains("asfalt"));
    }

    @Test
    public void allGravelAndDirt_suggestsLowerPressure() {
        StoredRoute route = routeWithOneClimb(
                segment(1000, SurfaceType.GRAVEL), segment(1000, SurfaceType.DIRT));

        TirePressureAdvice advice = TirePressureAdvisor.advise(route);

        assertEquals(TirePressureConstants.OFFROAD_MIN_PSI, advice.minPsi);
        assertEquals(TirePressureConstants.OFFROAD_MAX_PSI, advice.maxPsi);
    }

    @Test
    public void mixedSurfaces_suggestsMiddleGroundPressure() {
        // 50/50 asphalt/gravel: meaningful off-road present, but not dominant enough to
        // clear the SIGNIFICANT_OFFROAD_MIN_FRACTION threshold.
        StoredRoute route = routeWithOneClimb(
                segment(1000, SurfaceType.ASPHALT), segment(1000, SurfaceType.GRAVEL));

        TirePressureAdvice advice = TirePressureAdvisor.advise(route);

        assertEquals(TirePressureConstants.MIXED_MIN_PSI, advice.minPsi);
        assertEquals(TirePressureConstants.MIXED_MAX_PSI, advice.maxPsi);
    }

    @Test
    public void noSurfaceData_suggestsGenericDefault_withoutCrashing() {
        StoredRoute route = routeWithOneClimb(
                segment(1000, SurfaceType.UNKNOWN), segment(1000, SurfaceType.UNKNOWN));

        TirePressureAdvice advice = TirePressureAdvisor.advise(route);

        assertEquals(TirePressureConstants.DEFAULT_MIN_PSI, advice.minPsi);
        assertEquals(TirePressureConstants.DEFAULT_MAX_PSI, advice.maxPsi);
    }

    @Test
    public void noClimbsAtAll_suggestsGenericDefault_withoutCrashing() {
        StoredRoute route = new StoredRoute();
        route.climbs = null;
        route.surfaceSections = null;

        TirePressureAdvice advice = TirePressureAdvisor.advise(route);

        assertEquals(TirePressureConstants.DEFAULT_MIN_PSI, advice.minPsi);
        assertEquals(TirePressureConstants.DEFAULT_MAX_PSI, advice.maxPsi);
    }

    @Test
    public void nullRoute_suggestsGenericDefault_withoutCrashing() {
        TirePressureAdvice advice = TirePressureAdvisor.advise(null);

        assertEquals(TirePressureConstants.DEFAULT_MIN_PSI, advice.minPsi);
        assertEquals(TirePressureConstants.DEFAULT_MAX_PSI, advice.maxPsi);
    }

    @Test
    public void steepClimbOnGravel_lowersPressureFurtherThanGravelAlone() {
        StoredClimb flatGravel = climb(0.03,
                segment(1000, SurfaceType.GRAVEL), segment(1000, SurfaceType.DIRT));
        StoredClimb steepGravel = climb(0.11,
                segment(1000, SurfaceType.GRAVEL), segment(1000, SurfaceType.DIRT));

        StoredRoute flatRoute = new StoredRoute();
        flatRoute.climbs = new ArrayList<>();
        flatRoute.climbs.add(flatGravel);

        StoredRoute steepRoute = new StoredRoute();
        steepRoute.climbs = new ArrayList<>();
        steepRoute.climbs.add(steepGravel);

        TirePressureAdvice flatAdvice = TirePressureAdvisor.advise(flatRoute);
        TirePressureAdvice steepAdvice = TirePressureAdvisor.advise(steepRoute);

        assertEquals(TirePressureConstants.OFFROAD_MIN_PSI, flatAdvice.minPsi);
        assertEquals(TirePressureConstants.OFFROAD_MAX_PSI, flatAdvice.maxPsi);

        assertEquals(TirePressureConstants.OFFROAD_MIN_PSI
                        - TirePressureConstants.STEEP_OFFROAD_ADJUSTMENT_PSI,
                steepAdvice.minPsi);
        assertEquals(TirePressureConstants.OFFROAD_MAX_PSI
                        - TirePressureConstants.STEEP_OFFROAD_ADJUSTMENT_PSI,
                steepAdvice.maxPsi);
        assertTrue(steepAdvice.rationale.contains("11%"));
    }

    @Test
    public void steepClimbAdjustment_neverGoesBelowMinSensiblePsi() {
        StoredClimb steepGravel = climb(0.20,
                segment(1000, SurfaceType.GRAVEL));
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>();
        route.climbs.add(steepGravel);

        TirePressureAdvice advice = TirePressureAdvisor.advise(route);

        assertTrue(advice.minPsi >= TirePressureConstants.MIN_SENSIBLE_PSI);
    }

    @Test
    public void barConversion_isRoughlyPsiOverFourteenPointFive() {
        TirePressureAdvice advice = new TirePressureAdvice(87, 87, "");
        assertEquals(6.0, advice.minBar(), 0.05);
    }

    private static StoredRoute routeWithOneClimb(StoredSegment... segments) {
        StoredRoute route = new StoredRoute();
        route.climbs = new ArrayList<>();
        route.climbs.add(climb(0.05, segments));
        return route;
    }

    private static StoredClimb climb(double avgGradient, StoredSegment... segments) {
        StoredClimb climb = new StoredClimb();
        climb.avgGradient = avgGradient;
        climb.segments = new ArrayList<>();
        List<StoredSegment> segs = climb.segments;
        for (StoredSegment s : segments) segs.add(s);
        return climb;
    }

    private static StoredSegment segment(int distance, int surfaceType) {
        StoredSegment s = new StoredSegment();
        s.distance = distance;
        s.surfaceType = surfaceType;
        return s;
    }
}
