package nl.paree.climbpro.ui.routes;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.poi.OverpassClient;
import nl.paree.climbpro.data.poi.RoutePoiCache;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.poi.OverpassQueryBuilder;
import nl.paree.climbpro.domain.poi.RoutePoi;
import nl.paree.climbpro.domain.poi.RoutePoiLocator;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Bezienswaardigheden" (issue #208): viewpoints, monuments, castles and the like within
 * {@link #MAX_OFFSET_M} of a route, from OpenStreetMap via Overpass. Fetched once and cached
 * per route, so the list also works offline; "Vernieuwen" fetches again. Tapping a row opens
 * the spot in a map app via a {@code geo:} intent. Phone-only, nothing goes to the watch.
 */
public final class RoutePoiActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";
    /** How far beside the route a POI may lie to be listed. */
    static final int MAX_OFFSET_M = 300;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private String routeId;
    private TextView status;
    private Button refresh;
    private PoiAdapter adapter;

    public static Intent intentFor(Context ctx, String routeId) {
        Intent i = new Intent(ctx, RoutePoiActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_pois);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        routeId = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        status = findViewById(R.id.status);
        refresh = findViewById(R.id.btn_refresh);
        ListView list = findViewById(R.id.list);
        adapter = new PoiAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                openInMap(adapter.getItem(position)));
        refresh.setOnClickListener(v -> load(true));

        load(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** Cache first (offline); the network only when missing, stale or {@code force}. */
    private void load(boolean force) {
        refresh.setEnabled(false);
        status.setText(R.string.route_pois_loading);
        io.execute(() -> {
            String message;
            List<RoutePoi> pois = null;
            try {
                StoredRoute route = new RouteRepository(this).loadRoute(routeId);
                String fp = RoutePoiCache.fingerprint(route.lats, route.lons);
                RoutePoiCache cache = new RoutePoiCache(getFilesDir());
                RoutePoiCache.Entry cached = force ? null : cache.load(routeId, fp);
                if (cached != null) {
                    pois = cached.pois;
                    message = summary(pois.size(), cached.fetchedAtMs);
                } else {
                    String query = OverpassQueryBuilder.build(route.lats, route.lons, MAX_OFFSET_M);
                    if (query == null) {
                        message = getString(R.string.route_pois_no_route);
                    } else {
                        pois = RoutePoiLocator.locate(new OverpassClient().fetch(query),
                                route.lats, route.lons, MAX_OFFSET_M);
                        long now = System.currentTimeMillis();
                        cache.save(routeId, fp, now, pois);
                        message = summary(pois.size(), now);
                    }
                }
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName();
                message = getString(R.string.route_pois_failed, reason);
            }
            final List<RoutePoi> result = pois;
            final String text = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                refresh.setEnabled(true);
                status.setText(text);
                // A failed refresh keeps the list that was already shown.
                if (result != null) adapter.setPois(result);
            });
        });
    }

    private String summary(int count, long fetchedAtMs) {
        String when = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(fetchedAtMs));
        return count == 0
                ? getString(R.string.route_pois_none, MAX_OFFSET_M, when)
                : getString(R.string.route_pois_summary, count, MAX_OFFSET_M, when);
    }

    private void openInMap(RoutePoi p) {
        if (p == null) return;
        String coords = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon);
        Uri uri = Uri.parse("geo:" + coords + "?q=" + coords
                + "(" + Uri.encode(p.displayName()) + ")");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.route_pois_no_map_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** Two-line rows: name, then "Kasteel · km 12,4 · 80 m van de route". */
    private static final class PoiAdapter extends ArrayAdapter<RoutePoi> {

        PoiAdapter(Context ctx) {
            super(ctx, android.R.layout.simple_list_item_2, android.R.id.text1,
                    new ArrayList<>());
        }

        void setPois(List<RoutePoi> pois) {
            clear();
            addAll(pois);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            RoutePoi p = getItem(position);
            if (p != null) {
                ((TextView) v.findViewById(android.R.id.text1)).setText(p.displayName());
                ((TextView) v.findViewById(android.R.id.text2))
                        .setText(p.type.label + " · " + p.positionText());
            }
            return v;
        }
    }
}
