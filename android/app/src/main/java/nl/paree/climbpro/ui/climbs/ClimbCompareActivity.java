package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.climb.ClimbComparison;
import nl.paree.climbpro.domain.power.DurationFormat;

import java.util.List;
import java.util.Locale;

/**
 * "Klimmen vergelijken" (issue #214): the climb it was opened from next to one the rider picks,
 * with both profiles on one scale and a table of length, height, gradients, difficulty,
 * estimated time and the rider's own record. Phone-only.
 */
public final class ClimbCompareActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";
    private static final String EXTRA_CLIMB_INDEX = "climb_index";

    private ClimbCompareViewModel viewModel;
    private TableLayout table;
    private TextView verdict;
    private ClimbCompareProfileView profiles;

    public static Intent intentFor(Context ctx, String routeId, int climbIndex) {
        Intent i = new Intent(ctx, ClimbCompareActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        i.putExtra(EXTRA_CLIMB_INDEX, climbIndex);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_compare);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        table = findViewById(R.id.table);
        verdict = findViewById(R.id.verdict);
        profiles = findViewById(R.id.profiles);

        viewModel = new ViewModelProvider(this).get(ClimbCompareViewModel.class);
        viewModel.first().observe(this, s -> render());
        viewModel.second().observe(this, s -> render());
        viewModel.candidates().observe(this, list -> {
            if (viewModel.takeAutoPicker()) showPicker(list);
        });
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        findViewById(R.id.btn_pick_other).setOnClickListener(
                v -> showPicker(viewModel.candidates().getValue()));

        viewModel.load(getIntent().getStringExtra(EXTRA_ROUTE_ID),
                getIntent().getIntExtra(EXTRA_CLIMB_INDEX, 0));
    }

    private void showPicker(List<ClimbComparison.Candidate> list) {
        if (list == null) {
            Toast.makeText(this, "Klimmen laden…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "Je hebt nog geen andere klim om mee te vergelijken.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            ClimbComparison.Candidate c = list.get(i);
            labels[i] = String.format(Locale.getDefault(), "%s — %.1f km · %.1f %%",
                    c.name, c.lengthM / 1000.0, c.avgGradient * 100);
        }
        new AlertDialog.Builder(this)
                .setTitle("Vergelijk met…")
                .setItems(labels, (d, which) -> viewModel.pick(list.get(which)))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void render() {
        ClimbComparison.Side a = viewModel.first().getValue();
        ClimbComparison.Side b = viewModel.second().getValue();
        table.removeAllViews();
        if (a == null) return;
        verdict.setText(b != null ? ClimbComparison.verdict(a, b)
                : "Kies een klim om " + a.name + " mee te vergelijken.");
        profiles.setProfiles(a.profileDist, a.profileHeight,
                b != null ? b.profileDist : null, b != null ? b.profileHeight : null);

        addRow("", a.name, b != null ? b.name : "–", true);
        addRow("Lengte", km(a.lengthM), b != null ? km(b.lengthM) : "–", false);
        addRow("Hoogtemeters", a.elevationGainM + " m",
                b != null ? b.elevationGainM + " m" : "–", false);
        addRow("Gem. stijging", pct(a.avgGradient), b != null ? pct(b.avgGradient) : "–", false);
        addRow("Steilste segment", pct(a.maxSegmentGradient),
                b != null ? pct(b.maxSegmentGradient) : "–", false);
        addRow("Moeilijkheid", score(a.difficulty), b != null ? score(b.difficulty) : "–", false);
        addRow("Categorie", orDash(a.categoryLabel), b != null ? orDash(b.categoryLabel) : "–",
                false);
        addRow("Geschatte tijd", time(a.estimateSec), b != null ? time(b.estimateSec) : "–",
                false);
        addRow("Jouw record", time(a.prSec), b != null ? time(b.prSec) : "–", false);
        addRow("Keer gereden", String.valueOf(a.attempts),
                b != null ? String.valueOf(b.attempts) : "–", false);
        if (a.estimateSec == null) {
            addRow("", "Stel FTP en gewicht in voor een tijdschatting.", "", false);
        }
    }

    private void addRow(String label, String first, String second, boolean header) {
        TableRow row = new TableRow(this);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);
        row.addView(cell(label, false, R.color.color_text_tertiary));
        row.addView(cell(first, header, R.color.color_accent));
        TextView b = cell(second, header, R.color.color_text_primary);
        if (header) b.setTextColor(ClimbCompareProfileView.COLOR_SECOND);
        row.addView(b);
        table.addView(row);
    }

    private TextView cell(String text, boolean bold, int colorRes) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        t.setPadding(0, 0, pad, 0);
        t.setTextColor(ContextCompat.getColor(this, colorRes));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private static String km(int m) {
        return String.format(Locale.getDefault(), "%.1f km", m / 1000.0);
    }

    private static String pct(double fraction) {
        return String.format(Locale.getDefault(), "%.1f %%", fraction * 100);
    }

    private static String score(double s) {
        return String.format(Locale.getDefault(), "%.0f", s * 100);
    }

    private static String time(Integer sec) {
        return sec != null ? DurationFormat.format(sec) : "–";
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "–" : s;
    }
}
