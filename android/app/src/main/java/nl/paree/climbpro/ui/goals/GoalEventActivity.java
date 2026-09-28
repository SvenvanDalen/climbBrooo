package nl.paree.climbpro.ui.goals;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.goal.GoalEvent;
import nl.paree.climbpro.domain.goal.GoalEventProgress;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * "Doelevenement" screen (issue #221): set a target event (name, date, distance, elevation) and
 * see the countdown, training phase, readiness from the last four weeks and weekly volume.
 * Phone-only.
 */
public final class GoalEventActivity extends AppCompatActivity {

    private static final Locale NL = new Locale("nl");
    private final DateTimeFormatter longDate = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", NL);
    private final DateTimeFormatter shortDate = DateTimeFormatter.ofPattern("d MMM", NL);

    private GoalEventViewModel viewModel;
    private GoalEvent current;

    public static Intent intentFor(Context context) {
        return new Intent(context, GoalEventActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_goal_event);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        Button edit = findViewById(R.id.editButton);
        Button delete = findViewById(R.id.deleteButton);
        edit.setOnClickListener(v -> showEditDialog());
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Doelevenement verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.delete())
                .setNegativeButton("Annuleren", null)
                .show());

        viewModel = new ViewModelProvider(this).get(GoalEventViewModel.class);
        viewModel.message().observe(this, m -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        viewModel.state().observe(this, st -> {
            current = st.event;
            boolean has = st.event != null;
            findViewById(R.id.noEvent).setVisibility(has ? View.GONE : View.VISIBLE);
            findViewById(R.id.eventPanel).setVisibility(has ? View.VISIBLE : View.GONE);
            delete.setVisibility(has ? View.VISIBLE : View.GONE);
            edit.setText(has ? "Wijzigen" : "Doelevenement instellen");
            if (has) render(st.event, st.progress);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rides synced meanwhile change the progress.
        viewModel.load();
    }

    private void render(GoalEvent e, GoalEventProgress.Result p) {
        ((TextView) findViewById(R.id.eventName)).setText(
                e.name != null && !e.name.isEmpty() ? e.name : "Doelevenement");
        String day;
        try {
            day = longDate.format(LocalDate.parse(e.date));
        } catch (DateTimeParseException | NullPointerException ex) {
            day = "onbekende datum";
        }
        ((TextView) findViewById(R.id.eventDetails)).setText(String.format(NL,
                "%s  •  %d km  •  %d hm", day, e.distanceKm, e.elevationM));

        ((TextView) findViewById(R.id.countdown)).setText(countdown(p.daysLeft));
        ((TextView) findViewById(R.id.phase)).setText(phaseLabel(p.phase));

        int days = GoalEventProgress.READINESS_WINDOW_DAYS;
        ((TextView) findViewById(R.id.distanceText)).setText(String.format(NL,
                "Langste rit (laatste %d dagen): %.0f van %d km", days, p.longestRideKm,
                e.distanceKm));
        ((LinearProgressIndicator) findViewById(R.id.distanceBar))
                .setProgress((int) Math.round(p.distanceReadiness * 100));
        ((TextView) findViewById(R.id.elevationText)).setText(String.format(NL,
                "Meeste hoogtemeters in één rit (laatste %d dagen): %.0f van %d hm", days,
                p.mostElevationM, e.elevationM));
        ((LinearProgressIndicator) findViewById(R.id.elevationBar))
                .setProgress((int) Math.round(p.elevationReadiness * 100));
        ((TextView) findViewById(R.id.advice)).setText(p.advice);

        StringBuilder weeks = new StringBuilder();
        for (int i = 0; i < p.weeks.size(); i++) {
            GoalEventProgress.Week w = p.weeks.get(i);
            if (i > 0) weeks.append(System.lineSeparator());
            weeks.append(String.format(NL, "%-12s %5.0f km %6.0f hm  %d rit(ten)",
                    i == 0 ? "Deze week" : "wk " + shortDate.format(w.weekStart),
                    w.km, w.elevationM, w.rides));
        }
        ((TextView) findViewById(R.id.weeks)).setText(weeks.toString());
    }

    private static String countdown(long daysLeft) {
        if (daysLeft < 0) return "Voorbij";
        if (daysLeft == 0) return "Vandaag!";
        if (daysLeft == 1) return "Morgen";
        if (daysLeft < 7 * 3) return "Nog " + daysLeft + " dagen";
        return String.format(NL, "Nog %d dagen (%d weken)", daysLeft, daysLeft / 7);
    }

    private static String phaseLabel(GoalEventProgress.Phase phase) {
        switch (phase) {
            case BASE: return "Fase: basis. Duurvermogen opbouwen.";
            case BUILD: return "Fase: opbouw. Langere ritten en meer hoogtemeters.";
            case TAPER: return "Fase: afbouwen. Fris aan de start komen.";
            case EVENT_DAY: return "Het is vandaag.";
            default: return "Dit evenement ligt achter je.";
        }
    }

    private void showEditDialog() {
        GoalEvent e = current != null ? current : new GoalEvent();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText name = field(form, "Naam (bijv. La Marmotte)", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, e.name);
        EditText km = field(form, "Afstand (km)", InputType.TYPE_CLASS_NUMBER,
                e.distanceKm > 0 ? String.valueOf(e.distanceKm) : "");
        EditText hm = field(form, "Hoogtemeters", InputType.TYPE_CLASS_NUMBER,
                e.elevationM > 0 ? String.valueOf(e.elevationM) : "");
        Button date = new Button(this);
        final LocalDate[] picked = {parseOr(e.date, LocalDate.now().plusWeeks(12))};
        date.setText("Datum: " + longDate.format(picked[0]));
        date.setOnClickListener(v -> new DatePickerDialog(this, (dp, y, m, d) -> {
            picked[0] = LocalDate.of(y, m + 1, d);
            date.setText("Datum: " + longDate.format(picked[0]));
        }, picked[0].getYear(), picked[0].getMonthValue() - 1, picked[0].getDayOfMonth()).show());
        form.addView(date);

        new AlertDialog.Builder(this)
                .setTitle("Doelevenement")
                .setView(form)
                .setPositiveButton("Opslaan", (d, w) -> {
                    int distance = parseInt(km.getText().toString());
                    int elevation = parseInt(hm.getText().toString());
                    if (distance <= 0) {
                        Toast.makeText(this, "Vul een afstand in", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    GoalEvent out = new GoalEvent();
                    out.name = name.getText().toString().trim();
                    out.date = picked[0].toString();
                    out.distanceKm = Math.min(distance, 2000);
                    out.elevationM = Math.max(0, Math.min(elevation, 50_000));
                    viewModel.save(out);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private EditText field(LinearLayout form, String hint, int inputType, String value) {
        EditText t = new EditText(this);
        t.setHint(hint);
        t.setInputType(inputType);
        if (value != null) t.setText(value);
        form.addView(t);
        return t;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LocalDate parseOr(String iso, LocalDate fallback) {
        try {
            return iso != null ? LocalDate.parse(iso) : fallback;
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
