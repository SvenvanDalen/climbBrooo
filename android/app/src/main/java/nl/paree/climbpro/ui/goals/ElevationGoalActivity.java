package nl.paree.climbpro.ui.goals;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.climb.ElevationGoalCalculator.Progress;

import java.util.Locale;

/**
 * Issue #42: shows cumulative hoogtemeters vs. the user's weekly/monthly goal, with a progress
 * bar per period. Phone-only — see {@link ElevationGoalViewModel}.
 */
public final class ElevationGoalActivity extends AppCompatActivity {

    private ElevationGoalViewModel viewModel;
    private TextView weekProgressText;
    private TextView monthProgressText;
    private LinearProgressIndicator weekProgressBar;
    private LinearProgressIndicator monthProgressBar;
    private TextView noGoalText;

    public static Intent intentFor(Context context) {
        return new Intent(context, ElevationGoalActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_elevation_goal);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        weekProgressText = findViewById(R.id.weekProgressText);
        monthProgressText = findViewById(R.id.monthProgressText);
        weekProgressBar = findViewById(R.id.weekProgressBar);
        monthProgressBar = findViewById(R.id.monthProgressBar);
        noGoalText = findViewById(R.id.noGoalText);

        Button setGoalButton = findViewById(R.id.setGoalButton);
        setGoalButton.setOnClickListener(v -> showSetGoalDialog());

        viewModel = new ViewModelProvider(this).get(ElevationGoalViewModel.class);
        viewModel.weekProgress().observe(this, this::renderWeek);
        viewModel.monthProgress().observe(this, this::renderMonth);
        viewModel.load();
    }

    private void renderWeek(Progress p) {
        render(p, weekProgressText, weekProgressBar);
    }

    private void renderMonth(Progress p) {
        render(p, monthProgressText, monthProgressBar);
        noGoalText.setVisibility(p.goalM <= 0 ? View.VISIBLE : View.GONE);
    }

    private void render(Progress p, TextView text, LinearProgressIndicator bar) {
        if (p.goalM <= 0) {
            text.setText(String.format(Locale.getDefault(), "%d m (geen doel ingesteld)", p.gainedM));
            bar.setProgress(0);
        } else {
            text.setText(String.format(Locale.getDefault(), "%d / %d m", p.gainedM, p.goalM));
            int pct = (int) Math.round(Math.min(1.0, p.fraction()) * 100);
            bar.setProgress(pct);
        }
    }

    private void showSetGoalDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        Integer current = viewModel.weekProgress().getValue() != null
                ? viewModel.weekProgress().getValue().goalM : null;
        if (current != null && current > 0) input.setText(String.valueOf(current));

        new AlertDialog.Builder(this)
                .setTitle("Wekelijks hoogtemeter-doel (m)")
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) -> {
                    int metres = parseIntOrZero(input.getText().toString());
                    viewModel.setWeeklyGoalM(metres);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private static int parseIntOrZero(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
