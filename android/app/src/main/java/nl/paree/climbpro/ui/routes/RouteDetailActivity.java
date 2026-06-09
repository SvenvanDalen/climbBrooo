package nl.paree.climbpro.ui.routes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;

import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.databinding.ActivityRouteDetailBinding;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;

import java.util.ArrayList;
import java.util.List;

public final class RouteDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";

    private ActivityRouteDetailBinding binding;
    private RouteDetailViewModel        viewModel;
    private RouteDetailAdapter          adapter;
    private String                      routeId;

    public static Intent intentFor(Context ctx, String routeId) {
        Intent i = new Intent(ctx, RouteDetailActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding   = ActivityRouteDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK);
        binding.mapView.setMultiTouchControls(true);
        binding.mapView.getController().setZoom(13.0);

        routeId   = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        viewModel = new ViewModelProvider(this).get(RouteDetailViewModel.class);
        adapter   = new RouteDetailAdapter();

        binding.climbsRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.climbsRecycler.setAdapter(adapter);

        adapter.setOnClimbClickListener((climb, index) ->
                startActivity(ClimbDetailActivity.intentFor(this, routeId, index)));
        adapter.setOnFlatClickListener(this::zoomToFlat);
        adapter.setOnFlatLongClickListener(this::showFlatSurfaceDialog);

        viewModel.route().observe(this, route -> {
            if (route == null) return;
            String name = route.userDisplayName != null ? route.userDisplayName : route.name;
            binding.toolbar.setTitle(name != null ? name : route.routeId);
            binding.notesEdit.setText(route.notes != null ? route.notes : "");
            drawRoute(route);
        });

        viewModel.routeItems().observe(this, items -> adapter.setItems(items));

        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, ok -> {
            if (Boolean.TRUE.equals(ok)) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });

        binding.btnRename.setOnClickListener(v -> showRenameDialog());
        binding.btnSaveNotes.setOnClickListener(v ->
                viewModel.saveNotes(routeId, binding.notesEdit.getText().toString()));
        binding.btnSelectRoute.setOnClickListener(v -> {
            viewModel.setActiveRoute(routeId);
            Toast.makeText(this, "Route selected for watch", Toast.LENGTH_SHORT).show();
        });
        binding.btnShareToGarmin.setOnClickListener(v -> shareToGarminConnect());

        viewModel.loadRoute(routeId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.mapView.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void drawRoute(StoredRoute route) {
        if (route.lats == null || route.lons == null
                || route.lats.length == 0 || route.lons.length < route.lats.length) return;

        List<GeoPoint> points = new ArrayList<>(route.lats.length);
        for (int i = 0; i < route.lats.length; i++) {
            points.add(new GeoPoint(route.lats[i], route.lons[i]));
        }

        Polyline polyline = new Polyline();
        polyline.setColor(Color.BLUE);
        polyline.setWidth(5f);
        polyline.setPoints(points);

        binding.mapView.getOverlays().clear();
        binding.mapView.getOverlays().add(polyline);

        BoundingBox box = BoundingBox.fromGeoPoints(points);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 50));
        binding.mapView.invalidate();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setHint("New route name");
        input.setText(binding.toolbar.getTitle());
        new AlertDialog.Builder(this)
                .setTitle("Rename route")
                .setView(input)
                .setPositiveButton("Save", (d, w) ->
                        viewModel.renameRoute(routeId, input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void zoomToFlat(StoredFlatSegment flat) {
        if (Double.isNaN(flat.startLat) || Double.isNaN(flat.endLat)) return;
        List<GeoPoint> pts = new ArrayList<>();
        pts.add(new GeoPoint(flat.startLat, flat.startLon));
        pts.add(new GeoPoint(flat.endLat,   flat.endLon));
        BoundingBox box = BoundingBox.fromGeoPoints(pts);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 80));
    }

    private void showFlatSurfaceDialog(StoredFlatSegment flat) {
        String[] typeLabels = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "Onbekend"};
        int current = SurfaceType.fromInt(flat.surfaceType);
        new AlertDialog.Builder(this)
                .setTitle("Oppervlak voor vlak segment")
                .setSingleChoiceItems(typeLabels, current, null)
                .setPositiveButton("Opslaan", (dialog, which) -> {
                    android.widget.ListView lv = ((AlertDialog) dialog).getListView();
                    int chosen = lv.getCheckedItemPosition();
                    if (chosen >= 0 && chosen <= 5) {
                        viewModel.setFlatSegmentSurface(routeId, flat.startDistance, chosen);
                    }
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private void shareToGarminConnect() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/gpx+xml");
        share.putExtra(Intent.EXTRA_SUBJECT, "ClimbPro route");
        share.setPackage("com.garmin.android.apps.connectmobile");
        if (getPackageManager().resolveActivity(share, 0) == null) {
            share.setPackage(null);
        }
        startActivity(Intent.createChooser(share, "Open in Garmin Connect"));
    }
}
