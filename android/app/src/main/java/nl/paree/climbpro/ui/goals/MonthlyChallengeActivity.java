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
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Progress;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Type;

import java.util.Random;

/**
 * Issue #192: the monthly personal challenge — pick a type and target (pre-filled with a
 * suggestion from the last months) or let the app pick one ("Verras me"). Phone-only — see
 * {@link MonthlyChallengeViewModel}.
 */
public final class MonthlyChallengeActivity extends AppCompatActivity {

    private MonthlyChallengeViewModel viewModel;
    private TextView titleText;
    private TextView progressText;
    private TextView hintText;
    private LinearProgressIndicator progressBar;
    private Button clearButton;

    public static Intent intentFor(Context context) {
        return new Intent(context, MonthlyChallengeActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monthly_challenge);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        titleText = findViewById(R.id.challengeTitle);
        progressText = findViewById(R.id.challengeProgressText);
        hintText = findViewById(R.id.challengeHint);
        progressBar = findViewById(R.id.challengeProgressBar);
        clearButton = findViewById(R.id.clearChallengeButton);

        findViewById(R.id.pickChallengeButton).setOnClickListener(v -> showTypePicker());
        findViewById(R.id.surpriseChallengeButton).setOnClickListener(v -> surprise());
        clearButton.setOnClickListener(v -> viewModel.clearChallenge());

        viewModel = new ViewModelProvider(this).get(MonthlyChallengeViewModel.class);
        viewModel.state().observe(this, this::render);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void render(MonthlyChallengeViewModel.State s) {
        Progress p = s.progress;
        boolean has = p != null;
        progressText.setVisibility(has ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(has ? View.VISIBLE : View.GONE);
        clearButton.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) {
            titleText.setText("Nog geen uitdaging voor deze maand");
            hintText.setText("Kies zelf een uitdaging, of laat de app er een voorstellen op "
                    + "basis van je afgelopen maanden.");
            return;
        }
        titleText.setText(MonthlyChallengeCalculator.title(p.type, p.target, p.month));
        progressText.setText(MonthlyChallengeCalculator.progressLine(p));
        progressBar.setProgress((int) Math.round(p.fraction() * 100));
        hintText.setText(MonthlyChallengeCalculator.hint(p));
    }

    private void showTypePicker() {
        MonthlyChallengeViewModel.State s = viewModel.state().getValue();
        if (s == null) return;
        Type[] types = Type.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = capitalize(types[i].noun) + " (voorstel: "
                    + suggestionText(s, types[i]) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Soort uitdaging")
                .setItems(labels, (d, which) -> showTargetDialog(types[which],
                        suggestion(s, types[which])))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void showTargetDialog(Type type, int suggested) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(suggested));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Doel: aantal " + type.noun)
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) -> {
                    int target = parseIntOrZero(input.getText().toString());
                    if (target > 0) viewModel.setChallenge(type, target);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    /** "Krijg een uitdaging": a random type at its suggested target. */
    private void surprise() {
        MonthlyChallengeViewModel.State s = viewModel.state().getValue();
        if (s == null) return;
        Type[] types = Type.values();
        Type type = types[new Random().nextInt(types.length)];
        viewModel.setChallenge(type, suggestion(s, type));
    }

    private static int suggestion(MonthlyChallengeViewModel.State s, Type type) {
        Integer v = s.suggestions.get(type);
        return v != null ? v : 1;
    }

    private static String suggestionText(MonthlyChallengeViewModel.State s, Type type) {
        return String.format(new java.util.Locale("nl", "NL"), "%,d", suggestion(s, type));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int parseIntOrZero(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
