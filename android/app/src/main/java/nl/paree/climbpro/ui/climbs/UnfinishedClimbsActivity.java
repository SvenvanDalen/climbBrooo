package nl.paree.climbpro.ui.climbs;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;

/**
 * "Nooit voltooide klimmen" overview (issue #37): climbs the rider started but never
 * finished (stopped, turned back, or app/GPS lost track), reused from existing
 * attempt/climb-start data via {@link nl.paree.climbpro.domain.matching.ClimbEntryOnlyDetector}
 * and {@link UnfinishedClimbsCalculator}. Phone-only screen, no wire-format impact.
 */
public final class UnfinishedClimbsActivity extends AppCompatActivity {

    private UnfinishedClimbsViewModel viewModel;
    private UnfinishedClimbsAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unfinished_climbs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UnfinishedClimbsAdapter(this::openClimb);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(UnfinishedClimbsViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submit(rows);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.loadUnfinished();
    }

    private void openClimb(UnfinishedClimbsViewModel.Row row) {
        if (row.routeId == null || row.climbIndex < 0) {
            Toast.makeText(this, "Klim niet meer in een route gevonden", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(ClimbDetailActivity.intentFor(this, row.routeId, row.climbIndex));
    }
}
