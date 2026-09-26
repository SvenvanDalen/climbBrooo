package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.ride.HeartRateDriftAnalyzer;
import nl.paree.climbpro.domain.ride.HeartRateDriftCalculator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Hartslag-drift" screen (issue #222): the six-week trend of aerobic decoupling, then every
 * analyzed ride's drift, newest first. Values come from the ride stream analysis. Phone-only.
 */
public final class HeartRateDriftActivity extends AppCompatActivity {

    /** Rows shown; older rides still count towards the trend. */
    private static final int MAX_ROWS = 40;

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", new Locale("nl"));

    private HeartRateDriftViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, HeartRateDriftActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate_drift);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        LinearLayout rows = findViewById(R.id.rows);

        viewModel = new ViewModelProvider(this).get(HeartRateDriftViewModel.class);
        viewModel.state().observe(this, st -> {
            rows.removeAllViews();
            HeartRateDriftCalculator.Result r = st.result;
            String pending = st.ridesAwaitingAnalysis > 0
                    ? " Nog " + st.ridesAwaitingAnalysis + " rit(ten) te analyseren; dat gebeurt "
                    + "in stappen bij elke sync." : "";
            if (r.entries.isEmpty()) {
                empty.setVisibility(View.VISIBLE);
                empty.setText(st.archivedRides == 0
                        ? "Nog geen ritten in het archief. Open Ritten en tik op Ophalen."
                        : "Nog geen rit met een drift-waarde." + pending);
                return;
            }
            empty.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
            empty.setText(pending.trim());
            renderTrend(rows, r);
            int shown = Math.min(MAX_ROWS, r.entries.size());
            for (int i = 0; i < shown; i++) renderEntry(rows, r.entries.get(i));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void renderTrend(ViewGroup rows, HeartRateDriftCalculator.Result r) {
        int weeks = HeartRateDriftCalculator.TREND_WINDOW_DAYS / 7;
        if (r.recentAvg == null) {
            addRow(rows, "Trend", "–", "Geen ritten met drift in de laatste " + weeks + " weken.");
            return;
        }
        String value = percent(r.recentAvg);
        String detail;
        if (r.previousAvg == null) {
            detail = String.format(Locale.getDefault(),
                    "Gemiddeld over %d rit(ten) in de laatste %d weken; nog niets om mee te "
                            + "vergelijken.", r.recentCount, weeks);
        } else {
            double diff = r.recentAvg - r.previousAvg;
            String direction = Math.abs(diff) < 0.5 ? "ongeveer gelijk aan"
                    : diff < 0 ? "lager dan (beter)" : "hoger dan";
            detail = String.format(Locale.getDefault(),
                    "Gemiddeld over %d rit(ten) in de laatste %d weken, %s de %d weken daarvoor "
                            + "(%s over %d rit(ten)).", r.recentCount, weeks, direction, weeks,
                    percent(r.previousAvg), r.previousCount);
        }
        addRow(rows, "Trend", value, detail);
    }

    private void renderEntry(ViewGroup rows, HeartRateDriftCalculator.Entry e) {
        String date = e.ride.startEpochSec > 0
                ? dateFormat.format(new Date(e.ride.startEpochSec * 1000L)) : "onbekende datum";
        String name = e.ride.name != null && !e.ride.name.isEmpty() ? e.ride.name : "Rit";
        String basis = HeartRateDriftAnalyzer.BASIS_SPEED.equals(e.basis)
                ? "t.o.v. snelheid" : "t.o.v. vermogen";
        String detail = String.format(Locale.getDefault(), "%s, %d:%02d u geanalyseerd, %s",
                label(e.level), e.minutes / 60, e.minutes % 60, basis);
        addRow(rows, date + "  •  " + name, percent(e.percent), detail);
    }

    private static String label(HeartRateDriftCalculator.Level level) {
        switch (level) {
            case STABLE: return "Stabiel";
            case MODERATE: return "Matige drift";
            default: return "Sterke drift";
        }
    }

    private static String percent(double value) {
        return String.format(Locale.getDefault(), "%.1f %%", value);
    }

    private void addRow(ViewGroup container, String title, String value, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.value)).setText(value);
        ((TextView) v.findViewById(R.id.detail)).setText(detail);
        container.addView(v);
    }
}
