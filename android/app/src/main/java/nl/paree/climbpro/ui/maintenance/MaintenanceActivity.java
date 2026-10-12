package nl.paree.climbpro.ui.maintenance;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
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
import nl.paree.climbpro.ui.PastDayPicker;
import nl.paree.climbpro.data.bike.BikeCostLog;
import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.ui.bike.BikePicker;

import java.util.List;

/**
 * "Onderhoud" tracker (issue #154): chain, tyres, brake pads, service and custom parts, each
 * with a km and/or month interval counted from its last-serviced date using the synced ride
 * archive. Phone-only and offline; due parts also show as a banner on the route list.
 */
public final class MaintenanceActivity extends AppCompatActivity {

    private MaintenanceViewModel viewModel;
    private MaintenanceAdapter adapter;
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    public static Intent intentFor(Context context) {
        return new Intent(context, MaintenanceActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MaintenanceAdapter(new MaintenanceAdapter.Listener() {
            @Override public void onServiced(MaintenanceComponent c) { confirmServiced(c); }
            @Override public void onEdit(MaintenanceComponent c) { showEditDialog(c); }
        });
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(MaintenanceViewModel.class);

        findViewById(R.id.btn_add).setOnClickListener(v -> showEditDialog(null));

        viewModel.snapshot().observe(this, s -> {
            adapter.submit(s.statuses);
            empty.setVisibility(s.statuses.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload on resume so km (after a ride-archive sync) and months stay current.
        viewModel.load();
    }

    private void confirmServiced(MaintenanceComponent c) {
        new AlertDialog.Builder(this)
                .setTitle(c.name + " gedaan?")
                .setMessage("De teller begint vandaag opnieuw bij 0 km.")
                .setPositiveButton("Gedaan", (d, w) -> viewModel.markServiced(c.id, c.name))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void fillExisting(MaintenanceComponent existing, EditText name, EditText km,
                              EditText months, CheckBox virtual, TextView history,
                              EditText warrantyMonths) {
        name.setText(existing.name);
        km.setText(positiveText(existing.intervalKm));
        months.setText(positiveText(existing.intervalMonths));
        virtual.setChecked(existing.includeVirtualRides);
        history.setText(historyText(existing.serviceHistory));
        warrantyMonths.setText(positiveText(existing.warrantyMonths));
    }

    private String lastServicedLabel(long epochSec) {
        return epochSec > 0 ? "Laatst gedaan: " + adapter.formatDate(epochSec)
                            : "Laatst gedaan: onbekend";
    }

    /** The number, or empty for 0 (= not set). */
    private static String positiveText(int value) {
        return value > 0 ? String.valueOf(value) : "";
    }

    /** Add ({@code existing == null}) or edit a component. */
    private void showEditDialog(MaintenanceComponent existing) {
        View view = getLayoutInflater().inflate(R.layout.dialog_maintenance_component, null);
        EditText name = view.findViewById(R.id.input_name);
        EditText km = view.findViewById(R.id.input_km);
        EditText months = view.findViewById(R.id.input_months);
        Button dateButton = view.findViewById(R.id.btn_date);
        CheckBox virtual = view.findViewById(R.id.check_virtual);
        TextView history = view.findViewById(R.id.history);
        Button purchaseButton = view.findViewById(R.id.btn_purchase_date);
        EditText warrantyMonths = view.findViewById(R.id.input_warranty_months);
        Spinner bikeSpinner = view.findViewById(R.id.spinner_bike);
        MaintenanceStatusLoader.Snapshot snap = viewModel.snapshot().getValue();
        BikeCostLog garage = snap != null ? snap.garage : null;
        BikePicker.bind(bikeSpinner, garage, "Alle ritten (geen fiets)",
                existing != null ? existing.bikeId : null);

        if (existing != null) {
            fillExisting(existing, name, km, months, virtual, history, warrantyMonths);
        }
        history.setVisibility(history.length() > 0 ? View.VISIBLE : View.GONE);

        long current = existing != null ? existing.lastServicedEpochSec : 0;
        // 0 = unchanged; otherwise the picked day at noon (local time).
        final long[] picked = {0};
        dateButton.setText(lastServicedLabel(current));
        dateButton.setOnClickListener(v ->
                PastDayPicker.show(this, picked[0] > 0 ? picked[0] : current, day -> {
                    picked[0] = day;
                    dateButton.setText("Laatst gedaan: " + adapter.formatDate(day));
                }));

        // Purchase date for the warranty; starts at the stored value (0 = unknown).
        final long[] purchase = {existing != null ? existing.warrantyPurchaseEpochSec : 0};
        purchaseButton.setText(purchaseLabel(purchase[0]));
        purchaseButton.setOnClickListener(v -> PastDayPicker.show(this, purchase[0], day -> {
            purchase[0] = day;
            purchaseButton.setText(purchaseLabel(day));
        }));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Onderdeel bewerken" : "Onderdeel toevoegen")
                .setView(view)
                .setPositiveButton("Opslaan", (d, w) -> {
                    int warrantyTerm = parseNonNegative(warrantyMonths);
                    viewModel.saveComponent(
                            existing != null ? existing.id : null,
                            name.getText().toString(),
                            parseNonNegative(km), parseNonNegative(months),
                            virtual.isChecked(), picked[0], purchase[0], warrantyTerm,
                            BikePicker.selectedId(bikeSpinner, garage));
                    if (purchase[0] > 0 && warrantyTerm > 0) ensureNotificationPermission();
                })
                .setNegativeButton("Annuleren", null);
        if (existing != null) {
            builder.setNeutralButton("Verwijderen", (d, w) -> confirmDelete(existing));
        }
        builder.show();
    }

    private String purchaseLabel(long epochSec) {
        return epochSec > 0 ? "Aankoopdatum: " + adapter.formatDate(epochSec)
                            : "Aankoopdatum: onbekend";
    }

    /** Android 13+: the warranty reminder is a notification; ask once a warranty is entered. */
    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private String historyText(List<Long> dates) {
        if (dates == null || dates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Eerder gedaan: ");
        for (int i = dates.size() - 1; i >= 0; i--) {
            if (i < dates.size() - 1) sb.append(", ");
            sb.append(adapter.formatDate(dates.get(i)));
        }
        return sb.toString();
    }

    /** Empty or invalid input counts as 0 (= criterion off). */
    private static int parseNonNegative(EditText input) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void confirmDelete(MaintenanceComponent c) {
        new AlertDialog.Builder(this)
                .setTitle(c.name + " verwijderen?")
                .setMessage("Het onderdeel en de onderhoudshistorie worden verwijderd.")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.deleteComponent(c.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }
}
