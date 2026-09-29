package nl.paree.climbpro.ui.climbs;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.climb.ClimbOfTheWeek;
import nl.paree.climbpro.domain.weather.DailyForecast;
import nl.paree.climbpro.ui.routes.RouteDetailActivity;

import java.time.format.TextStyle;
import java.util.Locale;

/**
 * "Klim van de week" (issue #40): one suggested climb for this ISO week with the reasons
 * behind it — riding history, distance from the last known location and the weather
 * outlook. Phone-only; no watch or protocol involvement. From here the rider opens the
 * climb detail or the route (where it can be selected for the watch).
 */
public final class ClimbOfTheWeekActivity extends AppCompatActivity {

    private static final Locale NL = new Locale("nl", "NL");

    private ClimbOfTheWeekViewModel viewModel;
    private TextView weekLabel;
    private TextView empty;
    private View content;
    private TextView climbName;
    private TextView climbStats;
    private TextView reasons;
    private View locationButton;
    private View alternativesTitle;
    private LinearLayout alternatives;
    private View progress;
    private ClimbOfTheWeek.Candidate shown;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> viewModel.load());

    public static Intent intentFor(Context context) {
        return new Intent(context, ClimbOfTheWeekActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_of_the_week);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        weekLabel = findViewById(R.id.weekLabel);
        empty = findViewById(R.id.empty);
        content = findViewById(R.id.content);
        climbName = findViewById(R.id.climbName);
        climbStats = findViewById(R.id.climbStats);
        reasons = findViewById(R.id.reasons);
        locationButton = findViewById(R.id.locationButton);
        alternativesTitle = findViewById(R.id.alternativesTitle);
        alternatives = findViewById(R.id.alternatives);
        progress = findViewById(R.id.progress);

        findViewById(R.id.openClimbButton).setOnClickListener(v -> {
            if (shown != null) {
                startActivity(ClimbDetailActivity.intentFor(this, shown.routeId, shown.climbIndex));
            }
        });
        findViewById(R.id.openRouteButton).setOnClickListener(v -> {
            if (shown != null) startActivity(RouteDetailActivity.intentFor(this, shown.routeId));
        });
        locationButton.setOnClickListener(v -> locationPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }));

        viewModel = new ViewModelProvider(this).get(ClimbOfTheWeekViewModel.class);
        viewModel.state().observe(this, this::render);
        viewModel.busy().observe(this,
                b -> progress.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));
        if (savedInstanceState == null || viewModel.state().getValue() == null) {
            viewModel.load();
        }
    }

    private void render(ClimbOfTheWeekViewModel.State s) {
        if (s == null) return;
        weekLabel.setText(getString(R.string.cotw_week, s.weekKey));
        ClimbOfTheWeek.Suggestion top = s.top;
        if (top == null) {
            shown = null;
            empty.setVisibility(View.VISIBLE);
            content.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        shown = top.candidate;

        climbName.setText(top.candidate.name);
        climbStats.setText(stats(top.candidate));
        reasons.setText(reasonsFor(top));
        locationButton.setVisibility(s.hasLocationPermission ? View.GONE : View.VISIBLE);

        alternatives.removeAllViews();
        alternativesTitle.setVisibility(s.alternatives.isEmpty() ? View.GONE : View.VISIBLE);
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (ClimbOfTheWeek.Suggestion alt : s.alternatives) {
            ClimbOfTheWeek.Candidate c = alt.candidate;
            TextView row = new TextView(this);
            row.setPadding(0, padding, 0, padding);
            row.setBackgroundResource(ripple.resourceId);
            row.setText(getString(R.string.cotw_alternative_row, c.name, stats(c),
                    history(alt.daysSinceRidden)));
            row.setOnClickListener(v -> startActivity(
                    ClimbDetailActivity.intentFor(this, c.routeId, c.climbIndex)));
            alternatives.addView(row);
        }
    }

    private String stats(ClimbOfTheWeek.Candidate c) {
        return getString(R.string.cotw_stats, c.lengthM / 1000.0, c.elevationGainM,
                c.avgGradient * 100.0);
    }

    private String history(Integer days) {
        if (days == null) return getString(R.string.cotw_never_ridden);
        if (days == 0) return getString(R.string.cotw_ridden_today);
        return getResources().getQuantityString(R.plurals.cotw_days_since, days, days);
    }

    private CharSequence reasonsFor(ClimbOfTheWeek.Suggestion s) {
        StringBuilder b = new StringBuilder();
        if (s.completedThisWeek) bullet(b, getString(R.string.cotw_completed));
        bullet(b, history(s.daysSinceRidden));

        if (s.distanceUsed) {
            bullet(b, getString(R.string.cotw_distance, s.distanceM / 1000.0));
        } else {
            bullet(b, getString(R.string.cotw_distance_unknown));
        }

        if (s.weatherUsed && s.bestDay != null && s.outlook != null) {
            String day = dayLabel(s.bestDay);
            switch (s.outlook) {
                case GOOD:
                    bullet(b, getString(R.string.cotw_weather_good, day));
                    break;
                case MIXED:
                    bullet(b, getString(R.string.cotw_weather_mixed, day));
                    break;
                default:
                    bullet(b, getString(R.string.cotw_weather_poor, day));
                    break;
            }
        } else {
            bullet(b, getString(R.string.cotw_weather_offline));
        }

        if (s.pinned) bullet(b, getString(R.string.cotw_pinned));
        return b.toString();
    }

    private String dayLabel(DailyForecast.Day d) {
        String name = d.date.getDayOfWeek().getDisplayName(TextStyle.FULL, NL);
        String rain = d.rainPct == null ? "?" : String.valueOf(d.rainPct);
        String wind = Double.isNaN(d.windMaxKmh) ? "?"
                : String.valueOf(Math.round(d.windMaxKmh));
        String temp = Double.isNaN(d.tempMaxC) ? "?"
                : String.valueOf(Math.round(d.tempMaxC));
        return getString(R.string.cotw_day_detail, name, rain, wind, temp);
    }

    private static void bullet(StringBuilder b, String line) {
        if (b.length() > 0) b.append('\n');
        b.append("• ").append(line);
    }
}
