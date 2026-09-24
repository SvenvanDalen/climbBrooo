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
import nl.paree.climbpro.ui.activity.ActivityImportActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbLogbookActivity extends AppCompatActivity {

    private ClimbLogbookViewModel viewModel;
    private LogbookAdapter adapter;
    private TextView empty;
    private TextView streakText;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_logbook);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        empty = findViewById(R.id.empty);
        streakText = findViewById(R.id.streak);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogbookAdapter(this::openClimb);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(ClimbLogbookViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submit(rows);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.streak().observe(this, this::renderStreak);

        Button sync = findViewById(R.id.syncButton);
        sync.setOnClickListener(v -> syncFromStrava());
        findViewById(R.id.importGarminButton).setOnClickListener(v -> startActivity(
                ActivityImportActivity.pickIntent(this)));

        viewModel.loadLogbook();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        viewModel.loadLogbook(); // back from a Garmin import
    }

    private void renderStreak(nl.paree.climbpro.domain.climb.ClimbStreakCalculator.Streak streak) {
        if (streak.current <= 0) {
            streakText.setText("Nog geen actieve streak");
        } else {
            streakText.setText(String.format(java.util.Locale.getDefault(),
                    "Streak: %d dag(en) op rij (langste: %d)", streak.current, streak.longest));
        }
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
