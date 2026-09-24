package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator.Records;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator.Streak;

import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * "Records" screen (issue #156): longest ride, highest average speed, most elevation, longest
 * moving time and most consecutive riding days, from the ride archive (issue #160). Each record
 * shows its value, date and ride name. Phone-only.
 */
public final class RideRecordsActivity extends AppCompatActivity {

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", new Locale("nl"));
    private final DateTimeFormatter dayFormat =
            DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("nl"));

    private RideRecordsViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, RideRecordsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_records);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        LinearLayout container = findViewById(R.id.records);

        viewModel = new ViewModelProvider(this).get(RideRecordsViewModel.class);
        viewModel.records().observe(this, rec -> {
            container.removeAllViews();
            boolean none = rec == null || rec.isEmpty();
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            if (!none) render(container, rec);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload on every return so rides fetched in "Ritten" meanwhile show up immediately.
        viewModel.load();
    }

    private void render(LinearLayout container, Records rec) {
        if (rec.longestDistance != null) {
            addRideRecord(container, "Langste rit", String.format(Locale.getDefault(),
                    "%.1f km", rec.longestDistance.distanceM / 1000f), rec.longestDistance);
        }
        if (rec.fastestAvgSpeed != null) {
            addRideRecord(container, String.format(Locale.getDefault(),
                    "Hoogste gemiddelde snelheid (ritten vanaf %d km, geen indoor)",
                    Math.round(RideRecordsCalculator.SPEED_MIN_DISTANCE_M / 1000)),
                    String.format(Locale.getDefault(), "%.1f km/u",
                            rec.fastestAvgSpeed.avgSpeedMps * 3.6f),
                    rec.fastestAvgSpeed);
        }
        if (rec.mostElevation != null) {
            addRideRecord(container, "Meeste hoogtemeters in één rit",
                    Math.round(rec.mostElevation.elevationGainM) + " hm", rec.mostElevation);
        }
        if (rec.longestMovingTime != null) {
            int sec = rec.longestMovingTime.movingTimeSec;
            addRideRecord(container, "Langste rijtijd", String.format(Locale.getDefault(),
                    "%d:%02d u", sec / 3600, (sec % 3600) / 60), rec.longestMovingTime);
        }
        Streak s = rec.longestStreak;
        if (s != null) {
            String detail = s.days == 1
                    ? dayFormat.format(s.firstDay)
                    : dayFormat.format(s.firstDay) + " t/m " + dayFormat.format(s.lastDay);
            addRow(container, "Meeste dagen op rij",
                    s.days + (s.days == 1 ? " dag" : " dagen"), detail);
        }
    }

    private void addRideRecord(LinearLayout container, String title, String value, StoredRide r) {
        String date = r.startEpochSec > 0
                ? dateFormat.format(new Date(r.startEpochSec * 1000L)) : "onbekende datum";
        String name = r.name != null && !r.name.isEmpty() ? r.name : "Rit";
        addRow(container, title, value, date + "  •  " + name);
    }

    private void addRow(ViewGroup container, String title, String value, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.value)).setText(value);
        ((TextView) v.findViewById(R.id.detail)).setText(detail);
        container.addView(v);
    }
}
