package nl.paree.climbpro.ui.routes;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.route.GpxWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Export of a route with its climbs to Wahoo and Hammerhead bike computers (issue #254).
 * Both companion apps import a shared GPX as a route and sync it to the unit, so this is the
 * same file-handoff as {@link GarminHandoff} — plus a waypoint at the start and top of every
 * climb, which both units show as points of interest. No platform API, no account linking.
 *
 * <p>Companion apps are found among the installed GPX share targets by package keyword
 * rather than a hardcoded package name, so renamed/regional app variants still match.
 */
public final class BikeComputerExport {

    public enum Target {
        WAHOO("Wahoo ELEMNT", "wahoo"),
        HAMMERHEAD("Hammerhead Karoo", "hammerhead");

        public final String label;
        final String packageKeyword;

        Target(String label, String packageKeyword) {
            this.label = label;
            this.packageKeyword = packageKeyword;
        }
    }

    private BikeComputerExport() {}

    /** Name hints of the unit companion apps, preferred over other apps of the same vendor. */
    private static final String[] COMPANION_HINTS = {"companion", "elemnt", "bolt", "karoo"};

    /**
     * Package whose name contains the target's keyword, or null. A companion app wins over
     * another app of the same vendor (e.g. the legacy Wahoo Fitness app, which does not sync
     * routes to an ELEMNT), whatever order the package manager lists them in.
     */
    public static String findPackage(List<String> packages, Target target) {
        String any = null;
        for (String p : packages) {
            if (p == null) continue;
            String lower = p.toLowerCase(Locale.ROOT);
            if (!lower.contains(target.packageKeyword)) continue;
            for (String hint : COMPANION_HINTS) {
                if (lower.contains(hint)) return p;
            }
            if (any == null) any = p;
        }
        return any;
    }

    /** File name that survives in the companion app's route list: the route name, sanitised. */
    public static String fileName(StoredRoute route) {
        String name = route.userDisplayName != null && !route.userDisplayName.isEmpty()
                ? route.userDisplayName
                : route.name != null && !route.name.isEmpty() ? route.name : "ClimbPro route";
        name = name.replaceAll("(?i)\\.gpx$", "").replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
        if (name.isEmpty()) name = "ClimbPro route";
        if (name.length() > 60) name = name.substring(0, 60).trim();
        return name + ".gpx";
    }

    /** Writes the route GPX with climb waypoints under the shared-routes cache folder. */
    public static File writeGpx(Context ctx, StoredRoute route) throws IOException {
        File dir = new File(ctx.getCacheDir(), "shared_routes");
        dir.mkdirs();
        File file = new File(dir, fileName(route));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(GpxWriter.toGpx(route, true).getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /**
     * Share intent aimed at {@code target}'s companion app when installed; otherwise (or for
     * a null target) an untargeted intent for the system chooser.
     */
    public static Intent buildShareIntent(Context ctx, Uri gpxUri, Target target) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(GarminHandoff.GPX_MIME);
        share.putExtra(Intent.EXTRA_STREAM, gpxUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String pkg = target != null ? findPackage(gpxShareTargets(ctx), target) : null;
        if (pkg == null) return share;
        // Not every companion app declares the GPX mime type; use the first type it accepts.
        for (String mime : SHARE_MIMES) {
            share.setType(mime);
            share.setPackage(pkg);
            if (ctx.getPackageManager().resolveActivity(share, 0) != null) return share;
        }
        share.setType(GarminHandoff.GPX_MIME);
        share.setPackage(null);
        return share;
    }

    /** MIME types a GPX is offered as, most specific first. Keep in step with the manifest. */
    static final String[] SHARE_MIMES = {
            GarminHandoff.GPX_MIME, "application/octet-stream", "*/*"};

    /** Installed apps that accept a shared GPX (needs the matching {@code <queries>} entries). */
    static List<String> gpxShareTargets(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String mime : SHARE_MIMES) {
            Intent probe = new Intent(Intent.ACTION_SEND).setType(mime);
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(probe, 0)) {
                if (ri.activityInfo != null && !out.contains(ri.activityInfo.packageName)) {
                    out.add(ri.activityInfo.packageName);
                }
            }
        }
        return out;
    }

    public static boolean isInstalled(Context ctx, Target target) {
        return findPackage(gpxShareTargets(ctx), target) != null;
    }
}
