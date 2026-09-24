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
import nl.paree.climbpro.domain.ride.YearlyDistanceGoalCalculator;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.data.ride.YearlyDistanceGoalRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.ui.settings.SettingsActivity;
import nl.paree.climbpro.ui.strava.StravaAuthActivity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteListActivity extends AppCompatActivity {

    private ActivityRouteListBinding binding;
    private RouteListViewModel viewModel;
    private RouteListAdapter adapter;
    private final ExecutorService    executor = Executors.newSingleThreadExecutor();

    /** Status filter labels; index order matches {@link RouteStatusFilter}. */
    private static final String[] STATUS_FILTER_LABELS = {"Alle", "Wil ik rijden", "Gereden"};

    /**
     * The quick-start (issue #263) sync run whose outcome is still to be reported, or null.
     * Matched by id: with REPLACE the unique-work list can also hold the cancelled previous
     * run, which must not be reported as "horloge niet bereikt".
     */
    private java.util.UUID quickStartWorkId;

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
        updateStatusFilterSubtitle(viewModel.getStatusFilter()); // survives rotation via the ViewModel
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
                        .setItems(new String[]{"Toevoegen aan collectie", "Verwijderen",
                                "Nu rijden (naar horloge)"}, (d, which) -> {
                            if (which == 2) {
                                startQuickStart(entry);
                            } else if (which == 0) {
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
        viewModel.catalog().observe(this, catalog -> updateQuickStartButton());

        binding.btnQuickStart.setOnClickListener(v -> {
            RouteCatalogEntry route = QuickStart.resolve(
                    QuickStart.activeRouteId(this), viewModel.catalog().getValue());
            if (route != null) {
                startQuickStart(route);
            } else {
                pickQuickStartRoute();
            }
        });
        binding.btnQuickStartChange.setOnClickListener(v -> pickQuickStartRoute());
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
        binding.maintenanceBanner.setOnClickListener(v -> startActivity(
                nl.paree.climbpro.ui.maintenance.MaintenanceActivity.intentFor(this)));

        binding.fab.setOnClickListener(v -> showImportDialog());
        binding.tirePressureBanner.setOnClickListener(v -> startActivity(
                nl.paree.climbpro.ui.tire.TirePressureLogActivity.intentFor(this)));

        viewModel.yearlyGoal().observe(this, this::renderYearlyGoal);
        binding.yearlyGoalCard.setOnClickListener(v -> showYearlyGoalDialog());

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

            if (quickStartWorkId != null) {
                for (androidx.work.WorkInfo w : infos) {
                    if (!quickStartWorkId.equals(w.getId()) || !w.getState().isFinished()) continue;
                    quickStartWorkId = null;
                    // Cancelled = replaced by a newer sync, which reports for itself.
                    if (w.getState() != androidx.work.WorkInfo.State.CANCELLED) {
                        boolean sent = w.getOutputData().getBoolean(
                                nl.paree.climbpro.service.RouteSyncWorker.KEY_WATCH_SENT, false);
                        Toast.makeText(this, sent
                                        ? "Route staat klaar op je horloge"
                                        : "Horloge niet bereikt; de sync probeert het later opnieuw",
                                Toast.LENGTH_LONG).show();
                    }
                    break;
                }
            }

            if (info.getState().isFinished()) {
                if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
                    int changed = info.getOutputData().getInt(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_CHANGED, 0);
                    viewModel.loadRoutes();
                    viewModel.loadYearlyGoal(); // the Strava pull also refreshes the ride archive
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
        } else if (id == R.id.action_rides) {
            startActivity(nl.paree.climbpro.ui.rides.RideArchiveActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_records) {
            startActivity(nl.paree.climbpro.ui.records.RideRecordsActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_tire_pressure_log) {
            startActivity(nl.paree.climbpro.ui.tire.TirePressureLogActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_unfinished_climbs) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.climbs.UnfinishedClimbsActivity.class));
            return true;
        } else if (id == R.id.action_wrapped) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.wrapped.ClimbWrappedActivity.class));
            return true;
        } else if (id == R.id.action_recovery) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.recovery.RecoveryAdviceActivity.class));
            return true;
        } else if (id == R.id.action_photo_quiz) {
            startActivity(nl.paree.climbpro.ui.quiz.PhotoQuizActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_collections) {
            startActivity(nl.paree.climbpro.ui.collections.CollectionListActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_maintenance) {
            startActivity(nl.paree.climbpro.ui.maintenance.MaintenanceActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_climb_hygiene) {
            startActivity(nl.paree.climbpro.ui.climbs.ClimbHygieneActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_batch_export) {
            showBatchExportDialog();
            return true;
        } else if (id == R.id.action_export_csv) {
            exportCsv();
            return true;
        } else if (id == R.id.action_privacy) {
            startActivity(nl.paree.climbpro.ui.privacy.PrivacyDashboardActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_visited_regions) {
            startActivity(nl.paree.climbpro.ui.regions.VisitedRegionsActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_elevation_goal) {
            startActivity(nl.paree.climbpro.ui.goals.ElevationGoalActivity.intentFor(this));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_sort) {
            showSortDialog();
            return true;
        } else if (id == R.id.action_status_filter) {
            showStatusFilterDialog();
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
        refreshMaintenanceBanner();
        viewModel.loadRoutes();
        viewModel.loadYearlyGoal();
        refreshTirePressureBanner();
    }

    /**
     * Shows the in-app tire-pressure reminder (issue #155) when a check is due. Evaluated on
     * every resume, off the main thread; no notification or worker involved.
     */
    private void refreshTirePressureBanner() {
        executor.execute(() -> {
            String text = nl.paree.climbpro.ui.tire.TirePressureStatusLoader
                    .load(getApplicationContext(), System.currentTimeMillis() / 1000L)
                    .bannerText();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                binding.tirePressureBanner.setText(text);
                binding.tirePressureBanner.setVisibility(
                        text != null ? android.view.View.VISIBLE : android.view.View.GONE);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    /**
     * Yearly km goal card (issue #157). Without a goal the card stays visible with this year's
     * km and a subtle prompt, so the feature is discoverable; the bar is then hidden.
     */
    private void renderYearlyGoal(YearlyDistanceGoalCalculator.Progress p) {
        if (p == null) return;
        binding.yearlyGoalCard.setVisibility(android.view.View.VISIBLE);
        binding.yearlyGoalTitle.setText(YearlyDistanceGoalCalculator.headline(p));
        if (p.hasGoal()) {
            binding.yearlyGoalProgress.setVisibility(android.view.View.VISIBLE);
            binding.yearlyGoalProgress.setProgressCompat(
                    (int) Math.round(p.fraction * binding.yearlyGoalProgress.getMax()), false);
            binding.yearlyGoalHint.setText(YearlyDistanceGoalCalculator.paceHint(p));
            binding.yearlyGoalHint.setTextColor(ContextCompat.getColor(this,
                    p.pace == YearlyDistanceGoalCalculator.Pace.BEHIND_SCHEDULE
                            ? R.color.color_text_tertiary : R.color.color_success));
        } else {
            binding.yearlyGoalProgress.setVisibility(android.view.View.GONE);
            binding.yearlyGoalHint.setText("Tik om een jaardoel in te stellen");
            binding.yearlyGoalHint.setTextColor(
                    ContextCompat.getColor(this, R.color.color_text_tertiary));
        }
    }

    /** Sets or clears the yearly km goal; empty or 0 clears it. */
    private void showYearlyGoalDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("Doel in km, bv. 5000");
        int current = viewModel.getYearlyGoalKm();
        if (current > 0) {
            input.setText(String.valueOf(current));
            input.setSelection(input.getText().length());
        }
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Jaardoel " + java.time.LocalDate.now().getYear())
                .setMessage("Hoeveel km wil je dit jaar fietsen? Leeg of 0 wist het doel.")
                .setView(container)
                .setPositiveButton("Opslaan", (d, w) -> {
                    String text = input.getText().toString().trim();
                    int km;
                    try {
                        km = text.isEmpty() ? 0 : Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        km = -1; // too many digits for an int
                    }
                    if (km < 0 || km > YearlyDistanceGoalRepository.MAX_GOAL_KM) {
                        Toast.makeText(this, "Ongeldig doel (max "
                                        + YearlyDistanceGoalRepository.MAX_GOAL_KM + " km)",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.setYearlyGoalKm(km);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    /**
     * Shows the maintenance-due banner (issue #154) when a component needs service. Evaluated
     * on every resume, off the main thread; no notification or worker involved.
     */
    private void refreshMaintenanceBanner() {
        executor.execute(() -> {
            String text = nl.paree.climbpro.ui.maintenance.MaintenanceStatusLoader
                    .load(getApplicationContext(), System.currentTimeMillis() / 1000L)
                    .bannerText();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                binding.maintenanceBanner.setText(text);
                binding.maintenanceBanner.setVisibility(
                        text != null ? android.view.View.VISIBLE : android.view.View.GONE);
            });
        });
    }

    private void updateQuickStartButton() {
        RouteCatalogEntry route = QuickStart.resolve(
                QuickStart.activeRouteId(this), viewModel.catalog().getValue());
        binding.btnQuickStart.setText(QuickStart.buttonLabel(route));
    }

    /** Lets the user choose which route quick start sends; the pick starts the ride at once. */
    private void pickQuickStartRoute() {
        List<RouteCatalogEntry> catalog = viewModel.catalog().getValue();
        if (catalog == null || catalog.isEmpty()) {
            Toast.makeText(this, "Importeer eerst een route", Toast.LENGTH_SHORT).show();
            return;
        }
        List<RouteCatalogEntry> sorted = RouteSorting.sort(
                new java.util.ArrayList<>(catalog), RouteSorting.SORT_NAME_ASC);
        String[] names = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) names[i] = QuickStart.displayName(sorted.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Welke route rij je?")
                .setItems(names, (d, which) -> startQuickStart(sorted.get(which)))
                .show();
    }

    private void startQuickStart(RouteCatalogEntry route) {
        quickStartWorkId = QuickStart.start(this, route.routeId);
        updateQuickStartButton();
        Toast.makeText(this, QuickStart.displayName(route) + " wordt naar je horloge gestuurd",
                Toast.LENGTH_SHORT).show();
    }

    /** Issue #256: exports routes + climb attempts as CSV via the share sheet. */
    private void exportCsv() {
        executor.execute(() -> {
            try {
                Intent share = nl.paree.climbpro.ui.export.CsvExportHandoff.export(this);
                runOnUiThread(() -> startActivity(Intent.createChooser(share, "Exporteer CSV")));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "CSV-export mislukt: " + (e.getMessage() != null
                                ? e.getMessage() : e.getClass().getSimpleName()),
                        Toast.LENGTH_LONG).show());
            }
        });
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

    /**
     * Batch-export of every climb ridden within a chosen calendar year as one combined GPX
     * file (issue #91) — reuses the single-climb export path ({@link
     * nl.paree.climbpro.domain.climb.ClimbGpxWriter}) in a loop via {@link
     * nl.paree.climbpro.domain.climb.BatchClimbGpxWriter}, filtered by {@link
     * nl.paree.climbpro.domain.climb.SeasonClimbFilter}. "Season" here is kept simple: a
     * year picker showing only years that actually have dated attempts.
     */
    private void showBatchExportDialog() {
        executor.execute(() -> {
            nl.paree.climbpro.data.route.ClimbAttemptRepository attemptRepo =
                    new nl.paree.climbpro.data.route.ClimbAttemptRepository(this);
            List<Integer> years = nl.paree.climbpro.domain.climb.SeasonClimbFilter
                    .yearsWithAttempts(attemptRepo.loadAll(), java.util.TimeZone.getDefault());
            runOnUiThread(() -> {
                if (years.isEmpty()) {
                    Toast.makeText(this, "Geen ritten met datum gevonden om te exporteren",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[years.size()];
                for (int i = 0; i < years.size(); i++) labels[i] = String.valueOf(years.get(i));
                new AlertDialog.Builder(this)
                        .setTitle("Exporteer seizoen")
                        .setItems(labels, (d, which) -> exportSeason(years.get(which)))
                        .show();
            });
        });
    }

    private void exportSeason(int year) {
        executor.execute(() -> {
            try {
                RouteRepository repo = new RouteRepository(this);
                nl.paree.climbpro.data.route.ClimbAttemptRepository attemptRepo =
                        new nl.paree.climbpro.data.route.ClimbAttemptRepository(this);
                List<nl.paree.climbpro.data.route.StoredClimbAttempt> attempts = attemptRepo.loadAll();

                long[] range = nl.paree.climbpro.domain.climb.SeasonClimbFilter.yearRange(
                        year, java.util.TimeZone.getDefault());
                // One route at a time, keeping only routes with a climb ridden that year:
                // holding every route's full geometry at once can exhaust the heap on a large
                // library (OOM is not caught below).
                List<nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match> matches =
                        new ArrayList<>();
                java.util.Set<String> seenClimbIds = new java.util.HashSet<>();
                for (nl.paree.climbpro.data.route.RouteCatalogEntry entry : repo.loadCatalog()) {
                    try {
                        StoredRoute route = repo.loadRoute(entry.routeId);
                        for (nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match m
                                : nl.paree.climbpro.domain.climb.SeasonClimbFilter.climbsInPeriod(
                                        java.util.Collections.singletonList(route), attempts,
                                        range[0], range[1])) {
                            int len = m.climb.length > 0 ? m.climb.length
                                    : (m.climb.endDistance - m.climb.startDistance);
                            if (seenClimbIds.add(nl.paree.climbpro.domain.climb.ClimbIdentity.of(
                                    m.climb.startLat, m.climb.startLon, len))) {
                                matches.add(m);
                            }
                        }
                    } catch (IOException e) {
                        Log.w("RouteListActivity", "Skipping unreadable route " + entry.routeId, e);
                    }
                }

                if (matches.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Geen klimmen gevonden in " + year, Toast.LENGTH_SHORT).show());
                    return;
                }

                Map<String, nl.paree.climbpro.domain.climb.LogbookCalculator.Summary> summaries =
                        nl.paree.climbpro.domain.climb.LogbookCalculator.summaries(attempts);

                int privacyRadiusM = nl.paree.climbpro.domain.climb.CoordinateFuzzer.effectiveRadius(
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                                .getInt(nl.paree.climbpro.domain.climb.CoordinateFuzzer.PREF_PRIVACY_RADIUS_M,
                                        nl.paree.climbpro.domain.climb.CoordinateFuzzer.DEFAULT_PRIVACY_RADIUS_M));

                List<nl.paree.climbpro.domain.climb.BatchClimbGpxWriter.Entry> exportEntries =
                        new ArrayList<>();
                for (nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match m : matches) {
                    if (m.climb.isHome && !nl.paree.climbpro.domain.climb.CoordinateFuzzer
                            .isUsableZoneCentre(m.climb.privacyCentreLat, m.climb.privacyCentreLon,
                                    m.climb.startLat, m.climb.startLon, privacyRadiusM)) {
                        // Home climb (issue #92) without a usable zone centre: draw and persist
                        // one before sharing, exactly like the single-climb export does.
                        double[] centre = nl.paree.climbpro.domain.climb.CoordinateFuzzer
                                .randomZoneCentre(m.climb.startLat, m.climb.startLon,
                                        privacyRadiusM, new java.security.SecureRandom());
                        repo.setClimbPrivacyCentre(m.route.routeId, m.climbIndex,
                                centre[0], centre[1]);
                        m.climb.privacyCentreLat = centre[0];
                        m.climb.privacyCentreLon = centre[1];
                    }
                    int segCount = m.climb.segments != null ? m.climb.segments.size() : 0;
                    int len = m.climb.length > 0 ? m.climb.length
                            : (m.climb.endDistance - m.climb.startDistance);
                    String climbId = nl.paree.climbpro.domain.climb.ClimbIdentity.of(
                            m.climb.startLat, m.climb.startLon, len);
                    int[] bestSplitSec = nl.paree.climbpro.domain.climb.SegmentPrCalculator
                            .bestSplits(climbId, segCount, attempts);
                    nl.paree.climbpro.domain.climb.LogbookCalculator.Summary summary =
                            summaries.get(climbId);
                    Integer bestElapsedSec = summary != null ? summary.prSec : null;
                    exportEntries.add(new nl.paree.climbpro.domain.climb.BatchClimbGpxWriter.Entry(
                            m.route, m.climb, m.climbIndex, bestSplitSec, bestElapsedSec));
                }

                String gpx = nl.paree.climbpro.domain.climb.BatchClimbGpxWriter.toGpx(exportEntries,
                        privacyRadiusM);
                File file = nl.paree.climbpro.ui.climbs.ClimbGpxExportHandoff.writeGpxFile(
                        this, gpx, "season_" + year);
                runOnUiThread(() -> shareGpxFile(file, "Exporteer seizoen " + year));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Export mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void shareGpxFile(File file, String chooserTitle) {
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);
        Intent share = nl.paree.climbpro.ui.climbs.ClimbGpxExportHandoff.buildShareIntent(uri);
        startActivity(Intent.createChooser(share, chooserTitle));
    }

    /** Bucket-list filter (issue #158): Alle / Wil ik rijden / Gereden. */
    private void showStatusFilterDialog() {
        int current = viewModel.getStatusFilter();
        new AlertDialog.Builder(this)
                .setTitle("Filter op status")
                .setSingleChoiceItems(STATUS_FILTER_LABELS, current, (d, which) -> {
                    viewModel.setStatusFilter(which);
                    updateStatusFilterSubtitle(which);
                    d.dismiss();
                })
                .show();
    }

    /** Shows the active status filter in the toolbar so a filtered list isn't mistaken for missing routes. */
    private void updateStatusFilterSubtitle(int filter) {
        if (getSupportActionBar() == null) return;
        boolean filtered = filter > RouteStatusFilter.FILTER_ALL && filter < STATUS_FILTER_LABELS.length;
        getSupportActionBar().setSubtitle(filtered ? "Filter: " + STATUS_FILTER_LABELS[filter] : null);
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
