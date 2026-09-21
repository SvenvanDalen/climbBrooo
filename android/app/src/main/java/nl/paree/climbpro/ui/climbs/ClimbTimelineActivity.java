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
 * Chronological climb timeline: every attempt across every route, newest first,
 * grouped by month. Separate from {@link ClimbLogbookActivity}, which is a
 * per-climb PR roll-up. Phone-only screen, no wire-format impact.
 */
public final class ClimbTimelineActivity extends AppCompatActivity {

    private ClimbTimelineViewModel viewModel;
    private TimelineAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_timeline);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TimelineAdapter(this::openAttempt);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(ClimbTimelineViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submit(rows);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.loadTimeline();
    }

    private void openAttempt(ClimbTimelineViewModel.TimelineRow row) {
        if (row.routeId == null || row.climbIndex < 0) {
            Toast.makeText(this, "Klim niet meer in een route gevonden", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(ClimbDetailActivity.intentFor(this, row.routeId, row.climbIndex));
    }
}
