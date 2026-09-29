package nl.paree.climbpro.ui.fitness;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.training.TrainingLoadCalendar;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * "Trainingskalender" screen (issue #182): a year of daily training load as a
 * GitHub-contribution-style heatmap, with a tap-for-details day line and a short summary.
 * Phone-only, computed on the fly from the ride archive and climb attempts.
 */
public final class TrainingLoadCalendarActivity extends AppCompatActivity {

    private final DateTimeFormatter dayFormat =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", new Locale("nl"));
    private final DateTimeFormatter weekFormat =
            DateTimeFormatter.ofPattern("d MMMM", new Locale("nl"));

    private TrainingLoadCalendarViewModel viewModel;
    private TrainingLoadHeatmapView heatmap;

    public static Intent intentFor(Context context) {
        return new Intent(context, TrainingLoadCalendarActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training_load_calendar);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        heatmap = findViewById(R.id.heatmap);
        heatmap.setOnDayClickListener(this::showDay);
        buildLegend();

        viewModel = new ViewModelProvider(this).get(TrainingLoadCalendarViewModel.class);
        viewModel.state().observe(this, this::render);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Newly synced rides or a changed FTP change the load of every day.
        viewModel.load();
    }

    private void render(TrainingLoadCalendarViewModel.State st) {
        TrainingLoadCalendar.Result r = st.result;
        boolean none = r.activeDays == 0;
        findViewById(R.id.empty).setVisibility(none ? View.VISIBLE : View.GONE);
        findViewById(R.id.content).setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) return;

        heatmap.setResult(r);
        text(R.id.dayDetail, getString(R.string.load_calendar_tap_hint));
        // Today is in the last column: show the most recent weeks first.
        HorizontalScrollView scroll = findViewById(R.id.heatmapScroll);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_RIGHT));

        StringBuilder d = new StringBuilder(getString(R.string.load_calendar_summary,
                r.weeks, r.activeDays, r.totalLoad));
        d.append(System.lineSeparator())
                .append(getString(R.string.load_calendar_streak, r.longestStreakDays));
        if (r.busiestWeekStart != null) {
            d.append(System.lineSeparator()).append(getString(
                    R.string.load_calendar_busiest_week, weekFormat.format(r.busiestWeekStart),
                    r.busiestWeekLoad));
        }
        if (!st.ftpKnown) {
            d.append(System.lineSeparator()).append(getString(R.string.load_calendar_no_ftp));
        }
        text(R.id.details, d.toString());
    }

    private void showDay(TrainingLoadCalendar.Day day) {
        String date = dayFormat.format(day.date);
        if (day.level == TrainingLoadCalendar.Level.NONE) {
            text(R.id.dayDetail, getString(R.string.load_calendar_day_rest, date));
            return;
        }
        text(R.id.dayDetail, getString(R.string.load_calendar_day_detail, date, day.load,
                getString(levelLabel(day.level)), day.rideCount, day.climbCount,
                day.elevationGainM));
    }

    private static int levelLabel(TrainingLoadCalendar.Level level) {
        switch (level) {
            case LIGHT: return R.string.load_calendar_level_light;
            case MODERATE: return R.string.load_calendar_level_moderate;
            case HARD: return R.string.load_calendar_level_hard;
            default: return R.string.load_calendar_level_very_hard;
        }
    }

    private void buildLegend() {
        LinearLayout cells = findViewById(R.id.legendCells);
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(12 * density);
        int margin = Math.round(1.5f * density);
        for (int color : TrainingLoadHeatmapView.LEVEL_COLORS) {
            View cell = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(2 * density);
            cell.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            cells.addView(cell, lp);
        }
    }

    private void text(int id, String s) {
        ((TextView) findViewById(id)).setText(s);
    }
}
