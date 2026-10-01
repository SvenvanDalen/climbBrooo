package nl.paree.climbpro.ui.settings;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.R;
import nl.paree.climbpro.data.watch.WatchFieldLayoutStore;
import nl.paree.climbpro.domain.watch.WatchFieldLayout;
import nl.paree.climbpro.service.SyncScheduler;

/**
 * Lets the rider choose what each of the five stat slots on the datafield's "HUIDIGE KLIM"
 * page shows. Saving stores the layout and starts a sync; the layout rides along in the
 * climb payload ('lay'), so a watch that is not connected gets it on the next sync.
 */
public final class WatchFieldLayoutActivity extends AppCompatActivity {

    private final Spinner[] spinners = new Spinner[WatchFieldLayout.SLOT_COUNT];
    private WatchFieldLayoutStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch_field_layout);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        store = new WatchFieldLayoutStore(this);
        String[] labels = new String[WatchFieldLayout.metricCount()];
        for (int c = 0; c < labels.length; c++) labels[c] = WatchFieldLayout.metricLabel(c);

        LinearLayout container = findViewById(R.id.slot_container);
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            TextView title = new TextView(this);
            title.setText(WatchFieldLayout.slotLabel(s));
            title.setTextColor(getColor(R.color.color_text_primary));
            container.addView(title);

            Spinner spinner = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            container.addView(spinner);
            spinners[s] = spinner;
        }
        show(store.load());

        findViewById(R.id.btn_reset_layout).setOnClickListener(v -> show(WatchFieldLayout.defaults()));
        findViewById(R.id.btn_save_layout).setOnClickListener(v -> save());
    }

    private void show(WatchFieldLayout layout) {
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            spinners[s].setSelection(layout.code(s));
        }
    }

    private void save() {
        WatchFieldLayout layout = WatchFieldLayout.defaults();
        for (int s = 0; s < WatchFieldLayout.SLOT_COUNT; s++) {
            layout = layout.withCode(s, spinners[s].getSelectedItemPosition());
        }
        store.save(layout);
        SyncScheduler.triggerImmediateSync(this);
        boolean connected = ((ClimbProApplication) getApplication()).connectIqClient().isConnected();
        Toast.makeText(this, connected
                ? "Opgeslagen — wordt naar het horloge gestuurd"
                : "Opgeslagen — wordt meegestuurd bij de volgende sync",
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
