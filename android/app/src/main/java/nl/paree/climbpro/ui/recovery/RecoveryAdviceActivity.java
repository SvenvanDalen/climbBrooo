package nl.paree.climbpro.ui.recovery;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.climb.RecoveryAdvisor.Advice;

/**
 * "Rustdag-advies" (issue #61) — a small, deliberately calm phone-only screen showing
 * the rider's recent climbing load against their own baseline, and a rest-day
 * suggestion only when {@link nl.paree.climbpro.domain.climb.RecoveryAdvisor} actually
 * flags an overload. When it doesn't, the screen must read as plain information, not
 * as a warning that happens to be turned off — no red, no icons, just numbers.
 */
public final class RecoveryAdviceActivity extends AppCompatActivity {

    private RecoveryAdviceViewModel viewModel;
    private TextView statusText;
    private TextView rationaleText;
    private TextView recentGainText;
    private TextView baselineText;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recovery_advice);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        statusText = findViewById(R.id.statusText);
        rationaleText = findViewById(R.id.rationaleText);
        recentGainText = findViewById(R.id.recentGainText);
        baselineText = findViewById(R.id.baselineText);
        emptyText = findViewById(R.id.emptyText);

        viewModel = new ViewModelProvider(this).get(RecoveryAdviceViewModel.class);
        viewModel.advice().observe(this, this::render);
        viewModel.load();
    }

    private void render(Advice advice) {
        if (advice == null || !advice.rodeRecently) {
            emptyText.setVisibility(View.VISIBLE);
            statusText.setText("");
            rationaleText.setText("");
            recentGainText.setText("");
            baselineText.setText("");
            return;
        }
        emptyText.setVisibility(View.GONE);

        statusText.setText(advice.suggestRest ? "Overweeg een rustdag" : "Alles in balans");
        rationaleText.setText(advice.rationale);
        recentGainText.setText(advice.recentGainM + " hm");
        baselineText.setText(String.format(java.util.Locale.getDefault(),
                "%.0f hm", advice.baselineWeeklyAvgGainM));
    }
}
