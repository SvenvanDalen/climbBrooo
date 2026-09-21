package nl.paree.climbpro.ui.routes;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the pre-ride checklist ({@link PreRideChecklistBuilder}) before handing off to
 * "select route to follow" or "start navigation". This is an advisory, not a hard gate:
 * dismissing the dialog in any way (button, back press, tap outside) proceeds with the
 * action the user originally asked for — it never blocks the ride.
 */
public final class PreRideCheckDialog {

    private PreRideCheckDialog() {}

    /**
     * @param onProceed invoked exactly once, however the dialog is closed.
     */
    public static void show(Context ctx, RoutePassport passport, Runnable onProceed) {
        List<PreRideChecklistItem> items = PreRideChecklistBuilder.build(passport);
        StringBuilder body = new StringBuilder();
        for (PreRideChecklistItem item : items) {
            if (body.length() > 0) body.append("\n\n");
            body.append("• ").append(item.message);
        }

        AtomicBoolean proceeded = new AtomicBoolean(false);
        Runnable proceedOnce = () -> {
            if (proceeded.compareAndSet(false, true)) onProceed.run();
        };

        new AlertDialog.Builder(ctx)
                .setTitle("Uitrustingscheck")
                .setMessage(body.toString())
                .setCancelable(true)
                .setPositiveButton("Doorgaan", (d, w) -> proceedOnce.run())
                .setOnDismissListener(d -> proceedOnce.run())
                .show();
    }
}
