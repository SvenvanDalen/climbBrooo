package nl.paree.climbpro.ui.explore;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

import java.util.Locale;

/**
 * Explore-the-region map (issue #194): every ~150 m cell you rode through outdoors is
 * coloured on an osmdroid map, with the number of cells and an estimate of the road length
 * explored. Built phone-side from Strava ride tracks; nothing goes to the watch.
 */
public final class ExploreMapActivity extends AppCompatActivity {

    /** Semi-transparent orange: readable on the light OSM tiles and in both themes. */
    private static final int TILE_COLOR = 0xB0FF6D00;

    private ExploreMapViewModel vm;
    private MapView map;
    private Overlay tilesOverlay;
    private boolean zoomed;

    public static Intent intentFor(Context ctx) {
        return new Intent(ctx, ExploreMapActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_map);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.explore_map_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        map = findViewById(R.id.explore_map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(5.0);
        map.getController().setCenter(new GeoPoint(50.8, 5.7));

        TextView summary = findViewById(R.id.explore_summary);
        TextView pending = findViewById(R.id.explore_pending);
        Button process = findViewById(R.id.explore_process);
        summary.setText(R.string.explore_map_loading);

        vm = new ViewModelProvider(this).get(ExploreMapViewModel.class);
        vm.state().observe(this, s -> render(s, summary, pending));
        vm.busy().observe(this, b -> {
            boolean isBusy = Boolean.TRUE.equals(b);
            process.setEnabled(!isBusy);
            process.setText(isBusy ? R.string.explore_map_processing : R.string.explore_map_process);
        });
        vm.message().observe(this, m -> {
            if (m != null) Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        });
        process.setOnClickListener(v -> vm.processMore());
        vm.load();
    }

    private void render(ExploreMapViewModel.State s, TextView summary, TextView pending) {
        if (s == null) return;
        if (s.tileCount == 0) {
            summary.setText(R.string.explore_map_empty);
        } else {
            summary.setText(getString(R.string.explore_map_summary, s.tileCount,
                    String.format(Locale.getDefault(), "%.0f", s.exploredKm), s.rideCount));
        }
        if (s.pending < 0) {
            pending.setText(R.string.explore_map_no_strava);
        } else if (s.pending == 0) {
            pending.setText(R.string.explore_map_all_done);
        } else {
            pending.setText(getString(R.string.explore_map_pending, s.pending));
        }

        if (tilesOverlay != null) map.getOverlays().remove(tilesOverlay);
        tilesOverlay = new ExploredTilesOverlay(s.bounds, TILE_COLOR);
        map.getOverlays().add(tilesOverlay);
        if (!zoomed && s.bounds.length >= 4) {
            zoomed = true;
            double south = 90, west = 180, north = -90, east = -180;
            for (int i = 0; i + 3 < s.bounds.length; i += 4) {
                south = Math.min(south, s.bounds[i]);
                west = Math.min(west, s.bounds[i + 1]);
                north = Math.max(north, s.bounds[i + 2]);
                east = Math.max(east, s.bounds[i + 3]);
            }
            BoundingBox box = new BoundingBox(north, east, south, west);
            map.post(() -> map.zoomToBoundingBox(box.increaseByScale(1.2f), false));
        }
        map.invalidate();
    }

    @Override protected void onResume() { super.onResume(); map.onResume(); }

    @Override protected void onPause() { super.onPause(); map.onPause(); }
}
