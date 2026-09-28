package nl.paree.climbpro.ui.fitness;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.training.FitnessCalculator;

import java.util.Locale;

/**
 * "Fitheid & vorm" screen (issue #220): today's fitness, fatigue and form, a 90-day chart of
 * all three, the weekly ramp and how the load was measured. Phone-only.
 */
public final class FitnessActivity extends AppCompatActivity {

    private FitnessViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, FitnessActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fitness);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(FitnessViewModel.class);
        viewModel.state().observe(this, this::render);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A changed FTP or newly synced rides change every value.
        viewModel.load();
    }

    private void render(FitnessViewModel.State st) {
        FitnessCalculator.Result r = st.result;
        boolean none = r.today == null;
        findViewById(R.id.empty).setVisibility(none ? View.VISIBLE : View.GONE);
        findViewById(R.id.content).setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) return;

        FitnessCalculator.Day t = r.today;
        text(R.id.fitness, String.format(Locale.getDefault(), "Fitheid%n%.0f", t.ctl));
        text(R.id.fatigue, String.format(Locale.getDefault(), "Vermoeidheid%n%.0f", t.atl));
        text(R.id.form, String.format(Locale.getDefault(), "Vorm%n%+.0f", t.tsb));
        text(R.id.formLabel, formLabel(FitnessCalculator.formOf(t.tsb)));
        ((FitnessChartView) findViewById(R.id.chart)).setDays(r.days);

        StringBuilder d = new StringBuilder();
        d.append(String.format(Locale.getDefault(),
                "Fitheid %s %.1f in de afgelopen week.", r.rampPerWeek >= 0 ? "steeg" : "daalde",
                Math.abs(r.rampPerWeek)));
        if (r.rampPerWeek > 8) {
            d.append(" Dat is een snelle opbouw; houd herstel in de gaten.");
        }
        d.append(System.lineSeparator()).append(String.format(Locale.getDefault(),
                "%d rit(ten) meegeteld: %d met powermeter, %d met geschat vermogen, de rest op "
                        + "rijtijd.", r.ridesCounted, r.ridesWithMeasuredPower,
                r.ridesWithEstimatedPower));
        if (!st.ftpKnown) {
            d.append(System.lineSeparator()).append("Vul je FTP in bij Instellingen: dan telt "
                    + "vermogen mee en klopt de belasting per rit beter.");
        }
        if (r.historyDays < FitnessCalculator.CTL_DAYS) {
            d.append(System.lineSeparator()).append(String.format(Locale.getDefault(),
                    "Je archief beslaat pas %d dagen; fitheid heeft zo'n %d dagen nodig om "
                            + "betrouwbaar te worden.", r.historyDays, FitnessCalculator.CTL_DAYS));
        }
        text(R.id.details, d.toString());
    }

    private static String formLabel(FitnessCalculator.Form form) {
        switch (form) {
            case VERY_FRESH: return "Zeer fris: goed uitgerust, maar lang zo blijven kost fitheid.";
            case FRESH: return "Fris: klaar voor een wedstrijd of een zware klim.";
            case NEUTRAL: return "Neutraal: in balans.";
            case PRODUCTIVE: return "Productieve training: je bouwt op, plan ook rust.";
            default: return "Zwaar belast: grote kans op overbelasting, neem rust.";
        }
    }

    private void text(int id, String s) {
        ((TextView) findViewById(id)).setText(s);
    }
}
