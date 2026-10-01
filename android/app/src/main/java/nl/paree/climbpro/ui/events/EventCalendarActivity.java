package nl.paree.climbpro.ui.events;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.events.CyclingEvent;
import nl.paree.climbpro.data.events.EventCalendar;
import nl.paree.climbpro.domain.events.EventLevel;
import nl.paree.climbpro.domain.events.EventTextStats;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Event calendar (issue #241): tour rides and gran fondos near you, with distance, elevation,
 * date and whether they suit your level. Events come from iCal feeds the rider adds and from
 * manual entries — there is no central free source. Phone-only.
 */
public final class EventCalendarActivity extends AppCompatActivity {

    private static final Locale NL = new Locale("nl");
    private static final int[] RADII_KM = {25, 50, 75, 100, 150, 250};
    private final DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy", NL);

    private EventCalendarViewModel viewModel;
    private EventCalendarViewModel.State current;

    public static Intent intentFor(Context context) {
        return new Intent(context, EventCalendarActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_calendar);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(EventCalendarViewModel.class);
        viewModel.message().observe(this, m -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        viewModel.state().observe(this, this::render);

        findViewById(R.id.btn_refresh).setOnClickListener(v -> viewModel.refresh());
        findViewById(R.id.btn_radius).setOnClickListener(v -> pickRadius());
        findViewById(R.id.btn_feeds).setOnClickListener(v -> showFeeds());
        findViewById(R.id.btn_add_event).setOnClickListener(v -> showAddEvent());

        viewModel.load();
    }

    private void render(EventCalendarViewModel.State st) {
        current = st;
        ((Button) findViewById(R.id.btn_radius)).setText("Straal " + st.radiusKm + " km");
        findViewById(R.id.progress).setVisibility(st.loading ? View.VISIBLE : View.GONE);

        StringBuilder sum = new StringBuilder();
        sum.append(st.feeds.size()).append(st.feeds.size() == 1 ? " agenda" : " agenda's");
        if (st.lastFetchMs > 0) {
            sum.append(", bijgewerkt ").append(android.text.format.DateUtils
                    .getRelativeTimeSpanString(st.lastFetchMs));
        }
        if (!st.hasLocation) {
            sum.append(". Geen bekende locatie: alle evenementen worden getoond.");
        }
        if (st.capacity.known()) {
            sum.append(String.format(NL, "%nJouw niveau (laatste %d dagen): langste rit %.0f km, "
                    + "meeste klimwerk %.0f hm.", EventLevel.WINDOW_DAYS,
                    st.capacity.longestKm, st.capacity.mostElevationM));
        } else {
            sum.append("\nSynchroniseer ritten met Strava om te zien welke evenementen bij je "
                    + "niveau passen.");
        }
        ((TextView) findViewById(R.id.summary)).setText(sum.toString());

        LinearLayout list = findViewById(R.id.list);
        list.removeAllViews();
        findViewById(R.id.empty).setVisibility(st.rows.isEmpty() && !st.loading
                ? View.VISIBLE : View.GONE);
        int pad = dp(12);
        for (EventCalendarViewModel.Row row : st.rows) {
            TextView card = new TextView(this);
            card.setPadding(pad, pad, pad, pad);
            card.setBackgroundResource(android.R.drawable.list_selector_background);
            card.setText(cardText(row));
            card.setOnClickListener(v -> showDetails(row));
            list.addView(card);
            View divider = new View(this);
            divider.setBackgroundColor(0x22888888);
            list.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private String cardText(EventCalendarViewModel.Row row) {
        CyclingEvent e = row.event;
        StringBuilder sb = new StringBuilder();
        sb.append(e.name).append('\n').append(day(e.date));
        if (e.location != null) sb.append(" · ").append(e.location);
        if (row.distanceKm >= 0) sb.append(String.format(NL, " (%.0f km)", row.distanceKm));
        sb.append('\n').append(stats(e));
        String fit = fitLabel(row.level);
        if (fit != null) sb.append('\n').append(fit);
        return sb.toString();
    }

    private static String stats(CyclingEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.distancesKm != null && !e.distancesKm.isEmpty()) {
            for (int i = 0; i < e.distancesKm.size(); i++) {
                if (i > 0) sb.append('/');
                sb.append(e.distancesKm.get(i));
            }
            sb.append(" km");
        } else {
            sb.append("afstand onbekend");
        }
        if (e.elevationM != null) sb.append(" · ").append(e.elevationM).append(" hm");
        return sb.toString();
    }

    private static String fitLabel(EventLevel.Result r) {
        switch (r.fit) {
            case FITS: return "✓ Past bij je niveau (" + r.optionKm + " km)";
            case CHALLENGE: return "↗ Uitdaging (" + r.optionKm + " km)";
            case TOO_HARD: return "⚠ Flink boven je huidige niveau";
            default: return null;
        }
    }

    private String day(String iso) {
        try {
            return dayFormat.format(LocalDate.parse(iso));
        } catch (DateTimeParseException | NullPointerException e) {
            return "onbekende datum";
        }
    }

    private void showDetails(EventCalendarViewModel.Row row) {
        CyclingEvent e = row.event;
        StringBuilder msg = new StringBuilder(cardText(row).substring(e.name.length() + 1));
        if (e.description != null) msg.append("\n\n").append(e.description);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(msg.toString())
                .setPositiveButton("Als doelevenement", (d, w) -> viewModel.setAsGoal(e));
        if (e.url != null) {
            b.setNeutralButton("Website", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.url)));
                } catch (android.content.ActivityNotFoundException ex) {
                    Toast.makeText(this, "Geen app om de link te openen", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (e.feedUrl == null) {
            b.setNegativeButton("Verwijderen", (d, w) -> viewModel.removeManual(e.uid));
        }
        b.show();
    }

    private void pickRadius() {
        String[] labels = new String[RADII_KM.length];
        int checked = -1;
        for (int i = 0; i < RADII_KM.length; i++) {
            labels[i] = RADII_KM[i] + " km";
            if (current != null && current.radiusKm == RADII_KM[i]) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Zoekstraal")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    viewModel.setRadius(RADII_KM[which]);
                    d.dismiss();
                })
                .show();
    }

    private void showFeeds() {
        List<EventCalendar.Feed> feeds = current != null ? current.feeds : java.util.Collections.emptyList();
        String[] items = new String[feeds.size()];
        for (int i = 0; i < feeds.size(); i++) {
            EventCalendar.Feed f = feeds.get(i);
            items[i] = f.name + (f.lastError != null ? "  (fout: " + f.lastError + ")" : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Agenda's (iCal)")
                .setPositiveButton("Toevoegen", (d, w) -> showAddFeed())
                .setNegativeButton("Sluiten", null);
        if (items.length == 0) {
            b.setMessage("Nog geen agenda's. Veel organisatoren, clubs en fietsbonden bieden "
                    + "hun kalender als iCal-link (.ics of webcal://) aan.");
        } else {
            b.setItems(items, (d, which) -> confirmRemoveFeed(feeds.get(which)));
        }
        b.show();
    }

    private void confirmRemoveFeed(EventCalendar.Feed f) {
        new AlertDialog.Builder(this)
                .setMessage("Agenda \"" + f.name + "\" verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.removeFeed(f.url))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void showAddFeed() {
        LinearLayout box = form();
        EditText url = field(box, "iCal-link (https://… of webcal://…)", InputType.TYPE_TEXT_VARIATION_URI);
        EditText name = field(box, "Naam (optioneel)", InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this)
                .setTitle("Agenda toevoegen")
                .setView(box)
                .setPositiveButton("Toevoegen", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.matches("(?i)(https?|webcal)://\\S+")) {
                        Toast.makeText(this, "Vul een https- of webcal-link in",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.addFeed(u, name.getText().toString());
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void showAddEvent() {
        LinearLayout box = form();
        EditText name = field(box, "Naam", InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText place = field(box, "Plaats", InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText distances = field(box, "Afstanden in km (bijv. 60/100/150)", InputType.TYPE_CLASS_TEXT);
        EditText elevation = field(box, "Hoogtemeters (langste afstand)", InputType.TYPE_CLASS_NUMBER);
        EditText link = field(box, "Website (optioneel)", InputType.TYPE_TEXT_VARIATION_URI);
        final LocalDate[] date = {null};
        Button pick = new Button(this);
        pick.setText("Datum kiezen");
        pick.setOnClickListener(v -> {
            LocalDate init = date[0] != null ? date[0] : LocalDate.now().plusWeeks(4);
            new DatePickerDialog(this, (dp, y, m, dd) -> {
                date[0] = LocalDate.of(y, m + 1, dd);
                pick.setText(day(date[0].toString()));
            }, init.getYear(), init.getMonthValue() - 1, init.getDayOfMonth()).show();
        });
        box.addView(pick);
        new AlertDialog.Builder(this)
                .setTitle("Evenement toevoegen")
                .setView(box)
                .setPositiveButton("Opslaan", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty() || date[0] == null) {
                        Toast.makeText(this, "Naam en datum zijn nodig", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CyclingEvent e = new CyclingEvent();
                    e.name = n;
                    e.date = date[0].toString();
                    String p = place.getText().toString().trim();
                    e.location = p.isEmpty() ? null : p;
                    e.distancesKm = EventTextStats.distancesKm(
                            distances.getText().toString().trim() + " km");
                    try {
                        int hm = Integer.parseInt(elevation.getText().toString().trim());
                        e.elevationM = hm > 0 ? hm : null;
                    } catch (NumberFormatException ex) {
                        e.elevationM = null;
                    }
                    String u = link.getText().toString().trim();
                    e.url = u.isEmpty() ? null : u;
                    viewModel.addManual(e);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        return box;
    }

    private EditText field(LinearLayout box, String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | inputType);
        if (inputType == InputType.TYPE_CLASS_NUMBER) e.setInputType(inputType);
        box.addView(e);
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
