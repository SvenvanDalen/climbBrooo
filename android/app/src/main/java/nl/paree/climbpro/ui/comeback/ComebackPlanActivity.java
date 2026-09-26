package nl.paree.climbpro.ui.comeback;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.comeback.ComebackPlanStore;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.comeback.ComebackPlanner;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Terugkomstplan" (issue #226): after a break or injury, a week-by-week build-up based on the
 * rider's own load before the break, with what was actually ridden per week. Phone-only; the
 * ride archive is the data source, so actual km follow the archive sync.
 */
public final class ComebackPlanActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ZoneId zone = ZoneId.systemDefault();

    private TextView status;
    private CheckBox injury;
    private Button startButton;
    private Button stopButton;
    private TextView baseline;
    private LinearLayout weeksBox;

    public static Intent intentFor(Context context) {
        return new Intent(context, ComebackPlanActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comeback_plan);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        status = findViewById(R.id.status);
        injury = findViewById(R.id.check_injury);
        startButton = findViewById(R.id.btn_start);
        stopButton = findViewById(R.id.btn_stop);
        baseline = findViewById(R.id.baseline);
        weeksBox = findViewById(R.id.weeks);

        startButton.setOnClickListener(v -> startPlan());
        stopButton.setOnClickListener(v -> io.execute(() -> {
            new ComebackPlanStore(this).clear();
            runOnUiThread(this::load);
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private void load() {
        io.execute(() -> {
            List<StoredRide> rides = new RideRepository(this).loadAll();
            ComebackPlanStore.Saved saved = new ComebackPlanStore(this).load();
            long now = now();
            int days = ComebackPlanner.daysSinceLastRide(rides, now);
            ComebackPlanner.Plan plan = saved == null ? null : ComebackPlanner.plan(rides,
                    saved.lastRideBeforeBreakEpochSec, saved.planStartEpochSec, saved.injury);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (plan == null) showNoPlan(days); else showPlan(plan, now);
            });
        });
    }

    private void showNoPlan(int days) {
        injury.setVisibility(View.VISIBLE);
        startButton.setVisibility(View.VISIBLE);
        stopButton.setVisibility(View.GONE);
        baseline.setText("");
        weeksBox.removeAllViews();
        if (days < 0) {
            status.setText("Nog geen ritten in je archief. Synchroniseer eerst je ritten "
                    + "via het rittenarchief.");
            startButton.setEnabled(false);
            return;
        }
        startButton.setEnabled(true);
        String since = days == 1 ? "1 dag" : days + " dagen";
        if (days >= ComebackPlanner.MIN_BREAK_DAYS) {
            status.setText("Je laatste rit was " + since + " geleden. Bouw rustig op om "
                    + "overbelasting te voorkomen.");
        } else {
            status.setText("Je laatste rit was " + since + " geleden — dat is nog geen lange "
                    + "pauze. Na een blessure kun je toch een voorzichtig plan starten.");
        }
    }

    private void showPlan(ComebackPlanner.Plan plan, long now) {
        injury.setVisibility(View.GONE);
        startButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.VISIBLE);
        int current = plan.currentWeek(now);
        String kind = plan.injury ? "blessure/pauze" : "pauze";
        if (current >= plan.weeks.size()) {
            status.setText("Plan afgerond! Na " + plan.breakDays + " dagen " + kind
                    + " zit je weer op je oude niveau. Bouw vanaf hier normaal verder op.");
        } else {
            status.setText("Opbouwplan na " + plan.breakDays + " dagen " + kind + ", "
                    + plan.weeks.size() + " weken. Nu: week " + Math.max(1, current + 1) + ".");
        }
        baseline.setText(ComebackPlanner.baselineText(plan));

        weeksBox.removeAllViews();
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                getResources().getDisplayMetrics());
        for (ComebackPlanner.Week w : plan.weeks) {
            TextView tv = new TextView(this);
            tv.setPadding(0, pad, 0, pad);
            tv.setText(ComebackPlanner.weekText(w, current));
            boolean isCurrent = w.number - 1 == current;
            if (isCurrent) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
            if (w.overloaded()) {
                tv.setTextColor(ContextCompat.getColor(this, R.color.color_accent));
            }
            weeksBox.addView(tv);
        }
    }

    private void startPlan() {
        boolean inj = injury.isChecked();
        io.execute(() -> {
            List<StoredRide> rides = new RideRepository(this).loadAll();
            long now = now();
            long last = ComebackPlanner.lastRideStart(rides, now);
            long weekStart = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond();
            try {
                new ComebackPlanStore(this).save(last, weekStart, inj);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Opslaan mislukt: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(this::load);
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
