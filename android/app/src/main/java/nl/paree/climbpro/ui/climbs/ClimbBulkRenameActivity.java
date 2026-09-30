package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.databinding.ActivityClimbBulkRenameBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the user rename every climb of a route in one screen, instead of opening each climb's
 * detail screen individually (backlog #108).
 */
public final class ClimbBulkRenameActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";

    private ActivityClimbBulkRenameBinding binding;
    private ClimbBulkRenameViewModel       viewModel;
    private String                         routeId;
    private final List<EditText>           nameInputs = new ArrayList<>();

    public static Intent intentFor(Context ctx, String routeId) {
        Intent i = new Intent(ctx, ClimbBulkRenameActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityClimbBulkRenameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        routeId   = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        viewModel = new ViewModelProvider(this).get(ClimbBulkRenameViewModel.class);

        viewModel.climbs().observe(this, this::renderRows);
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, ok -> {
            if (Boolean.TRUE.equals(ok)) {
                Toast.makeText(this, "Opgeslagen", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        binding.btnSaveAll.setOnClickListener(v -> saveAll());

        viewModel.loadClimbs(routeId);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void renderRows(List<StoredClimb> climbs) {
        binding.climbNameRows.removeAllViews();
        nameInputs.clear();
        if (climbs == null) return;

        float density = getResources().getDisplayMetrics().density;
        int cardPad = (int) (14 * density);
        int cardMarginBottom = (int) (10 * density);
        int labelColor = ContextCompat.getColor(this, R.color.color_text_tertiary);
        int cardBg = R.drawable.bg_card;
        int inputBg = R.drawable.bg_input;

        for (int i = 0; i < climbs.size(); i++) {
            StoredClimb climb = climbs.get(i);
            String autoName = climb.name != null ? climb.name : "Klim " + (i + 1);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(cardBg);
            card.setPadding(cardPad, cardPad, cardPad, cardPad);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = cardMarginBottom;
            card.setLayoutParams(cardParams);

            TextView label = new TextView(this);
            label.setText("Gedetecteerd: " + autoName);
            label.setTextColor(labelColor);
            label.setTextSize(12f);
            label.setPadding(0, 0, 0, (int) (6 * density));
            card.addView(label);

            EditText input = new EditText(this);
            input.setHint(autoName);
            input.setSingleLine(true);
            input.setBackgroundResource(inputBg);
            if (climb.userDisplayName != null) input.setText(climb.userDisplayName);
            card.addView(input);

            binding.climbNameRows.addView(card);
            nameInputs.add(input);
        }
    }

    private void saveAll() {
        Map<Integer, String> names = new HashMap<>();
        for (int i = 0; i < nameInputs.size(); i++) {
            names.put(i, nameInputs.get(i).getText().toString());
        }
        viewModel.saveNames(routeId, names);
    }
}
