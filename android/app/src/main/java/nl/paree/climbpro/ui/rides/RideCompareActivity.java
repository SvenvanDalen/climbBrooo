package nl.paree.climbpro.ui.rides;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.ride.RideComparison;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Ride comparer (issue #199): two rides of the same route side by side per kilometre —
 * time, speed, heart rate and the running time difference. Reached from the ride archive.
 */
public final class RideCompareActivity extends AppCompatActivity {

    private static final String EXTRA_A = "activity_id_a";
    private static final String EXTRA_B = "activity_id_b";

    public static Intent intentFor(Context ctx, long activityIdA, long activityIdB) {
        return new Intent(ctx, RideCompareActivity.class)
                .putExtra(EXTRA_A, activityIdA)
                .putExtra(EXTRA_B, activityIdB);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_compare);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView summary = findViewById(R.id.summary);
        View header = findViewById(R.id.header);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        RideCompareAdapter adapter = new RideCompareAdapter();
        list.setAdapter(adapter);

        RideCompareViewModel vm = new ViewModelProvider(this).get(RideCompareViewModel.class);
        vm.result().observe(this, r -> {
            summary.setText(summaryText(r));
            header.setVisibility(r.rows.isEmpty() ? View.GONE : View.VISIBLE);
            adapter.submit(r.rows);
        });
        vm.error().observe(this, summary::setText);
        vm.load(getIntent().getLongExtra(EXTRA_A, -1L), getIntent().getLongExtra(EXTRA_B, -1L));
    }

    private static String summaryText(RideCompareViewModel.Result r) {
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy", Locale.getDefault());
        String a = "A: " + label(r.a, fmt);
        String b = "B: " + label(r.b, fmt);
        List<RideComparison.Km> rows = r.rows;
        if (rows.isEmpty()) {
            return a + "\n" + b + "\n\nGeen tijd- en afstandsdata bij Strava voor een van "
                    + "beide ritten, dus vergelijken per kilometer lukt niet.";
        }
        int delta = rows.get(rows.size() - 1).cumulativeDeltaSec;
        String verdict = delta < 0
                ? "B was " + RideCompareAdapter.duration(-delta) + " sneller"
                : delta > 0 ? "B was " + RideCompareAdapter.duration(delta) + " langzamer"
                : "Precies even snel";
        double km = 0;
        for (RideComparison.Km k : rows) km += k.lengthM;
        return a + "\n" + b + "\n\n" + verdict + String.format(Locale.getDefault(),
                " over %.1f km (rijtijd, stilstaan telt niet mee).", km / 1000);
    }

    private static String label(StoredRide r, SimpleDateFormat fmt) {
        String name = r.name != null && !r.name.isEmpty() ? r.name : "Rit";
        return r.startEpochSec > 0
                ? fmt.format(new Date(r.startEpochSec * 1000L)) + " – " + name : name;
    }
}
