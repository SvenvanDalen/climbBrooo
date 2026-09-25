package nl.paree.climbpro.ui.bike;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostEntry;
import nl.paree.climbpro.domain.bike.EuroAmount;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * "Fietskosten" (issue #233): per bike the purchase and part costs, the km (ride archive in a
 * date window plus manual km) and the resulting cost per km. Phone-only and offline.
 */
public final class BikeCostActivity extends AppCompatActivity {

    private BikeCostViewModel viewModel;
    private BikeCostAdapter adapter;

    public static Intent intentFor(Context context) {
        return new Intent(context, BikeCostActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bike_costs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Fietskosten");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BikeCostAdapter(this::showCostsDialog);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(BikeCostViewModel.class);
        findViewById(R.id.btn_add).setOnClickListener(v -> showBikeDialog(null));

        viewModel.summaries().observe(this, summaries -> {
            adapter.submit(summaries);
            empty.setVisibility(summaries.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload on resume so km stay current after a ride-archive sync.
        viewModel.load();
    }

    /** The bike's costs, newest first; tap one to delete it. */
    private void showCostsDialog(Bike bike) {
        List<BikeCostEntry> costs = new ArrayList<>(bike.costs);
        Collections.sort(costs, (a, b) -> Long.compare(b.epochSec, a.epochSec));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(bike.name)
                .setPositiveButton("Kosten toevoegen", (d, w) -> showAddCostDialog(bike))
                .setNeutralButton("Fiets bewerken", (d, w) -> showBikeDialog(bike))
                .setNegativeButton("Sluiten", null);
        if (costs.isEmpty()) {
            builder.setMessage("Nog geen kosten. Voeg de aankoop en je onderdelen toe.");
        } else {
            String[] items = new String[costs.size()];
            for (int i = 0; i < costs.size(); i++) items[i] = costLine(costs.get(i));
            builder.setItems(items, (d, which) -> confirmDeleteCost(bike, costs.get(which)));
        }
        builder.show();
    }

    private String costLine(BikeCostEntry c) {
        String line = BikeCostEntry.label(c.kind) + " · " + c.description + " · "
                + EuroAmount.format(c.amountCents);
        return c.epochSec > 0 ? line + " · " + adapter.formatDate(c.epochSec) : line;
    }

    private void confirmDeleteCost(Bike bike, BikeCostEntry c) {
        new AlertDialog.Builder(this)
                .setTitle("Kosten verwijderen?")
                .setMessage(costLine(c))
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.deleteCost(bike.id, c.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void showAddCostDialog(Bike bike) {
        View view = getLayoutInflater().inflate(R.layout.dialog_bike_cost_entry, null);
        RadioButton purchase = view.findViewById(R.id.radio_purchase);
        RadioButton part = view.findViewById(R.id.radio_part);
        EditText description = view.findViewById(R.id.input_description);
        EditText amount = view.findViewById(R.id.input_amount);

        boolean hasPurchase = false;
        for (BikeCostEntry c : bike.costs) {
            if (BikeCostEntry.KIND_PURCHASE.equals(c.kind)) hasPurchase = true;
        }
        if (hasPurchase) part.setChecked(true); else purchase.setChecked(true);

        new AlertDialog.Builder(this)
                .setTitle("Kosten toevoegen")
                .setView(view)
                .setPositiveButton("Opslaan", (d, w) -> {
                    long cents = EuroAmount.parseCents(amount.getText().toString());
                    if (cents == EuroAmount.INVALID) {
                        Toast.makeText(this, "Ongeldig bedrag, gebruik bv. 49,95",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    viewModel.addCost(bike.id,
                            purchase.isChecked() ? BikeCostEntry.KIND_PURCHASE
                                                 : BikeCostEntry.KIND_PART,
                            description.getText().toString(), cents);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    /** Add ({@code existing == null}) or edit a bike. */
    private void showBikeDialog(Bike existing) {
        View view = getLayoutInflater().inflate(R.layout.dialog_bike, null);
        EditText name = view.findViewById(R.id.input_name);
        CheckBox archive = view.findViewById(R.id.check_archive);
        Button sinceButton = view.findViewById(R.id.btn_since);
        CheckBox virtual = view.findViewById(R.id.check_virtual);
        EditText extraKm = view.findViewById(R.id.input_extra_km);
        CheckBox retired = view.findViewById(R.id.check_retired);

        if (existing != null) {
            name.setText(existing.name);
            archive.setChecked(existing.countArchiveRides);
            virtual.setChecked(existing.includeVirtualRides);
            extraKm.setText(existing.extraKm > 0 ? String.valueOf(existing.extraKm) : "");
            retired.setChecked(existing.retiredEpochSec > 0);
        } else {
            archive.setChecked(true);
        }

        // New bikes count rides from today; the user picks the purchase date if earlier.
        final long[] since = {existing != null && existing.sinceEpochSec > 0
                ? existing.sinceEpochSec : startOfTodayEpochSec()};
        sinceButton.setText("Ritten tellen vanaf: " + adapter.formatDate(since[0]));
        sinceButton.setOnClickListener(v -> {
            Calendar base = Calendar.getInstance();
            base.setTimeInMillis(since[0] * 1000L);
            DatePickerDialog dp = new DatePickerDialog(this, (dpv, y, m, d) -> {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(y, m, d, 0, 0, 0); // local start of day: that day's rides count
                since[0] = c.getTimeInMillis() / 1000L;
                sinceButton.setText("Ritten tellen vanaf: " + adapter.formatDate(since[0]));
            }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());
            dp.show();
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Fiets bewerken" : "Fiets toevoegen")
                .setView(view)
                .setPositiveButton("Opslaan", (d, w) -> viewModel.saveBike(
                        existing != null ? existing.id : null,
                        name.getText().toString(), since[0],
                        archive.isChecked(), virtual.isChecked(),
                        parseNonNegative(extraKm), retired.isChecked()))
                .setNegativeButton("Annuleren", null);
        if (existing != null) {
            builder.setNeutralButton("Verwijderen", (d, w) -> confirmDeleteBike(existing));
        }
        builder.show();
    }

    private void confirmDeleteBike(Bike bike) {
        new AlertDialog.Builder(this)
                .setTitle(bike.name + " verwijderen?")
                .setMessage("De fiets en al zijn kosten worden verwijderd.")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.deleteBike(bike.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private static long startOfTodayEpochSec() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000L;
    }

    /** Empty or invalid input counts as 0. */
    private static int parseNonNegative(EditText input) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
