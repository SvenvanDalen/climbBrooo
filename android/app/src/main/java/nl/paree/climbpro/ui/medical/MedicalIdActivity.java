package nl.paree.climbpro.ui.medical;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.R;
import nl.paree.climbpro.data.medical.MedicalId;
import nl.paree.climbpro.data.medical.MedicalIdRepository;
import nl.paree.climbpro.service.MedicalIdNotifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Medical ID (issue #230): blood type, allergies, medication and emergency contact. Saved on
 * the phone, optionally shown on the lock screen ({@link MedicalIdNotifier}) and pushed to the
 * watch widget as a MEDICAL_ID message (also re-sent whenever the widget asks for the route
 * list, so an offline save reaches the watch later).
 */
public final class MedicalIdActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private EditText name;
    private EditText bloodType;
    private EditText allergies;
    private EditText medication;
    private EditText contactName;
    private EditText contactPhone;
    private EditText notes;
    private SwitchCompat lockscreen;
    private TextView watchStatus;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    io.execute(() -> MedicalIdNotifier.refresh(this));
                } else {
                    Toast.makeText(this, "Zonder meldingen-toestemming verschijnt de medische ID "
                            + "niet op het vergrendelscherm.", Toast.LENGTH_LONG).show();
                }
            });

    public static Intent intentFor(Context context) {
        return new Intent(context, MedicalIdActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_medical_id);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        name = findViewById(R.id.input_name);
        bloodType = findViewById(R.id.input_blood_type);
        allergies = findViewById(R.id.input_allergies);
        medication = findViewById(R.id.input_medication);
        contactName = findViewById(R.id.input_contact_name);
        contactPhone = findViewById(R.id.input_contact_phone);
        notes = findViewById(R.id.input_notes);
        lockscreen = findViewById(R.id.switch_lockscreen);
        watchStatus = findViewById(R.id.watch_status);
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        io.execute(() -> {
            MedicalId id = new MedicalIdRepository(this).load();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                name.setText(id.name);
                bloodType.setText(id.bloodType);
                allergies.setText(id.allergies);
                medication.setText(id.medication);
                contactName.setText(id.contactName);
                contactPhone.setText(id.contactPhone);
                notes.setText(id.notes);
                lockscreen.setChecked(id.showOnLockscreen);
            });
        });
    }

    private void save() {
        MedicalId id = new MedicalId();
        id.name = text(name);
        id.bloodType = text(bloodType);
        id.allergies = text(allergies);
        id.medication = text(medication);
        id.contactName = text(contactName);
        id.contactPhone = text(contactPhone);
        id.notes = text(notes);
        id.showOnLockscreen = lockscreen.isChecked();
        if (id.showOnLockscreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        io.execute(() -> {
            try {
                new MedicalIdRepository(this).save(id);
            } catch (java.io.IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Opslaan mislukt",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            MedicalIdNotifier.refresh(this);
            boolean sent = ((ClimbProApplication) getApplication()).connectIqClient()
                    .sendMessage(id.toWatchMessage());
            runOnUiThread(() -> {
                if (isFinishing()) return;
                Toast.makeText(this, "Opgeslagen", Toast.LENGTH_SHORT).show();
                // A widget that isn't open drops the message, so this is best effort; the
                // widget gets it again whenever it asks for the route list.
                watchStatus.setText(sent
                        ? "Naar het horloge gestuurd. Staat de ClimbPro-widget niet open, dan "
                                + "haalt hij hem op zodra je hem opent."
                        : "Horloge niet verbonden: de ClimbPro-widget haalt hem op zodra je hem "
                                + "opent met de telefoon in de buurt.");
            });
        });
    }

    private static String text(EditText e) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? null : s;
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
