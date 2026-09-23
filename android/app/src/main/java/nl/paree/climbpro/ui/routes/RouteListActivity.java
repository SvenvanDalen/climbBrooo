package nl.paree.climbpro.ui.routes;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.databinding.ActivityRouteListBinding;
import nl.paree.climbpro.domain.route.GpxParseException;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.RouteSimplifier;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.climb.DuplicateClimbMatcher;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.ui.settings.SettingsActivity;
import nl.paree.climbpro.ui.strava.StravaAuthActivity;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteListActivity extends AppCompatActivity {

    private ActivityRouteListBinding binding;
    private RouteListViewModel viewModel;
    private RouteListAdapter adapter;
    private final ExecutorService    executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> gpxPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importGpx(uri); });

    private final ActivityResultLauncher<String[]> btPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> onBluetoothPermissionResult());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding   = ActivityRouteListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        viewModel = new ViewModelProvider(this).get(RouteListViewModel.class);
        adapter   = new RouteListAdapter();

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        adapter.setListener(new RouteListAdapter.OnRouteClickListener() {
            @Override
            public void onRouteClick(nl.paree.climbpro.data.route.RouteCatalogEntry entry) {
                startActivity(RouteDetailActivity.intentFor(RouteListActivity.this, entry.routeId));
            }
            @Override
            public void onRouteLongClick(nl.paree.climbpro.data.route.RouteCatalogEntry entry) {
                String name = entry.userDisplayName != null ? entry.userDisplayName : entry.name;
                new AlertDialog.Builder(RouteListActivity.this)
                        .setTitle(name != null ? name : entry.routeId)
                        .setItems(new String[]{"Toevoegen aan collectie", "Verwijderen"}, (d, which) -> {
                            if (which == 0) {
                                nl.paree.climbpro.ui.collections.CollectionMembershipDialog
                                        .showForRoute(RouteListActivity.this, entry.routeId);
                            } else {
                                confirmDeleteRoute(entry.routeId, name);
                            }
                        })
                        .show();
            }
        });

        viewModel.routes().observe(this, adapter::setItems);
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        binding.chipAll.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(-1);
        });
        binding.chipAsphalt.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.ASPHALT);
        });
        binding.chipGravel.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.GRAVEL);
        });
        binding.chipDirt.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.DIRT);
        });
        binding.chipCobblestone.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.COBBLESTONE);
        });
        binding.chipMixed.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.MIXED);
        });

        binding.fab.setOnClickListener(v -> showImportDialog());

        nl.paree.climbpro.service.SyncScheduler.manualSyncInfo(this).observe(this, infos -> {
            if (infos == null || infos.isEmpty()) return;
            androidx.work.WorkInfo info = infos.get(infos.size() - 1);

            boolean pullDone =
                    info.getProgress().getBoolean(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_PULL_DONE, false)
                 || info.getOutputData().getBoolean(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_PULL_DONE, false);
            if (pullDone) {
                viewModel.loadRoutes(); // new routes appear immediately (at the bottom with default sort)
            }

            if (info.getState().isFinished()) {
                if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
                    int changed = info.getOutputData().getInt(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_CHANGED, 0);
                    viewModel.loadRoutes();
                    Toast.makeText(this,
                            changed > 0
                                    ? ("Sync klaar: " + changed + " nieuwe/gewijzigde route(s)")
                                    : "Sync klaar — geen wijzigingen",
                            Toast.LENGTH_SHORT).show();
                } else if (info.getState() == androidx.work.WorkInfo.State.FAILED) {
                    Toast.makeText(this, "Sync mislukt", Toast.LENGTH_SHORT).show();
                }
            }
        });

        ensureBluetoothPermission();
        checkForAppUpdate(false);
    }

    /**
     * The Garmin Connect IQ SDK can only bind to the Garmin Connect Mobile Bluetooth
     * service when {@code BLUETOOTH_CONNECT} is granted (Android 12+ runtime permission).
     * Without it {@code getConnectedDevices()} returns empty and the watch widget shows
     * "No routes". A fresh (re)install resets this grant — and nothing requested it — so
     * the connection silently never came up. Request it here; on grant, (re)connect.
     */
    private void ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return; // pre-Android-12: BT perms are install-time; Application already connected
        }
        boolean granted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            return; // ClimbProApplication#onCreate already kicked off forceRebind() with permission
        }
        btPermissionLauncher.launch(new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
        });
    }

    private void onBluetoothPermissionResult() {
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            // The startup forceRebind() ran before the grant and is stuck retrying in ERROR
            // (no reachable device). Rebind now that the SDK can actually reach the watch.
            ((nl.paree.climbpro.ClimbProApplication) getApplication())
                    .connectIqClient().forceRebind();
        } else {
            Toast.makeText(this,
                    "Zonder Bluetooth-toestemming kan de telefoon de horloge-app niet bereiken",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Checks GitHub Releases for a newer build than the one currently installed and,
     * if found, asks the user to confirm before downloading + installing it. See
     * DEPLOYMENT.md for how release APKs are built and signed.
     */
    private void checkForAppUpdate(boolean verbose) {
        new nl.paree.climbpro.update.UpdateChecker(this).checkForUpdate(
                new nl.paree.climbpro.update.UpdateChecker.Callback() {
                    @Override
                    public void onUpdateAvailable(String tagName, String apkDownloadUrl) {
                        new AlertDialog.Builder(RouteListActivity.this)
                                .setTitle("Update beschikbaar")
                                .setMessage("ClimbPro " + tagName + " is beschikbaar. Nu downloaden en installeren?")
                                .setPositiveButton("Installeren",
                                        (d, w) -> startUpdateDownload(tagName, apkDownloadUrl))
                                .setNegativeButton("Later", null)
                                .show();
                    }

                    @Override
                    public void onUpToDate() {
                        // The silent startup check stays silent when there's nothing new;
                        // a manually triggered check still confirms it actually ran.
                        if (verbose) {
                            Toast.makeText(RouteListActivity.this,
                                    "Je hebt al de nieuwste versie (build "
                                            + nl.paree.climbpro.BuildConfig.VERSION_CODE + ")",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onCheckFailed(Exception e) {
                        Log.w("RouteListActivity", "Update check failed", e);
                        // Previously fully silent, which made a real failure (network,
                        // GitHub API rate limit, ...) indistinguishable from "no update
                        // available" — always surface it so it's not a mystery.
                        Toast.makeText(RouteListActivity.this,
                                "Update-check mislukt: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void startUpdateDownload(String tagName, String apkDownloadUrl) {
        nl.paree.climbpro.update.UpdateChecker checker =
                new nl.paree.climbpro.update.UpdateChecker(this);
        if (!checker.canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Toestemming nodig")
                    .setMessage("Sta \"apps installeren van deze bron\" toe om de update te installeren.")
                    .setPositiveButton("Instellingen openen",
                            (d, w) -> startActivity(checker.unknownAppsSettingsIntent()))
                    .setNegativeButton("Annuleren", null)
                    .show();
            return;
        }
        checker.downloadAndInstall(apkDownloadUrl, tagName);
        Toast.makeText(this, "Update wordt gedownload...", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.route_list_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sync) {
            viewModel.triggerSync();
            Toast.makeText(this, "Sync started", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_strava) {
            startActivity(new Intent(this, StravaAuthActivity.class));
            return true;
        } else if (id == R.id.action_logbook) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.climbs.ClimbLogbookActivity.class));
            return true;
        } else if (id == R.id.action_planning) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.planning.PlannedClimbListActivity.class));
            return true;
        } else if (id == R.id.action_elevation_target) {
            startActivity(nl.paree.climbpro.ui.planning.ElevationTargetActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_timeline) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.climbs.ClimbTimelineActivity.class));
            return true;
        } else if (id == R.id.action_unfinished_climbs) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.climbs.UnfinishedClimbsActivity.class));
            return true;
        } else if (id == R.id.action_wrapped) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.wrapped.ClimbWrappedActivity.class));
            return true;
        } else if (id == R.id.action_collections) {
            startActivity(nl.paree.climbpro.ui.collections.CollectionListActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_climb_hygiene) {
            startActivity(nl.paree.climbpro.ui.climbs.ClimbHygieneActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_sort) {
            showSortDialog();
            return true;
        } else if (id == R.id.action_check_update) {
            checkForAppUpdate(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.loadRoutes();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void confirmDeleteRoute(String routeId, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + name + "\"?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> viewModel.deleteRoute(routeId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showImportDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Add route")
                .setItems(new String[]{"Import GPX file", "Sync from Strava"}, (d, which) -> {
                    if (which == 0) {
                        gpxPicker.launch(new String[]{"*/*"});
                    } else {
                        if (viewModel.isSignedInToStrava()) {
                            viewModel.triggerSync();
                            Toast.makeText(this, "Strava sync started", Toast.LENGTH_SHORT).show();
                        } else {
                            startActivity(new Intent(this, StravaAuthActivity.class));
                        }
                    }
                }).show();
    }

    private void showSortDialog() {
        final String[] labels = {
                "Importdatum (nieuwste onderaan)",
                "Importdatum (nieuwste bovenaan)",
                "Naam (A–Z)"
        };
        int current = viewModel.getSortMode();
        new AlertDialog.Builder(this)
                .setTitle("Sorteer routes")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    viewModel.setSortMode(which);
                    d.dismiss();
                })
                .show();
    }

    private void importGpx(android.net.Uri uri) {
        executor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open file");
                byte[] bytes = readStream(in);

                List<RoutePoint> raw       = GpxParser.parse(new java.io.ByteArrayInputStream(bytes));
                List<RoutePoint> withDist  = CumulativeDistance.compute(raw);
                List<RoutePoint> smoothed  = ElevationSmoother.smooth(withDist, 5);
                List<RoutePoint> simple    = RouteSimplifier.simplify(smoothed, 5.0);
                List<Climb>      climbs    = ClimbDetector.detect(simple);
                int detectedSurface = nl.paree.climbpro.domain.segment.SurfaceTypeDetector
                        .detectFromGpxBytes(bytes);

                RouteRepository repo = new RouteRepository(this);
                List<DuplicateClimbMatcher.Match> duplicates = DuplicateClimbMatcher.findDuplicates(
                        climbs, repo.loadCatalog(), ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M);

                if (duplicates.isEmpty()) {
                    finishImport(uri, bytes, simple, climbs, detectedSurface);
                } else {
                    runOnUiThread(() -> promptDuplicateResolution(duplicates,
                            () -> executor.execute(
                                    () -> finishImport(uri, bytes, simple, climbs, detectedSurface))));
                }
            } catch (GpxParseException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "GPX error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * A newly-parsed GPX contains one or more climbs whose start coordinate matches an
     * already-imported climb within {@link ClimbConstants#DUPLICATE_CLIMB_MATCH_RADIUS_M}.
     * Ask the user whether to skip the import (the climb is already known — "merge") or
     * import anyway (keep both as separate routes, e.g. an out-and-back variant).
     * {@code onImportAnyway} continues the import; declining leaves nothing on disk.
     */
    private void promptDuplicateResolution(List<DuplicateClimbMatcher.Match> duplicates,
                                           Runnable onImportAnyway) {
        java.util.LinkedHashSet<String> routeNames = new java.util.LinkedHashSet<>();
        for (DuplicateClimbMatcher.Match m : duplicates) {
            String name = m.existingRoute.userDisplayName != null
                    ? m.existingRoute.userDisplayName : m.existingRoute.name;
            routeNames.add(name);
        }
        String message = duplicates.size() + " klim(men) uit dit bestand lijk(t)(en) al bekend "
                + "(uit: " + String.join(", ", routeNames) + "). Samenvoegen slaat deze import over "
                + "zodat de radius-index geen dubbele klim krijgt.";

        new AlertDialog.Builder(this)
                .setTitle("Klim al bekend")
                .setMessage(message)
                .setPositiveButton("Samenvoegen", (d, w) ->
                        Toast.makeText(this, "Niet geïmporteerd — klim is al bekend",
                                Toast.LENGTH_LONG).show())
                .setNegativeButton("Toch importeren", (d, w) -> onImportAnyway.run())
                .setNeutralButton("Annuleren", null)
                .show();
    }

    private void finishImport(android.net.Uri uri, byte[] bytes, List<RoutePoint> simple,
                              List<Climb> climbs, int detectedSurface) {
        try {
            String routeId = "gpx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            StoredRoute stored = new StoredRoute();
            stored.routeId      = routeId;
            stored.name         = uri.getLastPathSegment();
            stored.importedAtMs = System.currentTimeMillis();
            stored.sourceHash   = sha256(bytes);

            RouteRepository repo = new RouteRepository(this);
            repo.saveRoute(stored, simple, climbs);
            if (detectedSurface != SurfaceType.UNKNOWN) {
                try {
                    StoredRoute saved = repo.loadRoute(routeId);
                    if (saved.climbs != null) {
                        for (int ci = 0; ci < saved.climbs.size(); ci++) {
                            repo.setBulkClimbSurfaceType(routeId, ci, detectedSurface);
                        }
                    }
                } catch (Exception e) {
                    Log.w("RouteListActivity", "Surface type detection failed: " + e.getMessage());
                }
            }
            runOnUiThread(() -> {
                viewModel.loadRoutes();
                Toast.makeText(this,
                        "Imported: " + climbs.size() + " climb(s) detected",
                        Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this,
                    "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private static byte[] readStream(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }
}
