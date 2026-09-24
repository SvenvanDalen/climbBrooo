package nl.paree.climbpro.ui.routes;

import android.content.Context;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.service.SyncScheduler;

import java.util.List;
import java.util.UUID;

/**
 * Quick-start mode (issue #263): one tap on "Rit nu starten" makes a route the active watch
 * route and kicks off the existing immediate sync, which pushes it to the watch. Reuses the
 * same preferences the route-detail "select for watch" button writes, so no new sync path
 * or wire format is involved.
 */
public final class QuickStart {

    private QuickStart() {}

    /**
     * The route quick start will use: the currently active route while it still exists in
     * {@code catalog}, else {@code null} (the caller then asks the user to pick one).
     */
    public static RouteCatalogEntry resolve(String activeRouteId, List<RouteCatalogEntry> catalog) {
        if (activeRouteId == null || catalog == null) return null;
        for (RouteCatalogEntry e : catalog) {
            if (activeRouteId.equals(e.routeId)) return e;
        }
        return null;
    }

    /** Label for the quick-start button. */
    public static String buttonLabel(RouteCatalogEntry route) {
        return route == null ? "Rit nu starten" : "Rit nu starten: " + displayName(route);
    }

    public static String displayName(RouteCatalogEntry e) {
        String n = e.userDisplayName != null && !e.userDisplayName.isEmpty() ? e.userDisplayName : e.name;
        return n != null ? n : e.routeId;
    }

    public static String activeRouteId(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(RouteSyncWorker.PREF_ROUTE_ID, null);
    }

    /**
     * Makes {@code routeId} the active route (route-follow mode) and starts a sync now.
     *
     * @return id of that sync run
     */
    public static UUID start(Context ctx, String routeId) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putString(RouteSyncWorker.PREF_ROUTE_ID, routeId)
                .putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE)
                .apply();
        return SyncScheduler.triggerImmediateSync(ctx);
    }
}
