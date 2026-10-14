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
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.mywhoosh.CadenceByGrade;
import nl.paree.climbpro.domain.mywhoosh.EfficiencyFactorTrend;
import nl.paree.climbpro.domain.mywhoosh.IndoorOutdoorPowerCurve;
import nl.paree.climbpro.domain.mywhoosh.HeartRateRecoveryTrend;
import nl.paree.climbpro.domain.mywhoosh.IndoorSeasonSummary;
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.MyWhooshRouteCatalog;
import nl.paree.climbpro.domain.mywhoosh.PowerDecoupling;
import nl.paree.climbpro.domain.mywhoosh.RaceDetector;
import nl.paree.climbpro.domain.mywhoosh.VirtualElevation;
import nl.paree.climbpro.domain.ride.PowerCurveAnalyzer;
import nl.paree.climbpro.service.MyWhooshPrNotifier;
import nl.paree.climbpro.ui.climbs.SegmentColorPalette;
import nl.paree.climbpro.ui.rides.RideCompareActivity;
import nl.paree.climbpro.ui.routes.RouteDetailActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * "MyWhoosh-analyse": the indoor season against the previous one (issue #409), the catalog of
 * ridden MyWhoosh routes with a ride comparer per route (issues #406, #405), NP/IF/TSS per
 * ride with its work in kJ (issues #391, #404), races apart (issue #407), intervals per ride
 * (issue #400), Pw:HR decoupling (issue #399), the Efficiency Factor trend (issue #398), the
 * indoor vs outdoor power curve (issue #394), cadence per gradient (issue #403), heart-rate
 * recovery after intervals (issue #402), the weight log (issue #408) and the indoor settings
 * (issues #388, #393).
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
        renderRaces(rows, st.races);
        renderIntervals(rows, st.intervalRides);
        renderDecoupling(rows, st.decoupling);
        renderEfficiency(rows, st.efficiency);
        renderIndoorOutdoor(rows, st.indoorOutdoor);
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
                + "belastingkalender en de fitheidsgrafiek. kJ is de geleverde arbeid uit je "
                + "vermogen, ongeveer gelijk aan de verbrande kcal. Tik op een rit voor het "
                + "profiel met vermogen, hartslag en cadans.");
        for (MyWhooshViewModel.RideRow row : st.rides) {
            String title = date(row.ride.startEpochSec) + "  •  " + IndoorRides.routeTitle(row.ride);
            String work = row.workKj != null ? ", " + row.workKj + " kJ" : "";
            long activityId = row.ride.activityId;
            if (row.intensity == null) {
                addRow(rows, title, "–", duration(row.ride.movingTimeSec) + work
                        + (st.ftpWatts > 0 && row.workKj == null ? ", geen vermogen" : ""))
                        .setOnClickListener(v -> openProfile(activityId));
                continue;
            }
            View v = addRow(rows, title, String.format(Locale.getDefault(), "%.0f TSS", row.intensity.tss),
                    String.format(Locale.getDefault(), "NP %d W, IF %.2f, %s%s",
                            row.intensity.normalizedPower, row.intensity.intensityFactor,
                            duration(row.ride.movingTimeSec), work));
            v.setOnClickListener(x -> openProfile(activityId));
        }
    }

    // --- #390 -----------------------------------------------------------------------------

    private void openProfile(long activityId) {
        startActivity(RideProfileActivity.intentFor(this, activityId));
    }

    // --- #407 -----------------------------------------------------------------------------

    private void renderRaces(ViewGroup rows, List<RaceDetector.Race> races) {
        if (races.isEmpty()) return;
        addHeader(rows, "Wedstrijden");
        addText(rows, "Ritten met \"race\" of \"event\" in de naam, of op wedstrijdintensiteit "
                + "(IF ≥ 0,90) met je hardste sprint bij de start. Ze staan niet tussen de "
                + "trainingsritten hierboven.");
        for (RaceDetector.Race r : races) {
            String wkg = r.avgWattsPerKg != null
                    ? String.format(Locale.getDefault(), "%.2f W/kg", r.avgWattsPerKg) : "–";
            StringBuilder detail = new StringBuilder(duration(r.timeSec));
            if (r.peak1MinWatts != null) detail.append(", piek 1 min ").append(r.peak1MinWatts).append(" W");
            if (r.peak5MinWatts != null) detail.append(", 5 min ").append(r.peak5MinWatts).append(" W");
            if (r.reason == RaceDetector.Reason.INTENSITY) detail.append("\nHerkend aan de intensiteit");
            addRow(rows, date(r.ride.startEpochSec) + "  •  " + IndoorRides.routeTitle(r.ride),
                    wkg, detail.toString());
        }
    }

    // --- #400 -----------------------------------------------------------------------------

    private void renderIntervals(ViewGroup rows, List<MyWhooshViewModel.IntervalRide> rides) {
        addHeader(rows, "Intervallen");
        if (rides.isEmpty()) {
            addText(rows, "Nog geen MyWhoosh-rit met herkende intervallen geanalyseerd. Een "
                    + "interval is minstens een minuut ruim boven je genormaliseerde vermogen "
                    + "van die rit.");
            return;
        }
        for (MyWhooshViewModel.IntervalRide ir : rides) {
            addText(rows, date(ir.ride.startEpochSec) + "  •  " + IndoorRides.routeTitle(ir.ride)
                    + " (" + ir.intervals.size() + " interval(len))");
            for (int i = 0; i < ir.intervals.size(); i++) {
                StoredRideStreamStats.Interval iv = ir.intervals.get(i);
                StringBuilder detail = new StringBuilder("start na ")
                        .append(duration(iv.startSec));
                if (iv.maxHr != null) detail.append(", max ").append(iv.maxHr).append(" bpm");
                if (iv.recoveryBpm != null) {
                    detail.append(", herstel ").append(iv.recoveryBpm).append(" slagen");
                }
                addRow(rows, "#" + (i + 1) + "  " + duration(iv.durationSec),
                        iv.avgWatts + " W", detail.toString());
            }
        }
    }

    // --- #399 -----------------------------------------------------------------------------

    private void renderDecoupling(ViewGroup rows, List<PowerDecoupling.Entry> entries) {
        addHeader(rows, "Aerobe ontkoppeling (Pw:HR)");
        if (entries.isEmpty()) {
            addText(rows, "Nog geen MyWhoosh-rit van minstens 45 minuten met vermogen en "
                    + "hartslag geanalyseerd.");
            return;
        }
        addText(rows, "Hoeveel je hartslag ten opzichte van je vermogen opliep in de tweede "
                + "helft. Onder 5 % betekent dat je duurvermogen het volhield.");
        for (PowerDecoupling.Entry e : entries) {
            addRow(rows, date(e.ride.startEpochSec) + "  •  " + IndoorRides.routeTitle(e.ride),
                    String.format(Locale.getDefault(), "%.1f %%", e.percent),
                    verdict(e.rating) + ", over " + e.minutes + " min");
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
                            + IndoorRides.routeTitle(e.ride),
                    String.format(Locale.getDefault(), "%.0f slagen", e.avgDropBpm),
                    e.intervals + " interval(len)");
        }
    }

    // --- #398 -----------------------------------------------------------------------------

    private void renderEfficiency(ViewGroup rows, EfficiencyFactorTrend.Result r) {
        addHeader(rows, "Efficiency Factor (NP / hartslag)");
        if (r.isEmpty()) {
            addText(rows, "Nog geen MyWhoosh-rit van minstens 20 minuten met vermogen en "
                    + "hartslag in de laatste " + EfficiencyFactorTrend.WEEKS + " weken.");
            return;
        }
        String trend = r.change == null ? "Nog te weinig weken om een trend te zien."
                : String.format(Locale.getDefault(), "%+.2f t.o.v. de %d weken daarvoor (%s).",
                        r.change, EfficiencyFactorTrend.WEEKS / 2,
                        r.change >= 0 ? "meer watt per hartslag" : "minder watt per hartslag");
        addText(rows, "Gemiddeld per week. Hoger is beter: meer watt bij dezelfde hartslag. " + trend);
        List<Double> values = new ArrayList<>();
        for (EfficiencyFactorTrend.Week w : r.weeks) values.add(w.ef);
        SimpleLineChartView chart = new SimpleLineChartView(this);
        List<SimpleLineChartView.Series> series = new ArrayList<>();
        series.add(new SimpleLineChartView.Series(values,
                ContextCompat.getColor(this, R.color.color_accent), "EF", false,
                new HashSet<>(), v -> String.format(Locale.getDefault(), "%.2f", v)));
        chart.setSeries(series, false);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        chart.setPadding(pad, pad, pad, 0);
        rows.addView(chart);
        EfficiencyFactorTrend.Week last = r.weeks.get(r.weeks.size() - 1);
        addRow(rows, "Week van " + last.monday.getDayOfMonth() + "/" + last.monday.getMonthValue(),
                String.format(Locale.getDefault(), "%.2f", last.ef), last.rides + " rit(ten)");
    }

    // --- #394 -----------------------------------------------------------------------------

    private void renderIndoorOutdoor(ViewGroup rows, IndoorOutdoorPowerCurve.Result r) {
        addHeader(rows, "Vermogenscurve indoor vs buiten (90 dagen)");
        if (!r.isComparable()) {
            addText(rows, "Nodig: zowel MyWhoosh-ritten als buitenritten met vermogen in de "
                    + "laatste 90 dagen.");
            return;
        }
        addText(rows, String.format(Locale.getDefault(), "Beste vermogen per duur uit %d "
                + "MyWhoosh- en %d buitenrit(ten). Negatief = indoor lager.", r.indoorRides,
                r.outdoorRides));
        for (int k = 0; k < PowerCurveAnalyzer.DURATIONS_SEC.length; k++) {
            Double diff = r.differencePct(k);
            addRow(rows, curveLabel(PowerCurveAnalyzer.DURATIONS_SEC[k]),
                    diff != null ? String.format(Locale.getDefault(), "%+.0f %%", diff) : "–",
                    String.format(Locale.getDefault(), "indoor %s, buiten %s",
                            watts(r.indoorWatts[k]), watts(r.outdoorWatts[k])));
        }
    }

    private static String curveLabel(int sec) {
        return sec < 60 ? sec + " s" : sec / 60 + " min";
    }

    private static String watts(int w) {
        return w > 0 ? w + " W" : "–";
    }

    private static String verdict(PowerDecoupling.Rating rating) {
        if (rating == PowerDecoupling.Rating.GOOD) return "goed";
        if (rating == PowerDecoupling.Rating.FAIR) return "matig";
        return "zwak";
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
