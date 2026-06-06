package nl.paree.climbpro.ui.climbs;

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

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.databinding.ActivityClimbDetailBinding;

import java.util.ArrayList;
import java.util.List;

public final class ClimbDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID   = "route_id";
    private static final String EXTRA_CLIMB_INDEX = "climb_index";

    private ActivityClimbDetailBinding binding;
    private ClimbDetailViewModel viewModel;
    private ClimbSegmentAdapter  adapter;
    private String routeId;
    private int    climbIndex;

    private StoredRoute loadedRoute;
    private StoredClimb loadedClimb;

    public static Intent intentFor(Context ctx, String routeId, int climbIndex) {
        Intent i = new Intent(ctx, ClimbDetailActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        i.putExtra(EXTRA_CLIMB_INDEX, climbIndex);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityClimbDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK);
        binding.mapView.setMultiTouchControls(true);
        binding.mapView.getController().setZoom(14.0);

        routeId    = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        climbIndex = getIntent().getIntExtra(EXTRA_CLIMB_INDEX, 0);

        viewModel = new ViewModelProvider(this).get(ClimbDetailViewModel.class);
        adapter   = new ClimbSegmentAdapter();

        binding.segmentsRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.segmentsRecycler.setAdapter(adapter);

        viewModel.route().observe(this, route -> {
            loadedRoute = route;
            tryDrawMap();
        });

        viewModel.climb().observe(this, climb -> {
            if (climb == null) return;

            String name = climb.userDisplayName != null ? climb.userDisplayName : climb.name;
            binding.toolbar.setTitle(name != null ? name : "Climb " + (climbIndex + 1));
            binding.climbStats.setText(String.format(
                    "%d m total · %.1f%% avg gradient · %d m elevation gain",
                    climb.length, climb.avgGradient * 100, climb.elevationGain));
            binding.climbProfile.setSegments(climb.segments);
            adapter.setItems(climb.segments);

            loadedClimb = climb;
            tryDrawMap();
        });

        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, isSaved -> {
            if (Boolean.TRUE.equals(isSaved))
                Toast.makeText(this, "Opgeslagen", Toast.LENGTH_SHORT).show();
        });

        binding.btnRenameClimb.setOnClickListener(v -> showRenameDialog());
        binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());

        viewModel.loadClimb(routeId, climbIndex);
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

    private void tryDrawMap() {
        if (loadedRoute == null || loadedClimb == null) return;
        if (loadedRoute.lats == null || loadedRoute.lons == null
                || loadedRoute.lats.length == 0
                || loadedRoute.lons.length < loadedRoute.lats.length) return;

        // Full route — gray background
        List<GeoPoint> allPoints = new ArrayList<>(loadedRoute.lats.length);
        for (int i = 0; i < loadedRoute.lats.length; i++) {
            allPoints.add(new GeoPoint(loadedRoute.lats[i], loadedRoute.lons[i]));
        }
        Polyline routeLine = new Polyline();
        routeLine.setColor(Color.GRAY);
        routeLine.setWidth(4f);
        routeLine.setPoints(allPoints);

        // Climb segment — orange highlight
        List<GeoPoint> climbPoints = new ArrayList<>();
        for (int i = 0; i < loadedRoute.distances.length; i++) {
            if (loadedRoute.distances[i] >= loadedClimb.startDistance
                    && loadedRoute.distances[i] <= loadedClimb.endDistance) {
                climbPoints.add(new GeoPoint(loadedRoute.lats[i], loadedRoute.lons[i]));
            }
        }
        Polyline climbLine = new Polyline();
        climbLine.setColor(Color.parseColor("#FF8C00")); // orange
        climbLine.setWidth(7f);
        climbLine.setPoints(climbPoints);

        binding.mapView.getOverlays().clear();
        binding.mapView.getOverlays().add(routeLine);
        binding.mapView.getOverlays().add(climbLine);

        List<GeoPoint> zoomTarget = climbPoints.isEmpty() ? allPoints : climbPoints;
        BoundingBox box = BoundingBox.fromGeoPoints(zoomTarget);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 80));
        binding.mapView.invalidate();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setHint("Climb name");
        new AlertDialog.Builder(this)
                .setTitle("Rename climb")
                .setView(input)
                .setPositiveButton("Save", (d, w) ->
                        viewModel.renameClimb(routeId, climbIndex,
                                input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReSegmentDialog() {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(4);
        picker.setMaxValue(32);
        nl.paree.climbpro.data.route.StoredClimb current = viewModel.climb().getValue();
        int defaultCount = (current != null && current.segmentCount > 0) ? current.segmentCount : 16;
        picker.setValue(defaultCount);
        new AlertDialog.Builder(this)
                .setTitle("Segmenten per klim")
                .setView(picker)
                .setPositiveButton("Herbereken", (dialog, which) ->
                        viewModel.reSegment(routeId, climbIndex, picker.getValue()))
                .setNegativeButton("Annuleer", null)
                .show();
    }
}
