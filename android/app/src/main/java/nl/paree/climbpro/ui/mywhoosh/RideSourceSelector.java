package nl.paree.climbpro.ui.mywhoosh;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.mywhoosh.RideSource;

/**
 * "Alles / Buiten / MyWhoosh" filter (issue #392) placed right under a screen's toolbar. The
 * choice is one shared preference, read by the view models when they load.
 */
public final class RideSourceSelector {

    private static final String[] LABELS = {"Alles", "Buiten", "MyWhoosh"};

    private RideSourceSelector() {}

    public static RideSource current(Context context) {
        return RideSource.fromName(PreferenceManager.getDefaultSharedPreferences(context)
                .getString(RideSource.PREF_KEY, null));
    }

    /**
     * Inserts the selector after the view {@code anchorId} in its parent and calls
     * {@code onChange} after the rider picks another source.
     */
    public static RadioGroup attach(Activity activity, int anchorId, Runnable onChange) {
        View anchor = activity.findViewById(anchorId);
        ViewGroup parent = (ViewGroup) anchor.getParent();
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        group.setPadding(pad, pad / 4, pad, 0);
        RideSource selected = current(activity);
        RideSource[] sources = RideSource.values();
        for (int i = 0; i < sources.length; i++) {
            RadioButton b = new RadioButton(activity);
            b.setId(View.generateViewId());
            b.setText(LABELS[i]);
            b.setTag(sources[i]);
            group.addView(b, new RadioGroup.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (sources[i] == selected) b.setChecked(true);
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        group.setOnCheckedChangeListener((g, id) -> {
            View b = g.findViewById(id);
            if (b == null || !(b.getTag() instanceof RideSource)) return;
            prefs.edit().putString(RideSource.PREF_KEY, ((RideSource) b.getTag()).name()).apply();
            onChange.run();
        });
        parent.addView(group, parent.indexOfChild(anchor) + 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return group;
    }
}
