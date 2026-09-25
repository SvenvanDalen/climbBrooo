package nl.paree.climbpro.ui.frame;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.Locale;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.frame.FrameSizeCalculator;
import nl.paree.climbpro.domain.frame.FrameSizeResult;

/**
 * "Framemaat" screen (issue #235): indicative road and MTB frame size from body height and
 * inseam. Phone-only, stateless — nothing is stored and nothing goes to the watch.
 */
public final class FrameSizeActivity extends AppCompatActivity {

    private EditText heightInput;
    private EditText inseamInput;
    private TextView errorText;
    private TextView roadResult;
    private TextView mtbResult;

    public static Intent intentFor(Context context) {
        return new Intent(context, FrameSizeActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_frame_size);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        heightInput = findViewById(R.id.heightInput);
        inseamInput = findViewById(R.id.inseamInput);
        errorText = findViewById(R.id.errorText);
        roadResult = findViewById(R.id.roadResult);
        mtbResult = findViewById(R.id.mtbResult);
        TextView explanation = findViewById(R.id.explanationText);
        explanation.setText(explanationText());

        findViewById(R.id.calculateButton).setOnClickListener(v -> calculate());
        inseamInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                calculate();
                return true;
            }
            return false;
        });
    }

    /**
     * Result/error text is restored via {@code freezesText} (in super), but the error view's
     * visibility is not — re-derive it from the restored text.
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        errorText.setVisibility(errorText.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void calculate() {
        FrameSizeResult r = FrameSizeCalculator.calculate(
                heightInput.getText().toString(), inseamInput.getText().toString());
        if (!r.ok) {
            errorText.setText(r.error);
            errorText.setVisibility(View.VISIBLE);
            roadResult.setText("");
            mtbResult.setText("");
            return;
        }
        errorText.setText("");
        errorText.setVisibility(View.GONE);
        Locale locale = Locale.getDefault();
        roadResult.setText(String.format(locale,
                "Racefiets: %d cm (maat %s)", r.roadSeatTubeCm, r.roadLetterSize));
        mtbResult.setText(String.format(locale,
                "Mountainbike: %.1f cm (%.1f inch)", r.mtbSeatTubeCm, r.mtbSeatTubeInch));
    }

    private static String explanationText() {
        Locale locale = Locale.getDefault();
        return String.format(locale,
                "Hoe wordt dit berekend?\n"
                        + "• Racefiets: zitbuislengte (hart trapas tot bovenkant zitbuis)"
                        + " ≈ binnenbeenlengte × %.3f.\n"
                        + "• Mountainbike: zitbuislengte ≈ binnenbeenlengte × %.2f,"
                        + " omgerekend naar inch (÷ %.2f).\n"
                        + "• Lettermaat racefiets op basis van lengte: XS onder 165 cm,"
                        + " S 165–171, M 172–178, L 179–185, XL vanaf 186 cm.",
                FrameSizeCalculator.ROAD_FACTOR, FrameSizeCalculator.MTB_FACTOR,
                FrameSizeCalculator.CM_PER_INCH);
    }
}
