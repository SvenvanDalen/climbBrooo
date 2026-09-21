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

import nl.paree.climbpro.domain.route.SurfaceSectionGeometry;
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
        adapter.setOnStarredClickListener(this::showStarredSurfaceDialog);
        adapter.setOnSurfaceClickListener(this::showSurfaceSectionRowDialog);

        viewModel.route().observe(this, route -> {
            if (route == null) return;
            String name = route.userDisplayName != null ? route.userDisplayName : route.name;
            binding.toolbar.setTitle(name != null ? name : route.routeId);
            binding.notesEdit.setText(route.notes != null ? route.notes : "");
            drawRoute(route);
        });

        viewModel.passport().observe(this, this::renderPassport);
        viewModel.climbTargetSeconds().observe(this, secs -> adapter.setClimbTargetSeconds(secs));

        viewModel.routeItems().observe(this, items -> adapter.setItems(items));

        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, ok -> {
            if (Boolean.TRUE.equals(ok)) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        viewModel.onboardPushMessage().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        binding.btnRename.setOnClickListener(v -> showRenameDialog());
        binding.btnSaveNotes.setOnClickListener(v ->
                viewModel.saveNotes(routeId, binding.notesEdit.getText().toString()));
        binding.btnSelectRoute.setOnClickListener(v ->
                PreRideCheckDialog.show(this, viewModel.passport().getValue(), () -> {
                    viewModel.setActiveRoute(routeId);
                    Toast.makeText(this, "Route selected for watch", Toast.LENGTH_SHORT).show();
                }));
        binding.btnSendToOnboard.setOnClickListener(v -> viewModel.sendToOnboard(routeId));
        binding.btnShareToGarmin.setOnClickListener(v ->
                PreRideCheckDialog.show(this, viewModel.passport().getValue(),
                        this::shareToGarminConnect));
        binding.btnSurfaceSections.setOnClickListener(v -> showSurfaceSectionsManager());

        viewModel.loadRoute(routeId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
        if (routeId != null) viewModel.loadRoute(routeId);
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

    private void renderPassport(RoutePassport p) {
        if (p == null) { binding.passportSummary.setText(""); return; }
        StringBuilder sb = new StringBuilder();
        sb.append(p.climbCount).append(" klimmen · ")
          .append(p.totalElevationGain).append(" hm");
        if (p.hardestClimbName != null) {
            sb.append("\nZwaarste: ").append(p.hardestClimbName)
              .append(String.format(java.util.Locale.US, " (%.1f%%)", p.hardestClimbGradient * 100));
        }
        if (p.totalEstimatedSeconds >= 0) {
            sb.append("\nGeschatte tijd: ")
              .append(nl.paree.climbpro.domain.power.DurationFormat.format(p.totalEstimatedSeconds));
        } else {
            sb.append("\nGeschatte tijd: vul je profiel in (Instellingen)");
        }
        binding.passportSummary.setText(sb.toString());
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

        drawSurfaceSections(route);

        BoundingBox box = BoundingBox.fromGeoPoints(points);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 50));
        binding.mapView.invalidate();
    }

    /** Tekent elk handmatig ingevoerd ondergrond-stuk als gekleurde overlay op de route. */
    private void drawSurfaceSections(StoredRoute route) {
        if (route.surfaceSections == null) return;
        for (nl.paree.climbpro.data.route.StoredSurfaceSection s : route.surfaceSections) {
            List<double[]> coords = SurfaceSectionGeometry.pointsBetween(
                    route.distances, route.lats, route.lons,
                    s.startDistance, s.endDistance);
            if (coords.size() < 2) continue;

            List<GeoPoint> geo = new ArrayList<>(coords.size());
            for (double[] c : coords) geo.add(new GeoPoint(c[0], c[1]));

            Polyline overlay = new Polyline();
            overlay.setColor(SurfaceColorPalette.toColor(s.surfaceType));
            overlay.setWidth(12f);
            overlay.setPoints(geo);

            final String label = (s.name != null ? s.name : "(naamloos)")
                    + " · " + SURFACE_LABELS_NL[SurfaceType.fromInt(s.surfaceType)];
            overlay.setOnClickListener((polyline, mapView, eventPos) -> {
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
                return true;
            });

            binding.mapView.getOverlays().add(overlay);
        }
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
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Naam (optioneel)");
        nameInput.setSingleLine(true);
        if (flat.name != null) nameInput.setText(flat.name);
        layout.addView(nameInput);

        final android.widget.Spinner surface = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, SURFACE_LABELS_NL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        surface.setAdapter(adapter);
        surface.setSelection(SurfaceType.fromInt(flat.surfaceType));
        layout.addView(surface);

        new AlertDialog.Builder(this)
                .setTitle("Vlak segment")
                .setView(layout)
                .setPositiveButton("Opslaan", (dialog, which) ->
                        viewModel.updateFlatSegment(routeId, flat.startDistance,
                                surface.getSelectedItemPosition(),
                                nameInput.getText().toString()))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private void showStarredSurfaceDialog(
            nl.paree.climbpro.data.route.StoredStarredSegment seg) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);

        final android.widget.EditText nameInput = new android.widget.EditText(this);
        nameInput.setHint("Naam");
        nameInput.setSingleLine(true);
        if (seg.userDisplayName != null) nameInput.setText(seg.userDisplayName);
        else if (seg.name != null)       nameInput.setText(seg.name);
        layout.addView(nameInput);

        final android.widget.Spinner surface = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, SURFACE_LABELS_NL);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        surface.setAdapter(a);
        surface.setSelection(SurfaceType.fromInt(seg.surfaceType));
        layout.addView(surface);

        new AlertDialog.Builder(this)
                .setTitle("Ster-segment")
                .setView(layout)
                .setPositiveButton("Opslaan", (dialog, which) ->
                        viewModel.updateStarredSegment(routeId, seg.stravaId,
                                surface.getSelectedItemPosition(),
                                nameInput.getText().toString()))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    /**
     * Tapping a surface-section row in the list: edit its surface + name in place, or delete.
     * The section's index is resolved by identity against the route's surfaceSections list
     * (both come from the same loaded StoredRoute, so reference equality holds).
     */
    private void showSurfaceSectionRowDialog(
            nl.paree.climbpro.data.route.StoredSurfaceSection section) {
        java.util.List<nl.paree.climbpro.data.route.StoredSurfaceSection> sections =
                viewModel.surfaceSections().getValue();
        final int index = sections != null ? sections.indexOf(section) : -1;
        if (index < 0) {
            showSurfaceSectionsManager(); // fallback: open the manager if we lost the reference
            return;
        }

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Naam (optioneel)");
        nameInput.setSingleLine(true);
        if (section.name != null) nameInput.setText(section.name);
        layout.addView(nameInput);

        final android.widget.Spinner surface = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, SURFACE_LABELS_NL);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        surface.setAdapter(a);
        surface.setSelection(SurfaceType.fromInt(section.surfaceType));
        layout.addView(surface);

        new AlertDialog.Builder(this)
                .setTitle("Ondergrond-stuk")
                .setView(layout)
                .setPositiveButton("Opslaan", (d, w) ->
                        viewModel.updateSurfaceSection(routeId, index,
                                surface.getSelectedItemPosition(),
                                nameInput.getText().toString()))
                .setNeutralButton("Verwijder", (d, w) -> confirmDeleteSection(index))
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
                String label = String.format("%.1f–%.1f km · %s",
                        s.startDistance / 1000.0, s.endDistance / 1000.0,
                        SURFACE_LABELS_NL[SurfaceType.fromInt(s.surfaceType)]);
                rows[i] = (s.name != null ? s.name + " — " : "") + label;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Ondergrond-stukken")
                .setItems(rows, (dialog, which) -> {
                    if (!current.isEmpty()) showSectionActions(which, current.get(which).name);
                })
                .setPositiveButton("Toevoegen", (d, w) -> showAddSurfaceSectionDialog())
                .setNegativeButton("Sluiten", null)
                .show();
    }

    private void showSectionActions(int index, String currentName) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Hernoemen", "Verwijderen"}, (d, which) -> {
                    if (which == 0) showRenameSectionDialog(index, currentName);
                    else confirmDeleteSection(index);
                })
                .show();
    }

    private void showRenameSectionDialog(int index, String currentName) {
        final EditText input = new EditText(this);
        input.setHint("Naam");
        input.setSingleLine(true);
        if (currentName != null) input.setText(currentName);
        new AlertDialog.Builder(this)
                .setTitle("Hernoem stuk")
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) ->
                        viewModel.setSurfaceSectionName(routeId, index,
                                input.getText().toString()))
                .setNegativeButton("Annuleer", null)
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

        final android.widget.EditText nameInput = new android.widget.EditText(this);
        nameInput.setHint("Naam (optioneel)");
        nameInput.setSingleLine(true);
        layout.addView(nameInput);

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
                            surface.getSelectedItemPosition(),
                            nameInput.getText().toString());
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
        StoredRoute route = viewModel.route().getValue();
        if (route == null || route.lats == null || route.lats.length == 0) {
            Toast.makeText(this, "Route heeft nog geen geometrie om te delen",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.io.File gpx = GarminHandoff.writeRouteGpx(this, route);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", gpx);
            Intent share = GarminHandoff.buildShareIntent(this, uri);
            startActivity(Intent.createChooser(share, "Open in Garmin Connect"));
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Kon GPX niet aanmaken", Toast.LENGTH_SHORT).show();
        }
    }
}
