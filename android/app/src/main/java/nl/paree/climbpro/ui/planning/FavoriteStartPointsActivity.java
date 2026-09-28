package nl.paree.climbpro.ui.planning;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.FavoriteStartPoint;
import nl.paree.climbpro.data.planning.FavoriteStartPointStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Favoriete startpunten (issue #206): manage saved start points (home, work, a parking spot)
 * that the planning screens offer as start. Phone-only; nothing reaches the watch. All file IO
 * goes through {@link FavoriteStartPointStore} on one background thread.
 */
public final class FavoriteStartPointsActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FavoriteStartPointStore store;
    private ArrayAdapter<String> adapter;
    private TextView empty;
    private List<FavoriteStartPoint> current = new ArrayList<>();

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (hasLocationPermission()) {
                            addFromLocation();
                        } else {
                            toast(getString(R.string.fav_start_no_permission));
                            showAddDialog(null, null);
                        }
                    });

    public static Intent intentFor(Context context) {
        return new Intent(context, FavoriteStartPointsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorite_start_points);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        store = new FavoriteStartPointStore(
                new File(getFilesDir(), FavoriteStartPointStore.FILE_NAME));
        empty = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < current.size()) showActions(current.get(position));
        });

        findViewById(R.id.addLocationButton).setOnClickListener(v -> requestLocation());
        findViewById(R.id.addCoordinatesButton).setOnClickListener(v -> showAddDialog(null, null));
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void reload() {
        executor.execute(this::renderFromStore);
    }

    /** Runs on the executor: reads the store and renders on the UI thread. */
    private void renderFromStore() {
        List<FavoriteStartPoint> all = store.loadAll();
        runOnUiThread(() -> render(all));
    }

    private void render(List<FavoriteStartPoint> all) {
        if (isFinishing()) return;
        current = all;
        List<String> rows = new ArrayList<>();
        for (FavoriteStartPoint p : all) {
            rows.add(getString(R.string.fav_start_row, p.name, p.lat, p.lon));
        }
        adapter.clear();
        adapter.addAll(rows);
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (hasLocationPermission()) {
            addFromLocation();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    /** Uses the cached fix (same age rules as the hoogtemeter-doel screen), then asks a name. */
    private void addFromLocation() {
        executor.execute(() -> {
            Location fix = LastKnownLocation.freshest(this);
            String label = fix == null ? null : ElevationTargetViewModel.startLabelForFixAge(
                    (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000L);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (label == null) {
                    toast(getString(R.string.fav_start_no_location));
                    showAddDialog(null, null);
                    return;
                }
                showAddDialog(new double[]{fix.getLatitude(), fix.getLongitude()}, label);
            });
        });
    }

    /**
     * Name (+ coordinates when {@code fixed} is null) dialog. With a location fix the
     * coordinates are shown read-only in the message so the rider can check them.
     */
    private void showAddDialog(double[] fixed, String fixLabel) {
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.fav_start_name_hint);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(nameInput);
        EditText coordInput = null;
        if (fixed == null) {
            coordInput = new EditText(this);
            coordInput.setHint(R.string.fav_start_coordinates_hint);
            coordInput.setInputType(InputType.TYPE_CLASS_TEXT);
            form.addView(coordInput);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.fav_start_new_title)
                .setView(form)
                .setPositiveButton(R.string.fav_start_save, null)
                .setNegativeButton(R.string.fav_start_cancel, null);
        if (fixed != null) {
            builder.setMessage(getString(R.string.fav_start_location_message,
                    fixLabel, fixed[0], fixed[1]));
        }
        AlertDialog dialog = builder.create();
        EditText coords = coordInput;
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        nameInput.setError(getString(R.string.fav_start_name_required));
                        return;
                    }
                    double[] latLon = fixed;
                    if (latLon == null) {
                        latLon = FavoriteStartPoint.parseCoordinates(coords.getText().toString());
                        if (latLon == null) {
                            coords.setError(getString(R.string.fav_start_coordinates_invalid));
                            return;
                        }
                    }
                    save(name, latLon[0], latLon[1]);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void save(String name, double lat, double lon) {
        executor.execute(() -> {
            try {
                FavoriteStartPoint p = store.add(name, lat, lon);
                runOnUiThread(() -> toast(getString(R.string.fav_start_saved, p.name)));
            } catch (Exception e) {
                runOnUiThread(() -> toast(getString(R.string.fav_start_save_failed, e.getMessage())));
            }
            renderFromStore();
        });
    }

    private void showActions(FavoriteStartPoint p) {
        String[] actions = {getString(R.string.fav_start_rename), getString(R.string.fav_start_delete)};
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) showRename(p);
                    else confirmDelete(p);
                })
                .show();
    }

    private void showRename(FavoriteStartPoint p) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(p.name);
        input.setSelection(input.getText().length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.fav_start_rename)
                .setView(input)
                .setPositiveButton(R.string.fav_start_save, null)
                .setNegativeButton(R.string.fav_start_cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError(getString(R.string.fav_start_name_required));
                        return;
                    }
                    dialog.dismiss();
                    mutate(() -> store.rename(p.id, name));
                }));
        dialog.show();
    }

    private void confirmDelete(FavoriteStartPoint p) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.fav_start_delete_confirm, p.name))
                .setPositiveButton(R.string.fav_start_delete, (d, w) -> mutate(() -> store.delete(p.id)))
                .setNegativeButton(R.string.fav_start_cancel, null)
                .show();
    }

    private interface StoreAction { void run() throws Exception; }

    private void mutate(StoreAction action) {
        executor.execute(() -> {
            try {
                action.run();
            } catch (Exception e) {
                runOnUiThread(() -> toast(getString(R.string.fav_start_save_failed, e.getMessage())));
            }
            renderFromStore();
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
