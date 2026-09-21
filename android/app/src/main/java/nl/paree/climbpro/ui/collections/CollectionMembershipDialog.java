package nl.paree.climbpro.ui.collections;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reusable "add/remove from collection(s)" picker, invoked from a route's long-press menu
 * (RouteListActivity) or a climb's long-press menu (RouteDetailActivity). Loads collections
 * off the main thread, then shows a multi-choice dialog whose checked state reflects current
 * membership; toggling applies the diff on dismiss.
 */
public final class CollectionMembershipDialog {

    // Short-lived helper: one background thread for the create/toggle I/O below is enough
    // (writes are infrequent and each is a small JSON file), mirroring RouteListViewModel's
    // single-executor pattern rather than blocking the dialog's UI-thread callbacks.
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CollectionMembershipDialog() {}

    public static void showForRoute(Activity activity, String routeId) {
        show(activity, new RouteTarget(routeId));
    }

    public static void showForClimb(Activity activity, String routeId, int climbIndex) {
        show(activity, new ClimbTarget(routeId, climbIndex));
    }

    private static void show(Activity activity, Target target) {
        RouteCollectionRepository repo = new RouteCollectionRepository(activity);
        EXECUTOR.execute(() -> {
            List<RouteCollection> all = repo.loadAll();
            all.sort(Comparator.comparing((RouteCollection c) -> c.name == null ? "" : c.name,
                    String.CASE_INSENSITIVE_ORDER));
            activity.runOnUiThread(() -> showDialog(activity, repo, all, target));
        });
    }

    private static void showDialog(Activity activity, RouteCollectionRepository repo,
                                    List<RouteCollection> all, Target target) {
        if (all.isEmpty()) {
            showCreateDialog(activity, repo, target);
            return;
        }
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name != null ? all.get(i).name : "(naamloos)";
            checked[i] = target.isMember(all.get(i));
        }
        boolean[] result = checked.clone();

        new AlertDialog.Builder(activity)
                .setTitle("Toevoegen aan collectie")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> result[which] = isChecked)
                .setPositiveButton("Klaar", (d, w) -> EXECUTOR.execute(() -> {
                    for (int i = 0; i < all.size(); i++) {
                        if (result[i] && !checked[i]) target.add(repo, all.get(i));
                        else if (!result[i] && checked[i]) target.remove(repo, all.get(i));
                    }
                }))
                .setNeutralButton("Nieuwe collectie", (d, w) -> showCreateDialog(activity, repo, target))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private static void showCreateDialog(Activity activity, RouteCollectionRepository repo, Target target) {
        EditText input = new EditText(activity);
        input.setHint("Naam van collectie");
        new AlertDialog.Builder(activity)
                .setTitle("Nieuwe collectie")
                .setView(input)
                .setPositiveButton("Aanmaken", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(activity, "Naam mag niet leeg zijn", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    EXECUTOR.execute(() -> {
                        RouteCollection created = repo.create(name);
                        target.add(repo, created);
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Toegevoegd aan \"" + name + "\"", Toast.LENGTH_SHORT).show());
                    });
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private interface Target {
        boolean isMember(RouteCollection c);
        void add(RouteCollectionRepository repo, RouteCollection c);
        void remove(RouteCollectionRepository repo, RouteCollection c);
    }

    private static final class RouteTarget implements Target {
        final String routeId;
        RouteTarget(String routeId) { this.routeId = routeId; }
        @Override public boolean isMember(RouteCollection c) {
            return c.routeIds != null && c.routeIds.contains(routeId);
        }
        @Override public void add(RouteCollectionRepository repo, RouteCollection c) {
            repo.addRoute(c.id, routeId);
        }
        @Override public void remove(RouteCollectionRepository repo, RouteCollection c) {
            repo.removeRoute(c.id, routeId);
        }
    }

    private static final class ClimbTarget implements Target {
        final String routeId;
        final int climbIndex;
        ClimbTarget(String routeId, int climbIndex) {
            this.routeId = routeId;
            this.climbIndex = climbIndex;
        }
        @Override public boolean isMember(RouteCollection c) {
            if (c.climbs == null) return false;
            for (nl.paree.climbpro.data.route.ClimbMembership m : c.climbs) {
                if (m.routeId.equals(routeId) && m.climbIndex == climbIndex) return true;
            }
            return false;
        }
        @Override public void add(RouteCollectionRepository repo, RouteCollection c) {
            repo.addClimb(c.id, routeId, climbIndex);
        }
        @Override public void remove(RouteCollectionRepository repo, RouteCollection c) {
            repo.removeClimb(c.id, routeId, climbIndex);
        }
    }
}
