package nl.paree.climbpro.ui.routes;

import android.app.Activity;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteJoinService;
import nl.paree.climbpro.data.route.RouteRepository;

/**
 * Issue #204: "Samenvoegen met…" on the route detail screen. The current route comes first,
 * the picked route is appended; the result is saved as a new route and opened. A gap wider than
 * {@link nl.paree.climbpro.domain.route.RouteJoiner#LARGE_GAP_WARNING_M} is bridged with a
 * straight line, but only after the user confirms.
 */
final class RouteJoinDialog {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private RouteJoinDialog() {}

    static void show(Activity activity, String firstRouteId) {
        RouteRepository repo = new RouteRepository(activity);
        EXECUTOR.execute(() -> {
            List<RouteCatalogEntry> others = new ArrayList<>();
            for (RouteCatalogEntry e : repo.loadCatalog()) {
                if (!e.routeId.equals(firstRouteId)) others.add(e);
            }
            others.sort((x, y) -> label(x).compareToIgnoreCase(label(y)));
            activity.runOnUiThread(() -> showPicker(activity, repo, firstRouteId, others));
        });
    }

    private static void showPicker(Activity activity, RouteRepository repo, String firstRouteId,
                                   List<RouteCatalogEntry> others) {
        if (activity.isFinishing()) return;
        if (others.isEmpty()) {
            Toast.makeText(activity, R.string.route_join_no_other_routes, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[others.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = label(others.get(i));
        new AlertDialog.Builder(activity)
                .setTitle(R.string.route_join_pick_title)
                .setItems(labels, (d, which) ->
                        prepare(activity, repo, firstRouteId, others.get(which).routeId))
                .setNegativeButton(R.string.route_join_cancel, null)
                .show();
    }

    private static void prepare(Activity activity, RouteRepository repo,
                                String firstRouteId, String secondRouteId) {
        RouteJoinService service = new RouteJoinService(repo);
        EXECUTOR.execute(() -> {
            try {
                RouteJoinService.Prepared prepared = service.prepare(firstRouteId, secondRouteId);
                if (prepared.joined.hasLargeGap()) {
                    activity.runOnUiThread(() -> confirmGap(activity, service, prepared));
                } else {
                    save(activity, service, prepared);
                }
            } catch (Exception e) {
                fail(activity, e);
            }
        });
    }

    private static void confirmGap(Activity activity, RouteJoinService service,
                                   RouteJoinService.Prepared prepared) {
        if (activity.isFinishing()) return;
        String km = String.format(Locale.ROOT, "%.1f", prepared.joined.gapM / 1000.0);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.route_join_gap_title)
                .setMessage(activity.getString(R.string.route_join_gap_message, km))
                .setPositiveButton(R.string.route_join_confirm, (d, w) ->
                        EXECUTOR.execute(() -> save(activity, service, prepared)))
                .setNegativeButton(R.string.route_join_cancel, null)
                .show();
    }

    /** Runs on {@link #EXECUTOR}. */
    private static void save(Activity activity, RouteJoinService service,
                             RouteJoinService.Prepared prepared) {
        try {
            RouteJoinService.Saved saved = service.save(prepared);
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, activity.getResources().getQuantityString(
                        R.plurals.route_join_done, saved.climbCount,
                        saved.name, saved.climbCount), Toast.LENGTH_LONG).show();
                if (!activity.isFinishing()) {
                    activity.startActivity(RouteDetailActivity.intentFor(activity, saved.routeId));
                }
            });
        } catch (Exception e) {
            fail(activity, e);
        }
    }

    private static void fail(Activity activity, Exception e) {
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        activity.runOnUiThread(() -> Toast.makeText(activity,
                activity.getString(R.string.route_join_failed, reason),
                Toast.LENGTH_LONG).show());
    }

    private static String label(RouteCatalogEntry e) {
        if (e.userDisplayName != null && !e.userDisplayName.trim().isEmpty()) {
            return e.userDisplayName;
        }
        return e.name != null ? e.name : e.routeId;
    }
}
