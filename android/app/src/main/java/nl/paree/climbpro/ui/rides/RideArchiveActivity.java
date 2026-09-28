package nl.paree.climbpro.ui.rides;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.ride.RideCategory;
import nl.paree.climbpro.domain.ride.RideCategoryLabel;

import java.util.Map;

/**
 * "Ritten" archive (issue #160): synced rides automatically classified as woon-werk,
 * training or toerrit, filterable per category — no manual tagging. Phone-only.
 */
public final class RideArchiveActivity extends AppCompatActivity {

    /** Spinner position 0 = all, then one entry per category in this order. */
    private static final RideCategory[] FILTERS = {
            null, RideCategory.COMMUTE, RideCategory.TRAINING, RideCategory.TOUR};

    private RideArchiveViewModel viewModel;
    private ArrayAdapter<String> filterAdapter;
    private RideArchiveAdapter adapter;

    public static Intent intentFor(Context context) {
        return new Intent(context, RideArchiveActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_archive);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RideArchiveAdapter();
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(RideArchiveViewModel.class);

        Spinner filter = findViewById(R.id.filter);
        filterAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new java.util.ArrayList<>(java.util.Arrays.asList(labels(null))));
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(filterAdapter);
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                viewModel.setFilter(FILTERS[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        findViewById(R.id.btn_refresh).setOnClickListener(v -> viewModel.refreshFromStrava());

        viewModel.rows().observe(this, rows -> {
            adapter.submit(rows);
            empty.setVisibility(rows == null || rows.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.counts().observe(this, counts -> {
            filterAdapter.clear();
            filterAdapter.addAll(labels(counts));
            filterAdapter.notifyDataSetChanged();
        });
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        viewModel.load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) adapter.shutdown();
    }

    private static String[] labels(Map<RideCategory, Integer> counts) {
        String[] out = new String[FILTERS.length];
        int total = 0;
        if (counts != null) for (Integer n : counts.values()) total += n;
        out[0] = counts != null ? "Alle ritten (" + total + ")" : "Alle ritten";
        for (int i = 1; i < FILTERS.length; i++) {
            String label = RideCategoryLabel.forCategory(FILTERS[i]);
            out[i] = counts != null ? label + " (" + counts.get(FILTERS[i]) + ")" : label;
        }
        return out;
    }
}
