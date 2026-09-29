package nl.paree.climbpro.ui.rides;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.recovery.RecoveryCheck;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer;
import nl.paree.climbpro.domain.ride.RideCategory;
import nl.paree.climbpro.domain.ride.RideCategoryLabel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Ritten" archive (issue #160): synced rides automatically classified as woon-werk,
 * training or toerrit, filterable per category — no manual tagging. Tapping a ride offers its
 * post-ride recovery check (issue #183), the ride story and the ride comparer. Phone-only.
 */
public final class RideArchiveActivity extends AppCompatActivity {

    /** Spinner position 0 = all, then one entry per category in this order. */
    private static final RideCategory[] FILTERS = {
            null, RideCategory.COMMUTE, RideCategory.TRAINING, RideCategory.TOUR};
    /** Pre-selected values for a ride without a check yet. */
    private static final int DEFAULT_RPE = 5;
    private static final int DEFAULT_SLEEP = 3;

    private RideArchiveViewModel viewModel;
    private ArrayAdapter<String> filterAdapter;
    private RideArchiveAdapter adapter;

    public static Intent intentFor(Context context) {
        return new Intent(context, RideArchiveActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_archive);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RideArchiveAdapter(this::showRideActions);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(RideArchiveViewModel.class);

        Spinner filter = findViewById(R.id.filter);
        filterAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new java.util.ArrayList<>(java.util.Arrays.asList(labels(null))));
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(filterAdapter);
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                viewModel.setFilter(FILTERS[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        findViewById(R.id.btn_refresh).setOnClickListener(v -> viewModel.refreshFromStrava());
        findViewById(R.id.btn_recovery_trend).setOnClickListener(v -> startActivity(
                nl.paree.climbpro.ui.recovery.RecoveryTrendActivity.intentFor(this)));

        viewModel.rows().observe(this, rows -> {
            adapter.submit(rows);
            empty.setVisibility(rows == null || rows.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.counts().observe(this, counts -> {
            filterAdapter.clear();
            filterAdapter.addAll(labels(counts));
            filterAdapter.notifyDataSetChanged();
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        viewModel.load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) adapter.shutdown();
    }

    /**
     * Tap on a ride: recovery check (issue #183), share it as a story (issue #193) or compare
     * it (issue #199).
     */
    private void showRideActions(RideArchiveViewModel.Row row) {
        StoredRide ride = row.ride;
        new AlertDialog.Builder(this)
                .setTitle(ride.name != null && !ride.name.isEmpty() ? ride.name : "Rit")
                .setItems(new String[]{
                        getString(R.string.recovery_check_title), "Rit-verhaal delen",
                        "Vergelijk met…"}, (d, which) -> {
                    if (which == 0) {
                        showRecoveryDialog(row);
                    } else if (which == 1) {
                        startActivity(RideStoryActivity.intentFor(this, ride.activityId));
                    } else {
                        pickRideToCompare(ride);
                    }
                })
                .show();
    }

    /** Ride comparer (issue #199): offer the other rides that look like the same route. */
    private void pickRideToCompare(StoredRide base) {
        List<StoredRide> candidates = viewModel.sameRouteCandidates(base);
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Geen andere rit over dezelfde route gevonden om mee te vergelijken",
                    Toast.LENGTH_LONG).show();
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());
        String[] items = new String[candidates.size()];
        for (int i = 0; i < items.length; i++) {
            StoredRide r = candidates.get(i);
            items[i] = String.format(Locale.getDefault(), "%s  •  %.1f km  •  %.1f km/u",
                    r.startEpochSec > 0 ? fmt.format(new Date(r.startEpochSec * 1000L)) : "?",
                    r.distanceM / 1000f, r.avgSpeedMps * 3.6f);
        }
        new AlertDialog.Builder(this)
                .setTitle("Vergelijk met…")
                .setItems(items, (d, which) -> startActivity(RideCompareActivity.intentFor(
                        this, base.activityId, candidates.get(which).activityId)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Herstel-check (issue #183): RPE 1–10, sleep 1–5, optional hours and note. */
    private void showRecoveryDialog(RideArchiveViewModel.Row row) {
        StoredRide ride = row.ride;
        RecoveryCheck existing = row.recovery;
        View view = getLayoutInflater().inflate(R.layout.dialog_recovery_check, null);
        TextView rideLabel = view.findViewById(R.id.ride_label);
        TextView rpeLabel = view.findViewById(R.id.rpe_label);
        SeekBar rpeBar = view.findViewById(R.id.seek_rpe);
        TextView sleepLabel = view.findViewById(R.id.sleep_label);
        SeekBar sleepBar = view.findViewById(R.id.seek_sleep);
        EditText hours = view.findViewById(R.id.input_hours);
        EditText note = view.findViewById(R.id.input_note);

        rideLabel.setText(ride.name != null && !ride.name.isEmpty()
                ? ride.name : getString(R.string.recovery_ride_default_name));
        String[] rpeWords = getResources().getStringArray(R.array.recovery_rpe_words);
        String[] sleepWords = getResources().getStringArray(R.array.recovery_sleep_words);

        rpeBar.setMax(RecoveryTrendAnalyzer.MAX_RPE - RecoveryTrendAnalyzer.MIN_RPE);
        sleepBar.setMax(RecoveryTrendAnalyzer.MAX_SLEEP - RecoveryTrendAnalyzer.MIN_SLEEP);
        rpeBar.setOnSeekBarChangeListener(new LabelUpdater(rpeLabel,
                R.string.recovery_check_rpe_label, RecoveryTrendAnalyzer.MIN_RPE, rpeWords));
        sleepBar.setOnSeekBarChangeListener(new LabelUpdater(sleepLabel,
                R.string.recovery_check_sleep_label, RecoveryTrendAnalyzer.MIN_SLEEP, sleepWords));
        int rpe = existing != null ? existing.rpe : DEFAULT_RPE;
        int sleep = existing != null ? existing.sleepQuality : DEFAULT_SLEEP;
        rpeBar.setProgress(rpe - RecoveryTrendAnalyzer.MIN_RPE);
        sleepBar.setProgress(sleep - RecoveryTrendAnalyzer.MIN_SLEEP);
        // setProgress does not fire the listener when the value is unchanged (0).
        rpeLabel.setText(getString(R.string.recovery_check_rpe_label, rpe, rpeWords[rpe - 1]));
        sleepLabel.setText(getString(R.string.recovery_check_sleep_label, sleep,
                sleepWords[sleep - 1]));
        if (existing != null) {
            if (existing.sleepHours != null) {
                hours.setText(String.format(Locale.getDefault(), "%.1f", existing.sleepHours));
            }
            if (existing.note != null) note.setText(existing.note);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.recovery_check_title)
                .setView(view)
                .setPositiveButton(R.string.recovery_check_save, (d, w) ->
                        viewModel.saveRecovery(ride.activityId,
                                rpeBar.getProgress() + RecoveryTrendAnalyzer.MIN_RPE,
                                sleepBar.getProgress() + RecoveryTrendAnalyzer.MIN_SLEEP,
                                parseHours(hours.getText().toString()),
                                note.getText().toString()))
                .setNegativeButton(R.string.recovery_check_cancel, null);
        if (existing != null) {
            b.setNeutralButton(R.string.recovery_check_delete,
                    (d, w) -> viewModel.deleteRecovery(ride.activityId));
        }
        b.show();
    }

    /** Lenient: accepts "7,5" and "7.5"; blank or unparsable means "not filled in". */
    static Float parseHours(String text) {
        if (text == null) return null;
        String t = text.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try {
            return RecoveryTrendAnalyzer.clampSleepHours(Float.parseFloat(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Keeps a "label n/max (word)" text in step with its seek bar. */
    private final class LabelUpdater implements SeekBar.OnSeekBarChangeListener {
        private final TextView label;
        private final int format;
        private final int min;
        private final String[] words;

        LabelUpdater(TextView label, int format, int min, String[] words) {
            this.label = label;
            this.format = format;
            this.min = min;
            this.words = words;
        }

        @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
            int value = progress + min;
            int idx = Math.max(0, Math.min(words.length - 1, value - 1));
            label.setText(getString(format, value, words[idx]));
        }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    private static String[] labels(Map<RideCategory, Integer> counts) {
        String[] out = new String[FILTERS.length];
        int total = 0;
        if (counts != null) for (Integer n : counts.values()) total += n;
        out[0] = counts != null ? "Alle ritten (" + total + ")" : "Alle ritten";
        for (int i = 1; i < FILTERS.length; i++) {
            String label = RideCategoryLabel.forCategory(FILTERS[i]);
            out[i] = counts != null ? label + " (" + counts.get(FILTERS[i]) + ")" : label;
        }
        return out;
    }
}
