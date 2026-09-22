package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;

/**
 * Post-ride fatigue-curve screen (issue #21): chart + list of how climbing pace (actual VAM)
 * changed across consecutive climbs within one Strava activity. Phone-only, no wire-format
 * impact — pure read of already-synced {@code StoredClimbAttempt} data via
 * {@link RideFatigueViewModel}/{@link nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator}.
 *
 * <p>Reached from {@link ClimbTimelineActivity} (long-press an attempt row). Rides with fewer
 * than 2 climbs with usable data render the empty-state text instead of a crash or blank chart.
 */
public final class RideFatigueActivity extends AppCompatActivity {

    private static final String EXTRA_ACTIVITY_ID = "activity_id";

    private RideFatigueViewModel viewModel;
    private RideFatiguePointAdapter adapter;
    private RideFatigueChartView chart;
    private View empty;
    private View directionCaveat;

    public static Intent intentFor(Context ctx, long activityId) {
        Intent i = new Intent(ctx, RideFatigueActivity.class);
        i.putExtra(EXTRA_ACTIVITY_ID, activityId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_fatigue);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        empty = findViewById(R.id.empty);
        chart = findViewById(R.id.chart);
        directionCaveat = findViewById(R.id.directionCaveat);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RideFatiguePointAdapter();
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(RideFatigueViewModel.class);
        viewModel.curve().observe(this, points -> {
            boolean hasCurve = points != null && !points.isEmpty();
            empty.setVisibility(hasCurve ? View.GONE : View.VISIBLE);
            chart.setVisibility(hasCurve ? View.VISIBLE : View.GONE);
            directionCaveat.setVisibility(hasCurve ? View.VISIBLE : View.GONE);
            chart.setPoints(points);
            adapter.submit(points);
        });

        long activityId = getIntent().getLongExtra(EXTRA_ACTIVITY_ID, -1L);
        viewModel.loadForActivity(activityId);
    }
}
