package nl.paree.climbpro.ui.routes;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import java.util.Locale;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.advice.TirePressureAdvice;
import nl.paree.climbpro.domain.advice.TirePressureAdvisor;

/**
 * Shows the tire pressure / setup suggestion for a route (issue #90): a small, phone-only
 * pre-ride advice screen built on top of surface data the app already detects. Purely
 * informational — no action is gated behind it.
 */
public final class TirePressureAdviceDialog {

    private TirePressureAdviceDialog() {}

    public static void show(Context ctx, StoredRoute route) {
        TirePressureAdvice advice = TirePressureAdvisor.advise(route);
        String body = String.format(Locale.US,
                "%d–%d PSI (%.1f–%.1f bar)\n\n%s",
                advice.minPsi, advice.maxPsi,
                advice.minBar(), advice.maxBar(),
                advice.rationale);

        new AlertDialog.Builder(ctx)
                .setTitle("Bandenspanning-advies")
                .setMessage(body)
                .setPositiveButton("Sluiten", null)
                .show();
    }
}
