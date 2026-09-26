package nl.paree.climbpro.ui.sunscreen;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.domain.weather.HourlyForecast;
import nl.paree.climbpro.domain.weather.SunscreenAdvisor;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.service.SunscreenReminderWorker;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Zonnebrand-check" (issue #229): UV forecast for the planned ride window at the phone's
 * location, SPF advice, and optional phone reminders before the start and to re-apply during
 * the ride. The forecast is fetched once here; the reminders then fire offline.
 */
public final class SunscreenActivity extends AppCompatActivity {

    private static final int[] DURATION_HOURS = {1, 2, 3, 4, 5, 6, 8};
    private static final int DEFAULT_DURATION_INDEX = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ZoneId zone = ZoneId.systemDefault();
    private final DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");

    /** Planned start; null = now. */
    private ZonedDateTime plannedStart;
    private Button startButton;
    private Spinner durationSpinner;
    private TextView headline;
    private TextView detail;
    private TextView hourly;
    private Button remindButton;
    private List<SunscreenAdvisor.Reminder> pendingReminders;

    private final ActivityResultLauncher<String> locationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> check());
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> { });

    public static Intent intentFor(Context context) {
        return new Intent(context, SunscreenActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sunscreen);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        startButton = findViewById(R.id.btn_start);
        durationSpinner = findViewById(R.id.spinner_duration);
        headline = findViewById(R.id.headline);
        detail = findViewById(R.id.detail);
        hourly = findViewById(R.id.hourly);
        remindButton = findViewById(R.id.btn_remind);

        String[] labels = new String[DURATION_HOURS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = "Ritduur: " + DURATION_HOURS[i] + " uur";
        ArrayAdapter<String> durations = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        durations.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        durationSpinner.setAdapter(durations);
        durationSpinner.setSelection(DEFAULT_DURATION_INDEX);

        updateStartLabel();
        startButton.setOnClickListener(v -> pickStart());
        findViewById(R.id.btn_check).setOnClickListener(v -> checkWithPermission());
        remindButton.setOnClickListener(v -> scheduleReminders());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            SunscreenReminderWorker.cancel(getApplicationContext());
            Toast.makeText(this, "Zonnebrand-herinneringen gewist", Toast.LENGTH_SHORT).show();
        });
    }

    private void pickStart() {
        ZonedDateTime base = plannedStart != null ? plannedStart : ZonedDateTime.now(zone);
        new TimePickerDialog(this, (tp, h, m) -> {
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime t = ZonedDateTime.of(LocalDate.now(zone), LocalTime.of(h, m), zone);
            // A time that already passed today means tomorrow (the forecast covers two days).
            if (t.isBefore(now.minusMinutes(5))) t = t.plusDays(1);
            plannedStart = t;
            updateStartLabel();
        }, base.getHour(), base.getMinute(), true).show();
    }

    private void updateStartLabel() {
        if (plannedStart == null) {
            startButton.setText("Vertrek: nu");
            return;
        }
        boolean tomorrow = plannedStart.toLocalDate().isAfter(LocalDate.now(zone));
        startButton.setText("Vertrek: " + (tomorrow ? "morgen " : "vandaag ")
                + hm.format(plannedStart));
    }

    private void checkWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION);
            return;
        }
        check();
    }

    private void check() {
        headline.setText("UV-verwachting ophalen…");
        detail.setText("");
        hourly.setText("");
        remindButton.setEnabled(false);
        Instant start = plannedStart != null ? plannedStart.toInstant() : Instant.now();
        long durationSec = DURATION_HOURS[durationSpinner.getSelectedItemPosition()] * 3600L;
        io.execute(() -> {
            double[] loc = location();
            if (loc == null) {
                runOnUiThread(() -> headline.setText("Geen locatie bekend. Zet locatie aan "
                        + "of synchroniseer eerst met je horloge."));
                return;
            }
            try {
                HourlyForecast f = new OpenMeteoClient().fetch(loc[0], loc[1], Double.NaN);
                SunscreenAdvisor.Advice a = SunscreenAdvisor.advise(f, start, durationSec);
                List<SunscreenAdvisor.Reminder> reminders =
                        SunscreenAdvisor.reminders(a, start, Instant.now());
                String hours = hourlyText(f, start, durationSec);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    headline.setText(SunscreenAdvisor.headline(a));
                    detail.setText(SunscreenAdvisor.detail(a, zone));
                    hourly.setText(hours);
                    pendingReminders = reminders;
                    remindButton.setEnabled(!reminders.isEmpty());
                });
            } catch (Exception e) {
                runOnUiThread(() -> headline.setText("UV-verwachting ophalen mislukt: "
                        + e.getMessage()));
            }
        });
    }

    private String hourlyText(HourlyForecast f, Instant start, long durationSec) {
        StringBuilder sb = new StringBuilder();
        Instant end = start.plusSeconds(durationSec);
        for (int i = 0; i < f.times.length; i++) {
            Instant h = f.times[i];
            if (!h.plusSeconds(3600).isAfter(start) || !h.isBefore(end)) continue;
            if (Double.isNaN(f.uvIndex[i])) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(hm.format(h.atZone(zone))).append("  UV ")
              .append(String.format(Locale.GERMANY, "%.0f", f.uvIndex[i]));
        }
        return sb.toString();
    }

    private void scheduleReminders() {
        if (pendingReminders == null || pendingReminders.isEmpty()) return;
        int n = SunscreenReminderWorker.schedule(getApplicationContext(), pendingReminders,
                System.currentTimeMillis());
        Toast.makeText(this, n == 1 ? "1 herinnering ingesteld" : n + " herinneringen ingesteld",
                Toast.LENGTH_SHORT).show();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * Freshest cached fix from any provider; falls back to the last location stored for the
     * radius mode. UV varies slowly over distance, so a cached fix is precise enough.
     */
    @SuppressLint("MissingPermission")
    private double[] location() {
        Location best = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                for (String provider : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            }
        } catch (SecurityException e) {
            best = null;
        }
        if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.contains(RouteSyncWorker.PREF_LAST_LAT)) return null;
        return new double[]{
                Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LAT, 0)),
                Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LON, 0))};
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
