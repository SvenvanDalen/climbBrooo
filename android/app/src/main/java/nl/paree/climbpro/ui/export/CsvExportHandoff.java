package nl.paree.climbpro.ui.export;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.export.CsvExporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV export of routes and climb attempts (issue #256): writes {@code routes_<date>.csv} and
 * {@code klimpogingen_<date>.csv} under {@code cacheDir/shared_csv/} and builds the
 * {@code ACTION_SEND_MULTIPLE} intent that hands both to the share sheet (Drive, mail,
 * Files...). Phone-only; the watch payload is untouched. Call {@link #export} off the main
 * thread — it loads every route file.
 */
public final class CsvExportHandoff {

    public static final String CSV_MIME = "text/csv";
    private static final String TAG = "CsvExportHandoff";
    private static final String CACHE_SUBDIR = "shared_csv";

    private CsvExportHandoff() {}

    /** Writes both CSV files and returns the share intent, ready for a chooser. */
    public static Intent export(Context ctx) throws IOException {
        RouteRepository routeRepo = new RouteRepository(ctx);
        List<StoredRoute> routes = new ArrayList<>();
        for (RouteCatalogEntry e : routeRepo.loadCatalog()) {
            try {
                StoredRoute r = routeRepo.loadRoute(e.routeId);
                if (r == null) continue;
                // Keep only what the CSV needs: holding every route's full geometry at once
                // can exhaust the heap on a large library (OOM is not caught by the caller).
                double[] d = r.distances;
                r.distances = d != null && d.length > 0 ? new double[]{d[d.length - 1]} : null;
                r.lats = null;
                r.lons = null;
                r.elevations = null;
                r.flatSegments = null;
                r.surfaceSections = null;
                r.starredSegments = null;
                if (r.climbs != null) {
                    for (StoredClimb c : r.climbs) {
                        c.segments = null;
                        c.calibrationPoints = null;
                    }
                }
                routes.add(r);
            } catch (IOException ex) {
                Log.w(TAG, "Skipping unreadable route " + e.routeId, ex);
            }
        }
        ZoneId zone = ZoneId.systemDefault();
        String date = LocalDate.now(zone).toString();

        File dir = new File(ctx.getCacheDir(), CACHE_SUBDIR);
        dir.mkdirs();
        File routesFile = write(new File(dir, "routes_" + date + ".csv"),
                CsvExporter.routesCsv(routes, zone));
        File attemptsFile = write(new File(dir, "klimpogingen_" + date + ".csv"),
                CsvExporter.attemptsCsv(new ClimbAttemptRepository(ctx).loadAll(), routes, zone));

        String authority = ctx.getPackageName() + ".fileprovider";
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(FileProvider.getUriForFile(ctx, authority, routesFile));
        uris.add(FileProvider.getUriForFile(ctx, authority, attemptsFile));
        return buildShareIntent(uris);
    }

    /** Builds the ACTION_SEND_MULTIPLE intent carrying the CSV {@code uris}. */
    public static Intent buildShareIntent(ArrayList<Uri> uris) {
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.setType(CSV_MIME);
        share.putExtra(Intent.EXTRA_SUBJECT, "ClimbPro export");
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return share;
    }

    private static File write(File file, String csv) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((CsvExporter.BOM + csv).getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
