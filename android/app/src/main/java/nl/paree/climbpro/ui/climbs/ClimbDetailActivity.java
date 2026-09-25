package nl.paree.climbpro.ui.climbs;

import android.app.DatePickerDialog;
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
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.databinding.ActivityClimbDetailBinding;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.DurationFormat;
import nl.paree.climbpro.domain.sun.SunriseCalculator;
import nl.paree.climbpro.domain.sun.SunriseRidePlanner;
import nl.paree.climbpro.domain.weather.ClimbEndpoints;
import nl.paree.climbpro.domain.weather.HourlyForecast;
import nl.paree.climbpro.domain.weather.SummitWeather;
import nl.paree.climbpro.service.PlannedClimbWorkScheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
    private Integer lastEstimateSeconds;

    // Pending state while the note/photo edit dialog (issue #46) is open: the row being
    // edited and the photo the user just picked (persisted only on Save).
    private nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow pendingAttemptRow;
    private android.net.Uri pendingPhotoUri;
    private android.widget.ImageView pendingPhotoPreview;

    // Background executor for decoding attempt-photo thumbnails (history rows + the
    // picker preview) off the main thread — avoids UI-thread jank/ANR from synchronous
    // BitmapFactory decodes of on-disk/content-uri photos.
    private final java.util.concurrent.ExecutorService thumbnailExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final androidx.activity.result.ActivityResultLauncher<String> photoPickerLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        pendingPhotoUri = uri;
                        android.widget.ImageView preview = pendingPhotoPreview;
                        if (preview == null) return;
                        int sizePx = (int) (120 * getResources().getDisplayMetrics().density);
                        // Downsample instead of setImageURI(uri): a full-resolution gallery
                        // photo decoded just for a small preview can OOM on lower-memory
                        // devices (same risk loadAttemptThumbnail() already guards against).
                        thumbnailExecutor.execute(() -> {
                            android.graphics.Bitmap bmp =
                                    decodeSampledBitmapFromUri(this, uri, sizePx);
                            runOnUiThread(() -> {
                                if (pendingPhotoPreview != preview) return;
                                preview.setImageBitmap(bmp);
                                preview.setVisibility(android.view.View.VISIBLE);
                            });
                        });
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
            String surfaceLabel = nl.paree.climbpro.domain.climb.ClimbSurfaceLabel.forStoredClimb(climb);
            String categoryLabel = nl.paree.climbpro.domain.climb.ClimbCategoryLabel.forStoredClimb(climb);
            String statsText = String.format(
                    "%d m total · %.1f%% avg gradient · %d m elevation gain · %s",
                    climb.length, climb.avgGradient * 100, climb.elevationGain,
                    nl.paree.climbpro.domain.climb.ClimbShapeLabel.forStoredClimb(climb));
            if (!categoryLabel.isEmpty()) {
                statsText += " · " + categoryLabel;
            }
            if (!surfaceLabel.isEmpty()) {
                statsText += " · " + surfaceLabel;
            }
            binding.climbStats.setText(statsText);
            binding.climbProfile.setSegments(climb.segments);
            adapter.setItems(climb.segments);

            loadedClimb = climb;
            binding.btnToggleHomeClimb.setText(climb.isHome
                    ? "Thuisklim — startlocatie wordt gewazigd bij export"
                    : "Markeer als thuisklim");
            binding.climbRating.setText(
                    nl.paree.climbpro.domain.climb.ClimbRating.detailText(climb));
            binding.btnRateClimb.setText(
                    nl.paree.climbpro.domain.climb.ClimbRating.isRated(climb)
                            || climb.ratingNote != null
                            ? "Beoordeling aanpassen" : "Klim beoordelen");
            tryDrawMap();
            updateManualRefText();
        });

        viewModel.timeEstimate().observe(this, estimate -> {
            if (estimate == null) {
                binding.climbTimeEstimate.setText(
                        "Stel je FTP en gewicht in (Instellingen) voor een tijdschatting");
                adapter.setSegmentSeconds(null);
                lastTimeEstimateText = null;
                lastEstimateSeconds = null;
            } else {
                lastTimeEstimateText = String.format(java.util.Locale.US,
                        "Geschatte tijd: %s · %.0f W",
                        DurationFormat.format(estimate.totalSeconds),
                        estimate.assumedPowerWatts);
                binding.climbTimeEstimate.setText(lastTimeEstimateText);
                adapter.setSegmentSeconds(estimate.segmentSeconds);
                lastEstimateSeconds = estimate.totalSeconds;
            }
            updateManualRefText();
        });

        viewModel.seasonalComparison().observe(this, result -> {
            if (result == null) {
                binding.seasonalComparison.setVisibility(android.view.View.GONE);
                return;
            }
            String direction = result.percentFaster >= 0 ? "sneller" : "langzamer";
            binding.seasonalComparison.setText(String.format(java.util.Locale.getDefault(),
                    "%.0f%% %s dan in %d rond deze tijd van het jaar",
                    Math.abs(result.percentFaster), direction, result.priorYear));
            binding.seasonalComparison.setVisibility(android.view.View.VISIBLE);
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
                String deviationBadge = row.routeDeviation
                        ? "  ⚠️ Afwijkende route (niet meegeteld voor PR)" : "";
                tv.setText(String.format(java.util.Locale.getDefault(),
                        "%s   %d:%02d   (%s)%s%s", date, m, s, delta, badge, deviationBadge));
                rowLayout.addView(tv);

                String tempNote = nl.paree.climbpro.domain.climb.AttemptTemperature.label(row.avgTempC);
                if (tempNote != null) {
                    android.widget.TextView tempView = new android.widget.TextView(this);
                    tempView.setText(tempNote);
                    tempView.setTextSize(13f);
                    tempView.setPadding(0, 4, 0, 0);
                    rowLayout.addView(tempView);
                }

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
                    rowLayout.addView(thumb);
                    loadAttemptThumbnailAsync(row.photoFileName, sizePx, thumb);
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
        binding.btnManualRef.setOnClickListener(v -> showManualRefDialog());
        binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());
        binding.btnEditShape.setOnClickListener(v -> showShapeOverrideDialog());
        binding.btnRateClimb.setOnClickListener(v -> showRatingDialog());
        binding.btnShareClimb.setOnClickListener(v -> shareClimbAsImage());
        binding.btnExportGpx.setOnClickListener(v -> viewModel.exportGpx());
        binding.btnToggleHomeClimb.setOnClickListener(v -> {
            if (loadedClimb == null) return;
            viewModel.setHomeClimb(routeId, climbIndex, !loadedClimb.isHome);
        });
        binding.btnSunriseRide.setOnClickListener(v -> pickSunriseDate());
        binding.btnSummitWeather.setOnClickListener(v -> showSummitWeather());

        viewModel.gpxExportFile().observe(this, this::shareGpxFile);

        setupBulkSurfaceSetter();

        adapter.setOnSegmentLongClickListener((position, segment) ->
                showSegmentSurfaceDialog(position, segment));
        adapter.setOnSegmentClickListener((position, segment) ->
                showSegmentTargetTimeDialog(position, segment));

        viewModel.loadClimb(routeId, climbIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
        viewModel.refreshEstimate();
    }

    /** Issue #247: plan a ride that reaches this climb's top just before sunrise. */
    private void pickSunriseDate() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        DatePickerDialog picker = new DatePickerDialog(this, (dp, y, m, d) ->
                showSunrisePlan(LocalDate.of(y, m + 1, d)),
                tomorrow.getYear(), tomorrow.getMonthValue() - 1, tomorrow.getDayOfMonth());
        picker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000); // no past dates
        picker.show();
    }

    private void showSunrisePlan(LocalDate date) {
        StoredClimb c = viewModel.climb().getValue();
        if (c == null) return;
        Instant sunrise = SunriseCalculator
                .sunrise(date, c.startLat, c.startLon);
        if (sunrise == null) {
            new AlertDialog.Builder(this)
                    .setMessage("Op deze datum komt de zon hier niet op of gaat ze niet onder.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        ClimbTimeEstimate est = viewModel.timeEstimate().getValue();
        SunriseRidePlanner.Plan plan =
                SunriseRidePlanner.plan(sunrise, c.startDistance,
                        SunriseRidePlanner.DEFAULT_APPROACH_KMH,
                        est != null ? est.totalSeconds : 0, c.length,
                        SunriseRidePlanner.DEFAULT_BUFFER_MIN);
        ZoneId zone = ZoneId.systemDefault();
        Locale dutch = new Locale("nl");
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        DateTimeFormatter dayHm =
                DateTimeFormatter.ofPattern("EEE d MMM HH:mm", dutch);
        String name = c.userDisplayName != null ? c.userDisplayName
                : (c.name != null ? c.name : "Klim " + (climbIndex + 1));
        String msg = "Zon op: " + hm.format(sunrise.atZone(zone))
                + "\nOp de top: " + hm.format(plan.arrivalTop.atZone(zone))
                + " (" + SunriseRidePlanner.DEFAULT_BUFFER_MIN + " min vooraf)"
                + "\nVertrek vanaf de routestart: " + dayHm.format(plan.departure.atZone(zone))
                + "\n\nAanrit " + String.format(dutch, "%.1f", c.startDistance / 1000.0)
                + " km à 25 km/u (" + DurationFormat.format(plan.approachSec)
                + ") + klim " + DurationFormat.format(plan.climbSec)
                + (est == null ? " (schatting op 10 km/u; vul je profiel in voor een betere schatting)" : "");
        new AlertDialog.Builder(this)
                .setTitle("Zonsopkomst op " + name)
                .setMessage(msg)
                .setPositiveButton("Zet in klimplanning", (d, w) -> saveSunrisePlan(plan, name))
                .setNegativeButton("Sluiten", null)
                .show();
    }

    private void saveSunrisePlan(SunriseRidePlanner.Plan plan, String name) {
        if (SunriseRidePlanner.isInPast(plan, Instant.now())) {
            Toast.makeText(this, "Het vertrektijdstip is al voorbij", Toast.LENGTH_LONG).show();
            return;
        }
        PlannedClimb p = new PlannedClimb(
                UUID.randomUUID().toString(), routeId, climbIndex,
                "Zonsopkomst: " + name, plan.departure.getEpochSecond(), System.currentTimeMillis());
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                new PlannedClimbRepository(app).add(p);
                PlannedClimbWorkScheduler.schedule(app, p);
                runOnUiThread(() -> Toast.makeText(app, "Gepland; je krijgt een herinnering",
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(app, "Plannen mislukt: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                        Toast.LENGTH_LONG).show());
            }
        }, "sunrise-plan").start();
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

    /** Issue #246: valley vs summit weather, now and in 3 hours (Open-Meteo, off the UI thread). */
    private void showSummitWeather() {
        StoredRoute r = viewModel.route().getValue();
        StoredClimb c = viewModel.climb().getValue();
        if (r == null || c == null) return;
        binding.btnSummitWeather.setEnabled(false); // one request at a time, no stacked dialogs
        Toast.makeText(this, "Weer ophalen…", Toast.LENGTH_SHORT).show();
        ClimbEndpoints.Point foot =
                ClimbEndpoints.foot(r, c);
        ClimbEndpoints.Point top =
                ClimbEndpoints.top(r, c);
        new Thread(() -> {
            String msg;
            try {
                OpenMeteoClient client =
                        new OpenMeteoClient();
                HourlyForecast f = client.fetch(foot);
                HourlyForecast t = client.fetch(top);
                Instant now = Instant.now();
                String nowText = SummitWeather.describe(
                        f, t, now, foot.elevationM, top.elevationM);
                String laterText = SummitWeather.describe(
                        f, t, now.plusSeconds(3 * 3600), foot.elevationM, top.elevationM);
                StringBuilder sb = new StringBuilder();
                if (nowText != null) sb.append("NU\n").append(nowText);
                if (laterText != null) {
                    sb.append(sb.length() > 0 ? "\n\n" : "").append("OVER 3 UUR\n").append(laterText);
                }
                msg = sb.length() > 0 ? sb.toString() : "Geen verwachting beschikbaar voor dit moment";
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                msg = "Weer ophalen mislukt: " + reason;
            }
            String text = msg + "\n\nBron: Open-Meteo";
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.btnSummitWeather.setEnabled(true);
                new AlertDialog.Builder(this)
                        .setTitle("Weer op de top")
                        .setMessage(text)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }, "summit-weather").start();
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

    /**
     * Dialog to set/clear a manual WR/pro reference time (issue #59), e.g. "Pogačar 2024"
     * at "37:15". Time input accepts m:ss or h:mm:ss. "Opslaan" requires a valid, non-empty
     * time and shows an error otherwise; use the separate "Wissen" button to clear the
     * reference.
     */
    private void showManualRefDialog() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        EditText timeInput = new EditText(this);
        timeInput.setHint("Tijd (m:ss of h:mm:ss)");
        StoredClimb current = viewModel.climb().getValue();
        if (current != null && current.manualRefSec != null) {
            timeInput.setText(DurationFormat.format(current.manualRefSec));
        }
        container.addView(timeInput);

        EditText labelInput = new EditText(this);
        labelInput.setHint("Bron (bv. Pogačar 2024)");
        if (current != null && current.manualRefLabel != null) {
            labelInput.setText(current.manualRefLabel);
        }
        container.addView(labelInput);

        new AlertDialog.Builder(this)
                .setTitle("WR/pro-referentietijd")
                .setView(container)
                .setPositiveButton("Opslaan", (d, w) -> {
                    Integer sec = parseDurationToSeconds(timeInput.getText().toString().trim());
                    if (sec == null) {
                        Toast.makeText(this, "Ongeldige tijd (gebruik Wissen om te legen)",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.setManualRefTime(routeId, climbIndex, sec,
                            labelInput.getText().toString().trim());
                })
                .setNeutralButton("Wissen", (d, w) ->
                        viewModel.setManualRefTime(routeId, climbIndex, null, null))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    /** Parses "m:ss" or "h:mm:ss" into total seconds; returns null on invalid/empty input. */
    private static Integer parseDurationToSeconds(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] parts = text.split(":");
        try {
            if (parts.length == 2) {
                int m = Integer.parseInt(parts[0].trim());
                int s = Integer.parseInt(parts[1].trim());
                if (m < 0 || s < 0 || s >= 60) return null;
                return m * 60 + s;
            } else if (parts.length == 3) {
                int h = Integer.parseInt(parts[0].trim());
                int m = Integer.parseInt(parts[1].trim());
                int s = Integer.parseInt(parts[2].trim());
                if (h < 0 || m < 0 || m >= 60 || s < 0 || s >= 60) return null;
                return h * 3600 + m * 60 + s;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** Shows the manual reference (and delta vs. the current phone-side time estimate), if set. */
    private void updateManualRefText() {
        StoredClimb c = loadedClimb;
        if (c == null || c.manualRefSec == null) {
            binding.climbManualRef.setVisibility(android.view.View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Referentie: ").append(DurationFormat.format(c.manualRefSec));
        if (c.manualRefLabel != null && !c.manualRefLabel.isEmpty()) {
            sb.append(" (").append(c.manualRefLabel).append(")");
        }
        if (lastEstimateSeconds != null) {
            int deltaSec = lastEstimateSeconds - c.manualRefSec;
            String sign = deltaSec >= 0 ? "+" : "-";
            sb.append(" · jouw schatting ").append(sign)
                    .append(DurationFormat.format(Math.abs(deltaSec)));
        }
        binding.climbManualRef.setText(sb.toString());
        binding.climbManualRef.setVisibility(android.view.View.VISIBLE);
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

    /**
     * Lets the user manually override the auto-computed shape tag (issue #36), e.g. when the
     * heuristic in {@code ClimbShapeClassifier} mislabels a climb. "Automatisch" clears the
     * override, falling back to the auto classification again — same shape as clearing a rename
     * back to the auto name.
     */
    private void showShapeOverrideDialog() {
        nl.paree.climbpro.domain.climb.ClimbShape[] shapes =
                nl.paree.climbpro.domain.climb.ClimbShape.values();
        String[] labels = new String[shapes.length + 1];
        labels[0] = "Automatisch";
        for (int i = 0; i < shapes.length; i++) {
            labels[i + 1] = nl.paree.climbpro.domain.climb.ClimbShapeLabel.forShape(shapes[i]);
        }

        int current = 0;
        if (loadedClimb != null && loadedClimb.shapeOverride != null) {
            for (int i = 0; i < shapes.length; i++) {
                if (shapes[i].name().equals(loadedClimb.shapeOverride)) {
                    current = i + 1;
                    break;
                }
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Vorm van de klim")
                .setSingleChoiceItems(labels, current, null)
                .setPositiveButton("Opslaan", (dialog, which) -> {
                    android.widget.ListView lv = ((AlertDialog) dialog).getListView();
                    int chosen = lv.getCheckedItemPosition();
                    String shapeName = (chosen >= 1 && chosen <= shapes.length)
                            ? shapes[chosen - 1].name() : null;
                    viewModel.setShapeOverride(routeId, climbIndex, shapeName);
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }

    /** RatingBar value → 1–5, or null when the rider left it at 0 stars. */
    static Integer starsOrNull(float rating) {
        int stars = Math.round(rating);
        return nl.paree.climbpro.domain.climb.ClimbRating.normalize(stars);
    }

    /**
     * Rate this climb (issue #244): wegdek, verkeer (5 = rustig) and uitzicht, 0–5 stars each
     * (0 = niet beoordeeld), plus an optional note. "Wissen" clears the whole rating.
     */
    private void showRatingDialog() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        StoredClimb c = loadedClimb;
        android.widget.RatingBar road = addRatingRow(container, "Wegdek",
                c != null ? c.ratingRoad : null);
        android.widget.RatingBar traffic = addRatingRow(container, "Verkeer (5 = rustig)",
                c != null ? c.ratingTraffic : null);
        android.widget.RatingBar view = addRatingRow(container, "Uitzicht",
                c != null ? c.ratingView : null);

        EditText note = new EditText(this);
        note.setHint("Notitie (optioneel)");
        if (c != null && c.ratingNote != null) note.setText(c.ratingNote);
        container.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("Klim beoordelen")
                .setView(container)
                .setPositiveButton("Opslaan", (d, w) -> viewModel.setRating(routeId, climbIndex,
                        starsOrNull(road.getRating()),
                        starsOrNull(traffic.getRating()),
                        starsOrNull(view.getRating()),
                        note.getText().toString()))
                .setNeutralButton("Wissen", (d, w) ->
                        viewModel.setRating(routeId, climbIndex, null, null, null, null))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private android.widget.RatingBar addRatingRow(android.widget.LinearLayout container,
                                                  String label, Integer current) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(label);
        container.addView(tv);
        android.widget.RatingBar bar = new android.widget.RatingBar(this);
        bar.setNumStars(nl.paree.climbpro.domain.climb.ClimbRating.MAX_STARS);
        bar.setStepSize(1f);
        bar.setRating(current != null ? current : 0f);
        // WRAP_CONTENT is required: a match_parent RatingBar draws extra stars.
        container.addView(bar, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
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

    /**
     * Lets the user set (or clear) a manual pacing target for one segment (issue #23),
     * overriding {@code RoutePacingPlanner}'s automatic 'tsec' value for that segment only.
     */
    private void showSegmentTargetTimeDialog(int segmentIndex, StoredSegment segment) {
        EditText input = new EditText(this);
        input.setHint("mm:ss");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        if (segment.manualTargetSec != null) {
            input.setText(DurationFormat.format(segment.manualTargetSec));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Doeltijd segment " + (segmentIndex + 1))
                .setMessage("Laat leeg en kies \"Automatisch\" om de berekende tijd te gebruiken.")
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) -> {
                    Integer seconds = parseMmSs(input.getText().toString().trim());
                    if (seconds == null) {
                        Toast.makeText(this, "Ongeldige tijd, gebruik mm:ss", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.setSegmentManualTargetSec(routeId, climbIndex, segmentIndex, seconds);
                })
                .setNegativeButton("Annuleer", null);
        if (segment.manualTargetSec != null) {
            builder.setNeutralButton("Automatisch",
                    (d, w) -> viewModel.setSegmentManualTargetSec(
                            routeId, climbIndex, segmentIndex, null));
        }
        builder.show();
    }

    /**
     * Parses "mm:ss" or a bare seconds count; returns null on anything unparsable, negative or
     * zero (a 0 s target would reach the watch as a meaningless tsec).
     */
    private static Integer parseMmSs(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            if (text.contains(":")) {
                String[] parts = text.split(":", 2);
                int minutes = Integer.parseInt(parts[0].trim());
                int seconds = Integer.parseInt(parts[1].trim());
                if (minutes < 0 || seconds < 0 || seconds >= 60) return null;
                int total = minutes * 60 + seconds;
                return total > 0 ? total : null;
            }
            int seconds = Integer.parseInt(text);
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
            loadAttemptThumbnailAsync(row.photoFileName, sizePx, preview);
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
     *
     * <p>Runs the actual {@link android.graphics.BitmapFactory} decode on disk I/O, so callers
     * on the main thread must go through {@link #loadAttemptThumbnailAsync} instead of calling
     * this directly.
     */
    private android.graphics.Bitmap loadAttemptThumbnail(String photoFileName, int targetSizePx) {
        java.io.File file = nl.paree.climbpro.data.route.AttemptPhotoStore.fileFor(this, photoFileName);
        if (!file.exists()) return null;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetSizePx);
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    /**
     * Decodes {@link #loadAttemptThumbnail} off the main thread and posts the resulting
     * bitmap (possibly null) back onto {@code target} on the UI thread. History rows and the
     * note/photo dialog both have their own photo per attempt — decoding synchronously on the
     * main thread, once per row, risked visible jank/ANR on slower devices/storage.
     */
    private void loadAttemptThumbnailAsync(String photoFileName, int targetSizePx,
                                            android.widget.ImageView target) {
        thumbnailExecutor.execute(() -> {
            android.graphics.Bitmap bmp = loadAttemptThumbnail(photoFileName, targetSizePx);
            runOnUiThread(() -> target.setImageBitmap(bmp));
        });
    }

    /**
     * Same downsampling approach as {@link #loadAttemptThumbnail}, but decoding straight from
     * a content {@link android.net.Uri} (the photo picker result) instead of a file on disk —
     * used for the picker preview so a full-resolution gallery photo isn't decoded just to
     * fill a small {@code ImageView}. Returns null if the uri can't be opened/decoded.
     */
    private static android.graphics.Bitmap decodeSampledBitmapFromUri(
            Context ctx, android.net.Uri uri, int targetSizePx) {
        android.content.ContentResolver resolver = ctx.getContentResolver();
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            android.graphics.BitmapFactory.decodeStream(in, null, bounds);
        } catch (java.io.IOException e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetSizePx);
        try (java.io.InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            return android.graphics.BitmapFactory.decodeStream(in, null, opts);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Smallest power-of-two {@code inSampleSize} that keeps both dimensions under 2x target. */
    private static int sampleSizeFor(int outWidth, int outHeight, int targetSizePx) {
        int sample = 1;
        while ((outWidth / sample) > targetSizePx * 2 || (outHeight / sample) > targetSizePx * 2) {
            sample *= 2;
        }
        return sample;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbnailExecutor.shutdown();
    }
}
