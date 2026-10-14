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
import nl.paree.climbpro.domain.activity.MyWhooshRouteReader;
import nl.paree.climbpro.domain.route.GpxParseException;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.ShareLink;
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
import nl.paree.climbpro.data.route.MyWhooshRouteStore;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.ShareLinkRouteFetcher;
import nl.paree.climbpro.data.route.StoredRoute;
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
    private static final String TAG = "RouteListActivity";

    private ActivityRouteListBinding binding;
    private RouteListViewModel viewModel;
    private final ExecutorService    executor = Executors.newSingleThreadExecutor();

    /** Status filter labels; index order matches {@link RouteStatusFilter}. */
    private String[] statusFilterLabels() {
        return getResources().getStringArray(R.array.route_list_status_filters);
    }

    /**
     * The quick-start (issue #263) sync run whose outcome is still to be reported, or null.
     * Matched by id: with REPLACE the unique-work list can also hold the cancelled previous
     * run, which must not be reported as "horloge niet bereikt".
     */
    private java.util.UUID quickStartWorkId;

    private final ActivityResultLauncher<String[]> gpxPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importGpx(uri); });

    /** MyWhoosh ride import (issue #342): FIT or GPX exported from MyWhoosh. */
    private final ActivityResultLauncher<String[]> myWhooshPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importRoute(uri, true); });

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
        RouteListAdapter adapter   = new RouteListAdapter();

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        adapter.setListener(new RouteListAdapter.OnRouteClickListener() {
            @Override
            public void onRouteClick(nl.paree.climbpro.data.route.RouteCatalogEntry entry) {
                startActivity(RouteDetailActivity.intentFor(RouteListActivity.this, entry.routeId));
            }
            @Override
            public void onRouteLongClick(nl.paree.climbpro.data.route.RouteCatalogEntry entry) {
                showRouteActions(entry);
            }
        });

        viewModel.routes().observe(this, adapter::setItems);
        viewModel.catalog().observe(this, catalog -> updateQuickStartButton());

        binding.btnQuickStart.setOnClickListener(v -> onQuickStartClicked());
        binding.btnQuickStartChange.setOnClickListener(v -> pickQuickStartRoute());
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        bindSurfaceChip(binding.chipAll, -1);
        bindSurfaceChip(binding.chipAsphalt, SurfaceType.ASPHALT);
        bindSurfaceChip(binding.chipGravel, SurfaceType.GRAVEL);
        bindSurfaceChip(binding.chipDirt, SurfaceType.DIRT);
        bindSurfaceChip(binding.chipCobblestone, SurfaceType.COBBLESTONE);
        bindSurfaceChip(binding.chipMixed, SurfaceType.MIXED);
        binding.maintenanceBanner.setOnClickListener(v -> startActivity(
                nl.paree.climbpro.ui.maintenance.MaintenanceActivity.intentFor(this)));

        binding.fab.setOnClickListener(v -> showImportDialog());
        binding.tirePressureBanner.setOnClickListener(v -> startActivity(
                nl.paree.climbpro.ui.tire.TirePressureLogActivity.intentFor(this)));

        viewModel.yearlyGoal().observe(this, this::renderYearlyGoal);
        binding.yearlyGoalCard.setOnClickListener(v -> showYearlyGoalDialog());

        if (savedInstanceState == null) {
            handleShareIntent(getIntent()); // a Komoot/RideWithGPS link shared to ClimbPro
        }

        nl.paree.climbpro.service.SyncScheduler.manualSyncInfo(this).observe(this, this::onSyncInfo);

        ensureBluetoothPermission();
        // A local/dev build can't install a release APK over itself; only the menu item checks.
        if (!nl.paree.climbpro.update.UpdateChecker.isDevBuild(
                nl.paree.climbpro.BuildConfig.VERSION_NAME)) {
            checkForAppUpdate(false);
        }
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
                    R.string.route_list_bluetooth_denied,
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
                                .setTitle(R.string.route_list_update_title)
                                .setMessage(getString(R.string.route_list_update_message, tagName))
                                .setPositiveButton(R.string.route_list_update_install,
                                        (d, w) -> startUpdateDownload(tagName, apkDownloadUrl))
                                .setNegativeButton(R.string.action_later, null)
                                .show();
                    }

                    @Override
                    public void onUpToDate() {
                        // The silent startup check stays silent when there's nothing new;
                        // a manually triggered check still confirms it actually ran.
                        if (verbose) {
                            Toast.makeText(RouteListActivity.this,
                                    getString(R.string.route_list_update_latest,
                                            nl.paree.climbpro.BuildConfig.VERSION_NAME),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onCheckFailed(Exception e) {
                        Log.w(TAG, "Update check failed", e);
                        // Previously fully silent, which made a real failure (network,
                        // GitHub API rate limit, ...) indistinguishable from "no update
                        // available" — always surface it so it's not a mystery.
                        Toast.makeText(RouteListActivity.this,
                                getString(R.string.route_list_update_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void startUpdateDownload(String tagName, String apkDownloadUrl) {
        nl.paree.climbpro.update.UpdateChecker checker =
                new nl.paree.climbpro.update.UpdateChecker(this);
        if (!checker.canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.route_list_update_permission_title)
                    .setMessage(R.string.route_list_update_permission_message)
                    .setPositiveButton(R.string.route_list_update_open_settings,
                            (d, w) -> startActivity(checker.unknownAppsSettingsIntent()))
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        checker.downloadAndInstall(apkDownloadUrl, tagName);
        Toast.makeText(this, R.string.route_list_update_downloading, Toast.LENGTH_SHORT).show();
    }

    private void showRouteActions(RouteCatalogEntry entry) {
        String name = entry.userDisplayName != null ? entry.userDisplayName : entry.name;
        new AlertDialog.Builder(RouteListActivity.this)
                .setTitle(name != null ? name : entry.routeId)
                .setItems(new String[]{
                        getString(R.string.route_list_action_add_to_collection),
                        getString(R.string.action_delete),
                        getString(R.string.route_list_action_ride_now)}, (d, which) -> {
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

    private void onQuickStartClicked() {
        RouteCatalogEntry route = QuickStart.resolve(
                QuickStart.activeRouteId(this), viewModel.catalog().getValue());
        if (route != null) {
            startQuickStart(route);
        } else {
            pickQuickStartRoute();
        }
    }

    private void bindSurfaceChip(android.widget.CompoundButton chip, int surfaceType) {
        chip.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(surfaceType);
        });
    }

    private void onSyncInfo(List<androidx.work.WorkInfo> infos) {
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

        if (quickStartWorkId != null) reportQuickStart(infos);
        if (info.getState().isFinished()) reportSyncResult(info);
    }

    /** Reports whether the quick-start sync reached the watch, once its work has finished. */
    private void reportQuickStart(List<androidx.work.WorkInfo> infos) {
        for (androidx.work.WorkInfo w : infos) {
            if (quickStartWorkId.equals(w.getId()) && w.getState().isFinished()) {
                quickStartWorkId = null;
                // Cancelled = replaced by a newer sync, which reports for itself.
                if (w.getState() != androidx.work.WorkInfo.State.CANCELLED) {
                    boolean sent = w.getOutputData().getBoolean(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_WATCH_SENT, false);
                    Toast.makeText(this, sent
                                    ? R.string.route_list_quick_start_sent
                                    : R.string.route_list_quick_start_not_sent,
                            Toast.LENGTH_LONG).show();
                }
                break;
            }
        }
    }

    private void reportSyncResult(androidx.work.WorkInfo info) {
        if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
            int changed = info.getOutputData().getInt(
                    nl.paree.climbpro.service.RouteSyncWorker.KEY_CHANGED, 0);
            viewModel.loadRoutes();
            viewModel.loadYearlyGoal(); // the Strava pull also refreshes the ride archive
            Toast.makeText(this,
                    changed > 0
                            ? getString(R.string.route_list_sync_done_changes, changed)
                            : getString(R.string.route_list_sync_done_none),
                    Toast.LENGTH_SHORT).show();
        } else if (info.getState() == androidx.work.WorkInfo.State.FAILED) {
            Toast.makeText(this, R.string.route_list_sync_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.route_list_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        Intent screen = RouteListMenuScreens.intentFor(this, id);
        if (screen != null) {
            startActivity(screen);
            return true;
        }
        if (id == R.id.action_sync) {
            viewModel.triggerSync();
            Toast.makeText(this, R.string.route_list_sync_started, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_import_share_link) {
            showShareLinkDialog();
            return true;
        }
        if (id == R.id.action_batch_export) {
            showBatchExportDialog();
            return true;
        }
        if (id == R.id.action_export_csv) {
            exportCsv();
            return true;
        }
        if (id == R.id.action_sort) {
            showSortDialog();
            return true;
        }
        if (id == R.id.action_status_filter) {
            showStatusFilterDialog();
            return true;
        }
        if (id == R.id.action_check_update) {
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
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
            binding.yearlyGoalHint.setTextColor(
                    p.pace == YearlyDistanceGoalCalculator.Pace.BEHIND_SCHEDULE
                            ? ContextCompat.getColor(this, R.color.color_text_tertiary)
                            : nl.paree.climbpro.ui.climbs.SegmentColorPalette.statusOk());
        } else {
            binding.yearlyGoalProgress.setVisibility(android.view.View.GONE);
            binding.yearlyGoalHint.setText(R.string.route_list_yearly_goal_tap);
            binding.yearlyGoalHint.setTextColor(
                    ContextCompat.getColor(this, R.color.color_text_tertiary));
        }
    }

    /** Sets or clears the yearly km goal; empty or 0 clears it. */
    private void showYearlyGoalDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.route_list_yearly_goal_hint);
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
                .setTitle(getString(R.string.route_list_yearly_goal_title,
                        java.time.LocalDate.now(java.time.ZoneId.systemDefault()).getYear()))
                .setMessage(R.string.route_list_yearly_goal_message)
                .setView(container)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String text = input.getText().toString().trim();
                    int km;
                    try {
                        km = text.isEmpty() ? 0 : Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        km = -1; // too many digits for an int
                    }
                    if (km < 0 || km > YearlyDistanceGoalRepository.MAX_GOAL_KM) {
                        Toast.makeText(this, getString(R.string.route_list_yearly_goal_invalid,
                                        YearlyDistanceGoalRepository.MAX_GOAL_KM),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.setYearlyGoalKm(km);
                })
                .setNegativeButton(R.string.action_cancel, null)
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
            Toast.makeText(this, R.string.route_list_import_first, Toast.LENGTH_SHORT).show();
            return;
        }
        List<RouteCatalogEntry> sorted = RouteSorting.sort(
                new java.util.ArrayList<>(catalog), RouteSorting.SORT_NAME_ASC);
        String[] names = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) names[i] = QuickStart.displayName(sorted.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_list_pick_quick_start)
                .setItems(names, (d, which) -> startQuickStart(sorted.get(which)))
                .show();
    }

    private void startQuickStart(RouteCatalogEntry route) {
        quickStartWorkId = QuickStart.start(this, route.routeId);
        updateQuickStartButton();
        Toast.makeText(this, getString(R.string.route_list_quick_start_sending,
                        QuickStart.displayName(route)),
                Toast.LENGTH_SHORT).show();
    }

    /** Issue #256: exports routes + climb attempts as CSV via the share sheet. */
    private void exportCsv() {
        executor.execute(() -> {
            try {
                Intent share = nl.paree.climbpro.ui.export.CsvExportHandoff.export(this);
                runOnUiThread(() -> startActivity(Intent.createChooser(share,
                        getString(R.string.menu_export_csv))));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.route_list_csv_failed, e.getMessage() != null
                                ? e.getMessage() : e.getClass().getSimpleName()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmDeleteRoute(String routeId, String name) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.route_list_delete_title, name))
                .setMessage(R.string.route_list_delete_message)
                .setPositiveButton(R.string.action_delete, (d, w) -> viewModel.deleteRoute(routeId))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showImportDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_list_add_route)
                .setItems(new String[]{getString(R.string.route_list_import_gpx),
                        getString(R.string.route_list_import_strava),
                        getString(R.string.route_list_import_climb_code),
                        getString(R.string.route_list_import_mywhoosh),
                        getString(R.string.share_link_title)}, (d, which) -> {
                    if (which == 0) {
                        gpxPicker.launch(new String[]{"*/*"});
                    } else if (which == 4) {
                        showShareLinkDialog();
                    } else if (which == 3) {
                        showMyWhooshImportHelp();
                    } else if (which == 2) {
                        nl.paree.climbpro.ui.share.ClimbCodeSharing.showImportDialog(
                                this, executor, viewModel::loadRoutes);
                    } else {
                        if (viewModel.isSignedInToStrava()) {
                            viewModel.triggerSync();
                            Toast.makeText(this, R.string.route_list_strava_sync_started,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            startActivity(new Intent(this, StravaAuthActivity.class));
                        }
                    }
                }).show();
    }

    private void showSortDialog() {
        final String[] labels = getResources().getStringArray(R.array.route_list_sort_modes);
        int current = viewModel.getSortMode();
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_list_sort_title)
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
                    .yearsWithAttempts(attemptRepo.loadAll(), java.time.ZoneId.systemDefault());
            runOnUiThread(() -> {
                if (years.isEmpty()) {
                    Toast.makeText(this, R.string.route_list_season_none,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[years.size()];
                for (int i = 0; i < years.size(); i++) labels[i] = String.valueOf(years.get(i));
                new AlertDialog.Builder(this)
                        .setTitle(R.string.route_list_season_title)
                        .setItems(labels, (d, which) -> exportSeason(years.get(which)))
                        .show();
            });
        });
    }

    /**
     * Climbs ridden in the period, one copy per climb. One route at a time, keeping only
     * routes with a climb ridden that year: holding every route's full geometry at once can
     * exhaust the heap on a large library (OOM is not caught by the caller).
     */
    private static List<nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match> seasonMatches(
            RouteRepository repo, List<nl.paree.climbpro.data.route.StoredClimbAttempt> attempts,
            long[] range) {
        List<nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match> matches = new ArrayList<>();
        java.util.Set<String> seenClimbIds = new java.util.HashSet<>();
        for (nl.paree.climbpro.data.route.RouteCatalogEntry entry : repo.loadCatalog()) {
            try {
                StoredRoute route = repo.loadRoute(entry.routeId);
                for (nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match m
                        : nl.paree.climbpro.domain.climb.SeasonClimbFilter.climbsInPeriod(
                                java.util.Collections.singletonList(route), attempts,
                                range[0], range[1])) {
                    if (seenClimbIds.add(nl.paree.climbpro.domain.climb.ClimbIdentity.of(m.climb))) {
                        matches.add(m);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Skipping unreadable route " + entry.routeId, e);
            }
        }
        return matches;
    }

    /**
     * Home climb (issue #92) without a usable zone centre: draw and persist one before
     * sharing, exactly like the single-climb export does.
     */
    private static void ensurePrivacyCentre(RouteRepository repo,
                                            nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match m,
                                            int privacyRadiusM) throws IOException {
        if (!m.climb.isHome || nl.paree.climbpro.domain.climb.CoordinateFuzzer
                .isUsableZoneCentre(m.climb.privacyCentreLat, m.climb.privacyCentreLon,
                        m.climb.startLat, m.climb.startLon, privacyRadiusM)) {
            return;
        }
        double[] centre = nl.paree.climbpro.domain.climb.CoordinateFuzzer
                .randomZoneCentre(m.climb.startLat, m.climb.startLon,
                        privacyRadiusM, new java.security.SecureRandom());
        repo.setClimbPrivacyCentre(m.route.routeId, m.climbIndex, centre[0], centre[1]);
        m.climb.privacyCentreLat = centre[0];
        m.climb.privacyCentreLon = centre[1];
    }

    /** The export entry with the climb's best segment splits and PR time. */
    private static nl.paree.climbpro.domain.climb.BatchClimbGpxWriter.Entry exportEntry(
            nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match m,
            List<nl.paree.climbpro.data.route.StoredClimbAttempt> attempts,
            Map<String, nl.paree.climbpro.domain.climb.LogbookCalculator.Summary> summaries) {
        int segCount = m.climb.segments != null ? m.climb.segments.size() : 0;
        String climbId = nl.paree.climbpro.domain.climb.ClimbIdentity.of(m.climb);
        int[] bestSplitSec = nl.paree.climbpro.domain.climb.SegmentPrCalculator
                .bestSplits(climbId, segCount, attempts);
        nl.paree.climbpro.domain.climb.LogbookCalculator.Summary summary = summaries.get(climbId);
        Integer bestElapsedSec = summary != null ? summary.prSec : null;
        return new nl.paree.climbpro.domain.climb.BatchClimbGpxWriter.Entry(
                m.route, m.climb, m.climbIndex, bestSplitSec, bestElapsedSec);
    }

    private void exportSeason(int year) {
        executor.execute(() -> {
            try {
                RouteRepository repo = new RouteRepository(this);
                nl.paree.climbpro.data.route.ClimbAttemptRepository attemptRepo =
                        new nl.paree.climbpro.data.route.ClimbAttemptRepository(this);
                List<nl.paree.climbpro.data.route.StoredClimbAttempt> attempts = attemptRepo.loadAll();

                long[] range = nl.paree.climbpro.domain.climb.SeasonClimbFilter.yearRange(
                        year, java.time.ZoneId.systemDefault());
                List<nl.paree.climbpro.domain.climb.SeasonClimbFilter.Match> matches =
                        seasonMatches(repo, attempts, range);

                if (matches.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.route_list_season_no_climbs, year),
                            Toast.LENGTH_SHORT).show());
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
                    ensurePrivacyCentre(repo, m, privacyRadiusM);
                    exportEntries.add(exportEntry(m, attempts, summaries));
                }

                String gpx = nl.paree.climbpro.domain.climb.BatchClimbGpxWriter.toGpx(exportEntries,
                        privacyRadiusM);
                File file = nl.paree.climbpro.ui.climbs.ClimbGpxExportHandoff.writeGpxFile(
                        this, gpx, "season_" + year);
                runOnUiThread(() -> shareGpxFile(file,
                        getString(R.string.route_list_season_title_year, year)));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.route_list_export_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
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
                .setTitle(R.string.menu_status_filter)
                .setSingleChoiceItems(statusFilterLabels(), current, (d, which) -> {
                    viewModel.setStatusFilter(which);
                    updateStatusFilterSubtitle(which);
                    d.dismiss();
                })
                .show();
    }

    /** Shows the active status filter in the toolbar so a filtered list isn't mistaken for missing routes. */
    private void updateStatusFilterSubtitle(int filter) {
        if (getSupportActionBar() == null) return;
        String[] labels = statusFilterLabels();
        boolean filtered = filter > RouteStatusFilter.FILTER_ALL && filter < labels.length;
        getSupportActionBar().setSubtitle(filtered
                ? getString(R.string.route_list_filter_subtitle, labels[filter]) : null);
    }

    /** Explains where MyWhoosh keeps the FIT file before opening the picker (issue #342). */
    private void showMyWhooshImportHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_list_mywhoosh_title)
                .setMessage(getString(R.string.route_list_mywhoosh_message,
                        MyWhooshRouteStore.COLLECTION))
                .setPositiveButton(R.string.route_list_mywhoosh_pick,
                        (d, w) -> myWhooshPicker.launch(new String[]{"*/*"}))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void importGpx(android.net.Uri uri) {
        importRoute(uri, false);
    }

    /**
     * Parses a GPX (or, for {@code myWhoosh}, a MyWhoosh FIT/GPX export) and runs the climb
     * pipeline. Virtual MyWhoosh rides without GPS skip the duplicate check: their synthetic
     * coordinates all start at 0,0 and would falsely match each other.
     */
    private void importRoute(android.net.Uri uri, boolean myWhoosh) {
        executor.execute(() -> {
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open file");
                bytes = readStream(in);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.route_list_import_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
                return;
            }
            importBytes(bytes, myWhoosh ? fileTitle(uri) : uri.getLastPathSegment(), myWhoosh);
        });
    }

    /**
     * Runs the climb pipeline on an already-read GPX (or MyWhoosh FIT/GPX) document and saves
     * it under {@code name}. Shared by file import and share-link import (issue #210).
     * Must run on {@link #executor}.
     */
    private void importBytes(byte[] bytes, String name, boolean myWhoosh) {
        try {
            boolean virtual = false;
            List<RoutePoint> raw;
            if (myWhoosh) {
                MyWhooshRouteReader.Result read = MyWhooshRouteReader.read(bytes);
                raw = read.points;
                virtual = read.virtual;
            } else {
                raw = GpxParser.parse(new java.io.ByteArrayInputStream(bytes));
            }
            List<RoutePoint> withDist  = CumulativeDistance.compute(raw);
            List<RoutePoint> smoothed  = ElevationSmoother.smooth(withDist, 5);
            List<RoutePoint> simple    = myWhoosh
                    ? MyWhooshRouteReader.simplify(smoothed, virtual)
                    : RouteSimplifier.simplify(smoothed, 5.0);
            List<Climb>      climbs    = ClimbDetector.detect(simple);
            int detectedSurface = nl.paree.climbpro.domain.segment.SurfaceTypeDetector
                    .detectFromGpxBytes(bytes);
            final boolean isVirtual = virtual;

            RouteRepository repo = new RouteRepository(this);
            List<DuplicateClimbMatcher.Match> duplicates = isVirtual
                    ? new ArrayList<>()
                    : DuplicateClimbMatcher.findDuplicates(climbs, repo.loadCatalog(),
                            ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M);

            if (duplicates.isEmpty()) {
                finishImport(name, bytes, simple, climbs, detectedSurface, myWhoosh, isVirtual);
            } else {
                runOnUiThread(() -> promptDuplicateResolution(duplicates,
                        () -> executor.execute(() -> finishImport(name, bytes, simple, climbs,
                                detectedSurface, myWhoosh, isVirtual))));
            }
        } catch (GpxParseException e) {
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.route_list_gpx_error, e.getMessage()),
                    Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.route_list_import_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show());
        }
    }

    /**
     * Share-link import (issue #210): asks for a Komoot or RideWithGPS link, prefilled with
     * the clipboard when it holds one.
     */
    private void showShareLinkDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.share_link_hint);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        String clip = clipboardText();
        if (clip != null && ShareLink.find(clip) != null) {
            input.setText(clip.trim());
        }
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.share_link_title)
                .setMessage(R.string.share_link_message)
                .setView(box)
                .setPositiveButton(R.string.share_link_import,
                        (d, w) -> importShareLink(input.getText().toString()))
                .setNegativeButton(R.string.share_link_cancel, null)
                .show();
    }

    private String clipboardText() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            return text != null ? text.toString() : null;
        } catch (RuntimeException e) {
            return null; // clipboard access can be refused while the app is not focused
        }
    }

    /** Text shared to ClimbPro via ACTION_SEND text/plain (issue #210). */
    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        // Consume the intent so a rotation or recreation does not import twice.
        intent.setAction(Intent.ACTION_MAIN);
        intent.removeExtra(Intent.EXTRA_TEXT);
        importShareLink(text);
    }

    private void importShareLink(String text) {
        ShareLink link = ShareLink.find(text);
        if (link == null) {
            Toast.makeText(this, R.string.share_link_not_recognised, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.share_link_fetching, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            ShareLinkRouteFetcher.Result result;
            try {
                result = new ShareLinkRouteFetcher().fetch(link);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.share_link_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
                return;
            }
            importBytes(result.gpx, result.name, false);
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
        String message = getString(R.string.route_list_duplicate_message,
                duplicates.size(), String.join(", ", routeNames));

        new AlertDialog.Builder(this)
                .setTitle(R.string.route_list_duplicate_title)
                .setMessage(message)
                .setPositiveButton(R.string.route_list_duplicate_merge, (d, w) ->
                        Toast.makeText(this, R.string.route_list_duplicate_skipped,
                                Toast.LENGTH_LONG).show())
                .setNegativeButton(R.string.route_list_duplicate_import_anyway,
                        (d, w) -> onImportAnyway.run())
                .setNeutralButton(R.string.action_cancel, null)
                .show();
    }

    /** Gives every climb of a freshly imported route the detected surface type. */
    private static void applyDetectedSurface(RouteRepository repo, String routeId, int surface) {
        try {
            StoredRoute saved = repo.loadRoute(routeId);
            if (saved.climbs != null) {
                for (int ci = 0; ci < saved.climbs.size(); ci++) {
                    repo.setBulkClimbSurfaceType(routeId, ci, surface);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Surface type detection failed: " + e.getMessage());
        }
    }

    private void finishImport(String name, byte[] bytes, List<RoutePoint> simple,
                              List<Climb> climbs, int detectedSurface,
                              boolean myWhoosh, boolean virtual) {
        try {
            String prefix = myWhoosh ? "mywhoosh_" : "gpx_";
            String routeId = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            RouteRepository repo = new RouteRepository(this);
            if (myWhoosh) {
                new MyWhooshRouteStore(this).save(routeId, name, sha256(bytes), virtual,
                        simple, climbs);
            } else {
                StoredRoute stored = new StoredRoute();
                stored.routeId      = routeId;
                stored.name         = name;
                stored.importedAtMs = System.currentTimeMillis();
                stored.sourceHash   = sha256(bytes);
                repo.saveRoute(stored, simple, climbs);
            }
            if (detectedSurface != SurfaceType.UNKNOWN) {
                applyDetectedSurface(repo, routeId, detectedSurface);
            }
            runOnUiThread(() -> {
                viewModel.loadRoutes();
                Toast.makeText(this,
                        getString(R.string.route_list_imported, climbs.size()),
                        Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.route_list_import_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show());
        }
    }

    /** "primary:Download/Alula climb.fit" -> "Alula climb". */
    private static String fileTitle(android.net.Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "rit";
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf(':')) + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
