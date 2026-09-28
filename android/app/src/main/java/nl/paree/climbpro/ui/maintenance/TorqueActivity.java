package nl.paree.climbpro.ui.maintenance;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.maintenance.TorqueValue;
import nl.paree.climbpro.domain.maintenance.TorqueReference;

import java.util.ArrayList;
import java.util.List;

/**
 * "Aanhaalmomenten" (issue #237): a static table of typical torques per part plus the rider's
 * own values, each optionally labelled with a bike. Phone-only and offline.
 */
public final class TorqueActivity extends AppCompatActivity {

    private TorqueViewModel viewModel;
    private LinearLayout customList;
    private TextView emptyCustom;
    private List<TorqueValue> current = new ArrayList<>();

    public static Intent intentFor(Context context) {
        return new Intent(context, TorqueActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_torque);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.disclaimer)).setText(TorqueReference.DISCLAIMER);
        customList = findViewById(R.id.custom_list);
        emptyCustom = findViewById(R.id.empty_custom);

        LinearLayout referenceList = findViewById(R.id.reference_list);
        for (TorqueReference.Spec spec : TorqueReference.all()) {
            View row = addRow(referenceList, spec.part, null, spec.rangeLabel());
            row.setOnClickListener(v -> showEditDialog(null, spec));
        }

        findViewById(R.id.btn_add).setOnClickListener(v -> showEditDialog(null, null));

        viewModel = new ViewModelProvider(this).get(TorqueViewModel.class);
        viewModel.values().observe(this, this::showCustom);
        viewModel.message().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.load();
    }

    private void showCustom(List<TorqueValue> values) {
        current = values != null ? values : new ArrayList<>();
        customList.removeAllViews();
        for (TorqueValue tv : current) {
            View row = addRow(customList, TorqueReference.label(tv), tv.note,
                    TorqueReference.formatNm(tv.nm) + " Nm");
            row.setOnClickListener(v -> showEditDialog(tv, null));
        }
        emptyCustom.setVisibility(current.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View addRow(LinearLayout parent, String title, String detail, String value) {
        View row = getLayoutInflater().inflate(R.layout.item_torque_value, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView detailView = row.findViewById(R.id.detail);
        boolean hasDetail = detail != null && !detail.isEmpty();
        detailView.setText(hasDetail ? detail : "");
        detailView.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
        ((TextView) row.findViewById(R.id.value)).setText(value);
        parent.addView(row);
        return row;
    }

    /**
     * Add ({@code existing == null}) or edit a value. {@code template} pre-fills the part from
     * a reference row and shows its typical range as the Nm hint.
     */
    private void showEditDialog(TorqueValue existing, TorqueReference.Spec template) {
        View view = getLayoutInflater().inflate(R.layout.dialog_torque_value, null);
        AutoCompleteTextView bike = view.findViewById(R.id.input_bike);
        EditText part = view.findViewById(R.id.input_part);
        EditText nm = view.findViewById(R.id.input_nm);
        EditText note = view.findViewById(R.id.input_note);

        bike.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                TorqueReference.bikeLabels(current)));

        if (existing != null) {
            bike.setText(existing.bike != null ? existing.bike : "");
            part.setText(existing.part);
            nm.setText(TorqueReference.formatNm(existing.nm));
            note.setText(existing.note != null ? existing.note : "");
        } else if (template != null) {
            part.setText(template.part);
            nm.setHint("Aanhaalmoment in Nm (gangbaar " + template.rangeLabel() + ")");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Waarde bewerken" : "Eigen waarde toevoegen")
                .setView(view)
                // Listener set in onShow so an invalid Nm keeps the dialog open.
                .setPositiveButton("Opslaan", null)
                .setNegativeButton("Annuleren", null);
        if (existing != null) {
            builder.setNeutralButton("Verwijderen", (d, w) -> confirmDelete(existing));
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(di -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    double value = TorqueReference.parseNm(nm.getText().toString());
                    if (Double.isNaN(value)) {
                        nm.setError("Vul een waarde in tussen 0,1 en 200 Nm");
                        return;
                    }
                    viewModel.save(existing != null ? existing.id : null,
                            bike.getText().toString(), part.getText().toString(), value,
                            note.getText().toString());
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void confirmDelete(TorqueValue v) {
        new AlertDialog.Builder(this)
                .setTitle(TorqueReference.label(v) + " verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.delete(v.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }
}
