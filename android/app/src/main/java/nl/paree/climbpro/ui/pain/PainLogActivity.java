package nl.paree.climbpro.ui.pain;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import nl.paree.climbpro.data.pain.PainLogEntry;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.pain.PainArea;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * "Pijnlogboek" (issue #232): log complaints (knee, back, saddle, …) per ride and tie them to
 * a bike and a setup, with a pattern summary on top. Phone-only and offline.
 */
public final class PainLogActivity extends AppCompatActivity {

    private static final int DEFAULT_SEVERITY = 2;

    private PainLogViewModel viewModel;
    private PainLogAdapter adapter;

    public static Intent intentFor(Context context) {
        return new Intent(context, PainLogActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pain_log);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView summary = findViewById(R.id.summary);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PainLogAdapter(this::confirmDelete);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(PainLogViewModel.class);
        findViewById(R.id.btn_add).setOnClickListener(v -> showAddDialog());

        viewModel.snapshot().observe(this, s -> {
            adapter.submit(s.entries);
            summary.setText(s.summary);
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.load();
    }

    private void showAddDialog() {
        PainLogViewModel.Snapshot snap = viewModel.current();
        List<StoredRide> rides = snap != null ? snap.recentRides : new ArrayList<>();

        View view = getLayoutInflater().inflate(R.layout.dialog_pain_log_entry, null);
        Spinner rideSpinner = view.findViewById(R.id.spinner_ride);
        Button dateButton = view.findViewById(R.id.btn_date);
        LinearLayout areaBox = view.findViewById(R.id.area_box);
        SeekBar severityBar = view.findViewById(R.id.seek_severity);
        TextView severityLabel = view.findViewById(R.id.severity_label);
        EditText bike = view.findViewById(R.id.input_bike);
        EditText setup = view.findViewById(R.id.input_setup);
        EditText note = view.findViewById(R.id.input_note);

        // Ride picker: recent archived rides, plus "no ride" which enables the date button.
        List<String> rideLabels = new ArrayList<>();
        rideLabels.add("Geen rit uit het archief");
        for (StoredRide r : rides) {
            rideLabels.add(String.format(Locale.GERMANY, "%s · %s · %.0f km",
                    adapter.formatDate(r.startEpochSec),
                    r.name != null ? r.name : "Rit", r.distanceM / 1000.0));
        }
        ArrayAdapter<String> rideAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, rideLabels);
        rideAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rideSpinner.setAdapter(rideAdapter);
        if (!rides.isEmpty()) rideSpinner.setSelection(1);
        rideSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                dateButton.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        final long[] picked = {System.currentTimeMillis() / 1000L};
        dateButton.setText("Datum: " + adapter.formatDate(picked[0]));
        dateButton.setOnClickListener(v -> {
            Calendar base = Calendar.getInstance();
            base.setTimeInMillis(picked[0] * 1000L);
            DatePickerDialog dp = new DatePickerDialog(this, (dpv, y, m, d) -> {
                Calendar cal = Calendar.getInstance();
                cal.clear();
                cal.set(y, m, d, 12, 0, 0);
                picked[0] = Math.min(cal.getTimeInMillis(), System.currentTimeMillis()) / 1000L;
                dateButton.setText("Datum: " + adapter.formatDate(picked[0]));
            }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());
            dp.show();
        });

        List<CheckBox> areaChecks = new ArrayList<>();
        for (PainArea a : PainArea.values()) {
            CheckBox cb = new CheckBox(this);
            cb.setText(a.label);
            cb.setTag(a.name());
            areaBox.addView(cb);
            areaChecks.add(cb);
        }

        severityBar.setMax(4);
        severityBar.setProgress(DEFAULT_SEVERITY - 1);
        severityLabel.setText(severityText(DEFAULT_SEVERITY));
        severityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                severityLabel.setText(severityText(progress + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        // Pre-fill bike and setup from the latest entry: they rarely change between rides.
        if (snap != null && !snap.entries.isEmpty()) {
            PainLogEntry latest = snap.entries.get(0);
            if (latest.bike != null) bike.setText(latest.bike);
            if (latest.setup != null) setup.setText(latest.setup);
        }

        new AlertDialog.Builder(this)
                .setTitle("Klacht loggen")
                .setView(view)
                .setPositiveButton("Opslaan", (d, w) -> {
                    List<String> areas = new ArrayList<>();
                    for (CheckBox cb : areaChecks) if (cb.isChecked()) areas.add((String) cb.getTag());
                    if (areas.isEmpty()) {
                        Toast.makeText(this, "Kies minstens één plek", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int pos = rideSpinner.getSelectedItemPosition();
                    StoredRide ride = pos > 0 ? rides.get(pos - 1) : null;
                    viewModel.add(ride != null ? ride.activityId : 0,
                            ride != null ? ride.startEpochSec : picked[0],
                            areas, severityBar.getProgress() + 1,
                            bike.getText().toString(), setup.getText().toString(),
                            note.getText().toString());
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    static String severityText(int severity) {
        String[] words = {"licht", "merkbaar", "hinderlijk", "pijnlijk", "heftig"};
        return "Ernst: " + severity + "/5 (" + words[Math.max(0, Math.min(4, severity - 1))] + ")";
    }

    private void confirmDelete(PainLogEntry entry) {
        new AlertDialog.Builder(this)
                .setMessage("Klacht van " + adapter.formatDate(entry.timestampEpochSec)
                        + " verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.delete(entry.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }
}
