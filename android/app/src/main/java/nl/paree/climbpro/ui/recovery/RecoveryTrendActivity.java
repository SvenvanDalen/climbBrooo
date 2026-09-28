package nl.paree.climbpro.ui.recovery;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer.Direction;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer.Point;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer.Trend;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Herstel-trend" (issue #183): post-ride RPE and sleep over time, next to each ride's
 * performance data, with a recent-vs-previous comparison. Opened from the ride archive.
 * Phone-only and offline.
 */
public final class RecoveryTrendActivity extends AppCompatActivity {

    /** Most recent checks drawn in the chart; the list below shows them all. */
    static final int CHART_POINTS = 30;

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());

    public static Intent intentFor(Context context) {
        return new Intent(context, RecoveryTrendActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recovery_trend);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecoveryTrendViewModel viewModel =
                new ViewModelProvider(this).get(RecoveryTrendViewModel.class);
        viewModel.trend().observe(this, this::render);
        viewModel.load();
    }

    private void render(Trend t) {
        TextView summary = findViewById(R.id.summary);
        TextView warning = findViewById(R.id.warning);
        RecoveryTrendChartView chart = findViewById(R.id.chart);
        TextView legend = findViewById(R.id.legend);
        LinearLayout container = findViewById(R.id.points);
        container.removeAllViews();

        if (t.points.isEmpty()) {
            summary.setText(R.string.recovery_trend_empty);
            warning.setVisibility(View.GONE);
            chart.setVisibility(View.GONE);
            legend.setVisibility(View.GONE);
            return;
        }

        if (t.hasComparison()) {
            summary.setText(getString(R.string.recovery_trend_summary, t.windowSize,
                    t.rpeAvgPrevious, t.rpeAvgRecent, directionText(t.rpeDirection),
                    t.sleepAvgPrevious, t.sleepAvgRecent, directionText(t.sleepDirection)));
        } else {
            summary.setText(getString(R.string.recovery_trend_summary_few, t.points.size(),
                    t.rpeAvgRecent, t.sleepAvgRecent));
        }
        warning.setVisibility(t.recoveryWarning ? View.VISIBLE : View.GONE);

        List<Point> shown = t.points.size() > CHART_POINTS
                ? t.points.subList(t.points.size() - CHART_POINTS, t.points.size()) : t.points;
        chart.setVisibility(View.VISIBLE);
        chart.setPoints(shown);
        chart.setContentDescription(getString(R.string.recovery_trend_chart_description,
                shown.size()));
        legend.setVisibility(View.VISIBLE);
        legend.setText(getString(R.string.recovery_trend_legend, shown.size()));

        // Newest first below the chart.
        float density = getResources().getDisplayMetrics().density;
        for (int i = t.points.size() - 1; i >= 0; i--) {
            container.addView(pointView(t.points.get(i), density));
        }
    }

    private View pointView(Point p, float density) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (8 * density), 0, (int) (8 * density));

        String date = p.startEpochSec > 0
                ? dateFormat.format(new Date(p.startEpochSec * 1000L))
                : getString(R.string.recovery_unknown_date);
        String name = p.rideName != null && !p.rideName.isEmpty()
                ? p.rideName : getString(R.string.recovery_ride_default_name);
        TextView title = new TextView(this);
        title.setText(getString(R.string.recovery_point_title, date, name));
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(title);

        String stats = getString(R.string.recovery_point_stats, p.distanceKm,
                getString(R.string.recovery_duration, p.movingTimeSec / 3600,
                        (p.movingTimeSec % 3600) / 60),
                p.avgSpeedKmh, Math.round(p.elevationGainM));
        if (p.avgWatts != null) stats += getString(R.string.recovery_point_watts, p.avgWatts);
        TextView statsView = new TextView(this);
        statsView.setText(stats);
        statsView.setTextSize(13f);
        box.addView(statsView);

        String check = getString(R.string.recovery_point_check, p.rpe, p.sleepQuality,
                p.sessionLoad);
        if (p.sleepHours != null) check += getString(R.string.recovery_point_hours, p.sleepHours);
        if (p.note != null) check += "\n" + p.note;
        TextView checkView = new TextView(this);
        checkView.setText(check);
        checkView.setTextSize(13f);
        checkView.setTextColor(getColor(R.color.color_accent));
        box.addView(checkView);
        return box;
    }

    private String directionText(Direction d) {
        switch (d) {
            case UP: return getString(R.string.recovery_direction_up);
            case DOWN: return getString(R.string.recovery_direction_down);
            default: return getString(R.string.recovery_direction_stable);
        }
    }
}
