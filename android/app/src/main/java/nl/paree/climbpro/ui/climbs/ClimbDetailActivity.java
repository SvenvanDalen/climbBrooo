package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import nl.paree.climbpro.domain.segment.SurfaceType;

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
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.databinding.ActivityClimbDetailBinding;
import nl.paree.climbpro.domain.power.DurationFormat;

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
    private String lastTimeEstimateText;

    // Pending state while the note/photo edit dialog (issue #46) is open: the row being
    // edited and the photo the user just picked (persisted only on Save).
    private nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow pendingAttemptRow;
    private android.net.Uri pendingPhotoUri;
    private android.widget.ImageView pendingPhotoPreview;

    private final androidx.activity.result.ActivityResultLauncher<String> photoPickerLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        pendingPhotoUri = uri;
                        if (pendingPhotoPreview != null) {
                            pendingPhotoPreview.setImageURI(uri);
                            pendingPhotoPreview.setVisibility(android.view.View.VISIBLE);
                        }
                    });

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
            String categoryLabel = nl.paree.climbpro.domain.climb.ClimbCategoryLabel.forStoredClimb(climb);
            String categorySuffix = categoryLabel.isEmpty() ? "" : " · " + categoryLabel;
            binding.climbStats.setText(String.format(
                    "%d m total · %.1f%% avg gradient · %d m elevation gain · %s%s",
                    climb.length, climb.avgGradient * 100, climb.elevationGain,
                    nl.paree.climbpro.domain.climb.ClimbShapeLabel.forStoredClimb(climb),
                    categorySuffix));
            binding.climbProfile.setSegments(climb.segments);
            adapter.setItems(climb.segments);

            loadedClimb = climb;
            tryDrawMap();
        });

        viewModel.timeEstimate().observe(this, estimate -> {
            if (estimate == null) {
                binding.climbTimeEstimate.setText(
                        "Stel je FTP en gewicht in (Instellingen) voor een tijdschatting");
                adapter.setSegmentSeconds(null);
                lastTimeEstimateText = null;
            } else {
                lastTimeEstimateText = String.format(java.util.Locale.US,
                        "Geschatte tijd: %s · %.0f W",
                        DurationFormat.format(estimate.totalSeconds),
                        estimate.assumedPowerWatts);
                binding.climbTimeEstimate.setText(lastTimeEstimateText);
                adapter.setSegmentSeconds(estimate.segmentSeconds);
            }
        });

        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, isSaved -> {
            if (Boolean.TRUE.equals(isSaved))
                Toast.makeText(this, "Opgeslagen", Toast.LENGTH_SHORT).show();
        });

        viewModel.history().observe(this, rows -> {
            android.widget.TextView header = binding.historyHeader;
            android.widget.LinearLayout container = binding.historyContainer;
            container.removeAllViews();
            if (rows == null || rows.isEmpty()) {
                header.setVisibility(android.view.View.GONE);
                return;
            }
            header.setVisibility(android.view.View.VISIBLE);
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault());
            for (nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow row : rows) {
                android.widget.LinearLayout rowLayout = new android.widget.LinearLayout(this);
                rowLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
                rowLayout.setPadding(0, 8, 0, 16);

                android.widget.TextView tv = new android.widget.TextView(this);
                int m = row.elapsedSec / 60, s = row.elapsedSec % 60;
                String date = fmt.format(new java.util.Date(row.dateEpochSec * 1000L));
                String delta = row.deltaToPrSec == 0
                        ? "PR" : "+" + (row.deltaToPrSec / 60) + ":"
                        + String.format(java.util.Locale.getDefault(), "%02d", row.deltaToPrSec % 60);
                String badge = row.bestOfYear ? "  🏆 Beste van dit jaar" : "";
                tv.setText(String.format(java.util.Locale.getDefault(),
                        "%s   %d:%02d   (%s)%s", date, m, s, delta, badge));
                rowLayout.addView(tv);

                if (row.note != null && !row.note.isEmpty()) {
                    android.widget.TextView noteView = new android.widget.TextView(this);
                    noteView.setText("“" + row.note + "”");
                    noteView.setTextSize(13f);
                    noteView.setPadding(0, 4, 0, 0);
                    rowLayout.addView(noteView);
                }

                if (row.photoFileName != null && !row.photoFileName.isEmpty()) {
                    android.widget.ImageView thumb = new android.widget.ImageView(this);
                    int sizePx = (int) (72 * getResources().getDisplayMetrics().density);
                    android.widget.LinearLayout.LayoutParams lp =
                            new android.widget.LinearLayout.LayoutParams(sizePx, sizePx);
                    lp.topMargin = 8;
                    thumb.setLayoutParams(lp);
                    thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    thumb.setImageBitmap(loadAttemptThumbnail(row.photoFileName, sizePx));
                    rowLayout.addView(thumb);
                }

                android.widget.TextView editLink = new android.widget.TextView(this);
                editLink.setText(row.note != null || row.photoFileName != null
                        ? "Notitie/foto bewerken" : "+ Notitie/foto toevoegen");
                editLink.setTextColor(getResources().getColor(nl.paree.climbpro.R.color.color_accent));
                editLink.setPadding(0, 8, 0, 0);
                editLink.setOnClickListener(v -> showAttemptNoteDialog(row));
                rowLayout.addView(editLink);

                container.addView(rowLayout);
            }
        });

        binding.btnRenameClimb.setOnClickListener(v -> showRenameDialog());
        binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());
        binding.btnShareClimb.setOnClickListener(v -> shareClimbAsImage());
        binding.btnExportGpx.setOnClickListener(v -> viewModel.exportGpx());

        viewModel.gpxExportFile().observe(this, this::shareGpxFile);

        setupBulkSurfaceSetter();

        adapter.setOnSegmentLongClickListener((position, segment) ->
                showSegmentSurfaceDialog(position, segment));

        viewModel.loadClimb(routeId, climbIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
        viewModel.refreshEstimate();
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
        if (loadedRoute.lats == null || loadedRoute.lons == null || loadedRoute.distances == null
                || loadedRoute.lats.length == 0
                || loadedRoute.lons.length < loadedRoute.lats.length
                || loadedRoute.distances.length < loadedRoute.lats.length) return;

        // Full route — gray background
        List<GeoPoint> allPoints = new ArrayList<>(loadedRoute.lats.length);
        for (int i = 0; i < loadedRoute.lats.length; i++) {
            allPoints.add(new GeoPoint(loadedRoute.lats[i], loadedRoute.lons[i]));
        }
        Polyline routeLine = new Polyline();
        routeLine.setColor(Color.GRAY);
        routeLine.setWidth(4f);
        routeLine.setPoints(allPoints);

        binding.mapView.getOverlays().clear();
        binding.mapView.getOverlays().add(routeLine);

        // Per-segment colored polylines on the climb portion
        List<GeoPoint> allClimbPoints = new ArrayList<>();
        GeoPoint prevSegLastPoint = null;
        double segBoundary = loadedClimb.startDistance;

        if (loadedClimb.segments != null) {
            for (StoredSegment seg : loadedClimb.segments) {
                double segStart = segBoundary;
                double segEnd   = segBoundary + seg.distance;

                List<GeoPoint> segPoints = new ArrayList<>();
                if (prevSegLastPoint != null) segPoints.add(prevSegLastPoint);

                for (int i = 0; i < loadedRoute.distances.length; i++) {
                    if (loadedRoute.distances[i] >= segStart
                            && loadedRoute.distances[i] <= segEnd) {
                        GeoPoint p = new GeoPoint(loadedRoute.lats[i], loadedRoute.lons[i]);
                        segPoints.add(p);
                        allClimbPoints.add(p);
                    }
                }

                if (segPoints.size() >= 2) {
                    Polyline segLine = new Polyline();
                    segLine.setColor(SegmentColorPalette.toColor(seg.colorIndex));
                    segLine.setWidth(7f);
                    segLine.setPoints(segPoints);
                    binding.mapView.getOverlays().add(segLine);
                    prevSegLastPoint = segPoints.get(segPoints.size() - 1);
                }

                segBoundary = segEnd;
            }
        }

        List<GeoPoint> zoomTarget = allClimbPoints.isEmpty() ? allPoints : allClimbPoints;
        BoundingBox box = BoundingBox.fromGeoPoints(zoomTarget);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 80));
        binding.mapView.invalidate();
    }

    /**
     * Renders the climb profile + headline stats into a bitmap (issue #33) and hands it to
     * the standard Android share sheet via {@link ClimbShareHandoff}.
     */
    private void shareClimbAsImage() {
        if (loadedClimb == null) {
            Toast.makeText(this, "Klim nog niet geladen", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = loadedClimb.userDisplayName != null
                ? loadedClimb.userDisplayName
                : (loadedClimb.name != null ? loadedClimb.name : "Klim " + (climbIndex + 1));
        try {
            android.graphics.Bitmap bitmap = ClimbShareImageComposer.compose(
                    this, title, loadedClimb, lastTimeEstimateText);
            java.io.File file = ClimbShareHandoff.writeShareImage(this, bitmap);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent share = ClimbShareHandoff.buildShareIntent(uri);
            startActivity(Intent.createChooser(share, "Deel klim"));
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Kon afbeelding niet aanmaken", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Hands the GPX file {@link ClimbDetailViewModel#exportGpx()} just wrote off to the
     * standard Android share sheet (issue #79). Mirrors {@link #shareClimbAsImage()}'s
     * FileProvider handoff, using {@link ClimbGpxExportHandoff} instead.
     */
    private void shareGpxFile(java.io.File file) {
        if (file == null) return;
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);
        Intent share = ClimbGpxExportHandoff.buildShareIntent(uri);
        startActivity(Intent.createChooser(share, "Exporteer klim als GPX"));
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

    private void setupBulkSurfaceSetter() {
        String[] typeLabels = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed"};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, typeLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerSurfaceType.setAdapter(spinnerAdapter);

        binding.btnBulkSurface.setOnClickListener(v -> {
            int selected = binding.spinnerSurfaceType.getSelectedItemPosition();
            if (loadedClimb != null && loadedClimb.segments != null && !loadedClimb.segments.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Alle segmenten instellen?")
                        .setMessage("Dit overschrijft alle individuele instellingen voor deze klim.")
                        .setPositiveButton("Toepassen", (d, w) ->
                                viewModel.setBulkSurfaceType(routeId, climbIndex, selected))
                        .setNegativeButton("Annuleer", null)
                        .show();
            }
        });
    }

    private void showSegmentSurfaceDialog(int segmentIndex, nl.paree.climbpro.data.route.StoredSegment segment) {
        String[] typeLabels = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "Onbekend"};
        int current = SurfaceType.fromInt(segment.surfaceType);

        new AlertDialog.Builder(this)
                .setTitle("Oppervlak voor segment " + (segmentIndex + 1))
                .setSingleChoiceItems(typeLabels, current, null)
                .setPositiveButton("Opslaan", (dialog, which) -> {
                    android.widget.ListView lv =
                            ((AlertDialog) dialog).getListView();
                    int chosen = lv.getCheckedItemPosition();
                    if (chosen >= 0 && chosen <= 5) {
                        viewModel.setSurfaceType(routeId, climbIndex, segmentIndex, chosen);
                    }
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }

    /**
     * Small memory/diary edit dialog for one attempt (issue #46) — a free-text note and a
     * gallery photo picker, both purely phone-side. Reachable from the "+ Notitie/foto" link
     * on each history row.
     */
    private void showAttemptNoteDialog(nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow row) {
        pendingAttemptRow = row;
        pendingPhotoUri = null;

        android.widget.LinearLayout dialogLayout = new android.widget.LinearLayout(this);
        dialogLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        dialogLayout.setPadding(pad, pad, pad, pad);

        EditText noteInput = new EditText(this);
        noteInput.setHint("Notitie");
        noteInput.setText(row.note);
        dialogLayout.addView(noteInput);

        android.widget.ImageView preview = new android.widget.ImageView(this);
        int sizePx = (int) (120 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams previewLp =
                new android.widget.LinearLayout.LayoutParams(sizePx, sizePx);
        previewLp.topMargin = pad;
        preview.setLayoutParams(previewLp);
        preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        if (row.photoFileName != null && !row.photoFileName.isEmpty()) {
            preview.setImageBitmap(loadAttemptThumbnail(row.photoFileName, sizePx));
        } else {
            preview.setVisibility(android.view.View.GONE);
        }
        dialogLayout.addView(preview);
        pendingPhotoPreview = preview;

        android.widget.Button pickPhotoButton = new android.widget.Button(this);
        pickPhotoButton.setText(row.photoFileName != null ? "Andere foto kiezen" : "Foto kiezen");
        pickPhotoButton.setOnClickListener(v -> photoPickerLauncher.launch("image/*"));
        dialogLayout.addView(pickPhotoButton);

        new AlertDialog.Builder(this)
                .setTitle("Notitie & foto")
                .setView(dialogLayout)
                .setPositiveButton("Opslaan", (d, w) -> {
                    viewModel.saveAttemptNote(routeId, climbIndex, row.activityId, row.passIndex,
                            noteInput.getText().toString(), pendingPhotoUri);
                    pendingAttemptRow = null;
                    pendingPhotoUri = null;
                    pendingPhotoPreview = null;
                })
                .setNegativeButton("Annuleer", (d, w) -> {
                    pendingAttemptRow = null;
                    pendingPhotoUri = null;
                    pendingPhotoPreview = null;
                })
                .show();
    }

    /**
     * Decodes an attempt photo at roughly thumbnail resolution (avoids loading a full-size
     * gallery photo just to show a small preview). Returns null if the file is missing or
     * unreadable — callers must tolerate a null bitmap.
     */
    private android.graphics.Bitmap loadAttemptThumbnail(String photoFileName, int targetSizePx) {
        java.io.File file = nl.paree.climbpro.data.route.AttemptPhotoStore.fileFor(this, photoFileName);
        if (!file.exists()) return null;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while ((bounds.outWidth / sample) > targetSizePx * 2
                || (bounds.outHeight / sample) > targetSizePx * 2) {
            sample *= 2;
        }
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }
}
