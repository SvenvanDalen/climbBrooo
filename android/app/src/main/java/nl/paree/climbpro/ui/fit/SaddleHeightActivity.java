package nl.paree.climbpro.ui.fit;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.fit.SaddleHeightCalculator;

/**
 * "Zadelhoogte-assistent" (issue #236): phone-only screen that turns the rider's inseam into a
 * saddle-height starting point (LeMond and Hamley/109 %) with measuring instructions and the
 * heel-method / knee-angle check. Recomputes on every keystroke; stores nothing — the EditText's
 * own saved state covers rotation.
 */
public final class SaddleHeightActivity extends AppCompatActivity {

    private static final String HINT = "Vul je binnenbeenlengte in om een advies te zien.";

    private TextView messageText;
    private View resultPanel;
    private TextView lemondText;
    private TextView hamleyText;

    public static Intent intentFor(Context context) {
        return new Intent(context, SaddleHeightActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saddle_height);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        EditText inseamInput = findViewById(R.id.inseamInput);
        messageText = findViewById(R.id.messageText);
        resultPanel = findViewById(R.id.resultPanel);
        lemondText = findViewById(R.id.lemondText);
        hamleyText = findViewById(R.id.hamleyText);

        inseamInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                render(s.toString());
            }
        });
        render(inseamInput.getText().toString());
    }

    private void render(String input) {
        if (SaddleHeightCalculator.isBlank(input)) {
            messageText.setText(HINT);
            resultPanel.setVisibility(View.GONE);
            return;
        }
        Double inseam = SaddleHeightCalculator.parseInseamCm(input);
        if (inseam == null) {
            messageText.setText(SaddleHeightCalculator.RANGE_ERROR);
            resultPanel.setVisibility(View.GONE);
            return;
        }
        messageText.setText("");
        lemondText.setText(SaddleHeightCalculator.formatCm(SaddleHeightCalculator.lemondCm(inseam)));
        hamleyText.setText(SaddleHeightCalculator.formatCm(SaddleHeightCalculator.hamleyCm(inseam)));
        resultPanel.setVisibility(View.VISIBLE);
    }
}
