package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class BikeComputerExportTest {

    @Test
    public void findPackage_matchesByKeywordCaseInsensitive() {
        java.util.List<String> installed = Arrays.asList(
                "com.google.android.apps.docs", "com.wahoofitness.boltcompanion",
                "io.Hammerhead.companion");
        assertEquals("com.wahoofitness.boltcompanion",
                BikeComputerExport.findPackage(installed, BikeComputerExport.Target.WAHOO));
        assertEquals("io.Hammerhead.companion",
                BikeComputerExport.findPackage(installed, BikeComputerExport.Target.HAMMERHEAD));
        assertNull(BikeComputerExport.findPackage(Collections.singletonList("com.strava"),
                BikeComputerExport.Target.WAHOO));
    }

    @Test
    public void findPackage_prefersTheCompanionAppOverOtherVendorApps() {
        java.util.List<String> installed = Arrays.asList(
                "com.wahoofitness.fitness", "com.wahoofitness.boltcompanion");
        assertEquals("com.wahoofitness.boltcompanion",
                BikeComputerExport.findPackage(installed, BikeComputerExport.Target.WAHOO));
        assertEquals("com.wahoofitness.fitness", BikeComputerExport.findPackage(
                Collections.singletonList("com.wahoofitness.fitness"),
                BikeComputerExport.Target.WAHOO));
    }

    @Test
    public void fileName_usesSanitisedRouteName() {
        StoredRoute r = new StoredRoute();
        r.name = "strava_route.gpx";
        assertEquals("strava_route.gpx", BikeComputerExport.fileName(r));
        r.userDisplayName = "Limburg: 3 heuvels/Vaals?";
        assertEquals("Limburg_ 3 heuvels_Vaals_.gpx", BikeComputerExport.fileName(r));
        r.userDisplayName = null;
        r.name = null;
        assertEquals("ClimbPro route.gpx", BikeComputerExport.fileName(r));
    }
}
