package nl.paree.climbpro.ui.planning;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.FavoriteStartPoint;
import nl.paree.climbpro.domain.planning.LoopGenerator;
import nl.paree.climbpro.ui.routes.RouteDetailActivity;

import java.util.List;

/**
 * "Rondje-generator" (issue #202): the rider enters a distance and a start point; the app
 * suggests rides of about that length built from saved routes only (no road router). Tapping a
 * suggestion saves it as a new route and opens it, from where it can go to the watch or Garmin
 * Connect like any other route.
 */
public final class LoopGeneratorActivity extends AppCompatActivity {

    private static final double MAX_TARGET_KM = 400;

    private LoopGeneratorViewModel viewModel;
    private EditText distanceInput;
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
                            Toast.makeText(this, R.string.loop_gen_no_permission,
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    public static Intent intentFor(Context context) {
        return new Intent(context, LoopGeneratorActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loop_generator);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        distanceInput = findViewById(R.id.distanceInput);
        startLabel = findViewById(R.id.startLabel);
        summary = findViewById(R.id.summary);
        resultList = findViewById(R.id.resultList);
        progress = findViewById(R.id.progress);

        viewModel = new ViewModelProvider(this).get(LoopGeneratorViewModel.class);
        viewModel.start().observe(this, p -> startLabel.setText(p == null
                ? getString(R.string.loop_gen_start_none)
                : getString(R.string.loop_gen_start, p.label)));
        viewModel.suggestions().observe(this, this::render);
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        viewModel.busy().observe(this,
                b -> progress.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));
        viewModel.savedRouteId().observe(this, id -> {
            if (id == null) return;
            viewModel.consumeSavedRouteId();
            startActivity(RouteDetailActivity.intentFor(this, id));
        });

        findViewById(R.id.useLocationButton).setOnClickListener(v -> requestLocation());
        findViewById(R.id.pickFavoriteStartButton).setOnClickListener(v -> pickFavoriteStart());
        findViewById(R.id.suggestButton).setOnClickListener(v -> suggest());

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

    private void pickFavoriteStart() {
        viewModel.loadFavorites(favorites -> runOnUiThread(() -> {
            if (isFinishing()) return;
            Intent manage = FavoriteStartPointsActivity.intentFor(this);
            if (favorites.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.fav_start_none_yet)
                        .setPositiveButton(R.string.fav_start_manage, (d, w) -> startActivity(manage))
                        .setNegativeButton(R.string.fav_start_cancel, null)
                        .show();
                return;
            }
            String[] labels = new String[favorites.size()];
            for (int i = 0; i < favorites.size(); i++) labels[i] = favorites.get(i).name;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.fav_start_pick_title)
                    .setItems(labels, (d, which) -> {
                        FavoriteStartPoint f = favorites.get(which);
                        viewModel.setStart(new ElevationTargetViewModel.StartPoint(
                                f.lat, f.lon, f.name));
                    })
                    .setNeutralButton(R.string.fav_start_manage, (d, w) -> startActivity(manage))
                    .show();
        }));
    }

    private void suggest() {
        double km;
        try {
            km = Double.parseDouble(distanceInput.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            km = -1;
        }
        if (km < 2 || km > MAX_TARGET_KM) {
            distanceInput.setError(getString(R.string.loop_gen_invalid_distance));
            return;
        }
        viewModel.suggest(km);
    }

    private void render(List<LoopGenerator.Suggestion> list) {
        resultList.removeAllViews();
        if (list == null) {
            summary.setVisibility(View.GONE);
            return;
        }
        summary.setVisibility(View.VISIBLE);
        summary.setText(list.isEmpty() ? R.string.loop_gen_none : R.string.loop_gen_tap_hint);
        int padding = Math.round(10 * getResources().getDisplayMetrics().density);
        for (LoopGenerator.Suggestion s : list) {
            TextView row = new TextView(this);
            row.setPadding(0, padding, 0, padding);
            row.setText(getString(R.string.loop_gen_row, s.lengthM / 1000.0, kindLabel(s.kind),
                    String.join(" + ", s.routeNames), s.approachM / 1000.0));
            row.setOnClickListener(v -> viewModel.save(s));
            resultList.addView(row);
        }
    }

    private String kindLabel(LoopGenerator.Kind kind) {
        switch (kind) {
            case LOOP: return getString(R.string.loop_gen_kind_loop);
            case SHORTENED_LOOP: return getString(R.string.loop_gen_kind_shortened);
            case COMBINED: return getString(R.string.loop_gen_kind_combined);
            default: return getString(R.string.loop_gen_kind_out_and_back);
        }
    }
}
