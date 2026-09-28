package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.ride.PowerCurveCalculator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Vermogenscurve" screen (issue #219): the best power over 5 s, 1, 5, 20 and 60 min for a
 * chosen period, drawn against the all-time curve, with the ride each value came from.
 * Values come from the ride stream analysis. Phone-only.
 */
public final class PowerCurveActivity extends AppCompatActivity {

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", new Locale("nl"));

    private PowerCurveViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, PowerCurveActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_power_curve);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        LinearLayout rows = findViewById(R.id.rows);
        PowerCurveChartView chart = findViewById(R.id.chart);
        RadioGroup periods = findViewById(R.id.periods);

        viewModel = new ViewModelProvider(this).get(PowerCurveViewModel.class);
        periods.check(radioFor(viewModel.period()));
        periods.setOnCheckedChangeListener((group, checkedId) ->
                viewModel.setPeriod(periodFor(checkedId)));

        viewModel.state().observe(this, st -> {
            rows.removeAllViews();
            String pending = st.ridesAwaitingAnalysis > 0
                    ? " Nog " + st.ridesAwaitingAnalysis + " rit(ten) te analyseren; dat gebeurt "
                    + "in stappen bij elke sync." : "";
            if (st.allTime.isEmpty()) {
                chart.setVisibility(View.GONE);
                empty.setVisibility(View.VISIBLE);
                empty.setText(st.archivedRides == 0
                        ? "Nog geen ritten in het archief. Open Ritten en tik op Ophalen."
                        : "Nog geen rit met vermogensdata." + pending);
                return;
            }
            chart.setVisibility(View.VISIBLE);
            String[] labels = new String[st.result.bests.length];
            int[] period = new int[labels.length];
            int[] allTime = new int[labels.length];
            for (int k = 0; k < labels.length; k++) {
                labels[k] = durationLabel(st.result.bests[k].durationSec);
                period[k] = st.result.bests[k].watts;
                allTime[k] = st.allTime.bests[k].watts;
            }
            chart.setCurves(period, allTime, labels);

            String summary = st.result.isEmpty()
                    ? "Geen ritten met vermogen in deze periode."
                    : st.result.ridesWithPower + " rit(ten) met vermogen in deze periode.";
            empty.setVisibility(View.VISIBLE);
            empty.setText((summary + pending).trim());
            for (int k = 0; k < labels.length; k++) {
                renderBest(rows, st.result.bests[k], st.allTime.bests[k], st.weightKg,
                        st.period != PowerCurveCalculator.Period.ALL);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void renderBest(ViewGroup rows, PowerCurveCalculator.Best best,
                            PowerCurveCalculator.Best allTime, double weightKg,
                            boolean compare) {
        String title = durationLabel(best.durationSec);
        if (best.watts <= 0) {
            addRow(rows, title, "–", "Geen rit met vermogen die zo lang duurde.");
            return;
        }
        String value = best.watts + " W";
        if (weightKg > 0) {
            value += String.format(Locale.getDefault(), "  •  %.1f W/kg", best.watts / weightKg);
        }
        StringBuilder detail = new StringBuilder(rideLabel(best));
        if (compare && allTime.watts > 0) {
            detail.append(allTime.watts > best.watts
                    ? String.format(Locale.getDefault(), "\nBeste ooit: %d W (%d %%)",
                            allTime.watts, Math.round(100.0 * best.watts / allTime.watts))
                    : "\nDit is ook je beste ooit.");
        }
        addRow(rows, title, value, detail.toString());
    }

    private String rideLabel(PowerCurveCalculator.Best best) {
        String name = best.ride.name != null && !best.ride.name.isEmpty() ? best.ride.name : "Rit";
        String date = best.ride.startEpochSec > 0
                ? dateFormat.format(new Date(best.ride.startEpochSec * 1000L)) : "onbekende datum";
        return name + ", " + date;
    }

    static String durationLabel(int sec) {
        return sec < 60 ? sec + " s" : (sec / 60) + " min";
    }

    private static int radioFor(PowerCurveCalculator.Period p) {
        switch (p) {
            case WEEKS_6: return R.id.period_6w;
            case YEAR: return R.id.period_year;
            case ALL: return R.id.period_all;
            default: return R.id.period_90d;
        }
    }

    private static PowerCurveCalculator.Period periodFor(int id) {
        if (id == R.id.period_6w) return PowerCurveCalculator.Period.WEEKS_6;
        if (id == R.id.period_year) return PowerCurveCalculator.Period.YEAR;
        if (id == R.id.period_all) return PowerCurveCalculator.Period.ALL;
        return PowerCurveCalculator.Period.DAYS_90;
    }

    private void addRow(ViewGroup container, String title, String value, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.value)).setText(value);
        ((TextView) v.findViewById(R.id.detail)).setText(detail);
        container.addView(v);
    }
}
