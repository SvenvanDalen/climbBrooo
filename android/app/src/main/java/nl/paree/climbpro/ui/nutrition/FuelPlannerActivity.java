package nl.paree.climbpro.ui.nutrition;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import java.util.Locale;

import nl.paree.climbpro.R;
import nl.paree.climbpro.databinding.ActivityFuelPlannerBinding;
import nl.paree.climbpro.domain.nutrition.FuelPlan;
import nl.paree.climbpro.domain.nutrition.FuelPlanner;
import nl.paree.climbpro.domain.power.DurationFormat;

/**
 * "Voedingsplanner" (issue #185): per route, how many gels, bars and bottles to bring,
 * from the estimated ride time, the route's climbing and the temperature. The temperature
 * is prefilled from the forecast at the route start when online, and can always be typed.
 */
public final class FuelPlannerActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";
    private static final int[] BOTTLE_SIZES_ML = {500, 750};
    private static final int DEFAULT_BOTTLE_INDEX = 1;

    private ActivityFuelPlannerBinding binding;
    private FuelPlannerViewModel viewModel;
    private boolean temperatureRequested;

    public static Intent intentFor(Context ctx, String routeId) {
        return new Intent(ctx, FuelPlannerActivity.class).putExtra(EXTRA_ROUTE_ID, routeId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFuelPlannerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        String[] labels = new String[BOTTLE_SIZES_ML.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(R.string.fuel_bottle_size, BOTTLE_SIZES_ML[i]);
        }
        ArrayAdapter<String> bottles = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        bottles.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerBottle.setAdapter(bottles);
        binding.spinnerBottle.setSelection(DEFAULT_BOTTLE_INDEX);

        viewModel = new ViewModelProvider(this).get(FuelPlannerViewModel.class);
        viewModel.summary().observe(this, s -> {
            if (s == null) return;
            renderSummary(s);
            binding.btnCalculate.setEnabled(true);
            binding.btnFetchTemperature.setEnabled(true);
            // First open: show a plan straight away (default temperature), then refine it
            // with the forecast. After rotation the retained plan is shown as-is.
            if (!temperatureRequested && savedInstanceState == null) {
                calculate();
                fetchTemperature();
            }
        });
        viewModel.forecastTemperature().observe(this, t -> {
            if (t == null) return;
            viewModel.consumeForecastTemperature(); // don't overwrite typed input on rotation
            binding.btnFetchTemperature.setEnabled(true);
            if (Double.isNaN(t)) {
                binding.temperatureStatus.setText(R.string.fuel_temperature_unavailable);
                return;
            }
            binding.temperature.setText(String.format(Locale.US, "%.0f", t));
            binding.temperatureStatus.setText(getString(R.string.fuel_temperature_fetched, t));
            calculate();
        });
        viewModel.plan().observe(this, this::renderPlan);
        viewModel.message().observe(this, msg -> {
            if (msg == null) return;
            viewModel.consumeMessage();
            binding.btnFetchTemperature.setEnabled(viewModel.summary().getValue() != null);
            binding.temperatureStatus.setText(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        binding.btnFetchTemperature.setOnClickListener(v -> fetchTemperature());
        binding.btnCalculate.setOnClickListener(v -> calculate());

        viewModel.load(getIntent().getStringExtra(EXTRA_ROUTE_ID));
    }

    private void fetchTemperature() {
        temperatureRequested = true;
        binding.btnFetchTemperature.setEnabled(false);
        binding.temperatureStatus.setText(R.string.fuel_fetching_temperature);
        viewModel.fetchTemperature();
    }

    private void calculate() {
        double temp = Double.NaN;
        String raw = binding.temperature.getText().toString().trim().replace(',', '.');
        if (!raw.isEmpty()) {
            try {
                temp = Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                temp = Double.NaN;
            }
        }
        viewModel.calculate(temp, BOTTLE_SIZES_ML[binding.spinnerBottle.getSelectedItemPosition()]);
    }

    private void renderSummary(FuelPlannerViewModel.RideSummary s) {
        String line = getString(R.string.fuel_summary, s.distanceMeters / 1000.0,
                s.ascentMeters, DurationFormat.format(s.effort.seconds));
        String source = getString(s.effort.fromPowerModel
                ? R.string.fuel_summary_model : R.string.fuel_summary_fallback);
        binding.summary.setText(line + "\n" + source);
    }

    private void renderPlan(FuelPlan p) {
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(p.temperatureAssumed
                ? getString(R.string.fuel_result_temp_assumed, p.temperatureC)
                : getString(R.string.fuel_result_temp, p.temperatureC));
        sb.append('\n').append(getString(R.string.fuel_result_kcal, p.kcal));
        if (p.totalCarbsGrams == 0) {
            sb.append('\n').append(getString(R.string.fuel_result_no_food));
        } else {
            sb.append('\n').append(getString(R.string.fuel_result_carbs,
                    p.carbsPerHour, p.totalCarbsGrams));
            sb.append('\n').append(getString(R.string.fuel_result_food,
                    p.bars, FuelPlanner.BAR_CARBS_G, p.gels, FuelPlanner.GEL_CARBS_G));
        }
        sb.append('\n').append(getString(R.string.fuel_result_fluid,
                p.fluidMlPerHour, p.totalFluidMl / 1000.0));
        sb.append('\n').append(getString(R.string.fuel_result_bottles, p.bottles, p.bottleMl));
        if (p.refills > 0) {
            sb.append('\n').append(getString(R.string.fuel_result_refills,
                    FuelPlanner.BOTTLE_CAGES, p.refills));
        }
        if (p.electrolytesAdvised) {
            sb.append('\n').append(getString(R.string.fuel_result_electrolytes));
        }
        binding.result.setText(sb.toString());
    }
}
