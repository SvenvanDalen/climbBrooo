package nl.paree.climbpro.ui.mywhoosh;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.health.HealthConnectGateway;
import nl.paree.climbpro.data.rider.WeightEntry;
import nl.paree.climbpro.data.rider.WeightLogStore;
import nl.paree.climbpro.domain.rider.WeightHistory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weight log (issue #408): weights per day, entered by hand or imported from Health Connect,
 * so the W/kg of an older MyWhoosh attempt uses the weight of that day. Long-press deletes.
 * Phone-only.
 */
public final class WeightLogActivity extends AppCompatActivity {

    /** Health Connect import reaches back this far. */
    private static final int IMPORT_DAYS = 3 * 365;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DateTimeFormatter dateFormat =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy", new Locale("nl"));
    private WeightLogStore store;
    private LinearLayout rows;

    public static Intent intentFor(Context context) {
        return new Intent(context, WeightLogActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mywhoosh);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Gewichtslog");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        store = WeightLogStore.of(this);
        rows = findViewById(R.id.rows);
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void reload() {
        executor.execute(() -> {
            List<WeightEntry> entries = store.loadAll();
            runOnUiThread(() -> render(entries));
        });
    }

    private void render(List<WeightEntry> entries) {
        rows.removeAllViews();
        TextView intro = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        intro.setPadding(pad, pad, pad, pad / 2);
        intro.setTextSize(14);
        intro.setText("De W/kg van een klimpoging gebruikt je gewicht van die dag: de laatste "
                + "meting op of vóór die datum. Zonder metingen geldt het gewicht uit je "
                + "rijdersprofiel. Houd een meting vast om hem te verwijderen.");
        rows.addView(intro);

        Button add = new Button(this);
        add.setText("Gewicht toevoegen");
        add.setOnClickListener(v -> pickDate());
        rows.addView(add);
        Button importHc = new Button(this);
        importHc.setText("Importeren uit Health Connect");
        importHc.setOnClickListener(v -> importFromHealthConnect());
        rows.addView(importHc);

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setPadding(pad, pad, pad, 0);
            empty.setText("Nog geen metingen.");
            rows.addView(empty);
            return;
        }
        for (WeightEntry e : entries) {
            View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, rows, false);
            ((TextView) v.findViewById(R.id.title)).setText(LocalDate.parse(e.date).format(dateFormat));
            ((TextView) v.findViewById(R.id.value)).setText(
                    String.format(Locale.getDefault(), "%.1f kg", e.kg));
            ((TextView) v.findViewById(R.id.detail)).setText(
                    WeightEntry.SOURCE_HEALTH_CONNECT.equals(e.source) ? "Health Connect" : "Handmatig");
            v.setOnLongClickListener(x -> {
                confirmDelete(e);
                return true;
            });
            rows.addView(v);
        }
    }

    private void pickDate() {
        LocalDate today = LocalDate.now();
        new DatePickerDialog(this, (picker, y, m, d) -> askWeight(LocalDate.of(y, m + 1, d)),
                today.getYear(), today.getMonthValue() - 1, today.getDayOfMonth()).show();
    }

    private void askWeight(LocalDate date) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("kg");
        new AlertDialog.Builder(this)
                .setTitle("Gewicht op " + date.format(dateFormat))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    double kg;
                    try {
                        kg = Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
                    } catch (NumberFormatException ex) {
                        kg = 0;
                    }
                    if (!WeightHistory.isPlausible(kg)) {
                        Toast.makeText(this, "Vul een gewicht tussen " + (int) WeightHistory.MIN_KG
                                + " en " + (int) WeightHistory.MAX_KG + " kg in", Toast.LENGTH_LONG).show();
                        return;
                    }
                    double value = kg;
                    executor.execute(() -> {
                        try {
                            store.put(date, value, WeightEntry.SOURCE_MANUAL);
                        } catch (Exception ex) {
                            runOnUiThread(() -> Toast.makeText(this, "Opslaan mislukt",
                                    Toast.LENGTH_LONG).show());
                        }
                        reload();
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(WeightEntry e) {
        new AlertDialog.Builder(this)
                .setMessage("Meting van " + LocalDate.parse(e.date).format(dateFormat) + " verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> executor.execute(() -> {
                    try {
                        store.delete(e.date);
                    } catch (Exception ignored) {
                        // Reload shows whatever is stored.
                    }
                    reload();
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void importFromHealthConnect() {
        executor.execute(() -> {
            String message;
            try {
                HealthConnectGateway gateway = new HealthConnectGateway(this);
                if (gateway.availability() != HealthConnectGateway.Availability.AVAILABLE) {
                    message = "Health Connect is niet beschikbaar op deze telefoon";
                } else {
                    Map<LocalDate, Double> weights = gateway.weightsByDay(IMPORT_DAYS);
                    if (weights.isEmpty()) {
                        message = "Geen gewicht gevonden. Geef ClimbPro via Instellingen → "
                                + "Health Connect leestoegang tot je gewicht.";
                    } else {
                        int n = store.mergeImported(weights);
                        message = n + " meting(en) geïmporteerd; handmatige metingen blijven staan";
                    }
                }
            } catch (Exception e) {
                message = "Importeren mislukt: " + e.getClass().getSimpleName();
            }
            String shown = message;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
            reload();
        });
    }
}
