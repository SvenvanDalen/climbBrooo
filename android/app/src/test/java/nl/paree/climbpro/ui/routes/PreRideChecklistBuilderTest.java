package nl.paree.climbpro.ui.routes;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class PreRideChecklistBuilderTest {

    @Test
    public void alwaysIncludesBatteryReminder() {
        List<PreRideChecklistItem> items =
                PreRideChecklistBuilder.build(new RoutePassport(0, 0, null, 0.0, -1));
        assertTrue(items.stream().anyMatch(i -> i.type == PreRideChecklistItem.Type.BATTERY));
    }

    @Test
    public void durationItemShowsFormattedEstimateWhenAvailable() {
        RoutePassport passport = new RoutePassport(2, 300, "Keutenberg", 0.094, 3725);
        List<PreRideChecklistItem> items = PreRideChecklistBuilder.build(passport);

        PreRideChecklistItem duration = find(items, PreRideChecklistItem.Type.DURATION);
        assertNotNull(duration);
        assertTrue(duration.message.contains("1:02:05"));
    }

    @Test
    public void durationItemFallsBackToProfileHintWhenEstimateMissing() {
        RoutePassport passport = new RoutePassport(2, 300, "Keutenberg", 0.094, -1);
        List<PreRideChecklistItem> items = PreRideChecklistBuilder.build(passport);

        PreRideChecklistItem duration = find(items, PreRideChecklistItem.Type.DURATION);
        assertNotNull(duration);
        assertTrue(duration.message.toLowerCase().contains("rijdersprofiel"));
    }

    @Test
    public void climbsItemOmittedWhenRouteHasNoClimbs() {
        RoutePassport passport = new RoutePassport(0, 0, null, 0.0, -1);
        List<PreRideChecklistItem> items = PreRideChecklistBuilder.build(passport);

        assertNull(find(items, PreRideChecklistItem.Type.CLIMBS));
    }

    @Test
    public void climbsItemSummarisesCountElevationAndHardestClimb() {
        RoutePassport passport = new RoutePassport(3, 850, "Cauberg", 0.067, 4000);
        List<PreRideChecklistItem> items = PreRideChecklistBuilder.build(passport);

        PreRideChecklistItem climbs = find(items, PreRideChecklistItem.Type.CLIMBS);
        assertNotNull(climbs);
        assertTrue(climbs.message.contains("3 klimmen"));
        assertTrue(climbs.message.contains("850 hm"));
        assertTrue(climbs.message.contains("Cauberg"));
        assertTrue(climbs.message.contains("6.7%"));
    }

    @Test
    public void climbsItemUsesSingularWordingForOneClimb() {
        RoutePassport passport = new RoutePassport(1, 120, "Cauberg", 0.05, 600);
        List<PreRideChecklistItem> items = PreRideChecklistBuilder.build(passport);

        PreRideChecklistItem climbs = find(items, PreRideChecklistItem.Type.CLIMBS);
        assertNotNull(climbs);
        assertTrue(climbs.message.contains("1 klim "));
    }

    @Test
    public void nullPassportIsSafeAndStillOffersBatteryReminder() {
        List<PreRideChecklistItem> items = PreRideChecklistBuilder.build(null);
        assertEquals(2, items.size());
        assertNotNull(find(items, PreRideChecklistItem.Type.BATTERY));
        assertNotNull(find(items, PreRideChecklistItem.Type.DURATION));
    }

    private static PreRideChecklistItem find(List<PreRideChecklistItem> items,
                                             PreRideChecklistItem.Type type) {
        for (PreRideChecklistItem item : items) {
            if (item.type == type) return item;
        }
        return null;
    }
}
