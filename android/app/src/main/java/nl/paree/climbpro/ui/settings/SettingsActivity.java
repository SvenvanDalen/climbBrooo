package nl.paree.climbpro.ui.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.health.connect.client.PermissionController;
import androidx.lifecycle.ViewModelProvider;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.backup.BackupArchive;
import nl.paree.climbpro.data.backup.BackupRetention;
import nl.paree.climbpro.data.backup.LocalBackupService;
import nl.paree.climbpro.data.health.HealthConnectGateway;
import nl.paree.climbpro.databinding.ActivitySettingsBinding;
import nl.paree.climbpro.domain.climb.CoordinateFuzzer;
import nl.paree.climbpro.service.AutoBackupWorker;

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

        viewModel.stravaSignedIn().observe(this, signedIn -> {
            binding.btnStravaAuth.setText(Boolean.TRUE.equals(signedIn)
                    ? "Sign out of Strava" : "Sign in to Strava");
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
                binding.radiusLabel.setText(km + " km");
            }
        });

        viewModel.privacyRadiusM().observe(this, meters -> {
            if (meters != null) {
                binding.privacyRadiusSeekBar.setProgress(
                        meters - CoordinateFuzzer.MIN_PRIVACY_RADIUS_M);
                binding.privacyRadiusLabel.setText(meters + " m");
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
            binding.suggestedFtp.setText("Voorgestelde FTP: " + suggestion + " W (toepassen?)");
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
            Toast.makeText(this, "FTP bijgewerkt", Toast.LENGTH_SHORT).show();
        });

        binding.btnSaveProfile.setOnClickListener(v -> {
            int ftp = parseIntSafe(binding.inputFtp.getText().toString());
            double rider = parseDoubleSafe(binding.inputRiderWeight.getText().toString());
            double bike = parseDoubleSafe(binding.inputBikeWeight.getText().toString());
            int intensity = currentRideIntensityPct();
            viewModel.saveRiderProfile(ftp, rider, bike, intensity);
            Toast.makeText(this, "Profiel opgeslagen", Toast.LENGTH_SHORT).show();
        });

        binding.radioRoute.setOnClickListener(v ->
                viewModel.setSyncMode("route"));
        binding.radioRadius.setOnClickListener(v ->
                viewModel.setSyncMode("radius"));

        binding.radiusSeekBar.setMax(100);
        binding.radiusSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean user) {
                int km = Math.max(1, progress);
                binding.radiusLabel.setText(km + " km");
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
                binding.privacyRadiusLabel.setText(meters + " m");
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

    private void renderBackupStatus() {
        LocalBackupService service = new LocalBackupService(this);
        binding.backupStatus.setText(String.join("\n", service.status()));
        binding.btnBackupAuto.setText(service.autoBackupEnabled()
                ? "Automatische back-up uitzetten" : "Automatische back-up aanzetten");
    }

    private void createBackup(Uri uri) {
        backupExecutor.execute(() -> {
            String msg;
            try {
                BackupArchive.Summary s = new LocalBackupService(this).writeTo(uri);
                msg = "Back-up gemaakt: " + s.fileCount + " bestand(en)";
            } catch (Exception e) {
                msg = "Back-up mislukt: " + LocalBackupService.reason(e);
            }
            String toast = msg;
            runOnUiThread(() -> Toast.makeText(this, toast, Toast.LENGTH_LONG).show());
        });
    }

    private void confirmRestore(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("Back-up terugzetten?")
                .setMessage("Routes, pogingen, foto's, collecties, planning en instellingen "
                        + "worden vervangen door die uit de back-up. Strava moet je daarna "
                        + "opnieuw koppelen.")
                .setPositiveButton("Terugzetten", (d, w) -> restoreBackup(uri))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void restoreBackup(Uri uri) {
        backupExecutor.execute(() -> {
            String msg;
            try {
                BackupArchive.Summary s = new LocalBackupService(this).restoreFrom(uri);
                msg = "Back-up teruggezet: " + s.fileCount + " bestand(en)";
            } catch (Exception e) {
                msg = "Terugzetten mislukt: " + LocalBackupService.reason(e);
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
            Toast.makeText(this, "Geen blijvende toegang tot deze map", Toast.LENGTH_LONG).show();
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
                msg = "Automatische back-up staat aan; eerste back-up gemaakt";
            } catch (Exception e) {
                msg = "Automatische back-up staat aan, maar de eerste back-up mislukte: "
                        + LocalBackupService.reason(e);
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
        Toast.makeText(this, "Automatische back-up uitgezet", Toast.LENGTH_SHORT).show();
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
                        status = "Health Connect is niet beschikbaar op deze telefoon.";
                        break;
                    case NEEDS_UPDATE:
                        status = "Installeer of update de Health Connect-app om te koppelen.";
                        break;
                    default:
                        status = linked
                                ? "Gekoppeld (" + grantedCount + " van "
                                        + HealthConnectGateway.PERMISSIONS.size() + " toestemmingen)"
                                : "Nog niet gekoppeld.";
                }
                binding.healthStatus.setText(status);
                binding.btnHealthConnect.setEnabled(
                        availability != HealthConnectGateway.Availability.UNAVAILABLE);
                binding.btnHealthConnect.setText(availability
                        == HealthConnectGateway.Availability.NEEDS_UPDATE
                        ? "Health Connect installeren"
                        : linked ? "Toestemmingen wijzigen" : "Koppelen met Health Connect");
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
                Toast.makeText(this, "Open de Play Store en installeer Health Connect",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        healthPermissionLauncher.launch(HealthConnectGateway.PERMISSIONS);
    }

    private void exportRidesToHealth() {
        Toast.makeText(this, "Ritten ophalen uit Strava…", Toast.LENGTH_SHORT).show();
        healthExecutor.execute(() -> {
            String msg;
            try {
                int n = new HealthConnectGateway(this).exportRides();
                msg = n == 0 ? "Geen nieuwe ritten" : n + " rit(ten) naar Health Connect geschreven";
            } catch (Exception e) {
                msg = "Schrijven mislukt: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
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
                    Toast.makeText(this, "Geen gewicht gevonden in Health Connect (laatste 90 dagen)",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // Only fills the field; the user still saves the profile, like any edit.
                binding.inputRiderWeight.setText(formatKg(weight));
                Toast.makeText(this, "Gewicht " + formatKg(weight)
                        + " kg ingevuld; tik op Save rider profile", Toast.LENGTH_LONG).show();
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
