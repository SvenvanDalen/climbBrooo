package nl.paree.climbpro.ui.mywhoosh;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.mywhoosh.ClimbProgress;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;
import nl.paree.climbpro.ui.climbs.SegmentColorPalette;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Indoor en progressie" for one climb, opened from the climb detail: a progression chart of
 * time and W/kg over every attempt with the PRs marked (issue #387), the linked MyWhoosh or
 * real climb side by side (issue #395) and the outdoor time predicted from recent indoor power
 * (issue #396). Phone-only.
 */
public final class ClimbIndoorActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";
    private static final String EXTRA_CLIMB_INDEX = "climb_index";

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy", new Locale("nl"));

    public static Intent intentFor(Context ctx, String routeId, int climbIndex) {
        return new Intent(ctx, ClimbIndoorActivity.class)
                .putExtra(EXTRA_ROUTE_ID, routeId)
                .putExtra(EXTRA_CLIMB_INDEX, climbIndex);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mywhoosh);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Indoor en progressie");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout rows = findViewById(R.id.rows);
        ClimbIndoorViewModel vm = new ViewModelProvider(this).get(ClimbIndoorViewModel.class);
        vm.state().observe(this, st -> render(rows, st));
        vm.error().observe(this, msg -> {
            rows.removeAllViews();
            addText(rows, msg);
        });
        vm.load(getIntent().getStringExtra(EXTRA_ROUTE_ID),
                getIntent().getIntExtra(EXTRA_CLIMB_INDEX, 0));
    }

    private void render(ViewGroup rows, ClimbIndoorViewModel.State st) {
        rows.removeAllViews();
        if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(st.climbName);
        renderProgress(rows, st.progress);
        renderLink(rows, st);
        renderPrediction(rows, st);
    }

    // --- #387 -----------------------------------------------------------------------------

    private void renderProgress(ViewGroup rows, List<ClimbProgress.Point> points) {
        addHeader(rows, "Progressie");
        if (points.isEmpty()) {
            addText(rows, "Nog geen pogingen op deze klim.");
            return;
        }
        List<Double> times = new ArrayList<>();
        List<Double> wkg = new ArrayList<>();
        Set<Integer> timePr = new HashSet<>();
        Set<Integer> powerPr = new HashSet<>();
        boolean anyPower = false;
        for (int i = 0; i < points.size(); i++) {
            ClimbProgress.Point p = points.get(i);
            times.add((double) p.elapsedSec);
            wkg.add(p.wattsPerKg);
            if (p.wattsPerKg != null) anyPower = true;
            if (p.timePr) timePr.add(i);
            if (p.powerPr) powerPr.add(i);
        }
        if (points.size() >= 2) {
            SimpleLineChartView chart = new SimpleLineChartView(this);
            List<SimpleLineChartView.Series> series = new ArrayList<>();
            series.add(new SimpleLineChartView.Series(times,
                    ContextCompat.getColor(this, R.color.color_accent), "tijd", true, timePr,
                    v -> MyWhooshActivity.duration((int) Math.round(v))));
            if (anyPower) {
                series.add(new SimpleLineChartView.Series(wkg, SegmentColorPalette.statusOk(),
                        "W/kg", false, powerPr,
                        v -> String.format(Locale.getDefault(), "%.2f", v)));
            }
            chart.setSeries(series, false);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            chart.setPadding(pad, pad, pad, 0);
            rows.addView(chart);
            addText(rows, "Oud → nieuw. Hoger is beter: een snellere tijd staat hoger. "
                    + "Grote stippen zijn je PR's.");
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            ClimbProgress.Point p = points.get(i);
            StringBuilder detail = new StringBuilder(p.indoor ? "Indoor" : "Buiten");
            if (p.avgWatts != null) {
                detail.append(String.format(Locale.getDefault(), ", %d W", p.avgWatts));
            }
            if (p.wattsPerKg != null) {
                detail.append(String.format(Locale.getDefault(), ", %.2f W/kg", p.wattsPerKg));
            }
            if (p.timePr) detail.append("  •  PR tijd");
            if (p.powerPr) detail.append("  •  PR W/kg");
            addRow(rows, dateFormat.format(new Date(p.dateEpochSec * 1000L)),
                    MyWhooshActivity.duration(p.elapsedSec), detail.toString());
        }
    }

    // --- #395 -----------------------------------------------------------------------------

    private void renderLink(ViewGroup rows, ClimbIndoorViewModel.State st) {
        addHeader(rows, st.virtual ? "De echte klim" : "Op MyWhoosh");
        if (st.counterpart == null) {
            addText(rows, st.virtual
                    ? "Geen echte klim in je collectie gevonden met een vergelijkbare naam, "
                            + "lengte en helling."
                    : "Geen MyWhoosh-klim gevonden die deze klim nabootst (naam, lengte en "
                            + "helling moeten overeenkomen).");
            return;
        }
        View link = addRow(rows, st.counterpart.virtual ? "MyWhoosh-versie" : "Echte klim",
                st.counterpart.name, String.format(Locale.getDefault(),
                        "%.1f km, %.1f %% gemiddeld — tik om te openen",
                        st.counterpart.lengthM / 1000.0, st.counterpart.avgGradient * 100));
        link.setOnClickListener(v -> startActivity(ClimbDetailActivity.intentFor(this,
                st.counterpart.routeId, st.counterpart.climbIndex)));
        addSide(rows, st.self);
        if (st.other != null) addSide(rows, st.other);
    }

    private void addSide(ViewGroup rows, ClimbIndoorViewModel.Side s) {
        if (s.attempts == 0) {
            addRow(rows, s.label, "–", "Nog geen pogingen.");
            return;
        }
        StringBuilder detail = new StringBuilder(s.attempts + " poging(en)");
        if (s.bestWatts != null) detail.append(", beste gem. ").append(s.bestWatts).append(" W");
        if (s.bestWkg != null) {
            detail.append(String.format(Locale.getDefault(), ", beste %.2f W/kg", s.bestWkg));
        }
        addRow(rows, s.label, s.bestSec > 0 ? MyWhooshActivity.duration(s.bestSec) : "–",
                detail.toString());
    }

    // --- #396 -----------------------------------------------------------------------------

    private void renderPrediction(ViewGroup rows, ClimbIndoorViewModel.State st) {
        addHeader(rows, "Voorspelling buiten uit indoorvermogen");
        if (st.prediction == null) {
            addText(rows, st.predictionHint != null ? st.predictionHint : "Geen voorspelling mogelijk.");
            return;
        }
        String basis = String.format(Locale.getDefault(),
                "%s, uit je beste 20 min (%d W) en 60 min (%d W) indoor in de laatste %d dagen: "
                        + "indoor-FTP %d W, %.1f kg, rond %.0f W op de klim.",
                st.predictedClimbName, st.basis.best20MinWatts, st.basis.best60MinWatts,
                nl.paree.climbpro.domain.mywhoosh.IndoorClimbPredictor.WINDOW_DAYS,
                st.basis.indoorFtpWatts, st.weightKg, st.prediction.assumedPowerWatts);
        addRow(rows, "Verwachte tijd", MyWhooshActivity.duration(st.prediction.totalSeconds), basis);
        int[] seg = st.prediction.segmentSeconds;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.length; i++) {
            if (i > 0) sb.append(i % 4 == 0 ? "\n" : "   ");
            sb.append(String.format(Locale.getDefault(), "%d: %s", i + 1,
                    MyWhooshActivity.duration(seg[i])));
        }
        addRow(rows, "Per segment", "", sb.toString()).findViewById(R.id.value)
                .setVisibility(View.GONE);
    }

    // --- helpers --------------------------------------------------------------------------

    private void addHeader(ViewGroup rows, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ContextCompat.getColor(this, R.color.color_accent));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad + pad / 2, pad, 0);
        rows.addView(t);
    }

    private void addText(ViewGroup rows, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad / 2, pad, 0);
        rows.addView(t);
    }

    private View addRow(ViewGroup container, String title, String value, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.value)).setText(value);
        TextView d = v.findViewById(R.id.detail);
        d.setMaxLines(8);
        d.setText(detail);
        container.addView(v);
        return v;
    }
}
