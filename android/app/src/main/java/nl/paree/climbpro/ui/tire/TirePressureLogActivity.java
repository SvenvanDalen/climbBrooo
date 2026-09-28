package nl.paree.climbpro.ui.tire;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.tire.TirePressureLog;
import nl.paree.climbpro.data.tire.TirePressureLogEntry;
import nl.paree.climbpro.domain.tire.TirePressureReminderCalculator;
import nl.paree.climbpro.domain.tire.TirePressureReminderCalculator.Status;
import nl.paree.climbpro.domain.tire.TirePressureUnits;

import java.util.Calendar;
import java.util.Locale;

/**
 * "Bandenspanning" log (issue #155): manual front/rear pressure checks with a reminder after
 * X days and/or X outdoor km since the latest check. Phone-only and offline; the reminder is
 * surfaced in-app (status here + banner on the route list), not as a system notification.
 */
public final class TirePressureLogActivity extends AppCompatActivity {

    private TirePressureLogViewModel viewModel;
    private TirePressureLogAdapter adapter;

    public static Intent intentFor(Context context) {
        return new Intent(context, TirePressureLogActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tire_pressure_log);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView status = findViewById(R.id.status);
        TextView reminderSettings = findViewById(R.id.reminder_settings);
        TextView empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TirePressureLogAdapter(this::confirmDelete);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(TirePressureLogViewModel.class);

        findViewById(R.id.btn_add).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.btn_reminder).setOnClickListener(v -> showReminderDialog());

        viewModel.entries().observe(this, entries -> {
            adapter.submit(entries);
            empty.setVisibility(entries == null || entries.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.snapshot().observe(this, s -> {
            status.setText(statusText(s.status));
            status.setTextColor(ContextCompat.getColor(this,
                    s.status.due ? R.color.color_accent : R.color.color_text_primary));
            reminderSettings.setText(reminderText(s.log));
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload on resume so "days since" and km (after a ride-archive sync) stay current.
        viewModel.load();
    }

    private static String statusText(Status s) {
        if (!s.hasEntries) return "Nog geen controle gelogd. Log je eerste controle.";
        String since = TirePressureReminderCalculator.sinceText(s);
        return s.due ? "Bandenspanning controleren (" + since + ")"
                     : "Laatste controle " + since;
    }

    private static String reminderText(TirePressureLog log) {
        if (log.reminderDays <= 0 && log.reminderKm <= 0) return "Herinnering: uit";
        StringBuilder sb = new StringBuilder("Herinnering: elke ");
        if (log.reminderDays > 0) sb.append(log.reminderDays).append(" dagen");
        if (log.reminderDays > 0 && log.reminderKm > 0) sb.append(" of ");
        if (log.reminderKm > 0) sb.append(log.reminderKm).append(" km (buiten)");
        return sb.toString();
    }

    private void showAddDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_tire_pressure_entry, null);
        Button dateButton = view.findViewById(R.id.btn_date);
        EditText front = view.findViewById(R.id.input_front);
        EditText rear = view.findViewById(R.id.input_rear);
        EditText note = view.findViewById(R.id.input_note);

        // Pre-fill with the latest check: riders usually pump to the same pressures.
        TirePressureLogEntry latest = viewModel.current() != null
                ? TirePressureReminderCalculator.latest(viewModel.current().log.entries) : null;
        if (latest != null) {
            front.setText(String.format(Locale.ROOT, "%.1f", latest.frontBar));
            rear.setText(String.format(Locale.ROOT, "%.1f", latest.rearBar));
        }

        // null = "now"; otherwise the picked day at noon (local time).
        final Calendar[] picked = {null};
        dateButton.setText("Datum: vandaag");
        dateButton.setOnClickListener(v -> {
            Calendar base = picked[0] != null ? picked[0] : Calendar.getInstance();
            DatePickerDialog dp = new DatePickerDialog(this, (dpv, y, m, d) -> {
                Calendar today = Calendar.getInstance();
                if (y == today.get(Calendar.YEAR) && m == today.get(Calendar.MONTH)
                        && d == today.get(Calendar.DAY_OF_MONTH)) {
                    picked[0] = null;
                    dateButton.setText("Datum: vandaag");
                } else {
                    Calendar c = Calendar.getInstance();
                    c.clear();
                    c.set(y, m, d, 12, 0, 0);
                    picked[0] = c;
                    dateButton.setText("Datum: " + adapter.formatDate(c.getTimeInMillis() / 1000L));
                }
            }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());
            dp.show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Controle loggen")
                .setView(view)
                .setPositiveButton("Opslaan", null)
                .setNegativeButton("Annuleren", null)
                .create();
        // Validate before dismissing so a typo doesn't lose the other fields.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Double f = TirePressureUnits.parseBar(front.getText().toString());
                    Double r = TirePressureUnits.parseBar(rear.getText().toString());
                    if (f == null || r == null) {
                        Toast.makeText(this, String.format(Locale.getDefault(),
                                "Vul voor en achter een druk in tussen %.1f en %.1f bar",
                                TirePressureUnits.MIN_BAR, TirePressureUnits.MAX_BAR),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    long ts = picked[0] != null ? picked[0].getTimeInMillis() / 1000L
                                                : System.currentTimeMillis() / 1000L;
                    viewModel.addEntry(ts, f, r, note.getText().toString());
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showReminderDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_tire_pressure_reminder, null);
        EditText days = view.findViewById(R.id.input_days);
        EditText km = view.findViewById(R.id.input_km);
        TirePressureLog log = viewModel.current() != null ? viewModel.current().log : null;
        days.setText(String.valueOf(
                log != null ? log.reminderDays : TirePressureLog.DEFAULT_REMINDER_DAYS));
        km.setText(String.valueOf(
                log != null ? log.reminderKm : TirePressureLog.DEFAULT_REMINDER_KM));

        new AlertDialog.Builder(this)
                .setTitle("Herinnering")
                .setView(view)
                .setPositiveButton("Opslaan", (d, w) ->
                        viewModel.saveReminderSettings(parseNonNegative(days), parseNonNegative(km)))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    /** Empty or invalid input counts as 0 (= off). */
    private static int parseNonNegative(EditText input) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void confirmDelete(TirePressureLogEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Controle verwijderen?")
                .setMessage("Controle van " + adapter.formatDate(entry.timestampEpochSec)
                        + " wordt verwijderd.")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.deleteEntry(entry.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }
}
