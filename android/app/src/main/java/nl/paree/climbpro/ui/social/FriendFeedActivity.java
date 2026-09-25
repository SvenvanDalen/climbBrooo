package nl.paree.climbpro.ui.social;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.social.FriendFeedEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Vriendenfeed" (issue #240): friends' rides and first ascents, imported from share codes,
 * newest first. Also the target of "share to ClimbPro" for text, so a friend's chat message
 * can be imported without copy-paste. No server; phone-only.
 */
public final class FriendFeedActivity extends AppCompatActivity {

    /** Rows rendered at most; the store itself keeps up to 100 per friend. */
    private static final int MAX_ROWS = 200;
    private static final Locale NL = new Locale("nl");

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE d MMM yyyy", NL);
    private FriendFeedViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, FriendFeedActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friend_feed);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        LinearLayout feed = findViewById(R.id.feed);

        viewModel = new ViewModelProvider(this).get(FriendFeedViewModel.class);
        viewModel.entries().observe(this, list -> {
            feed.removeAllViews();
            boolean none = list == null || list.isEmpty();
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            if (!none) render(feed, list);
        });
        viewModel.message().observe(this, msg -> {
            if (msg == null) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            viewModel.consumeMessage();
        });
        viewModel.shareText().observe(this, text -> {
            if (text == null) return;
            viewModel.consumeShareText();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "Deel je ritten"));
        });

        findViewById(R.id.shareButton).setOnClickListener(v -> showShareDialog());
        findViewById(R.id.importButton).setOnClickListener(v -> showImportDialog());

        // Only on first creation: a rotation must not import the shared text a second time
        // (harmless thanks to de-duplication, but it would toast "Geen nieuwe items").
        if (savedInstanceState == null) handleIncomingShare(getIntent());
        viewModel.load();
    }

    private void handleIncomingShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        viewModel.importCode(text == null ? null : text.toString());
    }

    private void showShareDialog() {
        EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setHint("Je naam voor vrienden");
        name.setText(viewModel.shareName());
        new AlertDialog.Builder(this)
                .setTitle("Deel mijn ritten")
                .setMessage("De deelcode bevat je naam, en van de afgelopen 30 dagen de naam, "
                        + "datum, afstand, hoogtemeters en rijtijd van je ritten plus je eerste "
                        + "beklimmingen (geen thuisklimmen). Geen locaties of Strava-links.")
                .setView(name)
                .setPositiveButton("Delen", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "Vul eerst een naam in.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.share(n);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void showImportDialog() {
        EditText input = new EditText(this);
        input.setHint("Plak hier de deelcode");
        input.setMinLines(3);
        String clip = clipboardText();
        if (clip != null && clip.contains("CPF")) input.setText(clip);
        new AlertDialog.Builder(this)
                .setTitle("Code van een vriend")
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

    private void render(LinearLayout feed, List<FriendFeedEntry> list) {
        int n = Math.min(MAX_ROWS, list.size());
        for (int i = 0; i < n; i++) {
            FriendFeedEntry e = list.get(i);
            View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, feed, false);
            ((TextView) v.findViewById(R.id.title)).setText(
                    e.friendName + " · " + (e.isRide() ? "Rit" : "Mijlpaal"));
            ((TextView) v.findViewById(R.id.value)).setText(e.title);
            ((TextView) v.findViewById(R.id.detail)).setText(
                    dateFormat.format(new Date(e.epochSec * 1000L)) + "  •  " + stats(e));
            v.setOnLongClickListener(x -> {
                confirmRemove(e);
                return true;
            });
            feed.addView(v);
        }
    }

    private static String stats(FriendFeedEntry e) {
        if (!e.isRide()) return e.gainM > 0 ? e.gainM + " hm" : "Eerste keer boven";
        StringBuilder sb = new StringBuilder(
                String.format(NL, "%.1f km", e.distanceM / 1000f));
        if (e.gainM > 0) sb.append(" · ").append(e.gainM).append(" hm");
        if (e.movingSec > 0) {
            sb.append(String.format(NL, " · %d:%02d u", e.movingSec / 3600, (e.movingSec % 3600) / 60));
        }
        return sb.toString();
    }

    private void confirmRemove(FriendFeedEntry e) {
        new AlertDialog.Builder(this)
                .setTitle("Vriend verwijderen")
                .setMessage("Alle ritten en mijlpalen van " + e.friendName
                        + " uit je feed verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.removeFriend(e.friendId))
                .setNegativeButton("Annuleren", null)
                .show();
    }
}
