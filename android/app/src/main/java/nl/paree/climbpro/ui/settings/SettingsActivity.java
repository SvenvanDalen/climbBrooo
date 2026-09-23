package nl.paree.climbpro.ui.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.databinding.ActivitySettingsBinding;

public final class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SettingsViewModel       viewModel;

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
