package nl.paree.climbpro.ui.wrapped;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.climb.ElevationComparisons;
import nl.paree.climbpro.domain.climb.WrappedCalculator.Summary;

/**
 * "Klim Wrapped" — a Spotify-Wrapped-style yearly summary over locally stored climb
 * attempts. Phone-only: pure aggregation of {@code ClimbAttemptRepository} data via
 * {@link nl.paree.climbpro.domain.climb.WrappedCalculator}, no wire format involved.
 */
public final class ClimbWrappedActivity extends AppCompatActivity {

    private ClimbWrappedViewModel viewModel;
    private LinearLayout container;
    private TextView empty;
    private int year;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_wrapped);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.wrappedContainer);
        empty = findViewById(R.id.empty);

        year = ClimbWrappedViewModel.currentYear();

        viewModel = new ViewModelProvider(this).get(ClimbWrappedViewModel.class);
        viewModel.summary().observe(this, this::render);

        Button prev = findViewById(R.id.prevYearButton);
        Button next = findViewById(R.id.nextYearButton);
        prev.setOnClickListener(v -> { year--; loadYear(); });
        next.setOnClickListener(v -> {
            if (year < ClimbWrappedViewModel.currentYear()) { year++; loadYear(); }
        });

        loadYear();
    }

    private void loadYear() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Klim Wrapped " + year);
        viewModel.load(year);
    }

    private void render(Summary s) {
        container.removeAllViews();

        if (s.totalAttempts == 0) {
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);

        String cmp = ElevationComparisons.describe(s.totalElevationGainM);
        addCard("Totale hoogtemeters", formatMeters(s.totalElevationGainM)
                + (cmp != null ? "\nDat is " + cmp : ""));
        addCard("Klimpogingen", s.totalAttempts + " op " + s.distinctClimbCount + " verschillende klim(men)");
        addCard("Totale klimtijd", formatDuration(s.totalClimbingTimeSec));

        if (s.favoriteClimbId != null) {
            addCard("Favoriete klim",
                    s.favoriteClimbName + "\n" + s.favoriteClimbAttemptCount + " pogingen");
        }

        if (s.biggestImprovementClimbId != null && s.biggestImprovementSec > 0) {
            addCard("Grootste verbetering",
                    s.biggestImprovementClimbName + "\n" + formatDuration(s.biggestImprovementSec) + " sneller");
        }
    }

    private void addCard(String title, String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(getResources().getColor(R.color.color_surface, getTheme()));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        card.setLayoutParams(lp);

        int textColor = getResources().getColor(R.color.color_text_primary, getTheme());

        TextView titleView = new TextView(this);
        titleView.setText(title.toUpperCase());
        titleView.setTextColor(textColor);
        titleView.setTextSize(13);
        titleView.setAlpha(0.85f);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(textColor);
        valueView.setTextSize(22);
        valueView.setGravity(Gravity.START);
        valueView.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);

        card.addView(titleView);
        card.addView(valueView);
        container.addView(card);
    }

    private static String formatMeters(long meters) {
        return meters + " m";
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(java.util.Locale.getDefault(), "%dh %02dm", h, m);
        if (m > 0) return String.format(java.util.Locale.getDefault(), "%dm %02ds", m, s);
        return s + "s";
    }
}
