package nl.paree.climbpro.ui.settings;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.domain.strava.StravaTitleTemplateRenderer;

/**
 * Free-form Strava activity-title template editor (issue #60): a template string with
 * {@code {placeholder}} tokens, saved as the user types, with a live preview rendered against
 * a fixed sample context rather than live attempt data (simpler, and the issue only asks for
 * "live" — i.e. updates as you type — not for it to reflect a real ride).
 */
public final class StravaTitleTemplateActivity extends AppCompatActivity {

    private static final StravaTitleTemplateRenderer.TitleContext SAMPLE_CONTEXT =
            StravaTitleTemplateRenderer.TitleContext.of("Col du Sample", 754, 12, 1180);

    private EditText templateInput;
    private TextView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_strava_title_template);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        templateInput = findViewById(R.id.input_template);
        preview = findViewById(R.id.preview);

        String saved = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "");
        templateInput.setText(saved);
        updatePreview(saved);

        templateInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePreview(s.toString());
            }

            @Override public void afterTextChanged(Editable s) {
                PreferenceManager.getDefaultSharedPreferences(StravaTitleTemplateActivity.this)
                        .edit()
                        .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, s.toString())
                        .apply();
            }
        });
    }

    private void updatePreview(String template) {
        if (template == null || template.trim().isEmpty()) {
            preview.setText("(geen sjabloon — bestaande Strava-titel blijft ongewijzigd)");
            return;
        }
        preview.setText(StravaTitleTemplateRenderer.render(template, SAMPLE_CONTEXT));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
