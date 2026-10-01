package nl.paree.climbpro.ui.settings;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.health.connect.client.PermissionController;
import androidx.lifecycle.ViewModelProvider;

import androidx.preference.PreferenceManager;
import androidx.work.WorkInfo;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.backup.BackupArchive;
import nl.paree.climbpro.data.backup.BackupRetention;
import nl.paree.climbpro.data.backup.LocalBackupService;
import nl.paree.climbpro.data.health.HealthConnectGateway;
import nl.paree.climbpro.data.settings.UnitPreferencesRepository;
import nl.paree.climbpro.domain.units.UnitPreferences;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.databinding.ActivitySettingsBinding;
import nl.paree.climbpro.domain.climb.CoordinateFuzzer;
import nl.paree.climbpro.domain.segment.GradientPalette;
import nl.paree.climbpro.service.AutoBackupWorker;
import nl.paree.climbpro.service.StravaHistoryBackfillWorker;
import nl.paree.climbpro.service.SyncScheduler;
import nl.paree.climbpro.service.WetRideReminderJob;
import nl.paree.climbpro.ui.climbs.SegmentColorPalette;

import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SettingsViewModel       viewModel;
    private final ExecutorService healthExecutor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<Set<String>> healthPermissionLauncher =
            registerForActivityResult(
                    PermissionController.createRequestPermissionResultContract(),
                    granted -> renderHealthStatus());
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();

    // Cleaning reminder (issue #234): ask for the notification grant when it is switched on.
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, R.string.settings_wet_no_notifications,
                            Toast.LENGTH_LONG).show();
                    // The switch stayed on when the user tapped it; without the grant the
                    // reminder can never fire, so turn it (and the preference) back off rather
                    // than leave a setting on that silently does nothing.
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putBoolean(WetRideReminderJob.PREF_ENABLED, false).apply();
                    binding.switchWetRideReminder.setChecked(false);
                }
            });

    // Radius mode (issue #310) searches around the last known fix, which needs a location grant.
    private final ActivityResultLauncher<String> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    viewModel.refreshRadiusLocation();
                } else {
                    Toast.makeText(this, R.string.settings_radius_no_permission,
                            Toast.LENGTH_LONG).show();
                }
            });

    // Back-up (issue #257): Storage Access Framework pickers, so the target can be a local
    // folder or a cloud provider such as Google Drive.
    private final ActivityResultLauncher<String> backupCreator = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(BackupRetention.MIME),
            uri -> { if (uri != null) createBackup(uri); });
    private final ActivityResultLauncher<String[]> backupPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) confirmRestore(uri); });
    private final ActivityResultLauncher<Uri> backupFolderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> { if (uri != null) enableAutoBackup(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding   = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        viewModel = new ViewModelProvider(this).get(SettingsViewModel.class);

        renderLanguageButton();
        binding.btnLanguage.setOnClickListener(v -> showLanguageDialog());

        viewModel.stravaSignedIn().observe(this, signedIn -> {
            binding.btnStravaAuth.setText(Boolean.TRUE.equals(signedIn)
                    ? R.string.settings_strava_sign_out : R.string.settings_strava_sign_in);
        });

        viewModel.syncMode().observe(this, mode -> {
            boolean isRadius = "radius".equals(mode);
            binding.radioRoute.setChecked(!isRadius);
            binding.radioRadius.setChecked(isRadius);
            binding.radiusContainer.setVisibility(isRadius ? android.view.View.VISIBLE : android.view.View.GONE);
        });

        viewModel.radiusKm().observe(this, km -> {
            if (km != null) {
                binding.radiusSeekBar.setProgress(km);
                binding.radiusLabel.setText(getString(R.string.unit_km_value, km));
            }
        });

        viewModel.privacyRadiusM().observe(this, meters -> {
            if (meters != null) {
                binding.privacyRadiusSeekBar.setProgress(
                        meters - CoordinateFuzzer.MIN_PRIVACY_RADIUS_M);
                binding.privacyRadiusLabel.setText(getString(R.string.unit_m_value, meters));
            }
        });

        viewModel.syncStatus().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        viewModel.riderProfile().observe(this, profile -> {
            if (profile == null) return;
            binding.inputFtp.setText(profile.ftpWatts > 0 ? String.valueOf(profile.ftpWatts) : "");
            binding.inputRiderWeight.setText(
                    profile.riderWeightKg > 0 ? formatKg(profile.riderWeightKg) : "");
            binding.inputBikeWeight.setText(
                    profile.bikeWeightKg > 0 ? formatKg(profile.bikeWeightKg) : "");
            binding.inputRideIntensity.setText(String.valueOf(
                    profile.rideIntensityPct > 0
                            ? profile.rideIntensityPct
                            : nl.paree.climbpro.domain.power.RiderProfile.DEFAULT_RIDE_INTENSITY_PCT));
        });

        // Issue #20: suggested FTP re-estimate from repeated climb performances. Shown
        // only when computable and meaningfully different (SettingsViewModel gates this);
        // tapping it explicitly saves the suggestion — it is never applied automatically.
        viewModel.suggestedFtpWatts().observe(this, suggestion -> {
            if (suggestion == null) {
                binding.suggestedFtp.setVisibility(android.view.View.GONE);
                return;
            }
            binding.suggestedFtp.setText(getString(R.string.settings_suggested_ftp, suggestion));
            binding.suggestedFtp.setVisibility(android.view.View.VISIBLE);
        });
        // Issue #20 fix: apply the suggestion using whatever is currently typed in the
        // weight/bike/intensity fields (not the last-*saved* profile), exactly like a
        // normal "Opslaan profiel" tap would — otherwise unsaved edits to those fields
        // get silently reverted the moment the FTP suggestion is applied.
        binding.suggestedFtp.setOnClickListener(v -> {
            double rider = parseDoubleSafe(binding.inputRiderWeight.getText().toString());
            double bike = parseDoubleSafe(binding.inputBikeWeight.getText().toString());
            int intensity = currentRideIntensityPct();
            viewModel.applySuggestedFtp(rider, bike, intensity);
            Toast.makeText(this, R.string.settings_ftp_updated, Toast.LENGTH_SHORT).show();
        });

        binding.btnSaveProfile.setOnClickListener(v -> {
            int ftp = parseIntSafe(binding.inputFtp.getText().toString());
            double rider = parseDoubleSafe(binding.inputRiderWeight.getText().toString());
            double bike = parseDoubleSafe(binding.inputBikeWeight.getText().toString());
            int intensity = currentRideIntensityPct();
            viewModel.saveRiderProfile(ftp, rider, bike, intensity);
            Toast.makeText(this, R.string.settings_profile_saved, Toast.LENGTH_SHORT).show();
        });

        // Issue #31: virtual ghost at a target speed/VAM for climbs without a PR or
        // manual reference. Empty/0 switches that part off.
        viewModel.ghostTarget().observe(this, target -> {
            if (target == null) return;
            binding.inputGhostSpeed.setText(target.hasSpeed() ? formatKg(target.speedKmh) : "");
            binding.inputGhostVam.setText(target.hasVam() ? String.valueOf(target.vamMPerH) : "");
        });
        binding.btnSaveGhost.setOnClickListener(v -> {
            double speed = parseDoubleSafe(binding.inputGhostSpeed.getText().toString());
            int vam = parseIntSafe(binding.inputGhostVam.getText().toString());
            viewModel.saveGhostTarget(speed, vam);
            Toast.makeText(this, speed > 0 || vam > 0
                    ? R.string.settings_ghost_saved : R.string.settings_ghost_disabled,
                    Toast.LENGTH_SHORT).show();
        });

        binding.radioRoute.setOnClickListener(v ->
                viewModel.setSyncMode("route"));
        binding.radioRadius.setOnClickListener(v -> {
            viewModel.setSyncMode("radius");
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        });

        binding.radiusSeekBar.setMax(100);
        binding.radiusSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean user) {
                int km = Math.max(1, progress);
                binding.radiusLabel.setText(getString(R.string.unit_km_value, km));
                if (user) viewModel.setRadiusKm(km);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        // SeekBar has no API-24-safe min, so progress is an offset above the minimum radius:
        // a 0 m zone would silently disable privacy while the climb still says "gewazigd".
        binding.privacyRadiusSeekBar.setMax(
                CoordinateFuzzer.MAX_PRIVACY_RADIUS_M - CoordinateFuzzer.MIN_PRIVACY_RADIUS_M);
        binding.privacyRadiusSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean user) {
                int meters = CoordinateFuzzer.MIN_PRIVACY_RADIUS_M + progress;
                binding.privacyRadiusLabel.setText(getString(R.string.unit_m_value, meters));
                if (user) viewModel.setPrivacyRadiusM(meters);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        binding.btnStravaAuth.setOnClickListener(v -> {
            if (Boolean.TRUE.equals(viewModel.stravaSignedIn().getValue())) {
                viewModel.signOutStrava();
            } else {
                startActivity(new android.content.Intent(this,
                        nl.paree.climbpro.ui.strava.StravaAuthActivity.class));
            }
        });

        binding.btnSyncNow.setOnClickListener(v -> viewModel.syncNow());

        binding.btnStravaTitleTemplate.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, StravaTitleTemplateActivity.class)));
        binding.btnIntervalsIcu.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, IntervalsIcuSettingsActivity.class)));
        binding.btnWatchFieldLayout.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, WatchFieldLayoutActivity.class)));

        binding.btnStravaHistoryBackfill.setOnClickListener(v -> confirmHistoryBackfill());
        SyncScheduler.historyBackfillInfo(this).observe(this, this::renderHistoryBackfill);

        binding.btnBackupCreate.setOnClickListener(v -> backupCreator.launch(
                BackupRetention.fileName(System.currentTimeMillis(), ZoneId.systemDefault())));
        binding.btnBackupRestore.setOnClickListener(v -> backupPicker.launch(
                new String[]{"application/zip", "application/octet-stream"}));
        binding.btnBackupAuto.setOnClickListener(v -> {
            if (new LocalBackupService(this).autoBackupEnabled()) {
                disableAutoBackup();
            } else {
                backupFolderPicker.launch(null);
            }
        });
        renderBackupStatus();

        // Colorblind-friendly palette (issue #258): phone screens switch at once; the watch
        // gets the new 'pal' flag with the next sync, which we kick off right away.
        binding.switchColorblindPalette.setChecked(PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(GradientPalette.PREF_COLORBLIND, false));
        binding.switchColorblindPalette.setOnCheckedChangeListener((b, on) -> {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(GradientPalette.PREF_COLORBLIND, on).apply();
            SegmentColorPalette.setActive(GradientPalette.fromEnabled(on));
            try {
                SyncScheduler.triggerImmediateSync(this);
            } catch (IllegalStateException e) {
                // WorkManager not initialised (tests); the periodic sync picks it up later.
            }
        });
        bindUnitSwitches();

        // Cleaning reminder after wet rides (issue #234).
        binding.switchWetRideReminder.setChecked(PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(WetRideReminderJob.PREF_ENABLED, false));
        binding.switchWetRideReminder.setOnCheckedChangeListener((b, on) -> {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(WetRideReminderJob.PREF_ENABLED, on).apply();
            if (on) ensureNotificationPermission();
        });

        // Health Connect (issue #255).
        binding.btnHealthConnect.setOnClickListener(v -> connectHealth());
        binding.btnHealthExport.setOnClickListener(v -> exportRidesToHealth());
        binding.btnHealthWeight.setOnClickListener(v -> importWeightFromHealth());
        binding.switchHealthAuto.setChecked(PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(HealthConnectGateway.PREF_AUTO, false));
        binding.switchHealthAuto.setOnCheckedChangeListener((b, on) ->
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean(HealthConnectGateway.PREF_AUTO, on).apply());
        renderHealthStatus();
    }

    private void renderLanguageButton() {
        int index = AppLanguage.currentIndex();
        String name = index == 0
                ? getString(R.string.settings_language_system) : AppLanguage.ENDONYMS[index];
        binding.btnLanguage.setText(getString(R.string.settings_language_button, name));
    }

    /** Issue #261: per-app language; AppCompat recreates the activity in the new language. */
    private void showLanguageDialog() {
        String[] labels = new String[AppLanguage.TAGS.length];
        labels[0] = getString(R.string.settings_language_system);
        for (int i = 1; i < labels.length; i++) labels[i] = AppLanguage.ENDONYMS[i];
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_language_dialog_title)
                .setSingleChoiceItems(labels, AppLanguage.currentIndex(), (d, which) -> {
                    d.dismiss();
                    AppLanguage.apply(which);
                    renderLanguageButton();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Display units (issue #262): three independent switches, all off = metric. */
    private void bindUnitSwitches() {
        UnitPreferencesRepository repo = new UnitPreferencesRepository(this);
        UnitPreferences units = repo.load();
        binding.switchUnitsImperial.setChecked(units.imperial);
        binding.switchUnitsPsi.setChecked(units.psi);
        binding.switchUnitsFahrenheit.setChecked(units.fahrenheit);
        android.widget.CompoundButton.OnCheckedChangeListener save = (b, on) ->
                repo.save(new UnitPreferences(binding.switchUnitsImperial.isChecked(),
                        binding.switchUnitsPsi.isChecked(),
                        binding.switchUnitsFahrenheit.isChecked()));
        binding.switchUnitsImperial.setOnCheckedChangeListener(save);
        binding.switchUnitsPsi.setOnCheckedChangeListener(save);
        binding.switchUnitsFahrenheit.setOnCheckedChangeListener(save);
    }

    private void renderBackupStatus() {
        LocalBackupService service = new LocalBackupService(this);
        binding.backupStatus.setText(String.join("\n", service.status()));
        binding.btnBackupAuto.setText(service.autoBackupEnabled()
                ? R.string.settings_backup_auto_off : R.string.settings_backup_auto_on);
    }

    private void createBackup(Uri uri) {
        backupExecutor.execute(() -> {
            String msg;
            try {
                BackupArchive.Summary s = new LocalBackupService(this).writeTo(uri);
                msg = getString(R.string.settings_backup_created, s.fileCount);
            } catch (Exception e) {
                msg = getString(R.string.settings_backup_failed, LocalBackupService.reason(e));
            }
            String toast = msg;
            runOnUiThread(() -> Toast.makeText(this, toast, Toast.LENGTH_LONG).show());
        });
    }

    private void confirmRestore(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_restore_title)
                .setMessage(R.string.settings_restore_message)
                .setPositiveButton(R.string.settings_restore_confirm, (d, w) -> restoreBackup(uri))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void restoreBackup(Uri uri) {
        backupExecutor.execute(() -> {
            String msg;
            try {
                BackupArchive.Summary s = new LocalBackupService(this).restoreFrom(uri);
                msg = getString(R.string.settings_restored, s.fileCount);
            } catch (Exception e) {
                msg = getString(R.string.settings_restore_failed, LocalBackupService.reason(e));
            }
            String toast = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                viewModel.reload();
                renderBackupStatus();
            });
        });
    }

    private void enableAutoBackup(Uri treeUri) {
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.settings_backup_no_access, Toast.LENGTH_LONG).show();
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(LocalBackupService.PREF_TREE_URI, treeUri.toString())
                .apply();
        AutoBackupWorker.schedule(this);
        renderBackupStatus();
        // First backup right away so the user sees it works.
        backupExecutor.execute(() -> {
            String msg;
            try {
                new LocalBackupService(this).writeAutoBackup();
                msg = getString(R.string.settings_backup_auto_enabled);
            } catch (Exception e) {
                msg = getString(R.string.settings_backup_auto_first_failed,
                        LocalBackupService.reason(e));
            }
            String toast = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                renderBackupStatus();
            });
        });
    }

    private void disableAutoBackup() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String tree = prefs.getString(LocalBackupService.PREF_TREE_URI, null);
        if (tree != null) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(tree),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // already revoked by the user or the system
            }
        }
        prefs.edit().remove(LocalBackupService.PREF_TREE_URI).apply();
        AutoBackupWorker.cancel(this);
        renderBackupStatus();
        Toast.makeText(this, R.string.settings_backup_auto_disabled, Toast.LENGTH_SHORT).show();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void renderHealthStatus() {
        HealthConnectGateway gateway = new HealthConnectGateway(this);
        healthExecutor.execute(() -> {
            HealthConnectGateway.Availability availability = gateway.availability();
            int granted = 0;
            if (availability == HealthConnectGateway.Availability.AVAILABLE) {
                try {
                    Set<String> g = gateway.grantedPermissions();
                    for (String p : HealthConnectGateway.PERMISSIONS) if (g.contains(p)) granted++;
                } catch (Exception e) {
                    granted = -1;
                }
            }
            int grantedCount = granted;
            runOnUiThread(() -> {
                boolean linked = availability == HealthConnectGateway.Availability.AVAILABLE
                        && grantedCount > 0;
                String status;
                switch (availability) {
                    case UNAVAILABLE:
                        status = getString(R.string.settings_health_unavailable);
                        break;
                    case NEEDS_UPDATE:
                        status = getString(R.string.settings_health_needs_update);
                        break;
                    default:
                        status = linked
                                ? getString(R.string.settings_health_linked, grantedCount,
                                        HealthConnectGateway.PERMISSIONS.size())
                                : getString(R.string.settings_health_not_linked);
                }
                binding.healthStatus.setText(status);
                binding.btnHealthConnect.setEnabled(
                        availability != HealthConnectGateway.Availability.UNAVAILABLE);
                binding.btnHealthConnect.setText(availability
                        == HealthConnectGateway.Availability.NEEDS_UPDATE
                        ? R.string.settings_health_install
                        : linked ? R.string.settings_health_change_permissions
                                 : R.string.settings_health_connect);
                binding.btnHealthExport.setEnabled(linked);
                binding.btnHealthWeight.setEnabled(linked);
                binding.switchHealthAuto.setEnabled(linked);
            });
        });
    }

    private void connectHealth() {
        if (new HealthConnectGateway(this).availability()
                == HealthConnectGateway.Availability.NEEDS_UPDATE) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "market://details?id=com.google.android.apps.healthdata")));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, R.string.settings_health_open_store,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        healthPermissionLauncher.launch(HealthConnectGateway.PERMISSIONS);
    }

    private void confirmHistoryBackfill() {
        if (!Boolean.TRUE.equals(viewModel.stravaSignedIn().getValue())) {
            Toast.makeText(this, R.string.settings_strava_login_first, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_backfill_title)
                .setMessage(R.string.settings_backfill_message)
                .setPositiveButton(R.string.settings_backfill_start,
                        (d, w) -> SyncScheduler.startHistoryBackfill(this))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void renderHistoryBackfill(java.util.List<WorkInfo> infos) {
        boolean done = getSharedPreferences(StravaActivitiesRepository.PREFS, MODE_PRIVATE)
                .getBoolean(StravaActivitiesRepository.PREF_BACKFILL_DONE, false);
        WorkInfo info = infos == null || infos.isEmpty() ? null : infos.get(infos.size() - 1);
        boolean active = info != null && !info.getState().isFinished();
        binding.btnStravaHistoryBackfill.setEnabled(!done && !active);
        if (done) {
            binding.btnStravaHistoryBackfill.setText(R.string.settings_backfill_done);
            binding.stravaHistoryBackfillStatus.setVisibility(android.view.View.GONE);
            return;
        }
        if (!active) {
            binding.stravaHistoryBackfillStatus.setVisibility(android.view.View.GONE);
            return;
        }
        long cursor = info.getProgress().getLong(StravaHistoryBackfillWorker.KEY_CURSOR, 0L);
        boolean paused = info.getProgress().getBoolean(StravaHistoryBackfillWorker.KEY_PAUSED, false);
        String status = cursor > 0
                ? getString(R.string.settings_backfill_progress, java.time.format.DateTimeFormatter
                        .ofPattern("MMMM yyyy", getResources().getConfiguration().getLocales().get(0))
                        .format(java.time.Instant.ofEpochSecond(cursor).atZone(ZoneId.systemDefault())))
                : getString(R.string.settings_backfill_busy);
        if (paused) status = getString(R.string.settings_backfill_paused, status);
        binding.stravaHistoryBackfillStatus.setText(status);
        binding.stravaHistoryBackfillStatus.setVisibility(android.view.View.VISIBLE);
    }

    private void exportRidesToHealth() {
        Toast.makeText(this, R.string.settings_health_fetching, Toast.LENGTH_SHORT).show();
        healthExecutor.execute(() -> {
            String msg;
            try {
                int n = new HealthConnectGateway(this).exportRides();
                msg = n == 0 ? getString(R.string.settings_health_no_new)
                        : getString(R.string.settings_health_exported, n);
            } catch (Exception e) {
                msg = getString(R.string.settings_health_write_failed,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            String toast = msg;
            runOnUiThread(() -> Toast.makeText(this, toast, Toast.LENGTH_LONG).show());
        });
    }

    private void importWeightFromHealth() {
        healthExecutor.execute(() -> {
            Double kg;
            try {
                kg = new HealthConnectGateway(this).latestWeightKg();
            } catch (Exception e) {
                kg = null;
            }
            Double weight = kg;
            runOnUiThread(() -> {
                if (weight == null) {
                    Toast.makeText(this, R.string.settings_health_no_weight,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // Only fills the field; the user still saves the profile, like any edit.
                binding.inputRiderWeight.setText(formatKg(weight));
                Toast.makeText(this, getString(R.string.settings_health_weight_filled,
                        formatKg(weight)), Toast.LENGTH_LONG).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backupExecutor.shutdown();
        healthExecutor.shutdown();
    }

    /** Current ride-intensity field, normalized/clamped exactly like btnSaveProfile does. */
    private int currentRideIntensityPct() {
        int intensityRaw = parseIntSafe(binding.inputRideIntensity.getText().toString());
        return intensityRaw <= 0
                ? nl.paree.climbpro.domain.power.RiderProfile.DEFAULT_RIDE_INTENSITY_PCT
                : Math.max(nl.paree.climbpro.domain.power.RiderProfile.RIDE_INTENSITY_MIN_PCT,
                    Math.min(nl.paree.climbpro.domain.power.RiderProfile.RIDE_INTENSITY_MAX_PCT, intensityRaw));
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Weights are persisted as float, so widening back to double can leave noise
     * (e.g. 72.0000019). Show a single decimal; parseDoubleSafe reads it back.
     */
    private static String formatKg(double kg) {
        return String.format(java.util.Locale.US, "%.1f", kg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.reload();
    }
}
