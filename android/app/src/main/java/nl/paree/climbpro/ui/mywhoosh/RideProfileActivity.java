package nl.paree.climbpro.ui.mywhoosh;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.RideProfile;
import nl.paree.climbpro.domain.power.WorkoutCompliance;

import java.util.Locale;

/**
 * Ride detail with the elevation profile and power, heart rate and cadence over it, each
 * switchable, scrolling together (issue #390). Opened from a ride on the MyWhoosh screen.
 */
public final class RideProfileActivity extends AppCompatActivity {

    private static final String EXTRA_ACTIVITY_ID = "activity_id";
    private static final String[] LABELS = {"Vermogen", "Hartslag", "Cadans"};

    private LinearLayout rows;

    public static Intent intentFor(Context context, long activityId) {
        return new Intent(context, RideProfileActivity.class)
                .putExtra(EXTRA_ACTIVITY_ID, activityId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mywhoosh);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Ritprofiel");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rows = findViewById(R.id.rows);
        addText("Streams ophalen bij Strava…");
        RideProfileViewModel viewModel = new ViewModelProvider(this).get(RideProfileViewModel.class);
        viewModel.result().observe(this, this::render);
        viewModel.error().observe(this, msg -> {
            rows.removeAllViews();
            addText(msg);
        });
        viewModel.load(getIntent().getLongExtra(EXTRA_ACTIVITY_ID, -1));
    }

    private void render(RideProfileViewModel.Result r) {
        rows.removeAllViews();
        RideProfile p = r.profile;
        addText(IndoorRides.routeTitle(r.ride) + String.format(Locale.getDefault(),
                " — %.1f km. Het grijze vlak is het hoogteprofiel; elke lijn heeft een eigen "
                        + "schaal. Veeg opzij om door de rit te scrollen.",
                p.size() * p.binM / 1000));

        RideProfileChartView chart = new RideProfileChartView(this);
        chart.setProfile(p);
        double[][] series = {p.watts, p.heartrate, p.cadence};
        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        toggles.setPadding(pad, pad / 2, pad, 0);
        for (int k = 0; k < series.length; k++) {
            if (!p.has(series[k])) {
                chart.setVisible(k, false);
                continue;
            }
            CheckBox box = new CheckBox(this);
            box.setText(LABELS[k]);
            box.setChecked(true);
            box.setTextColor(RideProfileChartView.COLORS[k]);
            box.setButtonTintList(ColorStateList.valueOf(RideProfileChartView.COLORS[k]));
            int index = k;
            box.setOnCheckedChangeListener((b, on) -> chart.setVisible(index, on));
            toggles.addView(box, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (toggles.getChildCount() == 0) addText("Deze rit heeft geen vermogen, hartslag of cadans.");
        rows.addView(toggles);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setPadding(pad, pad / 2, pad, 0);
        scroll.addView(chart, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rows.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderCompliance(r.steps);
    }

    /** Planned versus ridden power per interval block (issue #401). */
    private void renderCompliance(java.util.List<WorkoutCompliance.Step> steps) {
        if (steps == null || steps.isEmpty()) return;
        Integer overall = WorkoutCompliance.overallPct(steps);
        addText(overall != null ? "Training vs plan — gemiddeld " + overall + " % van het doel"
                : "Training vs plan — geen vermogen gemeten");
        for (WorkoutCompliance.Step s : steps) {
            String ridden = s.avgWatts != null ? s.avgWatts + " W (" + s.compliancePct + " %, "
                    + verdictLabel(s.verdict) + ")" : "geen vermogen";
            addText(String.format(Locale.getDefault(), "%s — gepland %d W (%d–%d), gereden %s, %d:%02d",
                    s.name, s.targetWatts, s.lowWatts, s.highWatts, ridden,
                    s.elapsedSec / 60, s.elapsedSec % 60));
        }
    }

    private static String verdictLabel(WorkoutCompliance.Verdict v) {
        switch (v) {
            case BELOW: return "te laag";
            case ABOVE: return "te hoog";
            default:    return "in de band";
        }
    }

    private void addText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad / 2, pad, 0);
        rows.addView(t);
    }
}
