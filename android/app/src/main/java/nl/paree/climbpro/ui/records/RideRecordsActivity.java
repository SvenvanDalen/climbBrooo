package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
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
import nl.paree.climbpro.domain.power.DurationFormat;
import nl.paree.climbpro.domain.ride.FastestDistanceCalculator;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator.Records;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator.Streak;

import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Records" screen (issue #156): longest ride, highest average speed, most elevation, longest
 * moving time and most consecutive riding days, from the ride archive (issue #160). Each record
 * shows its value, date and ride name. Below that the fastest 10, 40 and 100 km inside rides
 * (issue #225), from the stream analysis. Phone-only.
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
        viewModel.state().observe(this, st -> {
            container.removeAllViews();
            boolean none = st == null || st.records == null || st.records.isEmpty();
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            if (none) return;
            render(container, st.records);
            renderFastest(container, st.fastest, st.ridesAwaitingAnalysis);
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

    private void renderFastest(LinearLayout container,
                               List<FastestDistanceCalculator.Distance> fastest, int awaiting) {
        TextView header = new TextView(this);
        header.setText("Snelste afstanden binnen een rit");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        header.setPadding(pad, pad, pad, 0);
        container.addView(header);

        if (awaiting > 0) {
            TextView note = new TextView(this);
            note.setText(String.format(Locale.getDefault(),
                    "Nog %d rit(ten) te analyseren; dat gebeurt in stappen bij elke sync. "
                            + "Tijden zijn verstreken tijd, stops tellen mee. Geen indoor- of "
                            + "e-bike-ritten.", awaiting));
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            note.setPadding(pad, pad / 2, pad, 0);
            container.addView(note);
        }

        for (FastestDistanceCalculator.Distance d : fastest) {
            String title = String.format(Locale.getDefault(), "Snelste %d km",
                    Math.round(d.distanceM / 1000));
            if (d.efforts.isEmpty()) {
                addRow(container, title, "–", "Nog geen rit van deze afstand geanalyseerd");
                continue;
            }
            FastestDistanceCalculator.Effort best = d.efforts.get(0);
            StringBuilder detail = new StringBuilder(rideLabel(best.ride));
            for (int i = 1; i < d.efforts.size(); i++) {
                FastestDistanceCalculator.Effort e = d.efforts.get(i);
                detail.append(System.lineSeparator()).append(i + 1).append(". ")
                        .append(DurationFormat.format(e.seconds)).append("  •  ")
                        .append(rideLabel(e.ride));
            }
            addRow(container, title, String.format(Locale.getDefault(), "%s  (%.1f km/u)",
                    DurationFormat.format(best.seconds), best.avgSpeedMps() * 3.6), detail.toString());
        }
    }

    private String rideLabel(StoredRide r) {
        String date = r.startEpochSec > 0
                ? dateFormat.format(new Date(r.startEpochSec * 1000L)) : "onbekende datum";
        String name = r.name != null && !r.name.isEmpty() ? r.name : "Rit";
        return date + "  •  " + name;
    }

    private void addRideRecord(LinearLayout container, String title, String value, StoredRide r) {
        addRow(container, title, value, rideLabel(r));
    }

    private void addRow(ViewGroup container, String title, String value, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.value)).setText(value);
        ((TextView) v.findViewById(R.id.detail)).setText(detail);
        container.addView(v);
    }
}
