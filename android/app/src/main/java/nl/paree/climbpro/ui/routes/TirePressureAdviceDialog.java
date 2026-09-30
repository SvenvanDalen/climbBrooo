package nl.paree.climbpro.ui.routes;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import java.util.Locale;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.advice.TirePressureAdvice;
import nl.paree.climbpro.domain.advice.TirePressureAdvisor;
import nl.paree.climbpro.domain.bike.BikeGarage;

/**
 * Shows the tire pressure / setup suggestion for a route (issue #90): a small, phone-only
 * pre-ride advice screen built on top of surface data the app already detects. Purely
 * informational — no action is gated behind it. Names the active garage bike and its tyre
 * width when known (issue #187), so the rider knows which setup the range is meant for.
 */
public final class TirePressureAdviceDialog {

    private TirePressureAdviceDialog() {}

    public static void show(Context ctx, StoredRoute route) {
        TirePressureAdvice advice = TirePressureAdvisor.advise(route);
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        // The garage is a small file, but still no disk IO on the main thread.
        new Thread(() -> {
            String bikeLine = bikeLine(app);
            main.post(() -> {
                if (ctx instanceof Activity && ((Activity) ctx).isFinishing()) return;
                showNow(ctx, advice, bikeLine);
            });
        }, "tire-advice-bike").start();
    }

    private static String bikeLine(Context app) {
        try {
            Bike active = BikeGarage.activeBike(new BikeCostRepository(app).load());
            if (active == null) return null;
            String line = "Actieve fiets: " + active.name;
            if (active.tyreWidthMm > 0) {
                line += " (" + active.tyreWidthMm + " mm banden)";
                if (active.tyreWidthMm >= 35) {
                    line += " — bredere banden rijden op een lagere spanning dan dit advies.";
                } else if (active.tyreWidthMm <= 25) {
                    line += " — smalle banden mogen aan de bovenkant van dit advies zitten.";
                }
            }
            return line;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void showNow(Context ctx, TirePressureAdvice advice, String bikeLine) {
        String body = String.format(Locale.US,
                "%d–%d PSI (%.1f–%.1f bar)\n\n%s",
                advice.minPsi, advice.maxPsi,
                advice.minBar(), advice.maxBar(),
                advice.rationale);
        if (bikeLine != null) body += "\n\n" + bikeLine;

        new AlertDialog.Builder(ctx)
                .setTitle("Bandenspanning-advies")
                .setMessage(body)
                .setPositiveButton("Sluiten", null)
                .show();
    }
}
