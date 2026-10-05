package nl.paree.climbpro.ui.mywhoosh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.mywhoosh.CadenceByGrade;
import nl.paree.climbpro.domain.mywhoosh.HeartRateRecoveryTrend;
import nl.paree.climbpro.domain.mywhoosh.IndoorSeasonSummary;
import nl.paree.climbpro.domain.mywhoosh.MyWhooshRouteCatalog;
import nl.paree.climbpro.domain.mywhoosh.VirtualElevation;
import nl.paree.climbpro.service.MyWhooshPrNotifier;
import nl.paree.climbpro.ui.climbs.SegmentColorPalette;
import nl.paree.climbpro.ui.rides.RideCompareActivity;
import nl.paree.climbpro.ui.routes.RouteDetailActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "MyWhoosh-analyse": the indoor season against the previous one (issue #409), the catalog of
 * ridden MyWhoosh routes with a ride comparer per route (issues #406, #405), NP/IF/TSS per
 * ride (issue #391), cadence per gradient (issue #403), heart-rate recovery after intervals
 * (issue #402), the weight log (issue #408) and the indoor settings (issues #388, #393).
 * Everything comes from the ride archive and its stream analysis; phone-only.
 */
public final class MyWhooshActivity extends AppCompatActivity {

    private static final String[] GRADE_LABELS = {"0–2 %", "2–4 %", "4–6 %", "6–8 %", "8–10 %", "10 %+"};
    private static final int MAX_RECOVERY_ROWS = 10;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy", new Locale("nl"));
    private MyWhooshViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, MyWhooshActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mywhoosh);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout rows = findViewById(R.id.rows);
        viewModel = new ViewModelProvider(this).get(MyWhooshViewModel.class);
        viewModel.state().observe(this, st -> render(rows, st));
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void render(ViewGroup rows, MyWhooshViewModel.State st) {
        rows.removeAllViews();
        if (st.myWhooshRides == 0) {
            addText(rows, "Nog geen MyWhoosh-ritten in het rittenarchief. MyWhoosh zet elke rit "
                    + "als \"MyWhoosh - <route>\" op Strava; open Ritten en tik op Ophalen.");
        } else if (st.awaitingAnalysis > 0) {
            addText(rows, "Nog " + st.awaitingAnalysis + " MyWhoosh-rit(ten) te analyseren; dat "
                    + "gebeurt in stappen bij elke sync. Tot dan ontbreken NP, cadans en herstel.");
        }
        renderSeasons(rows, st.seasons);
        renderCatalog(rows, st);
        renderIntensity(rows, st);
        renderCadence(rows, st.cadence);
        renderRecovery(rows, st.recovery);
        renderSettings(rows);
    }

    // --- #409 -----------------------------------------------------------------------------

    private void renderSeasons(ViewGroup rows, List<IndoorSeasonSummary.Season> seasons) {
        addHeader(rows, "Indoorseizoen (okt–mrt)");
        if (seasons.isEmpty()) {
            addText(rows, "Nog geen indoorritten in een winterseizoen.");
            return;
        }
        IndoorSeasonSummary.Season cur = seasons.get(0);
        IndoorSeasonSummary.Season prev = seasons.size() > 1 ? seasons.get(1) : null;
        addRow(rows, "Seizoen " + cur.label(), hours(cur.movingSec) + " indoor",
                seasonDetail(cur, prev));
        if (prev != null) {
            addRow(rows, "Vorig seizoen " + prev.label(), hours(prev.movingSec) + " indoor",
                    seasonDetail(prev, null));
        }
    }

    private static String seasonDetail(IndoorSeasonSummary.Season s, IndoorSeasonSummary.Season vs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.getDefault(),
                "%d rit(ten), %.0f TSS, %d klim(men), %.0f virtuele hm", s.rides, s.tss,
                s.climbs, s.elevationM));
        if (s.best20MinWatts > 0) {
            sb.append(String.format(Locale.getDefault(), "\nBeste 5 min %d W, 20 min %d W",
                    s.best5MinWatts, s.best20MinWatts));
        }
        if (vs != null) {
            sb.append(String.format(Locale.getDefault(),
                    "\nT.o.v. vorig seizoen: %+.1f u, %+.0f TSS, %+d klim(men)",
                    (s.movingSec - vs.movingSec) / 3600.0, s.tss - vs.tss, s.climbs - vs.climbs));
            if (s.best20MinWatts > 0 && vs.best20MinWatts > 0) {
                sb.append(String.format(Locale.getDefault(), ", 20 min %+d W",
                        s.best20MinWatts - vs.best20MinWatts));
            }
        }
        return sb.toString();
    }

    // --- #406 / #405 ----------------------------------------------------------------------

    private void renderCatalog(ViewGroup rows, MyWhooshViewModel.State st) {
        addHeader(rows, "Routecatalogus");
        if (st.catalog.isEmpty()) {
            addText(rows, "Nog geen MyWhoosh-routes gereden.");
            return;
        }
        addText(rows, "Tik op een route om hem te openen, houd vast om twee ritten te vergelijken.");
        for (MyWhooshRouteCatalog.Entry e : st.catalog) {
            String best = e.bestMovingSec > 0 ? "beste tijd " + duration(e.bestMovingSec) : "geen tijd";
            String watts = e.bestAvgWatts != null ? ", beste gem. " + e.bestAvgWatts + " W" : "";
            String detail = String.format(Locale.getDefault(), "%.1f km, %s%s, laatst %s",
                    e.distanceM / 1000f, best, watts, date(e.lastEpochSec));
            View v = addRow(rows, e.title, e.count + "× gereden", detail);
            String routeId = st.routeIds.get(MyWhooshRouteCatalog.key(e.title));
            v.setOnClickListener(x -> {
                if (routeId != null) {
                    startActivity(RouteDetailActivity.intentFor(this, routeId));
                } else {
                    Toast.makeText(this, "Deze route is (nog) niet als route geïmporteerd, "
                            + "bijvoorbeeld omdat hij geen klimmen heeft.", Toast.LENGTH_LONG).show();
                }
            });
            v.setOnLongClickListener(x -> {
                pickRidesToCompare(e.rides);
                return true;
            });
        }
    }

    private void pickRidesToCompare(List<StoredRide> rides) {
        if (rides.size() < 2) {
            Toast.makeText(this, "Rij deze route nog een keer om te kunnen vergelijken",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[rides.size()];
        for (int i = 0; i < items.length; i++) items[i] = rideLabel(rides.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Eerste rit")
                .setItems(items, (d, first) -> {
                    String[] others = new String[items.length - 1];
                    long[] ids = new long[items.length - 1];
                    for (int i = 0, k = 0; i < items.length; i++) {
                        if (i == first) continue;
                        others[k] = items[i];
                        ids[k++] = rides.get(i).activityId;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Vergelijk met…")
                            .setItems(others, (d2, second) -> startActivity(
                                    RideCompareActivity.intentFor(this,
                                            rides.get(first).activityId, ids[second])))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String rideLabel(StoredRide r) {
        String watts = r.avgWatts != null ? String.format(Locale.getDefault(), ", %.0f W", r.avgWatts) : "";
        return String.format(Locale.getDefault(), "%s  •  %s%s", date(r.startEpochSec),
                duration(r.movingTimeSec), watts);
    }

    // --- #391 -----------------------------------------------------------------------------

    private void renderIntensity(ViewGroup rows, MyWhooshViewModel.State st) {
        addHeader(rows, "Belasting per rit (NP, IF, TSS)");
        if (st.ftpWatts <= 0) {
            addText(rows, "Stel je FTP in bij Instellingen om IF en TSS te zien.");
        }
        if (st.rides.isEmpty()) return;
        addText(rows, "TSS = uren × IF² × 100. Deze ritten tellen ook mee in de "
                + "belastingkalender en de fitheidsgrafiek.");
        for (MyWhooshViewModel.RideRow row : st.rides) {
            String title = date(row.ride.startEpochSec) + "  •  "
                    + nl.paree.climbpro.domain.mywhoosh.IndoorRides.routeTitle(row.ride);
            if (row.intensity == null) {
                addRow(rows, title, "–", duration(row.ride.movingTimeSec)
                        + (st.ftpWatts > 0 ? ", geen vermogen" : ""));
                continue;
            }
            addRow(rows, title, String.format(Locale.getDefault(), "%.0f TSS", row.intensity.tss),
                    String.format(Locale.getDefault(), "NP %d W, IF %.2f, %s",
                            row.intensity.normalizedPower, row.intensity.intensityFactor,
                            duration(row.ride.movingTimeSec)));
        }
    }

    // --- #403 -----------------------------------------------------------------------------

    private void renderCadence(ViewGroup rows, CadenceByGrade.Result r) {
        addHeader(rows, "Cadans per helling");
        if (r.isEmpty()) {
            addText(rows, "Nog geen MyWhoosh-rit met cadans en hoogteprofiel geanalyseerd.");
            return;
        }
        addText(rows, "Gemiddelde trapfrequentie per hellingsklasse over " + r.rideCount
                + " rit(ten), alleen terwijl je trapt.");
        for (int c = 0; c < GRADE_LABELS.length; c++) {
            String value = r.avgRpm[c] > 0 ? r.avgRpm[c] + " rpm" : "–";
            View v = addRow(rows, GRADE_LABELS[c], value,
                    String.format(Locale.getDefault(), "%d min gereden", r.seconds[c] / 60));
            View chip = new View(this);
            int size = (int) (12 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size * 3, size / 2);
            chip.setLayoutParams(lp);
            chip.setBackgroundColor(SegmentColorPalette.toColor(c));
            ((ViewGroup) v).addView(chip, 1);
        }
    }

    // --- #402 -----------------------------------------------------------------------------

    private void renderRecovery(ViewGroup rows, HeartRateRecoveryTrend.Result r) {
        addHeader(rows, "Hartslagherstel na intervallen");
        if (r.entries.isEmpty()) {
            addText(rows, "Nog geen MyWhoosh-rit met intervallen, vermogen en hartslag "
                    + "geanalyseerd. Een interval is minstens een minuut ruim boven je "
                    + "genormaliseerde vermogen van die rit.");
            return;
        }
        int weeks = HeartRateRecoveryTrend.TREND_WINDOW_DAYS / 7;
        String trend;
        if (r.recentAvg == null) {
            trend = "Geen intervallen in de laatste " + weeks + " weken.";
        } else if (r.previousAvg == null) {
            trend = String.format(Locale.getDefault(), "Gemiddeld over %d interval(len) in de "
                    + "laatste %d weken; nog niets om mee te vergelijken.", r.recentIntervals, weeks);
        } else {
            double diff = r.recentAvg - r.previousAvg;
            trend = String.format(Locale.getDefault(), "%s dan de %d weken daarvoor (%.0f slagen).",
                    Math.abs(diff) < 1 ? "Ongeveer gelijk" : diff > 0 ? "Sneller herstel" : "Trager herstel",
                    weeks, r.previousAvg);
        }
        addRow(rows, "Daling 60 s na een interval",
                r.recentAvg != null ? String.format(Locale.getDefault(), "%.0f slagen", r.recentAvg) : "–",
                trend);
        for (int i = 0; i < r.entries.size() && i < MAX_RECOVERY_ROWS; i++) {
            HeartRateRecoveryTrend.Entry e = r.entries.get(i);
            addRow(rows, date(e.ride.startEpochSec) + "  •  "
                            + nl.paree.climbpro.domain.mywhoosh.IndoorRides.routeTitle(e.ride),
                    String.format(Locale.getDefault(), "%.0f slagen", e.avgDropBpm),
                    e.intervals + " interval(len)");
        }
    }

    // --- #408 / #388 / #393 ---------------------------------------------------------------

    private void renderSettings(ViewGroup rows) {
        addHeader(rows, "Instellingen");
        Button weight = new Button(this);
        weight.setText("Gewichtslog");
        weight.setOnClickListener(v -> startActivity(WeightLogActivity.intentFor(this)));
        rows.addView(weight, padded());
        addText(rows, "W/kg van oudere pogingen gebruikt je gewicht van die dag uit het gewichtslog.");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        addSwitch(rows, "Melding bij een PR na een MyWhoosh-rit",
                prefs, MyWhooshPrNotifier.PREF_ENABLED);
        addSwitch(rows, "Virtuele hoogtemeters meetellen in doelen en jaaroverzicht",
                prefs, VirtualElevation.PREF_COUNT_VIRTUAL);
    }

    private void addSwitch(ViewGroup rows, String label, SharedPreferences prefs, String key) {
        SwitchCompat sw = new SwitchCompat(this);
        sw.setText(label);
        sw.setChecked(prefs.getBoolean(key, true));
        sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        rows.addView(sw, padded());
    }

    // --- helpers --------------------------------------------------------------------------

    private LinearLayout.LayoutParams padded() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 4, pad, pad / 4);
        return lp;
    }

    private void addHeader(ViewGroup rows, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ContextCompat.getColor(this, R.color.color_accent));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad + pad / 2, pad, 0);
        rows.addView(t);
    }

    private void addText(ViewGroup rows, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad / 2, pad, 0);
        rows.addView(t);
    }

    private View addRow(ViewGroup container, String title, String value, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.value)).setText(value);
        ((TextView) v.findViewById(R.id.detail)).setText(detail);
        container.addView(v);
        return v;
    }

    private String date(long epochSec) {
        return epochSec > 0 ? dateFormat.format(new Date(epochSec * 1000L)) : "?";
    }

    static String duration(int sec) {
        return sec >= 3600
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
                : String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
    }

    private static String hours(int sec) {
        return String.format(Locale.getDefault(), "%.1f u", sec / 3600.0);
    }
}
