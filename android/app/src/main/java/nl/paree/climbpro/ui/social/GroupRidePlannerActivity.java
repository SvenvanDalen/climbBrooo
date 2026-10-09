package nl.paree.climbpro.ui.social;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.social.GroupRideParticipant;
import nl.paree.climbpro.domain.social.GroupRidePlanner;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Groepsrit plannen" (issue #195): pick a saved route and the riders, get the expected group
 * pace and duration plus date proposals, and share the whole proposal as text. Riders come from
 * the imported ride-buddy profile codes (#242), your own profile and manual entries. No server:
 * the proposal travels through any chat app.
 */
public final class GroupRidePlannerActivity extends AppCompatActivity {

    private static final Locale NL = new Locale("nl");

    private GroupRidePlannerViewModel viewModel;
    private List<GroupRidePlannerViewModel.RouteChoice> routes;
    private GroupRidePlannerViewModel.Plan current;

    public static Intent intentFor(Context context) {
        return new Intent(context, GroupRidePlannerActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_ride_planner);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView routeInfo = findViewById(R.id.routeInfo);
        LinearLayout participantList = findViewById(R.id.participantList);
        Button dateButton = findViewById(R.id.dateButton);
        TextView result = findViewById(R.id.result);
        Button shareButton = findViewById(R.id.shareButton);

        viewModel = new ViewModelProvider(this).get(GroupRidePlannerViewModel.class);
        viewModel.routes().observe(this, r -> routes = r);
        viewModel.plan().observe(this, p -> {
            current = p;
            if (p == null) return;
            routeInfo.setText(p.routeName == null ? "Nog geen route gekozen."
                    : p.routeName + " · " + String.format(NL, "%.1f km", p.distanceM / 1000.0)
                    + " · " + p.ascentM + " hm");
            renderParticipants(participantList, p);
            dateButton.setText(getString(R.string.group_ride_from_date,
                    GroupRidePlanner.formatDate(p.firstDay)));
            result.setText(summary(p));
            shareButton.setEnabled(p.shareText != null);
        });
        viewModel.message().observe(this, msg -> {
            if (msg == null) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            viewModel.consumeMessage();
        });

        findViewById(R.id.routeButton).setOnClickListener(v -> pickRoute());
        findViewById(R.id.participantsButton).setOnClickListener(v -> pickParticipants());
        findViewById(R.id.addRiderButton).setOnClickListener(v -> addManualRider());
        dateButton.setOnClickListener(v -> pickDate());
        shareButton.setOnClickListener(v -> share());

        viewModel.load();
    }

    private void pickRoute() {
        List<GroupRidePlannerViewModel.RouteChoice> r = routes;
        if (r == null || r.isEmpty()) {
            Toast.makeText(this, "Nog geen routes. Importeer eerst een route.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[r.size()];
        for (int i = 0; i < r.size(); i++) names[i] = r.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_ride_pick_route)
                .setItems(names, (d, which) -> viewModel.selectRoute(r.get(which).routeId))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void pickParticipants() {
        List<GroupRidePlannerViewModel.Candidate> cands = viewModel.candidates();
        if (cands.isEmpty()) return;
        Set<String> selected = new LinkedHashSet<>(viewModel.selectedIds());
        String[] labels = new String[cands.size()];
        boolean[] checked = new boolean[cands.size()];
        for (int i = 0; i < cands.size(); i++) {
            labels[i] = cands.get(i).label;
            checked[i] = selected.contains(cands.get(i).id);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.group_ride_participants)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    if (isChecked) selected.add(cands.get(which).id);
                    else selected.remove(cands.get(which).id);
                })
                .setPositiveButton("OK", (d, w) -> viewModel.setSelected(selected))
                .setNegativeButton("Annuleren", null);
        if (cands.size() == 1) {
            Toast.makeText(this, "Importeer profielcodes onder Ritmaatjes om andere rijders "
                    + "te kiezen, of voeg ze met de hand toe.", Toast.LENGTH_LONG).show();
        }
        b.show();
    }

    private void addManualRider() {
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setHint("Naam");
        box.addView(name);
        EditText speed = new EditText(this);
        speed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speed.setHint("Gemiddelde snelheid (km/u), bijv. 27");
        box.addView(speed);
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_ride_add_rider)
                .setView(box)
                .setPositiveButton("Toevoegen", (d, w) -> {
                    String n = name.getText().toString().trim();
                    double kmh = parseKmh(speed.getText().toString());
                    if (n.isEmpty()) {
                        Toast.makeText(this, "Vul een naam in.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (kmh < 5 || kmh > 60) {
                        Toast.makeText(this, "Vul een snelheid tussen 5 en 60 km/u in.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.addManual(n, kmh);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private static double parseKmh(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void pickDate() {
        LocalDate d = current != null ? current.firstDay : LocalDate.now().plusDays(1);
        DatePickerDialog dlg = new DatePickerDialog(this,
                (view, y, m, day) -> viewModel.setFirstDay(LocalDate.of(y, m + 1, day)),
                d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
        dlg.getDatePicker().setMinDate(System.currentTimeMillis() - 1_000);
        dlg.show();
    }

    private void renderParticipants(LinearLayout list, GroupRidePlannerViewModel.Plan p) {
        list.removeAllViews();
        if (p.participants.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Nog geen deelnemers gekozen.");
            list.addView(t);
            return;
        }
        for (int i = 0; i < p.participants.size(); i++) {
            GroupRideParticipant r = p.participants.get(i);
            TextView t = new TextView(this);
            t.setPadding(0, 4, 0, 4);
            StringBuilder sb = new StringBuilder("• ").append(r.name).append(" — ");
            sb.append(r.flatSpeedDkmh > 0
                    ? GroupRidePlanner.kmh(r.flatSpeedDkmh / 10.0) + " km/u" : "tempo onbekend");
            if (r.vamMph > 0) sb.append(" · ").append(r.vamMph).append(" m/u klimmen");
            int manualIndex = p.manualIndexOf(i);
            if (manualIndex >= 0) {
                sb.append("  (tik om te verwijderen)");
                t.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setMessage(r.name + " uit de planning halen?")
                        .setPositiveButton("Verwijderen", (d, w) -> viewModel.removeManual(manualIndex))
                        .setNegativeButton("Annuleren", null)
                        .show());
            }
            t.setText(sb.toString());
            list.addView(t);
        }
    }

    private String summary(GroupRidePlannerViewModel.Plan p) {
        if (p.routeName == null) return "Kies een route om het groepstempo te berekenen.";
        GroupRidePlanner.Estimate e = p.estimate;
        StringBuilder sb = new StringBuilder();
        sb.append("Verwacht groepstempo: ").append(GroupRidePlanner.kmh(e.avgSpeedKmh))
          .append(" km/u gemiddeld (").append(GroupRidePlanner.kmh(e.groupFlatDkmh / 10.0))
          .append(" km/u op vlak)\n");
        sb.append("Rijtijd: ").append(GroupRidePlanner.hm(e.movingSec));
        if (e.pauseSec > 0) sb.append(" — met pauzes ca. ").append(GroupRidePlanner.hm(e.totalSec()));
        sb.append('\n');
        if (!e.slowestName.isEmpty() && p.participants.size() > 1) {
            sb.append("Tempo bepaald door ").append(e.slowestName)
              .append("; op klimmen wacht de groep boven op elkaar.\n");
        }
        if (e.bigSpread) {
            sb.append("Let op: groot niveauverschil — de snelste rijder zou alleen ")
              .append(GroupRidePlanner.hm(e.fastestSoloSec)).append(" nodig hebben.\n");
        }
        if (e.unknownSpeedCount > 0) {
            sb.append(e.unknownSpeedCount).append(" rijder(s) zonder bekend tempo: gerekend met ")
              .append(GroupRidePlanner.kmh(GroupRidePlanner.DEFAULT_FLAT_DKMH / 10.0))
              .append(" km/u.\n");
        }
        if (!p.dates.isEmpty()) {
            sb.append("\nDatumvoorstellen:\n");
            for (GroupRidePlanner.DateOption o : p.dates) {
                sb.append("• ").append(GroupRidePlanner.formatDate(o.date)).append(", ")
                  .append(GroupRidePlanner.daypartName(o.daypart));
                if (o.total > 0) {
                    sb.append(" — ").append(o.available).append('/').append(o.total)
                      .append(" kunnen");
                    if (!o.unavailable.isEmpty()) {
                        sb.append(" (niet: ").append(String.join(", ", o.unavailable)).append(')');
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private void share() {
        GroupRidePlannerViewModel.Plan p = current;
        if (p == null || p.shareText == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, p.shareText);
        startActivity(Intent.createChooser(send, getString(R.string.group_ride_share)));
    }
}
