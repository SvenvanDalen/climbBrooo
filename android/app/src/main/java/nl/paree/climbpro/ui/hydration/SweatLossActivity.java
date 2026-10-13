package nl.paree.climbpro.ui.hydration;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.PastDayPicker;
import nl.paree.climbpro.data.hydration.SweatLossEntry;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.hydration.SweatLossCalculator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Zweetverlies-schatter (issue #186): log a before/after weigh-in plus the fluid drunk per
 * ride, see the sweat rate per ride and a personal drinking advice for future rides.
 * Phone-only and offline; nothing reaches the watch.
 */
public final class SweatLossActivity extends AppCompatActivity {

    private SweatLossViewModel viewModel;
    private ArrayAdapter<String> adapter;
    private TextView summary;
    private TextView empty;
    private List<SweatLossViewModel.Row> currentRows = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());

    public static Intent intentFor(Context context) {
        return new Intent(context, SweatLossActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sweat_loss);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        summary = findViewById(R.id.summary);
        empty = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < currentRows.size()) confirmDelete(currentRows.get(position).entry);
        });

        viewModel = new ViewModelProvider(this).get(SweatLossViewModel.class);
        findViewById(R.id.btn_add).setOnClickListener(v -> showAddDialog());
        viewModel.snapshot().observe(this, this::render);
        viewModel.message().observe(this, m -> Toast.makeText(this,
                getString(m.resId, m.args), Toast.LENGTH_SHORT).show());
        viewModel.load();
    }

    private void render(SweatLossViewModel.Snapshot s) {
        currentRows = s.rows;
        summary.setText(summaryText(s.summary));
        List<String> rows = new ArrayList<>();
        for (SweatLossViewModel.Row r : s.rows) rows.add(rowText(r));
        adapter.clear();
        adapter.addAll(rows);
        empty.setVisibility(s.rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String summaryText(SweatLossCalculator.Summary s) {
        if (s == null) return getString(R.string.sweat_summary_none);
        StringBuilder sb = new StringBuilder();
        sb.append(getResources().getQuantityString(R.plurals.sweat_summary_average, s.count,
                s.avgSweatRateLPerH, s.count, s.minSweatRateLPerH, s.maxSweatRateLPerH));
        sb.append("\n\n");
        int bottleMl = SweatLossCalculator.DEFAULT_BOTTLE_ML;
        double bottles = SweatLossCalculator.bottlesPerHour(s.advisedMlPerHour, bottleMl);
        sb.append(getString(R.string.sweat_advice, s.advisedMlPerHour,
                formatBottles(bottles), bottleMl));
        if (s.exceedsAbsorption) sb.append("\n\n").append(getString(R.string.sweat_advice_capped));
        return sb.toString();
    }

    private static String formatBottles(double bottles) {
        return bottles == Math.floor(bottles)
                ? String.valueOf((int) bottles)
                : String.format(Locale.getDefault(), "%.1f", bottles);
    }

    private String rowText(SweatLossViewModel.Row row) {
        SweatLossEntry e = row.entry;
        SweatLossCalculator.Result r = row.result;
        String duration = getString(R.string.sweat_duration_format,
                e.durationMin / 60, e.durationMin % 60);
        String text = getString(R.string.sweat_row, formatDate(e.timestampEpochSec),
                r.sweatRateLPerH, e.weightBeforeKg, e.weightAfterKg, e.drunkMl, duration,
                statusText(r));
        return e.note != null ? text + "\n" + e.note : text;
    }

    private String statusText(SweatLossCalculator.Result r) {
        switch (r.status) {
            case GAINED:   return getString(R.string.sweat_status_gained);
            case GOOD:     return getString(R.string.sweat_status_good, r.massLossPct);
            case MODERATE: return getString(R.string.sweat_status_moderate, r.massLossPct);
            case HIGH:
            default:       return getString(R.string.sweat_status_high, r.massLossPct);
        }
    }

    private String formatDate(long epochSec) {
        return dateFormat.format(new Date(epochSec * 1000L));
    }

    private void showAddDialog() {
        SweatLossViewModel.Snapshot snap = viewModel.current();
        List<StoredRide> rides = snap != null ? snap.recentRides : new ArrayList<>();

        View view = getLayoutInflater().inflate(R.layout.dialog_sweat_loss_entry, null);
        SweatForm form = new SweatForm(view);

        if (snap != null && snap.profileWeightKg > 0) {
            form.before.setText(String.format(Locale.getDefault(), "%.1f", snap.profileWeightKg));
        }

        // Ride picker: recent archived rides (fills the duration), plus "no ride" with a date.
        ArrayAdapter<String> rideAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, rideLabels(rides));
        rideAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        form.rideSpinner.setAdapter(rideAdapter);
        form.rideSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                onRidePicked(form, rides, pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        if (!rides.isEmpty()) form.rideSpinner.setSelection(1);

        form.pickedDay = System.currentTimeMillis() / 1000L;
        form.dateButton.setText(getString(R.string.sweat_date, formatDate(form.pickedDay)));
        form.dateButton.setOnClickListener(v -> PastDayPicker.show(this, form.pickedDay, day -> {
            form.pickedDay = day;
            form.dateButton.setText(getString(R.string.sweat_date, formatDate(day)));
        }));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.sweat_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.sweat_save, null)
                .setNegativeButton(R.string.sweat_cancel, null)
                .show();
        // Validate before closing so a typo doesn't throw the whole form away.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (saveEntry(form, rides)) dialog.dismiss();
        });
    }

    /** The add dialog's inputs plus the day picked for an entry without a ride. */
    private static final class SweatForm {
        final Spinner rideSpinner;
        final Button dateButton;
        final EditText before;
        final EditText after;
        final EditText drunk;
        final EditText duration;
        final EditText note;
        long pickedDay;

        SweatForm(View view) {
            rideSpinner = view.findViewById(R.id.spinner_ride);
            dateButton = view.findViewById(R.id.btn_date);
            before = view.findViewById(R.id.input_weight_before);
            after = view.findViewById(R.id.input_weight_after);
            drunk = view.findViewById(R.id.input_drunk);
            duration = view.findViewById(R.id.input_duration);
            note = view.findViewById(R.id.input_note);
        }
    }

    private List<String> rideLabels(List<StoredRide> rides) {
        List<String> rideLabels = new ArrayList<>();
        rideLabels.add(getString(R.string.sweat_no_ride));
        for (StoredRide r : rides) {
            rideLabels.add(getString(R.string.sweat_ride_item, formatDate(r.startEpochSec),
                    r.name != null ? r.name : getString(R.string.sweat_ride_default),
                    r.distanceM / 1000.0));
        }
        return rideLabels;
    }

    /** "No ride" shows the date button; a ride fills in its duration. */
    private static void onRidePicked(SweatForm form, List<StoredRide> rides, int pos) {
        form.dateButton.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
        if (pos <= 0) return;
        StoredRide ride = rides.get(pos - 1);
        int sec = ride.elapsedTimeSec > 0 ? ride.elapsedTimeSec : ride.movingTimeSec;
        if (sec > 0) form.duration.setText(String.valueOf(Math.round(sec / 60f)));
    }

    /** Validates and stores the entry; false (with a toast) when an input is invalid. */
    private boolean saveEntry(SweatForm form, List<StoredRide> rides) {
        double wBefore = parseDecimal(form.before.getText().toString());
        double wAfter = parseDecimal(form.after.getText().toString());
        int ml = parseInt(form.drunk.getText().toString(), 0);
        int min = parseInt(form.duration.getText().toString(), -1);
        SweatLossCalculator.Invalid invalid =
                SweatLossCalculator.validate(wBefore, wAfter, ml, min);
        if (invalid != null) {
            Toast.makeText(this, invalidText(invalid), Toast.LENGTH_LONG).show();
            return false;
        }
        int pos = form.rideSpinner.getSelectedItemPosition();
        StoredRide ride = pos > 0 ? rides.get(pos - 1) : null;
        viewModel.add(ride != null ? ride.activityId : 0,
                ride != null ? ride.startEpochSec : form.pickedDay,
                wBefore, wAfter, ml, min, form.note.getText().toString());
        return true;
    }

    private String invalidText(SweatLossCalculator.Invalid invalid) {
        switch (invalid) {
            case WEIGHT_OUT_OF_RANGE:   return getString(R.string.sweat_invalid_weight);
            case DURATION_OUT_OF_RANGE: return getString(R.string.sweat_invalid_duration);
            case FLUID_OUT_OF_RANGE:    return getString(R.string.sweat_invalid_fluid);
            case IMPLAUSIBLE_CHANGE:
            default:                    return getString(R.string.sweat_invalid_change);
        }
    }

    /** Accepts both "74,5" and "74.5"; NaN when empty or unparsable. */
    static double parseDecimal(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void confirmDelete(SweatLossEntry entry) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.sweat_delete_confirm,
                        formatDate(entry.timestampEpochSec)))
                .setPositiveButton(R.string.sweat_delete, (d, w) -> viewModel.delete(entry.id))
                .setNegativeButton(R.string.sweat_cancel, null)
                .show();
    }
}
