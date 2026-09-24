package nl.paree.climbpro.ui.planning;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.planning.MultiDayTourPlan;
import nl.paree.climbpro.domain.planning.MultiDayTourPlanner;
import nl.paree.climbpro.domain.planning.TourDay;
import nl.paree.climbpro.domain.planning.TourStop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Meerdaagse toer (issue #67): spreads the climbs of the Klimplanning over several days,
 * balanced on hoogtemeters (or estimated climbing time). Phone-only — no watch or protocol
 * involvement. Shows a day-by-day overview with hemelsbreed distances; the actual road route
 * per day is planned in Garmin/Strava/Komoot, never fabricated here.
 */
public final class MultiDayTourActivity extends AppCompatActivity {

    private static final int MAX_DAYS = 60;

    private MultiDayTourViewModel viewModel;
    private TextView selectionSummary;
    private EditText inputDays;
    private EditText inputMaxHm;
    private Spinner startSpinner;
    private CheckBox balanceOnTime;
    private LinearLayout result;
    private Button shareButton;

    private List<TourStop> candidates = new ArrayList<>();
    private MultiDayTourPlan currentPlan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_day_tour);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        selectionSummary = findViewById(R.id.selectionSummary);
        inputDays = findViewById(R.id.inputDays);
        inputMaxHm = findViewById(R.id.inputMaxHm);
        startSpinner = findViewById(R.id.startSpinner);
        balanceOnTime = findViewById(R.id.balanceOnTime);
        result = findViewById(R.id.result);
        shareButton = findViewById(R.id.shareButton);

        viewModel = new ViewModelProvider(this).get(MultiDayTourViewModel.class);
        viewModel.candidates().observe(this, this::onCandidates);
        viewModel.plan().observe(this, this::renderPlan);
        viewModel.profileComplete().observe(this, complete -> {
            boolean ok = Boolean.TRUE.equals(complete);
            balanceOnTime.setEnabled(ok);
            if (ok) {
                balanceOnTime.setText("Verdeel op geschatte klimtijd i.p.v. hoogtemeters");
            } else {
                balanceOnTime.setChecked(false);
                balanceOnTime.setText("Verdeel op geschatte klimtijd (vul eerst je rijdersprofiel in)");
            }
        });
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());

        findViewById(R.id.selectButton).setOnClickListener(v -> showSelectionDialog());
        findViewById(R.id.planButton).setOnClickListener(v -> requestPlan());
        shareButton.setOnClickListener(v -> sharePlan());

        viewModel.loadCandidates();
    }

    private void onCandidates(List<TourStop> stops) {
        candidates = stops;
        int selectedPosition = startSpinner.getSelectedItemPosition();
        List<String> startLabels = new ArrayList<>();
        startLabels.add("Automatisch (kortste volgorde)");
        for (TourStop s : stops) startLabels.add("Beginnen bij " + s.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, startLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        startSpinner.setAdapter(adapter);
        if (selectedPosition > 0 && selectedPosition < startLabels.size()) {
            startSpinner.setSelection(selectedPosition);
        }
        updateSelectionSummary();
    }

    private void updateSelectionSummary() {
        if (candidates.isEmpty()) {
            selectionSummary.setText("Er staan nog geen komende klimmen in je Klimplanning. "
                    + "Plan eerst klimmen om ze over meerdere dagen te verdelen.");
            return;
        }
        int selected = 0;
        for (TourStop s : candidates) if (viewModel.selectedKeys().contains(s.key)) selected++;
        selectionSummary.setText(selected + " van " + candidates.size()
                + " klimmen uit je Klimplanning geselecteerd.");
    }

    private void showSelectionDialog() {
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Geen klimmen in je Klimplanning", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[candidates.size()];
        boolean[] checked = new boolean[candidates.size()];
        Set<String> selected = viewModel.selectedKeys();
        for (int i = 0; i < candidates.size(); i++) {
            labels[i] = candidates.get(i).name;
            checked[i] = selected.contains(candidates.get(i).key);
        }
        new AlertDialog.Builder(this)
                .setTitle("Welke klimmen gaan mee?")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    Set<String> keys = new LinkedHashSet<>();
                    for (int i = 0; i < candidates.size(); i++) {
                        if (checked[i]) keys.add(candidates.get(i).key);
                    }
                    viewModel.setSelectedKeys(keys);
                    updateSelectionSummary();
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void requestPlan() {
        int days = parseInt(inputDays, 0);
        if (days < 1 || days > MAX_DAYS) {
            inputDays.setError("Kies 1 t/m " + MAX_DAYS + " dagen");
            return;
        }
        int maxHm = parseInt(inputMaxHm, 0);
        Double startLat = null;
        Double startLon = null;
        int startPos = startSpinner.getSelectedItemPosition();
        if (startPos > 0 && startPos - 1 < candidates.size()) {
            TourStop start = candidates.get(startPos - 1);
            startLat = start.startLat;
            startLon = start.startLon;
            if (!viewModel.selectedKeys().contains(start.key)) {
                Toast.makeText(this, "Let op: je startklim is niet geselecteerd; "
                        + "de toer begint bij de dichtstbijzijnde gekozen klim.",
                        Toast.LENGTH_LONG).show();
            }
        }
        MultiDayTourPlanner.BalanceMetric metric = balanceOnTime.isEnabled() && balanceOnTime.isChecked()
                ? MultiDayTourPlanner.BalanceMetric.CLIMB_TIME
                : MultiDayTourPlanner.BalanceMetric.ELEVATION;
        viewModel.computePlan(new MultiDayTourPlanner.Request(days, maxHm, startLat, startLon, metric));
    }

    private static int parseInt(EditText input, int fallback) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void renderPlan(MultiDayTourPlan plan) {
        currentPlan = plan;
        result.removeAllViews();
        if (plan == null) {
            shareButton.setVisibility(View.GONE);
            return;
        }
        if (plan.isEmpty()) {
            addText("Selecteer minstens één klim om een toer te plannen.", false);
            shareButton.setVisibility(View.GONE);
            return;
        }
        addText(MultiDayTourFormatter.summary(plan), true);
        for (String warning : MultiDayTourFormatter.warnings(plan)) addText(warning, false);
        for (TourDay day : plan.days) {
            addText(MultiDayTourFormatter.dayHeader(day), true);
            addText(MultiDayTourFormatter.dayBody(day, plan.maxElevationPerDayM), false);
        }
        addText(MultiDayTourFormatter.DISCLAIMER, false);
        shareButton.setVisibility(View.VISIBLE);
    }

    private void addText(String text, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, bold ? 24 : 4, 0, 4);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        result.addView(tv);
    }

    private void sharePlan() {
        if (currentPlan == null || currentPlan.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Meerdaagse toer");
        share.putExtra(Intent.EXTRA_TEXT, MultiDayTourFormatter.shareText(currentPlan));
        startActivity(Intent.createChooser(share, "Dagoverzicht delen"));
    }
}
