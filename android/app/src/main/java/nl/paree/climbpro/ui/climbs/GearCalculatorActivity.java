package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.power.GearCalculator;
import nl.paree.climbpro.domain.power.GearCalculator.Gear;
import nl.paree.climbpro.domain.power.GearCalculator.Result;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Issue #188: "Versnellingen" for a climb — per chainring × sprocket the cadence at the speed
 * the rider holds on the steepest segment and at the average gradient, plus advice whether the
 * easiest gear is light enough. Opened from the climb screen. Phone-only.
 */
public final class GearCalculatorActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";
    private static final String EXTRA_CLIMB_INDEX = "climb_index";
    private static final Locale NL = new Locale("nl", "NL");
    /** Only the easiest gears matter on a climb; the rest of the table is noise. */
    private static final int MAX_ROWS = 12;

    private GearCalculatorViewModel viewModel;
    private EditText chainrings;
    private AutoCompleteTextView cassette;
    private EditText wheel;
    private EditText cadence;
    private TextView summary;
    private TextView verdict;
    private TextView table;
    private String routeId;
    private int climbIndex;

    public static Intent intentFor(Context ctx, String routeId, int climbIndex) {
        Intent i = new Intent(ctx, GearCalculatorActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        i.putExtra(EXTRA_CLIMB_INDEX, climbIndex);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gear_calculator);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        routeId = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        climbIndex = getIntent().getIntExtra(EXTRA_CLIMB_INDEX, 0);

        chainrings = findViewById(R.id.input_chainrings);
        cassette = findViewById(R.id.input_cassette);
        wheel = findViewById(R.id.input_wheel);
        cadence = findViewById(R.id.input_cadence);
        summary = findViewById(R.id.gear_summary);
        verdict = findViewById(R.id.gear_verdict);
        table = findViewById(R.id.gear_table);
        table.setTypeface(Typeface.MONOSPACE);

        cassette.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>(GearCalculator.CASSETTES.keySet())));
        cassette.setThreshold(0);
        cassette.setOnClickListener(v -> cassette.showDropDown());

        viewModel = new ViewModelProvider(this).get(GearCalculatorViewModel.class);
        GearCalculatorViewModel.Inputs saved = viewModel.savedInputs();
        chainrings.setText(saved.chainrings);
        cassette.setText(saved.cassette, false);
        wheel.setText(String.valueOf(saved.wheelMm));
        cadence.setText(String.valueOf(saved.cadenceRpm));

        findViewById(R.id.btn_calculate).setOnClickListener(v -> calculate());
        viewModel.state().observe(this, this::render);
        if (savedInstanceState == null) calculate();
    }

    private void calculate() {
        viewModel.calculate(routeId, climbIndex, new GearCalculatorViewModel.Inputs(
                chainrings.getText().toString(), cassette.getText().toString(),
                parseInt(wheel.getText().toString()), parseInt(cadence.getText().toString())));
    }

    private void render(GearCalculatorViewModel.State s) {
        if (s.climbName != null && getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(s.climbName);
        }
        if (s.error != null) {
            summary.setText(s.error);
            verdict.setText("");
            table.setText("");
            return;
        }
        Result r = s.result;
        String profileNote = s.fallbackProfile
                ? "\nRuwe schatting: vul FTP en gewicht in bij Instellingen voor een eigen advies."
                : "";
        summary.setText(String.format(NL,
                "Steilste stuk %.0f%%: %.1f km/u · gemiddeld %.1f%%: %.1f km/u bij %.0f W.%s",
                r.steepestGradient * 100, r.speedSteepMps * 3.6, r.avgGradient * 100,
                r.speedAvgMps * 3.6, s.powerWatts, profileNote));
        verdict.setText(GearCalculator.verdict(r));

        StringBuilder sb = new StringBuilder(String.format(NL, "%-8s %9s %9s%n",
                "Verzet", "steilst", "gemiddeld"));
        int rows = Math.min(MAX_ROWS, r.gears.size());
        for (int i = 0; i < rows; i++) {
            Gear g = r.gears.get(i);
            sb.append(String.format(NL, "%-8s %5d rpm %5d rpm%n",
                    g.chainring + "×" + g.sprocket,
                    Math.round(g.cadenceSteepRpm), Math.round(g.cadenceAvgRpm)));
        }
        if (r.gears.size() > rows) {
            sb.append(String.format(NL, "… en %d zwaardere verzetten", r.gears.size() - rows));
        }
        table.setText(sb.toString());
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
