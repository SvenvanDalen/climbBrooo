package nl.paree.climbpro.ui.safehome;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.safehome.SafeHomeRepository;
import nl.paree.climbpro.data.safehome.SafeHomeSettings;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.service.SafeHomeWorker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings for the "Ik ben veilig thuis"-bericht (issue #231): contact, message, and whether
 * to send the SMS automatically or offer it as a one-tap notification. The actual check runs
 * in {@link SafeHomeWorker}.
 */
public final class SafeHomeActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SwitchCompat enabled;
    private EditText phone;
    private EditText message;
    private CheckBox autoSms;
    private TextView contactLabel;
    private String contactName;
    /** Number that belonged to {@link #contactName} when it was picked or loaded. */
    private String contactNumber;

    private final ActivityResultLauncher<Intent> pickContact = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    readPickedContact(result.getData().getData());
                }
            });
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                Boolean sms = granted.get(Manifest.permission.SEND_SMS);
                if (Boolean.FALSE.equals(sms)) {
                    Toast.makeText(this, "Zonder sms-toestemming krijg je een melding om het "
                            + "bericht zelf te versturen.", Toast.LENGTH_LONG).show();
                }
            });

    public static Intent intentFor(Context context) {
        return new Intent(context, SafeHomeActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_safe_home);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        enabled = findViewById(R.id.switch_enabled);
        phone = findViewById(R.id.input_phone);
        message = findViewById(R.id.input_message);
        autoSms = findViewById(R.id.check_auto_sms);
        contactLabel = findViewById(R.id.contact_label);
        TextView stravaWarning = findViewById(R.id.strava_warning);

        findViewById(R.id.btn_pick_contact).setOnClickListener(v -> pickContact.launch(
                new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)));
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        io.execute(() -> {
            SafeHomeSettings s = new SafeHomeRepository(this).load();
            boolean strava = new StravaAuthRepository(this).isAuthorised();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                enabled.setChecked(s.enabled);
                contactName = s.contactName;
                contactNumber = s.phoneNumber;
                phone.setText(s.phoneNumber);
                message.setText(s.message);
                autoSms.setChecked(s.autoSms);
                updateContactLabel();
                stravaWarning.setVisibility(strava ? android.view.View.GONE
                                                   : android.view.View.VISIBLE);
            });
        });
    }

    private void readPickedContact(Uri uri) {
        if (uri == null) return;
        String[] cols = {ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(uri, cols, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                phone.setText(c.getString(0));
                contactName = c.getString(1);
                contactNumber = c.getString(0);
                updateContactLabel();
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "Contact kon niet worden gelezen", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateContactLabel() {
        contactLabel.setText(contactName != null ? "Contact: " + contactName : "");
    }

    private void save() {
        String number = phone.getText().toString().trim();
        boolean on = enabled.isChecked();
        if (on && number.isEmpty()) {
            Toast.makeText(this, "Kies eerst een contact of vul een nummer in",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // A typed number that no longer matches the picked contact drops the stale name.
        String name = contactNumber != null && contactNumber.trim().equals(number)
                ? contactName : null;
        String msg = message.getText().toString();
        boolean sms = autoSms.isChecked();
        io.execute(() -> {
            try {
                new SafeHomeRepository(this).save(on, name, number, msg, sms,
                        System.currentTimeMillis() / 1000L);
                SafeHomeWorker.syncSchedule(getApplicationContext());
                runOnUiThread(() -> {
                    Toast.makeText(this, on ? "Veilig thuis-bericht staat aan" : "Uitgezet",
                            Toast.LENGTH_SHORT).show();
                    if (on) requestPermissions(sms);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Opslaan mislukt: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void requestPermissions(boolean sms) {
        List<String> needed = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && missing(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (sms && missing(Manifest.permission.SEND_SMS)) needed.add(Manifest.permission.SEND_SMS);
        if (!needed.isEmpty()) permissions.launch(needed.toArray(new String[0]));
    }

    private boolean missing(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
