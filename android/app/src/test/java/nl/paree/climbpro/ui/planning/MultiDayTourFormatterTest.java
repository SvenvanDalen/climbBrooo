package nl.paree.climbpro.ui.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import nl.paree.climbpro.domain.planning.MultiDayTourPlan;
import nl.paree.climbpro.domain.planning.MultiDayTourPlanner;
import nl.paree.climbpro.domain.planning.TourStop;

public class MultiDayTourFormatterTest {

    private static TourStop at(String key, double lon, int hm) {
        return new TourStop(key, "Klim " + key, 45.0, lon, 45.0, lon, hm, 5000, 900);
    }

    private static MultiDayTourPlan plan(int days, int maxHm, TourStop... stops) {
        return MultiDayTourPlanner.plan(Arrays.asList(stops), new MultiDayTourPlanner.Request(
                days, maxHm, null, null, MultiDayTourPlanner.BalanceMetric.ELEVATION));
    }

    @Test
    public void shareText_listsDaysAndIsHonestAboutDistances() {
        String text = MultiDayTourFormatter.shareText(
                plan(2, 0, at("a", 6.0, 1200), at("b", 6.1, 800), at("c", 6.2, 400)));
        assertTrue(text.contains("Dag 1"));
        assertTrue(text.contains("Dag 2"));
        assertTrue(text.contains("hemelsbreed"));
        assertTrue(text.contains("Komoot"));
        assertTrue(text.contains("1.200 hm"));
        assertFalse(text.toLowerCase().contains("gpx"));
    }

    @Test
    public void warnings_coverTooFewClimbsAndMaxHm() {
        List<String> few = MultiDayTourFormatter.warnings(plan(4, 0, at("a", 6.0, 500)));
        assertEquals(1, few.size());
        assertTrue(few.get(0).contains("1 dag."));

        List<String> tooMuch = MultiDayTourFormatter.warnings(
                plan(1, 1000, at("a", 6.0, 800), at("b", 6.1, 800)));
        assertEquals(1, tooMuch.size());
        assertTrue(tooMuch.get(0).contains("minstens 2 dagen"));

        MultiDayTourPlan overloaded = plan(1, 1000, at("a", 6.0, 800), at("b", 6.1, 800));
        assertTrue(MultiDayTourFormatter.dayBody(overloaded.days.get(0), 1000)
                .contains("boven je maximum van 1.000 hm"));

        assertTrue(MultiDayTourFormatter.warnings(plan(2, 5000, at("a", 6.0, 800))).size() == 1);
    }
}
