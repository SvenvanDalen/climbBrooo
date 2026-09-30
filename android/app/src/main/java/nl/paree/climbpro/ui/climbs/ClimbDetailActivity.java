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
import nl.paree.climbpro.data.weather.ClimateCache;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.power.DurationFormat;
import nl.paree.climbpro.domain.power.IntervalBlock;
import nl.paree.climbpro.domain.sun.SunriseCalculator;
import nl.paree.climbpro.domain.sun.SunriseRidePlanner;
import nl.paree.climbpro.domain.weather.BestTimeScorer;
import nl.paree.climbpro.domain.weather.ClimateNormals;
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

    // Issue #41 best-time section: cache checked once on open, network only on tap.
    private boolean bestTimeCacheChecked;
    private boolean bestTimeBusy;
    private boolean bestTimeLoaded;

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
            updateDescentInfo();
            loadBestTime(false);
        });

        viewModel.climb().observe(this, climb -> {
            if (climb == null) return;
            loadBestTime(false);

            String name = climb.userDisplayName != null ? climb.userDisplayName : climb.name;
            binding.toolbar.setTitle(name != null ? name
                    : getString(R.string.climb_detail_default_name, climbIndex + 1));
            String surfaceLabel = nl.paree.climbpro.domain.climb.ClimbSurfaceLabel.forStoredClimb(climb);
            String categoryLabel = nl.paree.climbpro.domain.climb.ClimbCategoryLabel.forStoredClimb(climb);
            String statsText = getString(R.string.climb_detail_stats,
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
                    ? R.string.climb_detail_home_on
                    : R.string.climb_detail_mark_home);
            binding.climbRating.setText(
                    nl.paree.climbpro.domain.climb.ClimbRating.detailText(climb));
            binding.btnRateClimb.setText(
                    nl.paree.climbpro.domain.climb.ClimbRating.isRated(climb)
                            || climb.ratingNote != null
                            ? R.string.climb_detail_rate_edit : R.string.climb_detail_rate);
            tryDrawMap();
            updateManualRefText();
            updateIntervalBlockText();
            updateDescentInfo();
        });

        viewModel.timeEstimate().observe(this, estimate -> {
            if (estimate == null) {
                binding.climbTimeEstimate.setText(R.string.climb_detail_estimate_missing);
                adapter.setSegmentSeconds(null);
                lastTimeEstimateText = null;
                lastEstimateSeconds = null;
            } else {
                lastTimeEstimateText = getString(R.string.climb_detail_estimate,
                        DurationFormat.format(estimate.totalSeconds),
                        estimate.assumedPowerWatts);
                binding.climbTimeEstimate.setText(lastTimeEstimateText);
                adapter.setSegmentSeconds(estimate.segmentSeconds);
                lastEstimateSeconds = estimate.totalSeconds;
            }
            updateManualRefText();
        });

        viewModel.segmentZones().observe(this, adapter::setSegmentZones);
        viewModel.windImpact().observe(this, this::showWindImpact);

        viewModel.seasonalComparison().observe(this, result -> {
            if (result == null) {
                binding.seasonalComparison.setVisibility(android.view.View.GONE);
                return;
            }
            binding.seasonalComparison.setText(getString(result.percentFaster >= 0
                            ? R.string.climb_detail_seasonal_faster
                            : R.string.climb_detail_seasonal_slower,
                    Math.abs(result.percentFaster), result.priorYear));
            binding.seasonalComparison.setVisibility(android.view.View.VISIBLE);
        });

        viewModel.trainingAdvice().observe(this, this::renderTrainingAdvice);
        viewModel.prChance().observe(this, this::showPrChance);

        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, isSaved -> {
            if (Boolean.TRUE.equals(isSaved))
                Toast.makeText(this, R.string.route_detail_saved, Toast.LENGTH_SHORT).show();
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
                String badge = row.bestOfYear
                        ? "  " + getString(R.string.climb_detail_history_best_of_year) : "";
                String deviationBadge = row.routeDeviation
                        ? "  " + getString(R.string.climb_detail_history_deviation) : "";
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

                String companionsLabel =
                        nl.paree.climbpro.domain.ride.SummitGroupPhotos.companionsLabel(row.companions);
                if (companionsLabel != null) {
                    android.widget.TextView groupView = new android.widget.TextView(this);
                    boolean hasPhoto = row.photoFileName != null && !row.photoFileName.isEmpty();
                    groupView.setText(hasPhoto
                            ? getString(R.string.climb_detail_group_photo, companionsLabel)
                            : "👥 " + companionsLabel);
                    groupView.setTextSize(13f);
                    groupView.setPadding(0, 4, 0, 0);
                    rowLayout.addView(groupView);
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
                        || row.companions != null
                        ? R.string.climb_detail_attempt_edit : R.string.climb_detail_attempt_add);
                editLink.setTextColor(getResources().getColor(nl.paree.climbpro.R.color.color_accent));
                editLink.setPadding(0, 8, 0, 0);
                editLink.setOnClickListener(v -> showAttemptNoteDialog(row));
                rowLayout.addView(editLink);

                container.addView(rowLayout);
            }
        });

        binding.btnRenameClimb.setOnClickListener(v -> showRenameDialog());
        binding.btnManualRef.setOnClickListener(v -> showManualRefDialog());
        binding.btnIntervalBlock.setOnClickListener(v -> showIntervalBlockDialog());
        binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());
        binding.btnEditShape.setOnClickListener(v -> showShapeOverrideDialog());
        binding.btnRateClimb.setOnClickListener(v -> showRatingDialog());
        binding.btnShareClimb.setOnClickListener(v -> shareClimbAsImage());
        binding.btnExportGpx.setOnClickListener(v -> viewModel.exportGpx());
        binding.btnExportWorkout.setOnClickListener(v -> pickWorkoutFormat());
        binding.btnShareCode.setOnClickListener(v -> {
            StoredRoute r = viewModel.route().getValue();
            if (r == null) {
                Toast.makeText(this, R.string.climb_detail_not_loaded, Toast.LENGTH_SHORT).show();
                return;
            }
            nl.paree.climbpro.ui.share.ClimbCodeSharing.shareClimb(this, r, climbIndex);
        });
        binding.btnToggleHomeClimb.setOnClickListener(v -> {
            if (loadedClimb == null) return;
            viewModel.setHomeClimb(routeId, climbIndex, !loadedClimb.isHome);
        });
        binding.btnSunriseRide.setOnClickListener(v -> pickSunriseDate());
        binding.btnSummitWeather.setOnClickListener(v -> showSummitWeather());
        binding.bestTime.setOnClickListener(v -> {
            if (bestTimeLoaded) {
                boolean shown = binding.bestTimeTable.getVisibility() == android.view.View.VISIBLE;
                binding.bestTimeTable.setVisibility(
                        shown ? android.view.View.GONE : android.view.View.VISIBLE);
            } else {
                loadBestTime(true);
            }
        });
        binding.btnCompareClimb.setOnClickListener(v ->
                startActivity(ClimbCompareActivity.intentFor(this, routeId, climbIndex)));
        binding.btnGearCalculator.setOnClickListener(v ->
                startActivity(GearCalculatorActivity.intentFor(this, routeId, climbIndex)));

        viewModel.gpxExportFile().observe(this, this::shareGpxFile);
        viewModel.workoutExport().observe(this, this::shareWorkout);
        viewModel.intervalsResult().observe(this, msg -> {
            if (msg == null) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            viewModel.consumeIntervalsResult();
        });

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
                    .setMessage(R.string.climb_detail_no_sunrise)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        ClimbTimeEstimate est = viewModel.timeEstimate().getValue();
        SunriseRidePlanner.Plan plan =
                SunriseRidePlanner.plan(sunrise, c.startDistance,
                        SunriseRidePlanner.DEFAULT_APPROACH_KMH,
                        est != null ? est.totalSeconds : 0, c.length,
                        SunriseRidePlanner.DEFAULT_BUFFER_MIN);
        ZoneId zone = ZoneId.systemDefault();
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        DateTimeFormatter dayHm =
                DateTimeFormatter.ofPattern("EEE d MMM HH:mm", locale);
        String name = c.userDisplayName != null ? c.userDisplayName
                : (c.name != null ? c.name
                        : getString(R.string.climb_detail_default_name, climbIndex + 1));
        String msg = getString(R.string.climb_detail_sunrise_message,
                hm.format(sunrise.atZone(zone)),
                hm.format(plan.arrivalTop.atZone(zone)),
                SunriseRidePlanner.DEFAULT_BUFFER_MIN,
                dayHm.format(plan.departure.atZone(zone)),
                String.format(locale, "%.1f", c.startDistance / 1000.0),
                DurationFormat.format(plan.approachSec),
                DurationFormat.format(plan.climbSec))
                + (est == null ? " " + getString(R.string.climb_detail_sunrise_estimate_default) : "");
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.climb_detail_sunrise_title, name))
                .setMessage(msg)
                .setPositiveButton(R.string.climb_detail_sunrise_plan, (d, w) -> saveSunrisePlan(plan, name))
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void saveSunrisePlan(SunriseRidePlanner.Plan plan, String name) {
        if (SunriseRidePlanner.isInPast(plan, Instant.now())) {
            Toast.makeText(this, R.string.climb_detail_sunrise_past, Toast.LENGTH_LONG).show();
            return;
        }
        PlannedClimb p = new PlannedClimb(
                UUID.randomUUID().toString(), routeId, climbIndex,
                getString(R.string.climb_detail_sunrise_planned_name, name),
                plan.departure.getEpochSecond(), System.currentTimeMillis());
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                new PlannedClimbRepository(app).add(p);
                PlannedClimbWorkScheduler.schedule(app, p);
                runOnUiThread(() -> Toast.makeText(app, R.string.climb_detail_sunrise_planned,
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(app, getString(R.string.climb_detail_sunrise_failed,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
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

    /**
     * Issue #215: what the descent after this climb looks like on this route. Hidden while the
     * route is loading or when it has no geometry.
     */
    private void updateDescentInfo() {
        if (loadedRoute == null || loadedClimb == null || loadedRoute.elevations == null) {
            binding.descentInfo.setVisibility(android.view.View.GONE);
            return;
        }
        binding.descentInfo.setText(nl.paree.climbpro.domain.climb.DescentLabel.format(
                nl.paree.climbpro.domain.climb.DescentAnalyzer.analyze(loadedRoute, climbIndex)));
        binding.descentInfo.setVisibility(android.view.View.VISIBLE);
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
            Toast.makeText(this, R.string.climb_detail_not_loaded, Toast.LENGTH_SHORT).show();
            return;
        }
        String title = loadedClimb.userDisplayName != null
                ? loadedClimb.userDisplayName
                : (loadedClimb.name != null ? loadedClimb.name
                        : getString(R.string.climb_detail_default_name, climbIndex + 1));
        try {
            android.graphics.Bitmap bitmap = ClimbShareImageComposer.compose(
                    this, title, loadedClimb, lastTimeEstimateText);
            java.io.File file = ClimbShareHandoff.writeShareImage(this, bitmap);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent share = ClimbShareHandoff.buildShareIntent(uri);
            startActivity(Intent.createChooser(share, getString(R.string.climb_detail_share_chooser)));
        } catch (java.io.IOException e) {
            Toast.makeText(this, R.string.climb_detail_image_failed, Toast.LENGTH_SHORT).show();
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
        startActivity(Intent.createChooser(share, getString(R.string.climb_detail_gpx_chooser)));
    }

    /**
     * Issue #223: choose Zwift or ERG, then export the climb as an indoor workout. Issue #19
     * adds the "N× deze klim" variants, which first ask for repeats and recovery; issue #85
     * adds a MyWhoosh-compatible {@code .zwo}; issue #78 plans it on intervals.icu.
     */
    private void pickWorkoutFormat() {
        String[] formats = {getString(R.string.climb_detail_workout_zwift),
                getString(R.string.climb_detail_workout_erg),
                getString(R.string.climb_detail_workout_repeat_zwift),
                getString(R.string.climb_detail_workout_repeat_erg),
                getString(R.string.climb_detail_workout_mywhoosh),
                getString(R.string.intervals_format_single),
                getString(R.string.intervals_format_repeat)};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_workout_title)
                .setItems(formats, (d, which) -> {
                    if (which == 0) viewModel.exportWorkout(ClimbDetailViewModel.WorkoutFormat.ZWIFT);
                    else if (which == 1) viewModel.exportWorkout(ClimbDetailViewModel.WorkoutFormat.ERG);
                    else if (which == 4) viewModel.exportWorkout(ClimbDetailViewModel.WorkoutFormat.MYWHOOSH);
                    else if (which == 5) startIntervalsPush(1, ClimbDetailViewModel.RECOVERY_AUTO);
                    else if (which == 6) showRepeatWorkoutDialog(getString(R.string.intervals_send),
                            this::startIntervalsPush);
                    else showRepeatWorkoutDialog(getString(R.string.climb_detail_export),
                            (reps, rec) -> viewModel.exportWorkout(
                            which == 2 ? ClimbDetailViewModel.WorkoutFormat.ZWIFT
                                    : ClimbDetailViewModel.WorkoutFormat.ERG, reps, rec));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Max recovery (min) offered in the repeat dialog; index 0 of the picker is "auto". */
    private static final int REPEAT_RECOVERY_MAX_MIN = 15;

    /** Receives the repeat dialog's choice; recovery is {@link ClimbDetailViewModel#RECOVERY_AUTO} or seconds. */
    private interface RepeatAction {
        void run(int repeats, int recoverySec);
    }

    /** Issue #19: pick the number of repeats and the recovery between them. */
    private void showRepeatWorkoutDialog(String positiveLabel, RepeatAction action) {
        android.widget.NumberPicker repeats = new android.widget.NumberPicker(this);
        repeats.setMinValue(nl.paree.climbpro.domain.export.ClimbWorkoutWriter.MIN_REPEATS);
        repeats.setMaxValue(nl.paree.climbpro.domain.export.ClimbWorkoutWriter.MAX_REPEATS);
        repeats.setValue(nl.paree.climbpro.domain.export.ClimbWorkoutWriter.DEFAULT_REPEATS);
        repeats.setFormatter(v -> v + "×");

        String[] recoveryLabels = new String[REPEAT_RECOVERY_MAX_MIN + 1];
        recoveryLabels[0] = getString(R.string.climb_detail_repeat_auto);
        for (int m = 1; m <= REPEAT_RECOVERY_MAX_MIN; m++) {
            recoveryLabels[m] = getString(R.string.climb_detail_repeat_minutes, m);
        }
        android.widget.NumberPicker recovery = new android.widget.NumberPicker(this);
        recovery.setMinValue(0);
        recovery.setMaxValue(REPEAT_RECOVERY_MAX_MIN);
        recovery.setDisplayedValues(recoveryLabels);
        recovery.setValue(0);

        android.widget.LinearLayout pickers = new android.widget.LinearLayout(this);
        pickers.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        pickers.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pickers.addView(labeled(getString(R.string.climb_detail_repeat_count), repeats), lp);
        pickers.addView(labeled(getString(R.string.climb_detail_repeat_recovery), recovery), lp);

        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_repeat_title)
                .setMessage(R.string.climb_detail_repeat_message)
                .setView(pickers)
                .setPositiveButton(positiveLabel, (d, w) -> action.run(
                        repeats.getValue(), recovery.getValue() == 0
                                ? ClimbDetailViewModel.RECOVERY_AUTO
                                : recovery.getValue() * 60))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Issue #78: plan the workout on intervals.icu — needs a linked key, then a date and
     * whether it is ridden indoors ({@code VirtualRide}) or outside ({@code Ride}).
     */
    private void startIntervalsPush(int repeats, int recoverySec) {
        if (!viewModel.isIntervalsConfigured()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.intervals_title)
                    .setMessage(R.string.intervals_not_configured)
                    .setPositiveButton(R.string.intervals_open_settings, (d, w) -> startActivity(
                            new Intent(this, nl.paree.climbpro.ui.settings.IntervalsIcuSettingsActivity.class)))
                    .setNegativeButton(R.string.intervals_cancel, null)
                    .show();
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        android.app.DatePickerDialog picker = new android.app.DatePickerDialog(this,
                (view, y, m, d) -> pickIntervalsType(repeats, recoverySec,
                        java.time.LocalDate.of(y, m + 1, d)),
                today.getYear(), today.getMonthValue() - 1, today.getDayOfMonth());
        picker.setTitle(R.string.intervals_pick_date);
        picker.show();
    }

    private void pickIntervalsType(int repeats, int recoverySec, java.time.LocalDate date) {
        String[] types = {getString(R.string.intervals_type_indoor),
                getString(R.string.intervals_type_outdoor)};
        final int[] choice = {0};
        new AlertDialog.Builder(this)
                .setTitle(R.string.intervals_pick_type)
                .setSingleChoiceItems(types, 0, (d, which) -> choice[0] = which)
                .setPositiveButton(R.string.intervals_send, (d, w) ->
                        viewModel.pushToIntervals(repeats, recoverySec, date, choice[0] == 0))
                .setNegativeButton(R.string.intervals_cancel, null)
                .show();
    }

    private android.view.View labeled(String label, android.view.View child) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(label);
        col.addView(tv);
        col.addView(child);
        return col;
    }

    private void shareWorkout(ClimbDetailViewModel.WorkoutExport export) {
        if (export == null) return;
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", export.file);
        Intent share = ClimbWorkoutExportHandoff.buildShareIntent(uri, export.mime);
        if (export.format != ClimbDetailViewModel.WorkoutFormat.MYWHOOSH) {
            startActivity(Intent.createChooser(share, getString(R.string.climb_detail_workout_share)));
            return;
        }
        // MyWhoosh has no import folder or share target: the file goes in via its web builder.
        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_mywhoosh_title)
                .setMessage(R.string.climb_detail_mywhoosh_message)
                .setPositiveButton(R.string.action_share, (d, w) -> startActivity(
                        Intent.createChooser(share, getString(R.string.climb_detail_mywhoosh_chooser))))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Issue #47: wind-corrected estimate and its delta, or a clear "uncorrected" label. */
    private void showWindImpact(ClimbDetailViewModel.WindImpactState state) {
        android.widget.TextView view = binding.climbWindImpact;
        if (state == null) {
            view.setVisibility(android.view.View.GONE);
            return;
        }
        view.setVisibility(android.view.View.VISIBLE);
        if (state.loading) {
            view.setText(R.string.wind_impact_loading);
            return;
        }
        nl.paree.climbpro.domain.power.WindImpactEstimator.Result r = state.result;
        if (r == null) {
            view.setText(R.string.wind_impact_unavailable);
            return;
        }
        int windKmh = (int) Math.round(r.windKmh);
        String from = getResources().getStringArray(R.array.wind_compass_points)[
                nl.paree.climbpro.domain.power.WindImpactEstimator.compassSector(r.windFromDeg)];
        String total = DurationFormat.format(state.windTotalSeconds());
        String delta = DurationFormat.format(Math.abs(r.deltaSeconds));
        switch (r.verdict()) {
            case HEADWIND:
                view.setText(getString(R.string.wind_impact_headwind, total, delta, windKmh, from));
                break;
            case TAILWIND:
                view.setText(getString(R.string.wind_impact_tailwind, total, delta, windKmh, from));
                break;
            default:
                view.setText(getString(R.string.wind_impact_negligible, windKmh, from));
                break;
        }
    }

    /** Issue #58: PR chance for today with the main reasons, weather left out when offline. */
    private void showPrChance(ClimbDetailViewModel.PrChance pc) {
        if (pc == null || pc.prediction == null) {
            binding.prChance.setVisibility(android.view.View.GONE);
            return;
        }
        nl.paree.climbpro.domain.climb.PrChancePredictor.Prediction p = pc.prediction;
        StringBuilder sb = new StringBuilder();
        if (p.firstAttempt) {
            sb.append(getString(nl.paree.climbpro.R.string.pr_chance_first_title)).append('\n')
                    .append(getString(nl.paree.climbpro.R.string.pr_chance_first_attempt));
        } else {
            sb.append(getString(nl.paree.climbpro.R.string.pr_chance_title, chanceLabel(p.chance)))
                    .append('\n')
                    .append(getResources().getQuantityString(
                            nl.paree.climbpro.R.plurals.pr_chance_pr_line, p.attemptCount,
                            DurationFormat.format(p.prSec), p.attemptCount));
            for (nl.paree.climbpro.domain.climb.PrChancePredictor.Reason r : p.reasons) {
                sb.append("\n• ").append(reasonText(r));
            }
            if (!pc.weatherIncluded) {
                sb.append("\n• ").append(getString(nl.paree.climbpro.R.string.pr_chance_weather_unknown));
            }
        }
        binding.prChance.setText(sb.toString());
        binding.prChance.setVisibility(android.view.View.VISIBLE);
    }

    private String chanceLabel(nl.paree.climbpro.domain.climb.PrChancePredictor.Chance c) {
        switch (c) {
            case GOOD: return getString(nl.paree.climbpro.R.string.pr_chance_good);
            case MODERATE: return getString(nl.paree.climbpro.R.string.pr_chance_moderate);
            default: return getString(nl.paree.climbpro.R.string.pr_chance_unlikely);
        }
    }

    private String reasonText(nl.paree.climbpro.domain.climb.PrChancePredictor.Reason r) {
        double v = r.value;
        int n = Double.isNaN(v) ? 0 : (int) Math.round(v);
        switch (r.factor) {
            case FIRST_ATTEMPT: return getString(nl.paree.climbpro.R.string.pr_chance_first_attempt);
            case RECENT_PR: return getString(nl.paree.climbpro.R.string.pr_chance_reason_recent_pr, n);
            case CLOSE_TO_PR: return getString(nl.paree.climbpro.R.string.pr_chance_reason_close_to_pr, v);
            case FAR_FROM_PR: return getString(nl.paree.climbpro.R.string.pr_chance_reason_far_from_pr, v);
            case NO_RECENT_ATTEMPT:
                return Double.isNaN(v)
                        ? getString(nl.paree.climbpro.R.string.pr_chance_reason_no_recent_undated)
                        : getString(nl.paree.climbpro.R.string.pr_chance_reason_no_recent, n);
            case FEW_ATTEMPTS:
                return getResources().getQuantityString(
                        nl.paree.climbpro.R.plurals.pr_chance_reason_few_attempts, n, n);
            case FITTER_THAN_PR: return getString(nl.paree.climbpro.R.string.pr_chance_reason_fitter, v);
            case LESS_FIT_THAN_PR: return getString(nl.paree.climbpro.R.string.pr_chance_reason_less_fit, v);
            case FRESH: return getString(nl.paree.climbpro.R.string.pr_chance_reason_fresh, v);
            case NEUTRAL_FORM: return getString(nl.paree.climbpro.R.string.pr_chance_reason_neutral_form, v);
            case TIRED: return getString(nl.paree.climbpro.R.string.pr_chance_reason_tired, v);
            case VERY_TIRED: return getString(nl.paree.climbpro.R.string.pr_chance_reason_very_tired, v);
            case WEATHER_IDEAL: return getString(nl.paree.climbpro.R.string.pr_chance_reason_weather_ideal, v);
            case WEATHER_RAIN: return getString(nl.paree.climbpro.R.string.pr_chance_reason_weather_rain, v);
            case WEATHER_WIND: return getString(nl.paree.climbpro.R.string.pr_chance_reason_weather_wind, v);
            case WEATHER_COLD: return getString(nl.paree.climbpro.R.string.pr_chance_reason_weather_cold, v);
            default: return getString(nl.paree.climbpro.R.string.pr_chance_reason_weather_hot, v);
        }
    }

    /** Issue #246: valley vs summit weather, now and in 3 hours (Open-Meteo, off the UI thread). */
    private void showSummitWeather() {
        StoredRoute r = viewModel.route().getValue();
        StoredClimb c = viewModel.climb().getValue();
        if (r == null || c == null) return;
        binding.btnSummitWeather.setEnabled(false); // one request at a time, no stacked dialogs
        Toast.makeText(this, R.string.climb_detail_weather_loading, Toast.LENGTH_SHORT).show();
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
                if (nowText != null) {
                    sb.append(getString(R.string.climb_detail_weather_now)).append('\n').append(nowText);
                }
                if (laterText != null) {
                    sb.append(sb.length() > 0 ? "\n\n" : "")
                            .append(getString(R.string.climb_detail_weather_later))
                            .append('\n').append(laterText);
                }
                msg = sb.length() > 0 ? sb.toString() : getString(R.string.climb_detail_weather_none);
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                msg = getString(R.string.climb_detail_weather_failed, reason);
            }
            String text = msg + "\n\n" + getString(R.string.source_open_meteo);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.btnSummitWeather.setEnabled(true);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.climb_detail_summit_weather)
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "summit-weather").start();
    }

    /**
     * Issue #41: best months and part of the day for this climb from a cached climatology
     * (Open-Meteo archive). {@code fetch=false} only reads the offline cache; a tap fetches.
     */
    private void loadBestTime(boolean fetch) {
        StoredRoute r = viewModel.route().getValue();
        StoredClimb c = viewModel.climb().getValue();
        if (r == null || c == null || bestTimeBusy || bestTimeLoaded) return;
        if (!fetch && bestTimeCacheChecked) return;
        bestTimeCacheChecked = true;
        bestTimeBusy = true;
        if (fetch) binding.bestTime.setText(R.string.climb_detail_best_loading);
        ClimbEndpoints.Point foot = ClimbEndpoints.foot(r, c);
        ClimbEndpoints.Point top = ClimbEndpoints.top(r, c);
        double bearing = ClimateNormals.bearingDeg(foot.lat, foot.lon, top.lat, top.lon);
        double elevation = Double.isNaN(foot.elevationM) ? top.elevationM
                : Double.isNaN(top.elevationM) ? foot.elevationM
                : (foot.elevationM + top.elevationM) / 2;
        java.io.File filesDir = getFilesDir();
        new Thread(() -> {
            ClimateCache cache = new ClimateCache(filesDir);
            ClimateNormals normals = cache.load(foot.lat, foot.lon);
            String error = null;
            if (normals == null && fetch) {
                try {
                    normals = new OpenMeteoClient().fetchClimate(
                            foot.lat, foot.lon, elevation, LocalDate.now().getYear());
                    cache.save(foot.lat, foot.lon, normals);
                } catch (java.net.UnknownHostException | java.net.ConnectException
                         | java.io.InterruptedIOException e) { // offline or timed out
                    error = getString(R.string.climb_detail_best_offline);
                } catch (Exception e) {
                    String reason = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName();
                    error = getString(R.string.climb_detail_best_failed, reason);
                }
            }
            BestTimeScorer.Result result =
                    normals == null ? null : BestTimeScorer.evaluate(normals, bearing);
            String message = error;
            boolean hadData = normals != null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bestTimeBusy = false;
                if (result != null) {
                    bestTimeLoaded = true;
                    binding.bestTime.setText(getString(R.string.climb_detail_best, result.summary()));
                    binding.bestTimeTable.setText(result.monthTable()
                            + "\n" + getString(R.string.climb_detail_best_footer));
                    binding.bestTimeTable.setVisibility(android.view.View.VISIBLE);
                } else if (message != null) {
                    binding.bestTime.setText(message);
                } else if (hadData) {
                    binding.bestTime.setText(R.string.climb_detail_best_no_history);
                }
            });
        }, "best-time").start();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.climb_detail_rename_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_rename)
                .setView(input)
                .setPositiveButton(R.string.action_save, (d, w) ->
                        viewModel.renameClimb(routeId, climbIndex,
                                input.getText().toString().trim()))
                .setNegativeButton(R.string.action_cancel, null)
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
        timeInput.setHint(R.string.climb_detail_ref_time_hint);
        StoredClimb current = viewModel.climb().getValue();
        if (current != null && current.manualRefSec != null) {
            timeInput.setText(DurationFormat.format(current.manualRefSec));
        }
        container.addView(timeInput);

        EditText labelInput = new EditText(this);
        labelInput.setHint(R.string.climb_detail_ref_source_hint);
        if (current != null && current.manualRefLabel != null) {
            labelInput.setText(current.manualRefLabel);
        }
        container.addView(labelInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_ref_title)
                .setView(container)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    Integer sec = parseDurationToSeconds(timeInput.getText().toString().trim());
                    if (sec == null) {
                        Toast.makeText(this, R.string.climb_detail_ref_invalid,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.setManualRefTime(routeId, climbIndex, sec,
                            labelInput.getText().toString().trim());
                })
                .setNeutralButton(R.string.action_clear, (d, w) ->
                        viewModel.setManualRefTime(routeId, climbIndex, null, null))
                .setNegativeButton(R.string.action_cancel, null)
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
        sb.append(getString(R.string.climb_detail_ref_text, DurationFormat.format(c.manualRefSec)));
        if (c.manualRefLabel != null && !c.manualRefLabel.isEmpty()) {
            sb.append(" (").append(c.manualRefLabel).append(")");
        }
        if (lastEstimateSeconds != null) {
            int deltaSec = lastEstimateSeconds - c.manualRefSec;
            String sign = deltaSec >= 0 ? "+" : "-";
            sb.append(" · ").append(getString(R.string.climb_detail_ref_estimate,
                    sign + DurationFormat.format(Math.abs(deltaSec))));
        }
        binding.climbManualRef.setText(sb.toString());
        binding.climbManualRef.setVisibility(android.view.View.VISIBLE);
    }

    /**
     * Issue #180: attach an interval block to this climb — a preset or a custom target % FTP.
     * The block runs from the foot to the top: the watch starts it at the climb-start alert and
     * shows the power band, and the indoor workout export uses it instead of gradient pacing.
     */
    private void showIntervalBlockDialog() {
        IntervalBlock.Preset[] presets = IntervalBlock.Preset.values();
        String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = presets[i] == IntervalBlock.Preset.CUSTOM
                    ? getString(R.string.climb_detail_interval_custom)
                    : IntervalBlock.of(presets[i]).label();
        }
        IntervalBlock current = loadedClimb != null
                ? IntervalBlock.fromStored(loadedClimb.intervalBlock) : null;
        int checked = current != null ? current.preset.ordinal() : -1;

        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_interval_title)
                .setSingleChoiceItems(labels, checked, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    int chosen = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    if (chosen < 0 || chosen >= presets.length) return;
                    if (presets[chosen] == IntervalBlock.Preset.CUSTOM) {
                        showCustomIntervalBlockDialog(current);
                    } else {
                        viewModel.setIntervalBlock(routeId, climbIndex,
                                IntervalBlock.of(presets[chosen]));
                    }
                })
                .setNeutralButton(R.string.action_delete, (d, w) ->
                        viewModel.setIntervalBlock(routeId, climbIndex, null))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showCustomIntervalBlockDialog(IntervalBlock current) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.climb_detail_interval_custom_hint,
                IntervalBlock.MIN_TARGET_PCT, IntervalBlock.MAX_TARGET_PCT));
        if (current != null && current.preset == IntervalBlock.Preset.CUSTOM) {
            input.setText(String.valueOf(Math.round(current.targetFraction() * 100)));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_interval_custom_title)
                .setView(input)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    try {
                        int pct = Integer.parseInt(input.getText().toString().trim());
                        viewModel.setIntervalBlock(routeId, climbIndex, IntervalBlock.custom(pct));
                    } catch (IllegalArgumentException e) {
                        // NumberFormatException is an IllegalArgumentException too.
                        Toast.makeText(this, getString(R.string.climb_detail_interval_custom_invalid,
                                IntervalBlock.MIN_TARGET_PCT, IntervalBlock.MAX_TARGET_PCT),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Shows the attached interval block with its watts at the current FTP, if any. */
    private void updateIntervalBlockText() {
        IntervalBlock block = loadedClimb != null
                ? IntervalBlock.fromStored(loadedClimb.intervalBlock) : null;
        if (block == null) {
            binding.climbIntervalBlock.setVisibility(android.view.View.GONE);
            binding.btnIntervalBlock.setText(R.string.climb_detail_link_interval);
            return;
        }
        StringBuilder sb = new StringBuilder(
                getString(R.string.climb_detail_interval_text, block.label()));
        int[] watts = block.wireWatts(viewModel.ftpWatts());
        if (watts != null) {
            sb.append(" · ").append(watts[1]).append("–").append(watts[2]).append(" W");
        } else {
            sb.append(" · ").append(getString(R.string.climb_detail_interval_no_ftp));
        }
        binding.climbIntervalBlock.setText(sb.toString());
        binding.climbIntervalBlock.setVisibility(android.view.View.VISIBLE);
        binding.btnIntervalBlock.setText(R.string.climb_detail_interval_edit);
    }

    private void showReSegmentDialog() {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(4);
        picker.setMaxValue(32);
        nl.paree.climbpro.data.route.StoredClimb current = viewModel.climb().getValue();
        int defaultCount = (current != null && current.segmentCount > 0) ? current.segmentCount : 16;
        picker.setValue(defaultCount);
        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_resegment_title)
                .setView(picker)
                .setPositiveButton(R.string.climb_detail_resegment_confirm, (dialog, which) ->
                        viewModel.reSegment(routeId, climbIndex, picker.getValue()))
                .setNegativeButton(R.string.action_cancel, null)
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
        labels[0] = getString(R.string.climb_detail_shape_auto);
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
                .setTitle(R.string.climb_detail_shape_title)
                .setSingleChoiceItems(labels, current, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    android.widget.ListView lv = ((AlertDialog) dialog).getListView();
                    int chosen = lv.getCheckedItemPosition();
                    String shapeName = (chosen >= 1 && chosen <= shapes.length)
                            ? shapes[chosen - 1].name() : null;
                    viewModel.setShapeOverride(routeId, climbIndex, shapeName);
                })
                .setNegativeButton(R.string.action_cancel, null)
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
        android.widget.RatingBar road = addRatingRow(container,
                getString(R.string.climb_detail_rating_road), c != null ? c.ratingRoad : null);
        android.widget.RatingBar traffic = addRatingRow(container,
                getString(R.string.climb_detail_rating_traffic), c != null ? c.ratingTraffic : null);
        android.widget.RatingBar view = addRatingRow(container,
                getString(R.string.climb_detail_rating_view), c != null ? c.ratingView : null);

        EditText note = new EditText(this);
        note.setHint(R.string.climb_detail_note_optional);
        if (c != null && c.ratingNote != null) note.setText(c.ratingNote);
        container.addView(note);

        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_rate)
                .setView(container)
                .setPositiveButton(R.string.action_save, (d, w) -> viewModel.setRating(routeId, climbIndex,
                        starsOrNull(road.getRating()),
                        starsOrNull(traffic.getRating()),
                        starsOrNull(view.getRating()),
                        note.getText().toString()))
                .setNeutralButton(R.string.action_clear, (d, w) ->
                        viewModel.setRating(routeId, climbIndex, null, null, null, null))
                .setNegativeButton(R.string.action_cancel, null)
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
        String[] typeLabels = java.util.Arrays.copyOf(
                getResources().getStringArray(R.array.surface_labels), SurfaceType.MIXED + 1);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, typeLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerSurfaceType.setAdapter(spinnerAdapter);

        binding.btnBulkSurface.setOnClickListener(v -> {
            int selected = binding.spinnerSurfaceType.getSelectedItemPosition();
            if (loadedClimb != null && loadedClimb.segments != null && !loadedClimb.segments.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.climb_detail_bulk_surface_title)
                        .setMessage(R.string.climb_detail_bulk_surface_message)
                        .setPositiveButton(R.string.action_apply, (d, w) ->
                                viewModel.setBulkSurfaceType(routeId, climbIndex, selected))
                        .setNegativeButton(R.string.action_cancel, null)
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
                .setTitle(getString(R.string.climb_detail_segment_target_title, segmentIndex + 1))
                .setMessage(R.string.climb_detail_segment_target_message)
                .setView(input)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    Integer seconds = parseMmSs(input.getText().toString().trim());
                    if (seconds == null) {
                        Toast.makeText(this, R.string.climb_detail_segment_target_invalid,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.setSegmentManualTargetSec(routeId, climbIndex, segmentIndex, seconds);
                })
                .setNegativeButton(R.string.action_cancel, null);
        if (segment.manualTargetSec != null) {
            builder.setNeutralButton(R.string.climb_detail_shape_auto,
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
        String[] typeLabels = getResources().getStringArray(R.array.surface_labels);
        int current = SurfaceType.fromInt(segment.surfaceType);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.climb_detail_segment_surface_title, segmentIndex + 1))
                .setSingleChoiceItems(typeLabels, current, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    android.widget.ListView lv =
                            ((AlertDialog) dialog).getListView();
                    int chosen = lv.getCheckedItemPosition();
                    if (chosen >= 0 && chosen <= 5) {
                        viewModel.setSurfaceType(routeId, climbIndex, segmentIndex, chosen);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Shows the pacing advice for the latest attempt (issue #64), or hides the block. */
    private void renderTrainingAdvice(ClimbDetailViewModel.TrainingAdvice result) {
        android.widget.TextView view = binding.trainingAdvice;
        if (result == null || result.advice.tips.isEmpty()) {
            view.setVisibility(android.view.View.GONE);
            return;
        }
        String date = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                .format(new java.util.Date(result.attemptDateEpochSec * 1000L));
        StringBuilder sb = new StringBuilder(getString(nl.paree.climbpro.R.string.training_advice_header, date));
        for (nl.paree.climbpro.domain.climb.ClimbPacingAdvisor.Tip tip : result.advice.tips) {
            sb.append('\n').append(getString(nl.paree.climbpro.R.string.training_advice_bullet, trainingTipText(tip)));
        }
        view.setText(sb.toString());
        view.setVisibility(android.view.View.VISIBLE);
    }

    private String trainingTipText(nl.paree.climbpro.domain.climb.ClimbPacingAdvisor.Tip tip) {
        switch (tip.type) {
            case FADED:
                return getString(nl.paree.climbpro.R.string.training_advice_faded, tip.startM, tip.percent);
            case HELD_BACK:
                return getString(nl.paree.climbpro.R.string.training_advice_held_back, tip.percent, tip.startM);
            case EVEN:
                return getString(nl.paree.climbpro.R.string.training_advice_even, tip.percent);
            case WEAKEST_SEGMENT:
                return getString(nl.paree.climbpro.R.string.training_advice_weakest, tip.startM / 1000.0,
                        tip.endM / 1000.0, tip.gradient * 100, tip.percent);
            case LOST_MOST_VS_PR:
            default:
                return getString(nl.paree.climbpro.R.string.training_advice_lost_vs_pr, tip.startM / 1000.0,
                        tip.endM / 1000.0, tip.seconds);
        }
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
        noteInput.setHint(R.string.climb_detail_note);
        noteInput.setText(row.note);
        dialogLayout.addView(noteInput);

        EditText companionsInput = new EditText(this);
        companionsInput.setHint(R.string.climb_detail_companions_hint);
        companionsInput.setText(row.companions);
        companionsInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        dialogLayout.addView(companionsInput);

        android.widget.TextView groupHint = new android.widget.TextView(this);
        groupHint.setText(R.string.climb_detail_group_hint);
        groupHint.setTextSize(12f);
        dialogLayout.addView(groupHint);

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
        pickPhotoButton.setText(row.photoFileName != null
                ? R.string.climb_detail_photo_change : R.string.climb_detail_photo_pick);
        pickPhotoButton.setOnClickListener(v -> photoPickerLauncher.launch("image/*"));
        dialogLayout.addView(pickPhotoButton);

        new AlertDialog.Builder(this)
                .setTitle(R.string.climb_detail_attempt_title)
                .setView(dialogLayout)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    viewModel.saveAttemptNote(routeId, climbIndex, row.activityId, row.passIndex,
                            noteInput.getText().toString(),
                            companionsInput.getText().toString(), pendingPhotoUri);
                    pendingAttemptRow = null;
                    pendingPhotoUri = null;
                    pendingPhotoPreview = null;
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
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
