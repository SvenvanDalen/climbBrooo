package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.databinding.ActivityClimbHygieneBinding;
import nl.paree.climbpro.domain.climb.NearDuplicateClimbFinder;

import java.util.List;
import java.util.Locale;

/**
 * Lists near-duplicate stored climbs found across the whole catalog (issue #76) and lets the
 * user confirm, pair by pair, which one to keep. Never merges automatically — a merge deletes
 * a {@code StoredClimb}, so every merge requires an explicit confirmation dialog.
 */
public final class ClimbHygieneActivity extends AppCompatActivity {

    private ActivityClimbHygieneBinding binding;
    private ClimbHygieneViewModel       viewModel;

    public static Intent intentFor(Context ctx) {
        return new Intent(ctx, ClimbHygieneActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityClimbHygieneBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        viewModel = new ViewModelProvider(this).get(ClimbHygieneViewModel.class);

        viewModel.candidates().observe(this, this::renderCandidates);
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        viewModel.mergeSuccess().observe(this, event -> {
            String msg = event != null ? event.consume() : null;
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        viewModel.scan();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void renderCandidates(List<NearDuplicateClimbFinder.Candidate> candidates) {
        binding.candidateRows.removeAllViews();
        if (candidates == null || candidates.isEmpty()) {
            binding.hygieneSummary.setText("Geen bijna-identieke klimmen gevonden.");
            return;
        }
        binding.hygieneSummary.setText(String.format(Locale.getDefault(),
                "%d mogelijk dubbele klim-paar(en) gevonden.", candidates.size()));

        float density = getResources().getDisplayMetrics().density;
        int cardPad = (int) (14 * density);
        int cardMarginBottom = (int) (10 * density);
        int labelColor = ContextCompat.getColor(this, R.color.color_text_tertiary);

        for (NearDuplicateClimbFinder.Candidate c : candidates) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(cardPad, cardPad, cardPad, cardPad);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = cardMarginBottom;
            card.setLayoutParams(cardParams);

            TextView title = new TextView(this);
            title.setText(climbLabel(c.a) + "  ↔  " + climbLabel(c.b));
            title.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary));
            title.setTextSize(14f);
            card.addView(title);

            TextView details = new TextView(this);
            details.setText(String.format(Locale.getDefault(),
                    "%.0f m uit elkaar · lengteverschil %d m · gradiëntverschil %.1f%%",
                    c.distanceM, c.lengthDiffM, c.gradientDiffAbs * 100));
            details.setTextColor(labelColor);
            details.setTextSize(12f);
            details.setPadding(0, (int) (4 * density), 0, (int) (8 * density));
            card.addView(details);

            LinearLayout buttonRow = new LinearLayout(this);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);

            Button keepA = new Button(this);
            keepA.setText("Houd: " + climbLabel(c.a));
            keepA.setOnClickListener(v -> confirmMerge(c.a, c.b));
            LinearLayout.LayoutParams keepAParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            keepAParams.setMarginEnd((int) (6 * density));
            buttonRow.addView(keepA, keepAParams);

            Button keepB = new Button(this);
            keepB.setText("Houd: " + climbLabel(c.b));
            keepB.setOnClickListener(v -> confirmMerge(c.b, c.a));
            LinearLayout.LayoutParams keepBParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            buttonRow.addView(keepB, keepBParams);

            card.addView(buttonRow);
            binding.candidateRows.addView(card);
        }
    }

    private void confirmMerge(NearDuplicateClimbFinder.ClimbRef keep,
                              NearDuplicateClimbFinder.ClimbRef remove) {
        new AlertDialog.Builder(this)
                .setTitle("Klimmen samenvoegen?")
                .setMessage("\"" + climbLabel(remove) + "\" wordt verwijderd en zijn geschiedenis "
                        + "wordt overgezet naar \"" + climbLabel(keep) + "\". Dit kan niet ongedaan "
                        + "worden gemaakt.")
                .setPositiveButton("Samenvoegen", (d, w) -> viewModel.merge(keep, remove))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private static String climbLabel(NearDuplicateClimbFinder.ClimbRef ref) {
        String climbName = ref.climb.userDisplayName != null ? ref.climb.userDisplayName
                : ref.climb.name != null ? ref.climb.name
                : "Klim " + (ref.climbIndex + 1);
        return climbName + " (" + ref.routeDisplayName + ")";
    }
}
