package nl.paree.climbpro.ui.goals;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.ride.BadgeCalculator.Badge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Issue #191: earned badges (newest first, with the date) and the ones still to get, with
 * progress. Phone-only — see {@link BadgesViewModel}.
 */
public final class BadgesActivity extends AppCompatActivity {

    private static final Locale NL = new Locale("nl", "NL");
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", NL);

    private BadgesViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, BadgesActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_badges);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.badges);
        viewModel = new ViewModelProvider(this).get(BadgesViewModel.class);
        viewModel.badges().observe(this, list -> render(container, list));
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void render(LinearLayout container, List<Badge> badges) {
        container.removeAllViews();
        if (badges == null) return;
        int earned = 0;
        for (Badge b : badges) if (b.earned()) earned++;

        addHeader(container, "Behaald (" + earned + " van " + badges.size() + ")");
        if (earned == 0) {
            addNote(container, "Nog geen badges. Haal je Strava-ritten op in Ritten; badges "
                    + "worden berekend uit je rittenarchief en klimpogingen.");
        }
        boolean headerDone = false;
        for (Badge b : badges) {
            if (!b.earned() && !headerDone) {
                addHeader(container, "Nog te halen");
                headerDone = true;
            }
            String value = b.earned()
                    ? "Behaald op " + dateFormat.format(Instant.ofEpochSecond(b.earnedEpochSec)
                            .atZone(ZoneId.systemDefault()))
                    : String.format(NL, "%,d / %,d", b.progress, b.goal);
            addRow(container, b.title, value, b.description);
        }
    }

    private void addRow(LinearLayout container, String title, String value, String detail) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.value)).setText(value);
        ((TextView) row.findViewById(R.id.detail)).setText(detail);
        container.addView(row);
    }

    private void addHeader(LinearLayout container, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        int pad = dp(16);
        header.setPadding(pad, pad, pad, 0);
        container.addView(header);
    }

    private void addNote(LinearLayout container, String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int pad = dp(16);
        note.setPadding(pad, pad / 2, pad, 0);
        container.addView(note);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
