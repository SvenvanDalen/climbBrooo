package nl.paree.climbpro.ui.planning;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.planning.ElevationTargetPlanner;
import nl.paree.climbpro.service.RouteSyncWorker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * "Hoogtemeter-doel" screen (issue #68): the rider enters a target elevation gain for the
 * day, and the app suggests a combination of known nearby climbs approximating it, in a
 * visiting order. Phone-only; no watch/protocol involvement. The suggestion can be added to
 * the existing Klimplanning — no GPX is fabricated, the road route itself must be planned in
 * Garmin/Strava/Komoot.
 */
public final class ElevationTargetActivity extends AppCompatActivity {

    private static final int DEFAULT_RADIUS_KM = 30;

    private ElevationTargetViewModel viewModel;
    private EditText targetInput;
    private EditText radiusInput;
    private EditText maxClimbsInput;
    private TextView startLabel;
    private TextView summary;
    private TextView disclaimer;
    private LinearLayout resultList;
    private View addToPlanningButton;
    private View progress;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (hasLocationPermission()) {
                            viewModel.useLastKnownLocation();
                        } else {
                            Toast.makeText(this,
                                    "Geen locatietoestemming — kies de start van een route.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    public static Intent intentFor(Context context) {
        return new Intent(context, ElevationTargetActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_elevation_target);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        targetInput = findViewById(R.id.targetInput);
        radiusInput = findViewById(R.id.radiusInput);
        maxClimbsInput = findViewById(R.id.maxClimbsInput);
        startLabel = findViewById(R.id.startLabel);
        summary = findViewById(R.id.summary);
        disclaimer = findViewById(R.id.disclaimer);
        resultList = findViewById(R.id.resultList);
        addToPlanningButton = findViewById(R.id.addToPlanningButton);
        progress = findViewById(R.id.progress);

        if (savedInstanceState == null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            int radiusKm = prefs.getInt(RouteSyncWorker.PREF_RADIUS_M, DEFAULT_RADIUS_KM * 1000) / 1000;
            radiusInput.setText(String.valueOf(radiusKm > 0 ? radiusKm : DEFAULT_RADIUS_KM));
        }

        viewModel = new ViewModelProvider(this).get(ElevationTargetViewModel.class);
        viewModel.start().observe(this, p -> startLabel.setText(p == null
                ? "Startpunt: nog niet gekozen"
                : "Startpunt: " + p.label));
        viewModel.suggestion().observe(this, this::render);
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        viewModel.busy().observe(this,
                b -> progress.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));

        findViewById(R.id.useLocationButton).setOnClickListener(v -> requestLocation());
        findViewById(R.id.pickRouteStartButton).setOnClickListener(v -> pickRouteStart());
        findViewById(R.id.suggestButton).setOnClickListener(v -> suggest());
        addToPlanningButton.setOnClickListener(v -> pickDateTimeAndAdd());

        // Default to the phone's location when we may already read it; otherwise wait for
        // the user to choose (no permission prompt without an explicit tap).
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
                Toast.makeText(this, "Geen routes gevonden om als start te gebruiken",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> labels = new ArrayList<>();
            for (ElevationTargetViewModel.RouteStart s : starts) labels.add(s.label);
            new AlertDialog.Builder(this)
                    .setTitle("Start van welke route?")
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        ElevationTargetViewModel.RouteStart s = starts.get(which);
                        viewModel.setStart(new ElevationTargetViewModel.StartPoint(
                                s.lat, s.lon, "start van " + s.label));
                    })
                    .show();
        }));
    }

    private void suggest() {
        int target = parseInt(targetInput, -1);
        if (target <= 0 || target > ElevationTargetPlanner.MAX_TARGET_M) {
            targetInput.setError("Vul een doel tussen 1 en "
                    + ElevationTargetPlanner.MAX_TARGET_M + " hm in");
            return;
        }
        int radiusKm = parseInt(radiusInput, -1);
        if (radiusKm <= 0) {
            radiusInput.setError("Vul een straal in km in");
            return;
        }
        int maxClimbs = parseInt(maxClimbsInput, 0);
        if (maxClimbs > ElevationTargetPlanner.MAX_CLIMBS_CAP) {
            maxClimbsInput.setError("Maximaal " + ElevationTargetPlanner.MAX_CLIMBS_CAP);
            return;
        }
        viewModel.suggest(target, radiusKm, maxClimbs);
    }

    private static int parseInt(EditText input, int fallback) {
        String s = input.getText().toString().trim();
        if (s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void render(ElevationTargetPlanner.Suggestion s) {
        resultList.removeAllViews();
        if (s == null) {
            summary.setVisibility(View.GONE);
            disclaimer.setVisibility(View.GONE);
            addToPlanningButton.setVisibility(View.GONE);
            return;
        }
        summary.setVisibility(View.VISIBLE);
        if (s.status != ElevationTargetPlanner.Status.OK) {
            summary.setText(failureText(s.status));
            disclaimer.setVisibility(View.GONE);
            addToPlanningButton.setVisibility(View.GONE);
            return;
        }

        String delta = s.deltaM == 0 ? "precies op doel"
                : (s.deltaM > 0 ? s.deltaM + " hm boven doel" : (-s.deltaM) + " hm onder doel");
        summary.setText(String.format(Locale.getDefault(),
                "%d klimmen · %d hm (doel %d hm, %s)\nKlimmen samen %.1f km · verbindingen ≈ %.0f km hemelsbreed (incl. terug naar start)\n%d bekende klimmen binnen de straal",
                s.climbs.size(), s.totalGainM, s.targetGainM, delta,
                s.climbLengthM / 1000.0, s.straightLineConnectM / 1000.0, s.candidatesInRadius));

        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < s.climbs.size(); i++) {
            ElevationTargetPlanner.Candidate c = s.climbs.get(i);
            TextView row = new TextView(this);
            row.setPadding(0, padding, 0, 0);
            row.setText(String.format(Locale.getDefault(),
                    "%d. %s\n    %d hm · %.1f km · %.1f km hemelsbreed vanaf %s",
                    i + 1, c.name, c.elevationGainM, c.lengthM / 1000.0,
                    s.legsM[i] / 1000.0, i == 0 ? "start" : "vorige top"));
            resultList.addView(row);
        }
        disclaimer.setVisibility(View.VISIBLE);
        addToPlanningButton.setVisibility(View.VISIBLE);
    }

    private static String failureText(ElevationTargetPlanner.Status status) {
        switch (status) {
            case INVALID_TARGET: return "Ongeldig hoogtemeter-doel.";
            case INVALID_RADIUS: return "Ongeldige straal.";
            case NO_CLIMBS_IN_RADIUS:
                return "Geen bekende klimmen binnen deze straal. Vergroot de straal of importeer meer routes.";
            default: return "Geen suggestie mogelijk.";
        }
    }

    private void pickDateTimeAndAdd() {
        ElevationTargetPlanner.Suggestion s = viewModel.suggestion().getValue();
        if (s == null || s.climbs.isEmpty()) return;
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, day);
            new TimePickerDialog(this, (tView, hour, minute) -> {
                picked.set(Calendar.HOUR_OF_DAY, hour);
                picked.set(Calendar.MINUTE, minute);
                picked.set(Calendar.SECOND, 0);
                picked.set(Calendar.MILLISECOND, 0);
                viewModel.addToPlanning(s, picked.getTimeInMillis() / 1000L);
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }
}
