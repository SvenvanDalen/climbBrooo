package nl.paree.climbpro.ui.training;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Plan;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Session;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Week;

import java.util.Locale;

/**
 * "Trainingsblok" (issue #63): shows a multi-week block (3 build weeks + 1 recovery week)
 * of gradually increasing climbing load, built from the rider's own known climbs by
 * {@link nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner}. Read-only; phone-only.
 */
public final class ClimbPeriodizationActivity extends AppCompatActivity {

    private TextView summaryText;
    private LinearLayout weeksContainer;
    private TextView emptyText;

    public static Intent intentFor(Context context) {
        return new Intent(context, ClimbPeriodizationActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_periodization);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        summaryText = findViewById(R.id.summaryText);
        weeksContainer = findViewById(R.id.weeksContainer);
        emptyText = findViewById(R.id.emptyText);

        ClimbPeriodizationViewModel viewModel =
                new ViewModelProvider(this).get(ClimbPeriodizationViewModel.class);
        viewModel.state().observe(this, state -> render(state.plan));
        viewModel.load();
    }

    private void render(Plan plan) {
        weeksContainer.removeAllViews();
        if (plan == null) {
            emptyText.setVisibility(View.VISIBLE);
            summaryText.setText("");
            return;
        }
        emptyText.setVisibility(View.GONE);

        StringBuilder summary = new StringBuilder(plan.baselineFromHistory
                ? getString(R.string.periodization_start_history, plan.startWeeklyGainM)
                : getString(R.string.periodization_start_default, plan.startWeeklyGainM));
        if (!plan.fromRiddenClimbs) {
            summary.append("\n\n").append(getString(R.string.periodization_not_ridden));
        }
        summaryText.setText(summary);

        for (Week week : plan.weeks) weeksContainer.addView(weekCard(week));
    }

    private View weekCard(Week week) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(ContextCompat.getColor(this, R.color.color_surface));
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        card.setLayoutParams(lp);

        String title = getString(week.recovery
                ? R.string.periodization_week_recovery
                : R.string.periodization_week_build, week.number);
        TextView header = text(title, 18, R.color.color_text_primary);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(header);
        card.addView(text(getString(R.string.periodization_week_load,
                week.plannedGainM, week.targetGainM), 14, R.color.color_text_secondary));

        for (Session s : week.sessions) {
            String name = s.climb.name != null ? s.climb.name
                    : getString(R.string.periodization_unnamed_climb);
            String line = getString(R.string.periodization_session, s.repeats, name,
                    String.format(Locale.getDefault(), "%.1f", s.climb.lengthM / 1000.0),
                    String.format(Locale.getDefault(), "%.1f", s.climb.avgGradient * 100),
                    s.gainM());
            TextView row = text(line, 15, R.color.color_text_primary);
            ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = dp(8);
            card.addView(row);
        }
        return card;
    }

    private TextView text(String value, int sp, int colorRes) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
