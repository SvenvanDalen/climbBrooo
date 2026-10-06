package nl.paree.climbpro.ui;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Shared helpers for the ui/* Robolectric tests. */
public final class UiTestEnv {

    private UiTestEnv() {}

    /** Test WorkManager whose executor never runs work (sync/CIQ workers would block). */
    public static void initWorkManager() {
        Context app = ApplicationProvider.getApplicationContext();
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app,
                new androidx.work.Configuration.Builder().setExecutor(r -> { }).build());
    }

    @SuppressWarnings("RestrictedApi")
    public static void resetWorkManager() {
        androidx.work.impl.WorkManagerImpl.setDelegate(null);
    }

    /** Drains the main looper until it stays quiet, giving background loads time to post. */
    public static void settle() {
        org.robolectric.shadows.ShadowLooper looper = shadowOf(Looper.getMainLooper());
        int quiet = 0;
        for (int i = 0; i < 150 && quiet < 6; i++) {
            boolean busy = !looper.isIdle();
            looper.idle();
            quiet = busy ? 0 : quiet + 1;
            try {
                Thread.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        looper.idle();
    }

    /** Idles the main looper until {@code condition} holds or 3 s pass. */
    public static boolean waitFor(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        shadowOf(Looper.getMainLooper()).idle();
        return condition.getAsBoolean();
    }

    /**
     * Observes {@code data} until a non-null value matches {@code p} (or 3 s pass) and returns
     * it, else null. The observer is removed afterwards so later posts don't re-run {@code p}.
     */
    public static <T> T awaitValue(androidx.lifecycle.LiveData<T> data,
                                   java.util.function.Predicate<T> p) {
        java.util.concurrent.atomic.AtomicReference<T> box =
                new java.util.concurrent.atomic.AtomicReference<>();
        androidx.lifecycle.Observer<T> o = v -> {
            if (v != null && box.get() == null && p.test(v)) box.set(v);
        };
        data.observeForever(o);
        waitFor(() -> box.get() != null);
        data.removeObserver(o);
        return box.get();
    }

    public static String latestToast() {
        CharSequence t = ShadowToast.getTextOfLatestToast();
        return t != null ? t.toString() : null;
    }

    public static AlertDialog latestAlert() {
        Dialog d = ShadowDialog.getLatestDialog();
        return d instanceof AlertDialog && d.isShowing() ? (AlertDialog) d : null;
    }

    /** The message text of an appcompat AlertDialog. */
    public static String messageOf(AlertDialog d) {
        TextView tv = d.findViewById(android.R.id.message);
        return tv != null && tv.getText() != null ? tv.getText().toString() : null;
    }

    public static MenuItem menuItem(Activity activity, int id) {
        Menu menu = shadowOf(activity).getOptionsMenu();
        return menu != null ? menu.findItem(id) : null;
    }

    /** Selects an options-menu item; returns the handler's result. */
    public static boolean clickMenu(Activity activity, int id) {
        MenuItem item = menuItem(activity, id);
        if (item == null) throw new AssertionError("menu item not found: " + id);
        boolean handled = activity.onOptionsItemSelected(item);
        settle();
        return handled;
    }

    /** Lays out and draws a view on a bitmap, returning the bitmap. */
    public static Bitmap draw(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    /** All TextView texts under {@code root}, depth first. */
    public static List<String> texts(View root) {
        List<String> out = new ArrayList<>();
        collectTexts(root, out);
        return out;
    }

    public static boolean hasText(View root, String fragment) {
        for (String s : texts(root)) if (s.contains(fragment)) return true;
        return false;
    }

    private static void collectTexts(View v, List<String> out) {
        if (v instanceof TextView && ((TextView) v).getText() != null) {
            out.add(((TextView) v).getText().toString());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), out);
        }
    }
}
