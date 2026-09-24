package nl.paree.climbpro.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import nl.paree.climbpro.data.activity.ActivityFileImporter;
import nl.paree.climbpro.ui.climbs.ClimbLogbookActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Garmin Connect ride import (issue #253). Reached three ways: sharing a FIT/GPX/zip from
 * Garmin Connect or a file manager to ClimbPro, opening such a file with ClimbPro, or the
 * logbook's "Importeer Garmin-rit" button (file picker, {@link #EXTRA_PICK}). Shows one
 * summary dialog and closes. Translucent — only the dialog is visible.
 */
public final class ActivityImportActivity extends AppCompatActivity {

    public static final String EXTRA_PICK = "pick";
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) {
                    finish();
                } else {
                    importAll(uris);
                }
            });

    public static Intent pickIntent(Context ctx) {
        return new Intent(ctx, ActivityImportActivity.class).putExtra(EXTRA_PICK, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // picker/import already running
        Intent in = getIntent();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            uris.add(in.getData());
        }
        if (!uris.isEmpty()) {
            importAll(uris);
        } else if (in.getBooleanExtra(EXTRA_PICK, false)) {
            picker.launch(new String[]{"*/*"});
        } else {
            finish();
        }
    }

    private void importAll(List<Uri> uris) {
        executor.execute(() -> {
            ActivityFileImporter importer = new ActivityFileImporter(this);
            int activities = 0, added = 0, known = 0;
            List<String> errors = new ArrayList<>();
            for (Uri uri : uris) {
                String name = displayName(uri);
                try {
                    ActivityFileImporter.Result r = importer.importFile(name, read(uri));
                    activities += r.activities;
                    added += r.newAttempts;
                    known += r.alreadyKnown;
                } catch (Exception e) {
                    errors.add(name + ": " + e.getMessage());
                }
            }
            StringBuilder msg = new StringBuilder();
            if (activities > 0) {
                msg.append(activities).append(" rit(ten) gelezen\n")
                   .append(added).append(" nieuwe klimpoging(en) in je logboek");
                if (known > 0) msg.append("\n").append(known).append(" al bekend (bijv. via Strava)");
            }
            for (String e : errors) msg.append(msg.length() > 0 ? "\n\n" : "").append(e);
            boolean anyAdded = added > 0;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Garmin-rit importeren")
                    .setMessage(msg.toString())
                    .setPositiveButton(anyAdded ? "Naar logboek" : "OK", (d, w) -> {
                        if (anyAdded) {
                            startActivity(new Intent(this, ClimbLogbookActivity.class));
                        }
                    })
                    .setOnDismissListener(d -> finish())
                    .show());
        });
    }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Kan bestand niet openen");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
                if (buf.size() > MAX_FILE_BYTES) throw new IOException("Bestand is te groot");
            }
            return buf.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
            // fall back to the last path segment
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "bestand";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
