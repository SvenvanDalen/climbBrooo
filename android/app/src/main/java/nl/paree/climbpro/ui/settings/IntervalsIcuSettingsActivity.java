package nl.paree.climbpro.ui.settings;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;

/**
 * Link intervals.icu (issue #78) with a personal API key (intervals.icu → Settings →
 * Developer Settings) and athlete id, so climb workouts can be planned on its calendar.
 */
public final class IntervalsIcuSettingsActivity extends AppCompatActivity {

    private IntervalsIcuSettingsViewModel viewModel;
    private EditText keyInput;
    private EditText athleteInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intervals_icu_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        keyInput = findViewById(R.id.input_api_key);
        athleteInput = findViewById(R.id.input_athlete_id);
        status = findViewById(R.id.status);

        viewModel = new ViewModelProvider(this).get(IntervalsIcuSettingsViewModel.class);
        viewModel.state().observe(this, s -> {
            if (s == null) return;
            keyInput.setText("");
            keyInput.setHint(s.configured ? R.string.intervals_api_key_hint_saved
                    : R.string.intervals_api_key_hint);
            athleteInput.setText(s.athleteId);
            findViewById(R.id.btn_clear).setEnabled(s.configured);
        });
        viewModel.status().observe(this, msg -> status.setText(msg != null ? msg : ""));

        findViewById(R.id.btn_save).setOnClickListener(v ->
                viewModel.save(keyInput.getText().toString(), athleteInput.getText().toString()));
        findViewById(R.id.btn_test).setOnClickListener(v ->
                viewModel.testConnection(keyInput.getText().toString(),
                        athleteInput.getText().toString()));
        findViewById(R.id.btn_clear).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.intervals_clear)
                .setMessage(R.string.intervals_clear_confirm)
                .setPositiveButton(R.string.intervals_clear, (d, w) -> viewModel.clear())
                .setNegativeButton(R.string.intervals_cancel, null)
                .show());

        viewModel.load();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
