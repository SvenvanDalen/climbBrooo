package nl.paree.climbpro.ui.routes;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.databinding.ActivityRouteListBinding;
import nl.paree.climbpro.domain.route.GpxParseException;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.RouteSimplifier;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.ui.settings.SettingsActivity;
import nl.paree.climbpro.ui.strava.StravaAuthActivity;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteListActivity extends AppCompatActivity {

    private ActivityRouteListBinding binding;
    private RouteListViewModel viewModel;
    private RouteListAdapter adapter;
    private final ExecutorService    executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> gpxPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importGpx(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding   = ActivityRouteListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        viewModel = new ViewModelProvider(this).get(RouteListViewModel.class);
        adapter   = new RouteListAdapter();

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        adapter.setListener(new RouteListAdapter.OnRouteClickListener() {
            @Override
            public void onRouteClick(nl.paree.climbpro.data.route.RouteCatalogEntry entry) {
                startActivity(RouteDetailActivity.intentFor(RouteListActivity.this, entry.routeId));
            }
            @Override
            public void onRouteLongClick(nl.paree.climbpro.data.route.RouteCatalogEntry entry) {
                String name = entry.userDisplayName != null ? entry.userDisplayName : entry.name;
                new AlertDialog.Builder(RouteListActivity.this)
                        .setTitle("Delete \"" + name + "\"?")
                        .setMessage("This cannot be undone.")
                        .setPositiveButton("Delete", (d, w) -> viewModel.deleteRoute(entry.routeId))
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        viewModel.routes().observe(this, adapter::setItems);
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        binding.chipAll.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(-1);
        });
        binding.chipAsphalt.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.ASPHALT);
        });
        binding.chipGravel.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.GRAVEL);
        });
        binding.chipDirt.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.DIRT);
        });
        binding.chipCobblestone.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.COBBLESTONE);
        });
        binding.chipMixed.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) viewModel.setSurfaceFilter(SurfaceType.MIXED);
        });

        binding.fab.setOnClickListener(v -> showImportDialog());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.route_list_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sync) {
            viewModel.triggerSync();
            Toast.makeText(this, "Sync started", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_strava) {
            startActivity(new Intent(this, StravaAuthActivity.class));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.loadRoutes();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void showImportDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Add route")
                .setItems(new String[]{"Import GPX file", "Sync from Strava"}, (d, which) -> {
                    if (which == 0) {
                        gpxPicker.launch(new String[]{"*/*"});
                    } else {
                        if (viewModel.isSignedInToStrava()) {
                            viewModel.triggerSync();
                            Toast.makeText(this, "Strava sync started", Toast.LENGTH_SHORT).show();
                        } else {
                            startActivity(new Intent(this, StravaAuthActivity.class));
                        }
                    }
                }).show();
    }

    private void importGpx(android.net.Uri uri) {
        executor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open file");
                byte[] bytes = readStream(in);

                List<RoutePoint> raw       = GpxParser.parse(new java.io.ByteArrayInputStream(bytes));
                List<RoutePoint> withDist  = CumulativeDistance.compute(raw);
                List<RoutePoint> smoothed  = ElevationSmoother.smooth(withDist, 5);
                List<RoutePoint> simple    = RouteSimplifier.simplify(smoothed, 5.0);
                List<Climb>      climbs    = ClimbDetector.detect(simple);
                int detectedSurface = nl.paree.climbpro.domain.segment.SurfaceTypeDetector
                        .detectFromGpxBytes(bytes);

                String routeId = "gpx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                StoredRoute stored = new StoredRoute();
                stored.routeId      = routeId;
                stored.name         = uri.getLastPathSegment();
                stored.importedAtMs = System.currentTimeMillis();
                stored.sourceHash   = sha256(bytes);

                new RouteRepository(this).saveRoute(stored, simple, climbs);
                if (detectedSurface != SurfaceType.UNKNOWN) {
                    RouteRepository repo = new RouteRepository(this);
                    try {
                        StoredRoute saved = repo.loadRoute(routeId);
                        if (saved.climbs != null) {
                            for (int ci = 0; ci < saved.climbs.size(); ci++) {
                                repo.setBulkClimbSurfaceType(routeId, ci, detectedSurface);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                runOnUiThread(() -> {
                    viewModel.loadRoutes();
                    Toast.makeText(this,
                            "Imported: " + climbs.size() + " climb(s) detected",
                            Toast.LENGTH_LONG).show();
                });
            } catch (GpxParseException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "GPX error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static byte[] readStream(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }
}
