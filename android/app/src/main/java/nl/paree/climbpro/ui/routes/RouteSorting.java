package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.data.route.RouteCatalogEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure sorting helper for the route list. The mode is user-selectable and is
 * persisted in SharedPreferences (see RouteListViewModel).
 */
public final class RouteSorting {

    /** Ascending by import time: newest routes at the bottom. (Default.) */
    public static final int SORT_IMPORT_ASC  = 0;
    /** Descending by import time: newest routes at the top. */
    public static final int SORT_IMPORT_DESC = 1;
    /** Alphabetical by (display) name, case-insensitive. */
    public static final int SORT_NAME_ASC    = 2;

    private RouteSorting() {}

    /** Returns a new, sorted list; the input list is left unchanged. */
    public static List<RouteCatalogEntry> sort(List<RouteCatalogEntry> in, int mode) {
        List<RouteCatalogEntry> out = new ArrayList<>(in);
        switch (mode) {
            case SORT_IMPORT_DESC:
                out.sort((a, b) -> Long.compare(b.importedAtMs, a.importedAtMs));
                break;
            case SORT_NAME_ASC:
                out.sort((a, b) -> displayName(a).compareToIgnoreCase(displayName(b)));
                break;
            case SORT_IMPORT_ASC:
            default:
                out.sort((a, b) -> Long.compare(a.importedAtMs, b.importedAtMs));
                break;
        }
        return out;
    }

    private static String displayName(RouteCatalogEntry e) {
        if (e.userDisplayName != null) return e.userDisplayName;
        return e.name != null ? e.name : "";
    }
}
