package nl.paree.climbpro.ui.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import nl.paree.climbpro.data.route.ClimbMembership;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.SharedClimbImporter;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.share.ClimbShareCode;
import nl.paree.climbpro.domain.share.ClimbShareExtractor;
import nl.paree.climbpro.domain.share.SharedClimb;
import nl.paree.climbpro.domain.share.SharedClimbImportPlanner;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Share codes for climbs and collections (issue #216): sending one through the share sheet,
 * and the paste-and-confirm import flow. Nothing is imported before the rider confirms.
 */
public final class ClimbCodeSharing {

    private ClimbCodeSharing() {}

    /** Shares one climb, or explains why a home climb or a climb without geometry can't be. */
    public static void shareClimb(Activity activity, StoredRoute route, int climbIndex) {
        if (ClimbShareExtractor.isHome(route, climbIndex)) {
            Toast.makeText(activity, "Een thuisklim deel je niet als code: de route zou laten "
                    + "zien waar je woont.", Toast.LENGTH_LONG).show();
            return;
        }
        SharedClimb climb = ClimbShareExtractor.extract(route, climbIndex);
        if (climb == null) {
            Toast.makeText(activity, "Deze klim heeft geen route om te delen.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String code = ClimbShareCode.encode(
                new ClimbShareCode.Payload(null, Collections.singletonList(climb)));
        send(activity, "Klim: " + climb.name, "Klim \"" + climb.name + "\" uit ClimbPro. "
                + "Importeer hem in ClimbPro via het menu > Klimcode importeren:\n\n" + code);
    }

    /** Builds the code off the main thread (routes are loaded from disk), then shares it. */
    public static void shareCollection(Activity activity, Executor executor,
                                       RouteCollection collection) {
        executor.execute(() -> {
            RouteRepository repo = new RouteRepository(activity);
            Map<String, StoredRoute> byId = new HashMap<>();
            Set<String> ids = new LinkedHashSet<>();
            if (collection.routeIds != null) ids.addAll(collection.routeIds);
            if (collection.climbs != null) {
                for (ClimbMembership m : collection.climbs) {
                    ids.add(m.routeId);
                }
            }
            for (String id : ids) {
                try {
                    byId.put(id, repo.loadRoute(id));
                } catch (IOException ignored) {
                    // A deleted route simply isn't shared.
                }
            }
            ClimbShareExtractor.Selection s = ClimbShareExtractor.collect(collection, byId);
            activity.runOnUiThread(() -> {
                if (s.climbs.isEmpty()) {
                    Toast.makeText(activity, s.skippedHome > 0
                            ? "Deze collectie bevat alleen thuisklimmen; die deel je niet."
                            : "Deze collectie bevat geen klimmen om te delen.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                StringBuilder note = new StringBuilder();
                if (s.skippedHome > 0) {
                    note.append(s.skippedHome).append(" thuisklim(men) niet gedeeld. ");
                }
                if (s.skippedOverLimit > 0) {
                    note.append(s.skippedOverLimit).append(" klim(men) vallen buiten de code (max ")
                            .append(ClimbShareCode.MAX_CLIMBS).append(").");
                }
                if (note.length() > 0) {
                    Toast.makeText(activity, note.toString().trim(), Toast.LENGTH_LONG).show();
                }
                String name = collection.name != null ? collection.name : "Collectie";
                String code = ClimbShareCode.encode(new ClimbShareCode.Payload(name, s.climbs));
                send(activity, "Klimcollectie: " + name, String.format(Locale.getDefault(),
                        "Collectie \"%s\" met %d klim(men) uit ClimbPro. Importeer in ClimbPro "
                                + "via het menu > Klimcode importeren:\n\n%s",
                        name, s.climbs.size(), code));
            });
        });
    }

    private static void send(Activity activity, String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(send, "Deel klimcode"));
    }

    /**
     * Asks for a code (prefilled from the clipboard when it holds one), shows what it contains,
     * and imports after confirmation. {@code onImported} runs on the main thread afterwards.
     */
    public static void showImportDialog(Activity activity, Executor executor,
                                        Runnable onImported) {
        EditText input = new EditText(activity);
        input.setHint(ClimbShareCode.PREFIX + "…");
        input.setMaxLines(4);
        String clip = clipboardText(activity);
        if (clip != null && clip.contains(ClimbShareCode.PREFIX)) input.setText(clip);
        new AlertDialog.Builder(activity)
                .setTitle("Klimcode importeren")
                .setMessage("Plak de klimcode of het hele bericht dat je kreeg.")
                .setView(input)
                .setPositiveButton("Verder", (d, w) -> {
                    String text = input.getText().toString();
                    executor.execute(() -> preview(activity, executor, text, onImported));
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private static void preview(Activity activity, Executor executor, String text,
                                Runnable onImported) {
        SharedClimbImporter importer = new SharedClimbImporter(activity);
        SharedClimbImporter.Preview p;
        try {
            p = importer.preview(text);
        } catch (ClimbShareCode.InvalidCodeException e) {
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }
        String summary = summary(p);
        activity.runOnUiThread(() -> {
            AlertDialog.Builder b = new AlertDialog.Builder(activity)
                    .setTitle(p.payload.collectionName != null
                            ? "Collectie \"" + p.payload.collectionName + "\"" : "Klim importeren")
                    .setMessage(summary)
                    .setNegativeButton("Annuleren", null);
            if (p.count(true) > 0 || (p.payload.collectionName != null && p.count(false) > 0)) {
                b.setPositiveButton("Importeren", (d, w) -> executor.execute(
                        () -> runImport(activity, importer, p, onImported)));
            }
            b.show();
        });
    }

    static String summary(SharedClimbImporter.Preview p) {
        StringBuilder sb = new StringBuilder();
        for (SharedClimbImportPlanner.Plan plan : p.plans) {
            sb.append("• ").append(plan.shared.name.isEmpty() ? "Klim" : plan.shared.name);
            if (plan.climb == null) {
                sb.append(": geen klim gevonden, wordt overgeslagen");
            } else {
                sb.append(String.format(Locale.getDefault(), ": %.1f km, %.1f %%",
                        plan.climb.length / 1000.0, plan.climb.avgGradient * 100));
                if (!plan.isNew()) sb.append(" (heb je al)");
            }
            sb.append('\n');
        }
        int fresh = p.count(true);
        int known = p.count(false);
        sb.append('\n');
        if (fresh == 0 && known > 0 && p.payload.collectionName == null) {
            sb.append("Deze klim heb je al; er valt niets te importeren.");
        } else if (fresh == 0 && known == 0) {
            sb.append("Er valt niets te importeren.");
        } else {
            sb.append(fresh).append(" nieuwe klim(men) worden als route toegevoegd.");
            if (p.payload.collectionName != null) {
                sb.append(" Er komt een collectie met alle ").append(fresh + known)
                        .append(" klim(men).");
            }
        }
        return sb.toString();
    }

    private static void runImport(Activity activity, SharedClimbImporter importer,
                                  SharedClimbImporter.Preview p, Runnable onImported) {
        try {
            SharedClimbImporter.Result r = importer.importPreview(p);
            activity.runOnUiThread(() -> {
                String msg = r.imported + " klim(men) geïmporteerd"
                        + (r.alreadyKnown > 0 ? ", " + r.alreadyKnown + " al bekend" : "")
                        + (r.collectionName != null
                        ? ", collectie \"" + r.collectionName + "\" aangemaakt" : "") + ".";
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                if (onImported != null) onImported.run();
                if (r.single != null && r.collectionName == null) {
                    activity.startActivity(ClimbDetailActivity.intentFor(activity,
                            r.single.routeId, r.single.climbIndex));
                }
            });
        } catch (IOException e) {
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "Importeren mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private static String clipboardText(Context context) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence t = clip.getItemAt(0).coerceToText(context);
        return t != null ? t.toString() : null;
    }
}
