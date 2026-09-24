package nl.paree.climbpro.ui.privacy;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.privacy.PrivacyCategory;
import nl.paree.climbpro.ui.privacy.PrivacyDashboardViewModel.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Privacy dashboard (issue #264): one card per {@link PrivacyCategory} with what is stored,
 * a "Bekijk" details dialog and a confirmed "Verwijder". Phone-only; no wire-format impact.
 */
public final class PrivacyDashboardActivity extends AppCompatActivity {

    private PrivacyDashboardViewModel viewModel;
    private final Adapter adapter = new Adapter();

    public static Intent intentFor(Context ctx) {
        return new Intent(ctx, PrivacyDashboardActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_dashboard);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(PrivacyDashboardViewModel.class);
        viewModel.rows().observe(this, adapter::submit);
        viewModel.message().observe(this, msg -> {
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
        viewModel.details().observe(this, text -> {
            if (text == null) return;
            viewModel.consumeDetails();
            new AlertDialog.Builder(this)
                    .setMessage(text)
                    .setPositiveButton("Sluiten", null)
                    .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void confirmDelete(PrivacyCategory c) {
        new AlertDialog.Builder(this)
                .setTitle(c.label + " verwijderen?")
                .setMessage(deleteWarning(c))
                .setPositiveButton("Verwijder", (d, w) -> viewModel.delete(c))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private static String deleteWarning(PrivacyCategory c) {
        switch (c) {
            case ROUTES:
                return "Alle routes en hun klimmen, namen en notities worden gewist en uit "
                        + "collecties gehaald. Dit kan niet ongedaan worden gemaakt.";
            case ATTEMPTS:
                return "Alle klimtijden, notities en foto's bij pogingen worden gewist. Al "
                        + "gesynchroniseerde Strava-activiteiten worden niet opnieuw opgehaald.";
            case PLANNING:
                return "Alle geplande klimmen en hun herinneringen worden gewist. Afspraken in "
                        + "je agenda blijven staan.";
            case STRAVA:
                return "Strava wordt ontkoppeld. Routes en pogingen blijven staan.";
            default:
                return "Dit kan niet ongedaan worden gemaakt.";
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        private final List<Row> rows = new ArrayList<>();

        void submit(List<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_privacy_category, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row row = rows.get(position);
            h.title.setText(row.category.label);
            h.description.setText(row.category.description);
            h.summary.setText(row.summary);
            h.view.setOnClickListener(v -> viewModel.showDetails(row.category));
            h.delete.setEnabled(row.hasData);
            h.delete.setOnClickListener(v -> confirmDelete(row.category));
        }

        @Override
        public int getItemCount() { return rows.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title, description, summary;
            final Button view, delete;

            VH(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.title);
                description = v.findViewById(R.id.description);
                summary = v.findViewById(R.id.summary);
                view = v.findViewById(R.id.btn_view);
                delete = v.findViewById(R.id.btn_delete);
            }
        }
    }
}
