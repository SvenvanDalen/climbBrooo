package nl.paree.climbpro.ui.climbs;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.climb.RegionalTopClimbs;
import nl.paree.climbpro.service.RouteSyncWorker;

import java.util.ArrayList;
import java.util.List;

/**
 * "Top 10 zwaarste klimmen in je regio" (issue #211): the hardest known climbs within a
 * radius of the phone's location (or a route start), ranked by
 * {@link nl.paree.climbpro.domain.climb.DifficultyScoreCalculator}. Phone-only; no watch or
 * protocol involvement. Tapping a row opens the climb detail screen.
 */
public final class TopClimbsActivity extends AppCompatActivity {

    private static final int DEFAULT_RADIUS_KM = 30;

    private TopClimbsViewModel viewModel;
    private EditText radiusInput;
    private TextView startLabel;
    private TextView summary;
    private LinearLayout resultList;
    private View progress;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (hasLocationPermission()) {
                            viewModel.useLastKnownLocation();
                        } else {
                            Toast.makeText(this, R.string.top_climbs_no_permission,
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    public static Intent intentFor(Context context) {
        return new Intent(context, TopClimbsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_top_climbs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        radiusInput = findViewById(R.id.radiusInput);
        startLabel = findViewById(R.id.startLabel);
        summary = findViewById(R.id.summary);
        resultList = findViewById(R.id.resultList);
        progress = findViewById(R.id.progress);

        if (savedInstanceState == null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            int radiusKm = prefs.getInt(RouteSyncWorker.PREF_RADIUS_M, DEFAULT_RADIUS_KM * 1000) / 1000;
            radiusInput.setText(String.valueOf(radiusKm > 0 ? radiusKm : DEFAULT_RADIUS_KM));
        }

        viewModel = new ViewModelProvider(this).get(TopClimbsViewModel.class);
        viewModel.start().observe(this, p -> startLabel.setText(p == null
                ? getString(R.string.top_climbs_start_none)
                : getString(R.string.top_climbs_start, p.label)));
        viewModel.result().observe(this, this::render);
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        viewModel.busy().observe(this,
                b -> progress.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));

        findViewById(R.id.useLocationButton).setOnClickListener(v -> requestLocation());
        findViewById(R.id.pickRouteStartButton).setOnClickListener(v -> pickRouteStart());
        findViewById(R.id.searchButton).setOnClickListener(v -> search());

        // Default to the phone's location when we may already read it; no permission prompt
        // without an explicit tap.
        if (savedInstanceState == null && hasLocationPermission()) {
            viewModel.useLastKnownLocation();
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (hasLocationPermission()) {
            viewModel.useLastKnownLocation();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void pickRouteStart() {
        viewModel.loadRouteStarts(starts -> runOnUiThread(() -> {
            if (isFinishing()) return;
            if (starts.isEmpty()) {
                Toast.makeText(this, R.string.top_climbs_no_routes, Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> labels = new ArrayList<>();
            for (TopClimbsViewModel.StartPoint s : starts) labels.add(s.label);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.top_climbs_pick_route_title)
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        TopClimbsViewModel.StartPoint s = starts.get(which);
                        viewModel.setStart(new TopClimbsViewModel.StartPoint(
                                s.lat, s.lon, getString(R.string.top_climbs_route_start, s.label)));
                    })
                    .show();
        }));
    }

    private void search() {
        int radiusKm = -1;
        try {
            radiusKm = Integer.parseInt(radiusInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // handled below
        }
        if (radiusKm <= 0) {
            radiusInput.setError(getString(R.string.top_climbs_radius_error));
            return;
        }
        viewModel.search(radiusKm);
    }

    private void render(TopClimbsViewModel.Result r) {
        resultList.removeAllViews();
        if (r == null) {
            summary.setVisibility(View.GONE);
            return;
        }
        summary.setVisibility(View.VISIBLE);
        if (r.climbs.isEmpty()) {
            summary.setText(R.string.top_climbs_empty);
            return;
        }
        summary.setText(getString(R.string.top_climbs_summary, r.climbs.size(), r.radiusKm));

        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (int i = 0; i < r.climbs.size(); i++) {
            RegionalTopClimbs.Ranked ranked = r.climbs.get(i);
            RegionalTopClimbs.Candidate c = ranked.candidate;
            TextView row = new TextView(this);
            row.setPadding(0, padding, 0, padding);
            row.setBackgroundResource(ripple.resourceId);
            row.setText(getString(R.string.top_climbs_row,
                    i + 1, c.name, ranked.score, c.elevationGainM, c.lengthM / 1000.0,
                    c.avgGradient * 100.0, ranked.distanceM / 1000.0));
            row.setOnClickListener(v -> startActivity(
                    ClimbDetailActivity.intentFor(this, c.routeId, c.climbIndex)));
            resultList.addView(row);
        }
    }
}
