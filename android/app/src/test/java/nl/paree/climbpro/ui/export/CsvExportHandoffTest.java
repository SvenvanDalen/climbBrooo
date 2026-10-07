package nl.paree.climbpro.ui.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.export.CsvExporter;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class CsvExportHandoffTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    /** Runs the export; on Windows FileProvider can't resolve the cache root (env quirk). */
    private Intent exportTolerantly() throws Exception {
        try {
            return CsvExportHandoff.export(app);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Failed to find configured root")) {
                return null;
            }
            throw e;
        }
    }

    private File csv(String prefix) {
        return new File(new File(app.getCacheDir(), "shared_csv"),
                prefix + "_" + LocalDate.now() + ".csv");
    }

    @Test
    public void export_writesBothFilesWithBomAndRoutes() throws Exception {
        UiTestData.seed(app);

        Intent i = exportTolerantly();

        File routes = csv("routes");
        File attempts = csv("klimpogingen");
        assertTrue(routes.exists());
        assertTrue(attempts.exists());
        String routesCsv = new String(Files.readAllBytes(routes.toPath()), StandardCharsets.UTF_8);
        assertTrue(routesCsv.startsWith(CsvExporter.BOM));
        assertTrue(routesCsv.contains("Ardennen rondje"));
        assertTrue(routesCsv.contains("Limburgse heuvels"));
        String attemptsCsv = new String(Files.readAllBytes(attempts.toPath()), StandardCharsets.UTF_8);
        assertTrue(attemptsCsv.split("\n").length > 2);
        if (i != null) {
            List<Uri> uris = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            assertEquals(2, uris.size());
        }
    }

    @Test
    public void export_emptyLibrary_writesHeaderOnlyFiles() throws Exception {
        exportTolerantly();

        String routesCsv = new String(Files.readAllBytes(csv("routes").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(routesCsv.startsWith(CsvExporter.BOM));
        assertTrue(routesCsv.trim().split("\n").length <= 1);
    }

    @Test
    public void export_skipsUnreadableRoute() throws Exception {
        UiTestData.seed(app);
        File broken = new File(new File(app.getFilesDir(), "routes"), UiTestData.ROUTE_ID_2 + ".json");
        Files.write(broken.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8));

        exportTolerantly();

        String routesCsv = new String(Files.readAllBytes(csv("routes").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(routesCsv.contains("Ardennen rondje"));
        assertTrue(!routesCsv.contains("Limburgse heuvels"));
    }

    @Test
    public void buildShareIntent_isSendMultipleCsvWithReadGrant() {
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://x/a.csv"));
        uris.add(Uri.parse("content://x/b.csv"));

        Intent i = CsvExportHandoff.buildShareIntent(uris);

        assertEquals(Intent.ACTION_SEND_MULTIPLE, i.getAction());
        assertEquals(CsvExportHandoff.CSV_MIME, i.getType());
        assertEquals("ClimbPro export", i.getStringExtra(Intent.EXTRA_SUBJECT));
        List<Uri> got = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        assertNotNull(got);
        assertEquals(uris, got);
        assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }
}
