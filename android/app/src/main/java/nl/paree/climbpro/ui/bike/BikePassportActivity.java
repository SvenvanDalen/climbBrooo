package nl.paree.climbpro.ui.bike;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.bike.BikePassport;
import nl.paree.climbpro.data.bike.BikePassportPhotoStore;
import nl.paree.climbpro.domain.bike.BikePassportText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #190: "Fietspaspoort" — frame number, brand/model, photos and purchase receipt per bike,
 * shareable in one go (text + attachments) for a police report or insurance claim. Phone-only.
 */
public final class BikePassportActivity extends AppCompatActivity {

    private BikePassportViewModel viewModel;
    private LinearLayout container;
    private TextView empty;
    private static final String STATE_PENDING_ID = "pending_passport_id";

    /** Passport a pending photo pick is for; kept across recreation while the picker is open. */
    private String pendingId;

    private final ActivityResultLauncher<String> photoPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> onPicked(uri, false));
    private final ActivityResultLauncher<String[]> receiptPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> onPicked(uri, true));

    public static Intent intentFor(Context context) {
        return new Intent(context, BikePassportActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bike_passport);
        if (savedInstanceState != null) pendingId = savedInstanceState.getString(STATE_PENDING_ID);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.passports);
        empty = findViewById(R.id.empty);
        findViewById(R.id.addPassportButton).setOnClickListener(v -> showEditDialog(null));

        viewModel = new ViewModelProvider(this).get(BikePassportViewModel.class);
        viewModel.passports().observe(this, this::render);
        viewModel.messages().observe(this, m -> {
            if (m != null) Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        });
        viewModel.load();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_ID, pendingId);
    }

    private void render(List<BikePassport> list) {
        container.removeAllViews();
        empty.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        if (list == null) return;
        for (BikePassport p : list) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
            ((TextView) row.findViewById(R.id.title)).setText(p.name);
            ((TextView) row.findViewById(R.id.value)).setText(BikePassportText.summary(p));
            int photos = p.photoFileNames == null ? 0 : p.photoFileNames.size();
            ((TextView) row.findViewById(R.id.detail)).setText(
                    (photos == 1 ? "1 foto" : photos + " foto's") + " · "
                            + (p.receiptFileName != null ? "aankoopbewijs ✓" : "geen aankoopbewijs"));
            row.setOnClickListener(v -> showActions(p));
            container.addView(row);
        }
    }

    private void showActions(BikePassport p) {
        String[] actions = {"Delen (aangifte/verzekering)", "Bewerken", "Foto toevoegen",
                "Aankoopbewijs toevoegen", "Foto's wissen", "Verwijderen"};
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0: share(p); break;
                        case 1: showEditDialog(p); break;
                        case 2: pendingId = p.id; photoPicker.launch("image/*"); break;
                        case 3:
                            pendingId = p.id;
                            receiptPicker.launch(new String[]{"image/*", "application/pdf"});
                            break;
                        case 4: viewModel.clearPhotos(p.id); break;
                        default: confirmDelete(p); break;
                    }
                })
                .show();
    }

    private void onPicked(Uri uri, boolean receipt) {
        if (uri == null || pendingId == null) return;
        viewModel.addPhoto(pendingId, uri, receipt);
        pendingId = null;
    }

    private void showEditDialog(BikePassport existing) {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_bike_passport, null);
        EditText name = form.findViewById(R.id.input_name);
        EditText brand = form.findViewById(R.id.input_brand);
        EditText model = form.findViewById(R.id.input_model);
        EditText color = form.findViewById(R.id.input_color);
        EditText frame = form.findViewById(R.id.input_frame_number);
        EditText date = form.findViewById(R.id.input_purchase_date);
        EditText price = form.findViewById(R.id.input_purchase_price);
        EditText shop = form.findViewById(R.id.input_shop);
        EditText features = form.findViewById(R.id.input_features);
        if (existing != null) {
            name.setText(existing.name);
            brand.setText(existing.brand);
            model.setText(existing.model);
            color.setText(existing.color);
            frame.setText(existing.frameNumber);
            date.setText(existing.purchaseDate);
            price.setText(existing.purchasePrice);
            shop.setText(existing.shop);
            features.setText(existing.features);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Fiets toevoegen" : "Fiets bewerken")
                .setView(form)
                .setPositiveButton("Opslaan", null)
                .setNegativeButton("Annuleren", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (name.getText().toString().trim().isEmpty()) {
                        name.setError("Naam is verplicht");
                        return;
                    }
                    BikePassport p = existing != null ? existing : new BikePassport();
                    p.name = name.getText().toString();
                    p.brand = brand.getText().toString();
                    p.model = model.getText().toString();
                    p.color = color.getText().toString();
                    p.frameNumber = frame.getText().toString();
                    p.purchaseDate = date.getText().toString();
                    p.purchasePrice = price.getText().toString();
                    p.shop = shop.getText().toString();
                    p.features = features.getText().toString();
                    viewModel.save(p);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void confirmDelete(BikePassport p) {
        new AlertDialog.Builder(this)
                .setTitle("Paspoort verwijderen?")
                .setMessage("\"" + p.name + "\" en alle bijbehorende foto's worden verwijderd.")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.delete(p.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    /** Text + every photo/receipt through the share sheet (mail, messenger, cloud drive). */
    private void share(BikePassport p) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (String f : p.allFileNames()) {
            File file = BikePassportPhotoStore.fileFor(this, f);
            if (file.exists()) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
            }
        }
        String text = BikePassportText.format(p);
        Intent share;
        if (uris.isEmpty()) {
            share = new Intent(Intent.ACTION_SEND).setType("text/plain");
        } else {
            share = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData clip = ClipData.newRawUri(null, uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            share.setClipData(clip);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        share.putExtra(Intent.EXTRA_SUBJECT, "Fietspaspoort: " + p.name);
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, "Deel fietspaspoort"));
    }
}
