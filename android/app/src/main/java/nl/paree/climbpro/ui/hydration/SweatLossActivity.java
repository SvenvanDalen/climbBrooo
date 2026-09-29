package nl.paree.climbpro.ui.hydration;

import android.app.DatePickerDialog;
import android.content.Context;
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
import nl.paree.climbpro.data.hydration.SweatLossEntry;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.hydration.SweatLossCalculator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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
        Spinner rideSpinner = view.findViewById(R.id.spinner_ride);
        Button dateButton = view.findViewById(R.id.btn_date);
        EditText before = view.findViewById(R.id.input_weight_before);
        EditText after = view.findViewById(R.id.input_weight_after);
        EditText drunk = view.findViewById(R.id.input_drunk);
        EditText duration = view.findViewById(R.id.input_duration);
        EditText note = view.findViewById(R.id.input_note);

        if (snap != null && snap.profileWeightKg > 0) {
            before.setText(String.format(Locale.getDefault(), "%.1f", snap.profileWeightKg));
        }

        // Ride picker: recent archived rides (fills the duration), plus "no ride" with a date.
        List<String> rideLabels = new ArrayList<>();
        rideLabels.add(getString(R.string.sweat_no_ride));
        for (StoredRide r : rides) {
            rideLabels.add(getString(R.string.sweat_ride_item, formatDate(r.startEpochSec),
                    r.name != null ? r.name : getString(R.string.sweat_ride_default),
                    r.distanceM / 1000.0));
        }
        ArrayAdapter<String> rideAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, rideLabels);
        rideAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rideSpinner.setAdapter(rideAdapter);
        rideSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                dateButton.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                if (pos > 0) {
                    StoredRide ride = rides.get(pos - 1);
                    int sec = ride.elapsedTimeSec > 0 ? ride.elapsedTimeSec : ride.movingTimeSec;
                    if (sec > 0) duration.setText(String.valueOf(Math.round(sec / 60f)));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        if (!rides.isEmpty()) rideSpinner.setSelection(1);

        final long[] picked = {System.currentTimeMillis() / 1000L};
        dateButton.setText(getString(R.string.sweat_date, formatDate(picked[0])));
        dateButton.setOnClickListener(v -> {
            Calendar base = Calendar.getInstance();
            base.setTimeInMillis(picked[0] * 1000L);
            DatePickerDialog dp = new DatePickerDialog(this, (dpv, y, m, d) -> {
                Calendar cal = Calendar.getInstance();
                cal.clear();
                cal.set(y, m, d, 12, 0, 0);
                picked[0] = Math.min(cal.getTimeInMillis(), System.currentTimeMillis()) / 1000L;
                dateButton.setText(getString(R.string.sweat_date, formatDate(picked[0])));
            }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());
            dp.show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.sweat_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.sweat_save, null)
                .setNegativeButton(R.string.sweat_cancel, null)
                .show();
        // Validate before closing so a typo doesn't throw the whole form away.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double wBefore = parseDecimal(before.getText().toString());
            double wAfter = parseDecimal(after.getText().toString());
            int ml = parseInt(drunk.getText().toString(), 0);
            int min = parseInt(duration.getText().toString(), -1);
            SweatLossCalculator.Invalid invalid =
                    SweatLossCalculator.validate(wBefore, wAfter, ml, min);
            if (invalid != null) {
                Toast.makeText(this, invalidText(invalid), Toast.LENGTH_LONG).show();
                return;
            }
            int pos = rideSpinner.getSelectedItemPosition();
            StoredRide ride = pos > 0 ? rides.get(pos - 1) : null;
            viewModel.add(ride != null ? ride.activityId : 0,
                    ride != null ? ride.startEpochSec : picked[0],
                    wBefore, wAfter, ml, min, note.getText().toString());
            dialog.dismiss();
        });
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
