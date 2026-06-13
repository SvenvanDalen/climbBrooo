package nl.paree.climbpro.ui.climbs;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbLogbookActivity extends AppCompatActivity {

    private ClimbLogbookViewModel viewModel;
    private LogbookAdapter adapter;
    private TextView empty;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_logbook);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogbookAdapter(this::openClimb);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(ClimbLogbookViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submit(rows);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });

        Button sync = findViewById(R.id.syncButton);
        sync.setOnClickListener(v -> syncFromStrava());

        viewModel.loadLogbook();
    }

    private void openClimb(ClimbLogbookViewModel.LogbookRow row) {
        if (row.routeId == null || row.climbIndex < 0) {
            Toast.makeText(this, "Klim niet meer in een route gevonden", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(ClimbDetailActivity.intentFor(this, row.routeId, row.climbIndex));
    }

    private void syncFromStrava() {
        Toast.makeText(this, "Ritten ophalen…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                StravaActivitiesRepository repo = new StravaActivitiesRepository(
                        getApplicationContext(),
                        new StravaAuthRepository(getApplicationContext()),
                        new RouteRepository(this),
                        new ClimbAttemptRepository(this));
                int created = repo.syncActivities();
                runOnUiThread(() -> {
                    Toast.makeText(this, created + " nieuwe poging(en)", Toast.LENGTH_SHORT).show();
                    viewModel.loadLogbook();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Ophalen mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
