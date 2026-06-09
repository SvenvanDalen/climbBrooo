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

    /** Dutch surface labels, index = SurfaceType constant (0..5). */
    private static final String[] SURFACE_LABELS_NL =
            {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "Onbekend"};

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
        binding.btnSurfaceSections.setOnClickListener(v -> showSurfaceSectionsManager());

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

    private void showSurfaceSectionsManager() {
        java.util.List<nl.paree.climbpro.data.route.StoredSurfaceSection> sections =
                viewModel.surfaceSections().getValue();
        if (sections == null) sections = java.util.Collections.emptyList();

        final java.util.List<nl.paree.climbpro.data.route.StoredSurfaceSection> current = sections;
        String[] rows;
        if (current.isEmpty()) {
            rows = new String[]{"(nog geen stukken)"};
        } else {
            rows = new String[current.size()];
            for (int i = 0; i < current.size(); i++) {
                nl.paree.climbpro.data.route.StoredSurfaceSection s = current.get(i);
                rows[i] = String.format("%.1f–%.1f km · %s",
                        s.startDistance / 1000.0, s.endDistance / 1000.0,
                        SURFACE_LABELS_NL[SurfaceType.fromInt(s.surfaceType)]);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Ondergrond-stukken")
                .setItems(rows, (dialog, which) -> {
                    if (!current.isEmpty()) confirmDeleteSection(which);
                })
                .setPositiveButton("Toevoegen", (d, w) -> showAddSurfaceSectionDialog())
                .setNegativeButton("Sluiten", null)
                .show();
    }

    private void confirmDeleteSection(int index) {
        new AlertDialog.Builder(this)
                .setTitle("Stuk verwijderen?")
                .setPositiveButton("Verwijder", (d, w) ->
                        viewModel.deleteSurfaceSection(routeId, index))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private void showAddSurfaceSectionDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final android.widget.EditText startKm = new android.widget.EditText(this);
        startKm.setHint("Start (km)");
        startKm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(startKm);

        final android.widget.EditText endKm = new android.widget.EditText(this);
        endKm.setHint("Eind (km)");
        endKm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(endKm);

        // Surface picker excludes "Onbekend" (index 0..4 only).
        final android.widget.Spinner surface = new android.widget.Spinner(this);
        String[] choices = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed"};
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        surface.setAdapter(adapter);
        layout.addView(surface);

        new AlertDialog.Builder(this)
                .setTitle("Nieuw ondergrond-stuk")
                .setView(layout)
                .setPositiveButton("Toevoegen", (dialog, which) -> {
                    Integer startM = parseKmToMeters(startKm.getText().toString());
                    Integer endM   = parseKmToMeters(endKm.getText().toString());
                    if (startM == null || endM == null) {
                        Toast.makeText(this, "Vul start en eind in km in", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.addSurfaceSection(routeId, startM, endM,
                            surface.getSelectedItemPosition());
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }

    /** Parses a kilometre string (e.g. "1.5") to integer metres, or null if blank/invalid. */
    private static Integer parseKmToMeters(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return (int) Math.round(Double.parseDouble(text.trim()) * 1000.0);
        } catch (NumberFormatException e) {
            return null;
        }
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
