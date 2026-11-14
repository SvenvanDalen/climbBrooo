package nl.paree.climbpro.ui.mywhoosh;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.ui.EpochDates;
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.MyWhooshDashboard;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MyWhoosh dashboard (issue #383): the latest ride on top, totals over every MyWhoosh ride and
 * distance per week or month as bars. Opened from the MyWhoosh analysis screen; phone-only.
 */
public final class MyWhooshDashboardActivity extends AppCompatActivity {

    private static final String[] MONTHS =
            {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"};

    /** Loads the archive and its stream analysis off the main thread. */
    public static final class DashboardViewModel extends AndroidViewModel {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final MutableLiveData<MyWhooshDashboard.Result> result = new MutableLiveData<>();

        public DashboardViewModel(@NonNull Application app) {
            super(app);
        }

        public LiveData<MyWhooshDashboard.Result> result() { return result; }

        public void load() {
            executor.execute(() -> {
                Application app = getApplication();
                result.postValue(MyWhooshDashboard.compute(new RideRepository(app).loadAll(),
                        new RideStreamStatsRepository(app).loadById(),
                        System.currentTimeMillis() / 1000L, ZoneId.systemDefault()));
            });
        }

        @Override
        protected void onCleared() { executor.shutdown(); }
    }

    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("nl"));
    private LinearLayout rows;
    private boolean monthly;
    private MyWhooshDashboard.Result last;

    public static Intent intentFor(Context context) {
        return new Intent(context, MyWhooshDashboardActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mywhoosh);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("MyWhoosh-dashboard");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        rows = findViewById(R.id.rows);

        DashboardViewModel vm = new ViewModelProvider(this).get(DashboardViewModel.class);
        vm.result().observe(this, r -> {
            last = r;
            render();
        });
        vm.load();
    }

    private void render() {
        rows.removeAllViews();
        MyWhooshDashboard.Result r = last;
        if (r == null) return;
        if (r.latest == null) {
            addText("Nog geen MyWhoosh-ritten in het rittenarchief. MyWhoosh zet elke rit als "
                    + "\"MyWhoosh - <route>\" op Strava; open Ritten en tik op Ophalen.");
            return;
        }
        addHeader("Laatste rit");
        StringBuilder detail = new StringBuilder(String.format(Locale.getDefault(),
                "%.1f km, %.0f hm, %s", r.latest.distanceM / 1000f, r.latest.elevationGainM,
                MyWhooshActivity.duration(r.latest.movingTimeSec)));
        if (r.latest.avgWatts != null) {
            detail.append(String.format(Locale.getDefault(), ", gem. %.0f W", r.latest.avgWatts));
        }
        if (r.latestNormalizedPower != null) detail.append(", NP ").append(r.latestNormalizedPower).append(" W");
        if (r.latestWorkKj != null) detail.append(", ").append(r.latestWorkKj).append(" kJ");
        addRow(IndoorRides.routeTitle(r.latest), date(r.latest.startEpochSec), detail.toString());

        MyWhooshDashboard.Totals t = r.totals;
        addHeader("Totaal");
        addRow("Ritten", String.valueOf(t.rides), "");
        addRow("Afstand", String.format(Locale.getDefault(), "%.0f km", t.distanceM / 1000), "");
        addRow("Virtuele hoogtemeters", String.format(Locale.getDefault(), "%.0f m", t.elevationM), "");
        addRow("Tijd", String.format(Locale.getDefault(), "%.1f u", t.movingSec / 3600.0), "");
        addRow("Arbeid", t.ridesWithWork > 0 ? t.workKj + " kJ" : "–",
                t.ridesWithWork < t.rides
                        ? "Uit " + t.ridesWithWork + " van " + t.rides + " rit(ten) met vermogen" : "");

        addHeader("Kilometers per " + (monthly ? "maand" : "week"));
        addPeriodToggle();
        List<MyWhooshDashboard.Bucket> buckets = monthly ? r.months : r.weeks;
        double[] km = new double[buckets.size()];
        String[] labels = new String[buckets.size()];
        for (int i = 0; i < km.length; i++) {
            MyWhooshDashboard.Bucket b = buckets.get(i);
            km[i] = b.distanceM / 1000;
            labels[i] = monthly ? MONTHS[b.start.getMonthValue() - 1]
                    : b.start.getDayOfMonth() + "/" + b.start.getMonthValue();
        }
        SimpleBarChartView chart = new SimpleBarChartView(this);
        chart.setBars(km, labels, "km");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        chart.setPadding(pad, pad, pad, 0);
        rows.addView(chart);
    }

    private void addPeriodToggle() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        group.setPadding(pad, 0, pad, 0);
        RadioButton week = new RadioButton(this);
        week.setId(View.generateViewId());
        week.setText("Week");
        RadioButton month = new RadioButton(this);
        month.setId(View.generateViewId());
        month.setText("Maand");
        group.addView(week);
        group.addView(month);
        group.check(monthly ? month.getId() : week.getId());
        group.setOnCheckedChangeListener((g, id) -> {
            monthly = id == month.getId();
            render();
        });
        rows.addView(group);
    }

    private void addHeader(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ContextCompat.getColor(this, R.color.color_accent));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad + pad / 2, pad, 0);
        rows.addView(t);
    }

    private void addText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad / 2, pad, 0);
        rows.addView(t);
    }

    private void addRow(String title, String value, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, rows, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.value)).setText(value);
        ((TextView) v.findViewById(R.id.detail)).setText(detail);
        rows.addView(v);
    }

    private String date(long epochSec) {
        return EpochDates.format(dateFormat, epochSec);
    }
}
