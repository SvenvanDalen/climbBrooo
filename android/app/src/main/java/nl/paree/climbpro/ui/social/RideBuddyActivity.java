package nl.paree.climbpro.ui.social;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.social.RideBuddyCode;
import nl.paree.climbpro.domain.social.RideBuddyMatcher;
import nl.paree.climbpro.domain.social.RideBuddyProfile;
import nl.paree.climbpro.domain.social.RideBuddyProfileBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Ritmaatjes" (issue #242): share your rider profile as a code, import other riders' codes and
 * see them ranked by how well you'd ride together. Backend-free like the friends' feed: codes
 * travel through any chat app, matching runs on this phone. Also the target of "share to
 * ClimbPro" for text, always behind a confirmation dialog.
 */
public final class RideBuddyActivity extends AppCompatActivity {

    private static final int PREFILL_MAX_CHARS = RideBuddyCode.MAX_CODE_CHARS + 500;
    private static final Locale NL = new Locale("nl");

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy", NL);
    private RideBuddyViewModel viewModel;
    private RideBuddyProfile own;

    public static Intent intentFor(Context context) {
        return new Intent(context, RideBuddyActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_buddies);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView ownView = findViewById(R.id.ownProfile);
        TextView empty = findViewById(R.id.empty);
        LinearLayout list = findViewById(R.id.list);

        viewModel = new ViewModelProvider(this).get(RideBuddyViewModel.class);
        viewModel.own().observe(this, p -> {
            own = p;
            ownView.setText(ownSummary(p));
        });
        viewModel.matches().observe(this, matches -> {
            list.removeAllViews();
            boolean none = matches == null || matches.isEmpty();
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            if (!none) render(list, matches);
        });
        viewModel.message().observe(this, msg -> {
            if (msg == null) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            viewModel.consumeMessage();
        });
        viewModel.preview().observe(this, preview -> {
            if (preview == null) return;
            viewModel.consumePreview();
            showPreview(preview);
        });
        viewModel.shareText().observe(this, text -> {
            if (text == null) return;
            viewModel.consumeShareText();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, getString(R.string.ride_buddies_share)));
        });

        findViewById(R.id.shareButton).setOnClickListener(v -> showShareDialog());
        findViewById(R.id.importButton).setOnClickListener(v -> showImportDialog(null));

        if (savedInstanceState == null) handleIncomingShare(getIntent());
        viewModel.load();
    }

    private void handleIncomingShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        String s = text.toString();
        showImportDialog(s.length() > PREFILL_MAX_CHARS ? s.substring(0, PREFILL_MAX_CHARS) : s);
    }

    private String ownSummary(RideBuddyProfile p) {
        if (p == null) return "";
        if (p.rideCount < RideBuddyProfileBuilder.MIN_RIDES) {
            return "Te weinig ritten voor een profiel (" + p.rideCount + " in het afgelopen "
                    + "halfjaar, minstens " + RideBuddyProfileBuilder.MIN_RIDES + " nodig). "
                    + "Haal eerst je ritten op via Ritten.";
        }
        StringBuilder sb = new StringBuilder("Jouw profiel: ");
        sb.append(RideBuddyCode.kmh(p.flatSpeedDkmh)).append(" km/u op vlak");
        if (p.vamMph > 0) sb.append(" · ").append(p.vamMph).append(" m/u klimmen");
        sb.append(" · ~").append(p.typicalDistanceKm).append(" km · ")
          .append(RideBuddyCode.typeNames(p.rideTypes));
        return sb.toString();
    }

    private void showShareDialog() {
        RideBuddyProfile me = own;
        if (me == null) return;
        if (me.rideCount < RideBuddyProfileBuilder.MIN_RIDES) {
            Toast.makeText(this, ownSummary(me), Toast.LENGTH_LONG).show();
            return;
        }
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setHint("Je naam voor andere rijders");
        name.setText(viewModel.shareName());
        box.addView(name);

        TextView intro = new TextView(this);
        intro.setText("Kies wat je deelt. Je ziet hierna precies wat er in de code staat.");
        intro.setPadding(0, pad / 2, 0, pad / 4);
        box.addView(intro);

        int available = me.availableFields();
        List<CheckBox> boxes = new ArrayList<>();
        List<Integer> bits = new ArrayList<>();
        addOption(box, boxes, bits, available, RideBuddyProfile.FIELD_PACE, true,
                "Tempo op vlak terrein (" + RideBuddyCode.kmh(me.flatSpeedDkmh) + " km/u)");
        addOption(box, boxes, bits, available, RideBuddyProfile.FIELD_CLIMB, true,
                "Klimsnelheid (" + me.vamMph + " m/u)");
        addOption(box, boxes, bits, available, RideBuddyProfile.FIELD_DISTANCE, true,
                "Gebruikelijke ritlengte (" + me.typicalDistanceKm + " km)");
        addOption(box, boxes, bits, available, RideBuddyProfile.FIELD_TYPE, true,
                "Rittype (" + RideBuddyCode.typeNames(me.rideTypes) + ")");
        addOption(box, boxes, bits, available, RideBuddyProfile.FIELD_SCHEDULE, true,
                "Rijdagen en dagdelen");
        // Opt-in only: the one field that says something about where you live.
        addOption(box, boxes, bits, available, RideBuddyProfile.FIELD_AREA, false,
                "Grof gebied (vak van ~5 km, nooit je exacte adres)");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ride_buddies_share)
                .setView(scroll)
                .setPositiveButton("Verder", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "Vul eerst een naam in.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int fields = 0;
                    for (int i = 0; i < boxes.size(); i++) {
                        if (boxes.get(i).isChecked()) fields |= bits.get(i);
                    }
                    viewModel.prepareShare(n, fields);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void addOption(LinearLayout box, List<CheckBox> boxes, List<Integer> bits,
                           int available, int bit, boolean checked, String label) {
        if ((available & bit) == 0) return;
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(checked);
        box.addView(cb);
        boxes.add(cb);
        bits.add(bit);
    }

    private void showPreview(RideBuddyViewModel.Preview preview) {
        StringBuilder sb = new StringBuilder("Dit staat in je profielcode:\n\n");
        for (String line : preview.lines) sb.append("• ").append(line).append('\n');
        sb.append("\nPlus een willekeurig id, zodat een nieuwe code je oude vervangt. "
                + "Geen routes, ritten, Strava-gegevens of exacte locaties.");
        new AlertDialog.Builder(this)
                .setTitle("Controleer je code")
                .setMessage(sb.toString())
                .setPositiveButton("Delen", (d, w) -> viewModel.confirmShare(preview.code))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void showImportDialog(String prefill) {
        EditText input = new EditText(this);
        input.setHint("Plak hier de profielcode");
        input.setMinLines(3);
        String text = prefill;
        if (text == null) {
            String clip = clipboardText();
            if (clip != null && clip.contains("CPR")) text = clip;
        }
        if (text != null) input.setText(text);
        new AlertDialog.Builder(this)
                .setTitle("Profielcode van een rijder")
                .setView(input)
                .setPositiveButton("Importeren",
                        (d, w) -> viewModel.importCode(input.getText().toString()))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence t = clip.getItemAt(0).coerceToText(this);
            return t == null ? null : t.toString();
        } catch (RuntimeException e) {
            return null; // clipboard access can be denied; the user just pastes by hand
        }
    }

    private void render(LinearLayout list, List<RideBuddyMatcher.Match> matches) {
        for (RideBuddyMatcher.Match m : matches) {
            View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, list, false);
            ((TextView) v.findViewById(R.id.title)).setText(m.score + "% match");
            ((TextView) v.findViewById(R.id.value)).setText(m.buddy.name);
            String when = m.buddy.createdEpochSec > 0
                    ? "Profiel van " + dateFormat.format(new Date(m.buddy.createdEpochSec * 1000L))
                    : "Profiel";
            ((TextView) v.findViewById(R.id.detail)).setText(
                    m.explanation + "\n" + when + " · " + m.buddy.rideCount + " rit(ten)");
            v.setOnClickListener(x -> showDetails(m.buddy));
            v.setOnLongClickListener(x -> {
                confirmRemove(m.buddy);
                return true;
            });
            list.addView(v);
        }
    }

    private void showDetails(RideBuddyProfile p) {
        StringBuilder sb = new StringBuilder();
        for (String line : RideBuddyCode.describe(p)) sb.append("• ").append(line).append('\n');
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .setNegativeButton("Verwijderen", (d, w) -> confirmRemove(p))
                .show();
    }

    private void confirmRemove(RideBuddyProfile p) {
        new AlertDialog.Builder(this)
                .setTitle("Rijder verwijderen")
                .setMessage("Het profiel van " + p.name + " verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.remove(p.riderId))
                .setNegativeButton("Annuleren", null)
                .show();
    }
}
