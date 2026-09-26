package nl.paree.climbpro.ui.battery;

import android.Manifest;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.domain.battery.BatteryKind;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * "Accu's" (issue #238): when were the e-shifting, lights and power-meter batteries last
 * charged, with a per-device reminder after N days ({@link nl.paree.climbpro.service.BatteryReminderWorker}).
 * Phone-only and offline; reading sensor battery levels from the watch is out of scope.
 */
public final class BatteryActivity extends AppCompatActivity {

    private BatteryViewModel viewModel;
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());

    public static Intent intentFor(Context context) {
        return new Intent(context, BatteryActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_battery);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        viewModel = new ViewModelProvider(this).get(BatteryViewModel.class);
        BatteryAdapter adapter = new BatteryAdapter(new BatteryAdapter.Listener() {
            @Override public void onEdit(BatteryDevice d) { showDeviceDialog(d); }
            @Override public void onCharged(BatteryDevice d) { viewModel.markCharged(d.id); }
        });
        list.setAdapter(adapter);

        findViewById(R.id.btn_add).setOnClickListener(v -> showDeviceDialog(null));

        viewModel.devices().observe(this, devices -> {
            adapter.submit(devices);
            empty.setVisibility(devices == null || devices.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload on resume so "days since" stays current.
        viewModel.load();
    }

    private void showDeviceDialog(BatteryDevice existing) {
        View view = getLayoutInflater().inflate(R.layout.dialog_battery_device, null);
        EditText name = view.findViewById(R.id.input_name);
        Spinner kindSpinner = view.findViewById(R.id.spinner_kind);
        EditText interval = view.findViewById(R.id.input_interval);
        Button dateButton = view.findViewById(R.id.btn_date);

        BatteryKind[] kinds = BatteryKind.values();
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) labels[i] = kinds[i].label;
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        kindSpinner.setAdapter(kindAdapter);

        BatteryKind initialKind = existing != null ? BatteryKind.fromName(existing.kind)
                                                   : BatteryKind.E_SHIFTING;
        kindSpinner.setSelection(initialKind.ordinal());
        if (existing != null) {
            name.setText(existing.name);
            interval.setText(existing.intervalDays > 0 ? String.valueOf(existing.intervalDays) : "");
        } else {
            interval.setText(String.valueOf(initialKind.defaultIntervalDays));
        }
        // New device: follow the kind's default interval until the rider edits it themselves.
        final boolean[] intervalTouched = {existing != null};
        final boolean[] settingDefault = {false};
        interval.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (!settingDefault[0]) intervalTouched[0] = true;
            }
        });
        kindSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (intervalTouched[0]) return;
                settingDefault[0] = true;
                interval.setText(String.valueOf(kinds[pos].defaultIntervalDays));
                settingDefault[0] = false;
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        // Last charge: stored value, "now" for a new device; picked days land at noon.
        final long[] charged = {existing != null ? existing.lastChargedEpochSec
                                                 : System.currentTimeMillis() / 1000L};
        dateButton.setText(chargedLabel(charged[0]));
        dateButton.setOnClickListener(v -> {
            Calendar base = Calendar.getInstance();
            if (charged[0] > 0) base.setTimeInMillis(charged[0] * 1000L);
            DatePickerDialog dp = new DatePickerDialog(this, (dpv, y, m, d) -> {
                Calendar cal = Calendar.getInstance();
                cal.clear();
                cal.set(y, m, d, 12, 0, 0);
                charged[0] = Math.min(cal.getTimeInMillis(), System.currentTimeMillis()) / 1000L;
                dateButton.setText(chargedLabel(charged[0]));
            }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());
            dp.show();
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Accu bewerken" : "Accu toevoegen")
                .setView(view)
                .setPositiveButton("Opslaan", (d, w) -> {
                    int days = parseNonNegative(interval);
                    viewModel.saveDevice(existing != null ? existing.id : null,
                            name.getText().toString(),
                            kinds[kindSpinner.getSelectedItemPosition()].name(),
                            days, charged[0]);
                    if (days > 0) ensureNotificationPermission();
                })
                .setNegativeButton("Annuleren", null);
        if (existing != null) {
            builder.setNeutralButton("Verwijderen", (d, w) -> confirmDelete(existing));
        }
        builder.show();
    }

    private String chargedLabel(long epochSec) {
        return epochSec > 0 ? "Laatst geladen: " + dateFormat.format(new Date(epochSec * 1000L))
                            : "Laatst geladen: onbekend";
    }

    private void confirmDelete(BatteryDevice d) {
        new AlertDialog.Builder(this)
                .setMessage("\"" + d.name + "\" verwijderen?")
                .setPositiveButton("Verwijderen", (dlg, w) -> viewModel.deleteDevice(d.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    /** Android 13+: the reminder is a notification; ask once a reminder is configured. */
    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private static int parseNonNegative(EditText input) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
