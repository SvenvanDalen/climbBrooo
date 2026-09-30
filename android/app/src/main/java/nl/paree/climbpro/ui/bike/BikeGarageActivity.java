package nl.paree.climbpro.ui.bike;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
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
import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.domain.bike.BikeCostCalculator;
import nl.paree.climbpro.domain.bike.BikeGarage;

import java.util.ArrayList;
import java.util.List;

/**
 * "Fietsgarage" (issue #187): all bikes with type, weight, tyre width, gearing and Strava gear
 * link; one is active (estimates, gear calculator, fallback for rides) and one can take the
 * indoor rides. Rides are assigned per {@link BikeGarage}. Phone-only and offline.
 */
public final class BikeGarageActivity extends AppCompatActivity {

    private BikeGarageViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, BikeGarageActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bike_garage);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Fietsgarage");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        BikeGarageAdapter adapter = new BikeGarageAdapter(this::showBikeDialog);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(BikeGarageViewModel.class);
        findViewById(R.id.btn_add).setOnClickListener(v -> showBikeDialog(null));

        viewModel.state().observe(this, s -> {
            adapter.submit(s);
            empty.setVisibility(s.garage.bikes.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload on resume so ride totals stay current after a ride-archive sync.
        viewModel.load();
    }

    /** Add ({@code existing == null}) or edit a bike. */
    private void showBikeDialog(Bike existing) {
        BikeGarageViewModel.State s = viewModel.state().getValue();
        if (s == null) return;

        View view = getLayoutInflater().inflate(R.layout.dialog_bike_garage, null);
        EditText name = view.findViewById(R.id.input_name);
        Spinner type = view.findViewById(R.id.spinner_type);
        EditText weight = view.findViewById(R.id.input_weight);
        EditText tyre = view.findViewById(R.id.input_tyre_width);
        EditText chainrings = view.findViewById(R.id.input_chainrings);
        EditText cassette = view.findViewById(R.id.input_cassette);
        Spinner gear = view.findViewById(R.id.spinner_gear);
        CheckBox active = view.findViewById(R.id.check_active);
        CheckBox indoor = view.findViewById(R.id.check_indoor);

        String[] typeLabels = new String[BikeGarage.TYPES.length];
        int typeIndex = 0;
        for (int i = 0; i < BikeGarage.TYPES.length; i++) {
            typeLabels[i] = BikeGarage.typeLabel(BikeGarage.TYPES[i]);
            if (existing != null && BikeGarage.TYPES[i].equals(existing.type)) typeIndex = i;
        }
        type.setAdapter(spinnerAdapter(typeLabels));
        type.setSelection(typeIndex);

        // Gear choices: "none", every gear id in the archive, plus the stored one if unseen.
        List<String> gearIds = new ArrayList<>();
        List<String> gearLabels = new ArrayList<>();
        gearIds.add(null);
        gearLabels.add("Geen koppeling");
        for (BikeGarage.GearUsage g : s.gears) {
            gearIds.add(g.gearId);
            String owner = ownerName(s, g.gearId, existing);
            gearLabels.add(g.gearId + " — " + g.rides + (g.rides == 1 ? " rit, " : " ritten, ")
                    + BikeCostCalculator.kmText(g.meters)
                    + (owner != null ? " (nu: " + owner + ")" : ""));
        }
        if (existing != null && existing.stravaGearId != null
                && !gearIds.contains(existing.stravaGearId)) {
            gearIds.add(existing.stravaGearId);
            gearLabels.add(existing.stravaGearId + " — geen ritten in het archief");
        }
        gear.setAdapter(spinnerAdapter(gearLabels.toArray(new String[0])));
        gear.setSelection(existing != null ? Math.max(0, gearIds.indexOf(existing.stravaGearId))
                                           : 0);

        Bike currentActive = BikeGarage.activeBike(s.garage);
        Bike currentIndoor = BikeGarage.indoorBike(s.garage);
        if (existing != null) {
            name.setText(existing.name);
            weight.setText(existing.weightKg > 0 ? BikeGarage.formatKg(existing.weightKg) : "");
            tyre.setText(existing.tyreWidthMm > 0 ? String.valueOf(existing.tyreWidthMm) : "");
            chainrings.setText(existing.chainrings != null ? existing.chainrings : "");
            cassette.setText(existing.cassette != null ? existing.cassette : "");
            active.setChecked(currentActive != null && currentActive.id.equals(existing.id));
            indoor.setChecked(currentIndoor != null && currentIndoor.id.equals(existing.id));
        } else {
            active.setChecked(currentActive == null);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Fiets bewerken" : "Fiets toevoegen")
                .setView(view)
                .setPositiveButton("Opslaan", (d, w) -> viewModel.saveBike(
                        existing != null ? existing.id : null,
                        name.getText().toString(),
                        BikeGarage.TYPES[type.getSelectedItemPosition()],
                        parseKg(weight), parseInt(tyre),
                        chainrings.getText().toString(), cassette.getText().toString(),
                        gearIds.get(Math.max(0, gear.getSelectedItemPosition())),
                        active.isChecked(), indoor.isChecked()))
                .setNegativeButton("Annuleren", null);
        if (existing != null) {
            builder.setNeutralButton("Verwijderen", (d, w) -> confirmDelete(existing));
        }
        builder.show();
    }

    /** Name of another bike already linked to {@code gearId}, or null. */
    private static String ownerName(BikeGarageViewModel.State s, String gearId, Bike self) {
        for (Bike b : s.garage.bikes) {
            if (b == null || (self != null && self.id.equals(b.id))) continue;
            if (gearId.equals(b.stravaGearId)) {
                return b.name;
            }
        }
        return null;
    }

    private void confirmDelete(Bike bike) {
        new AlertDialog.Builder(this)
                .setTitle(bike.name + " verwijderen?")
                .setMessage("De fiets en al zijn kosten worden verwijderd. Onderdelen die aan "
                        + "deze fiets gekoppeld waren tellen daarna weer alle ritten.")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.deleteBike(bike.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private ArrayAdapter<String> spinnerAdapter(String[] labels) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    /** Accepts "8,2" and "8.2"; empty or invalid = 0 (unknown). */
    private static double parseKg(EditText input) {
        try {
            return Math.max(0, Double.parseDouble(
                    input.getText().toString().trim().replace(',', '.')));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(EditText input) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
