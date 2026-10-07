package nl.paree.climbpro.ui;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Helpers for driving screens in Robolectric activity tests. */
public final class ActivityTestSupport {

    private ActivityTestSupport() {}

    /**
     * Settles the main looper, tolerating the Windows-only FileProvider quirk
     * ("Failed to find configured root"); returns true when the quirk was hit.
     */
    public static boolean settleTolerant() {
        try {
            UiTestEnv.settle();
            return false;
        } catch (RuntimeException e) {
            if (isWindowsFileProviderQuirk(e)) {
                UiTestEnv.settle();
                return true;
            }
            throw e;
        }
    }

    public static boolean isWindowsFileProviderQuirk(Throwable t) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IllegalArgumentException && c.getMessage() != null
                    && c.getMessage().startsWith("Failed to find configured root")) {
                return true;
            }
        }
        return false;
    }

    /** The latest dialog when it is showing, else null. */
    public static Dialog showingDialog() {
        Dialog d = ShadowDialog.getLatestDialog();
        return d != null && d.isShowing() ? d : null;
    }

    public static List<EditText> editTexts(Dialog d) {
        List<EditText> out = new ArrayList<>();
        collect(d.getWindow().getDecorView(), EditText.class, out);
        return out;
    }

    public static <T extends View> List<T> views(View root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends View> void collect(View v, Class<T> type, List<T> out) {
        if (type.isInstance(v)) out.add(type.cast(v));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), type, out);
        }
    }

    /** Next started activity, unwrapping a chooser to its target intent. */
    public static Intent nextStarted(Activity a) {
        Intent i = shadowOf(a).getNextStartedActivity();
        if (i != null && Intent.ACTION_CHOOSER.equals(i.getAction())) {
            Intent inner = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (inner != null) return inner;
        }
        return i;
    }

    /** Waits until the latest toast equals {@code text}. */
    public static boolean awaitToast(String text) {
        return UiTestEnv.waitFor(() -> text.equals(UiTestEnv.latestToast()));
    }
}
