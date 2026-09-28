package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.ride.ZoneCalculator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Zonetijd" screen (issue #218): time per heart-rate and power zone for the last four weeks,
 * then per ride, newest first. The max heart rate can be set here; until then the highest
 * heart rate held for 30 s in any ride is used. Power zones use the FTP from the settings.
 * Values come from the ride stream analysis. Phone-only.
 */
public final class ZoneDistributionActivity extends AppCompatActivity {

    /** Rows shown; older rides are still analyzed. */
    private static final int MAX_ROWS = 40;
    /** Plausible max heart rates; anything else is a typo. */
    private static final int MIN_MAX_HR = 120;
    private static final int MAX_MAX_HR = 230;

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", new Locale("nl"));

    private ZoneDistributionViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, ZoneDistributionActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zone_distribution);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView empty = findViewById(R.id.empty);
        TextView thresholds = findViewById(R.id.thresholds);
        LinearLayout rows = findViewById(R.id.rows);
        EditText maxHrInput = findViewById(R.id.input_max_hr);

        viewModel = new ViewModelProvider(this).get(ZoneDistributionViewModel.class);
        findViewById(R.id.btn_save_max_hr).setOnClickListener(v -> {
            String text = maxHrInput.getText().toString().trim();
            int bpm;
            try {
                bpm = text.isEmpty() ? 0 : Integer.parseInt(text);
            } catch (NumberFormatException e) {
                bpm = -1;
            }
            if (bpm != 0 && (bpm < MIN_MAX_HR || bpm > MAX_MAX_HR)) {
                Toast.makeText(this, "Vul een maximale hartslag tussen " + MIN_MAX_HR + " en "
                        + MAX_MAX_HR + " in, of laat het leeg.", Toast.LENGTH_LONG).show();
                return;
            }
            viewModel.setMaxHeartRate(bpm);
        });

        viewModel.state().observe(this, st -> {
            if (st.maxHrIsSet && maxHrInput.getText().length() == 0) {
                maxHrInput.setText(String.valueOf(st.maxHr));
            }
            thresholds.setText(thresholdText(st));

            rows.removeAllViews();
            ZoneCalculator.Result r = st.result;
            String pending = st.ridesAwaitingAnalysis > 0
                    ? " Nog " + st.ridesAwaitingAnalysis + " rit(ten) te analyseren; dat gebeurt "
                    + "in stappen bij elke sync." : "";
            if (r.entries.isEmpty()) {
                empty.setVisibility(View.VISIBLE);
                empty.setText(st.archivedRides == 0
                        ? "Nog geen ritten in het archief. Open Ritten en tik op Ophalen."
                        : "Nog geen rit met hartslag- of vermogensdata." + pending);
                return;
            }
            empty.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
            empty.setText(pending.trim());
            addRow(rows, "Laatste " + ZoneCalculator.TOTAL_WINDOW_DAYS / 7 + " weken", null,
                    r.recentHrZones, r.recentPowerZones);
            int shown = Math.min(MAX_ROWS, r.entries.size());
            for (int i = 0; i < shown; i++) {
                ZoneCalculator.Entry e = r.entries.get(i);
                String date = e.ride.startEpochSec > 0
                        ? dateFormat.format(new Date(e.ride.startEpochSec * 1000L))
                        : "onbekende datum";
                String name = e.ride.name != null && !e.ride.name.isEmpty() ? e.ride.name : "Rit";
                addRow(rows, date, name, e.hrZones, e.powerZones);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private static String thresholdText(ZoneDistributionViewModel.State st) {
        String hr;
        if (st.maxHr <= 0) {
            hr = "Hartslagzones: vul je maximale hartslag in.";
        } else if (st.maxHrIsSet) {
            hr = "Hartslagzones op basis van " + st.maxHr + " bpm.";
        } else {
            hr = "Hartslagzones op basis van " + st.maxHr + " bpm, de hoogste hartslag die je "
                    + "30 s volhield. Vul je echte maximum in als je dat weet.";
        }
        String power = st.ftp > 0
                ? "Vermogenszones op basis van FTP " + st.ftp + " W."
                : "Vermogenszones: vul je FTP in bij Instellingen.";
        return hr + "\n" + power;
    }

    private void addRow(ViewGroup container, String title, String subtitle, int[] hr,
                        int[] power) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_zone_ride, container, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        TextView sub = v.findViewById(R.id.subtitle);
        sub.setText(subtitle);
        sub.setVisibility(subtitle != null ? View.VISIBLE : View.GONE);
        bindZones(v.findViewById(R.id.hr_bar), v.findViewById(R.id.hr_text), hr,
                ZoneBarView.HR_COLORS, "Hartslag");
        bindZones(v.findViewById(R.id.power_bar), v.findViewById(R.id.power_text), power,
                ZoneBarView.POWER_COLORS, "Vermogen");
        container.addView(v);
    }

    private static void bindZones(ZoneBarView bar, TextView text, int[] zones, int[] colors,
                                  String label) {
        if (zones == null) {
            bar.setVisibility(View.GONE);
            text.setText(label + ": –");
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setZones(zones, colors);
        text.setText(label + " (" + duration(ZoneCalculator.total(zones)) + "): "
                + zoneSummary(zones));
    }

    /** "Z1 12 % · Z2 40 % · ...", leaving out empty zones. */
    static String zoneSummary(int[] zones) {
        int total = ZoneCalculator.total(zones);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < zones.length; k++) {
            if (zones[k] <= 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.getDefault(), "Z%d %d %%", k + 1,
                    Math.round(100.0 * zones[k] / total)));
        }
        return sb.toString();
    }

    private static String duration(int sec) {
        int min = Math.round(sec / 60f);
        return String.format(Locale.getDefault(), "%d:%02d u", min / 60, min % 60);
    }
}
