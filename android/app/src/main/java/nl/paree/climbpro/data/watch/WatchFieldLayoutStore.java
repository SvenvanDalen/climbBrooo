package nl.paree.climbpro.data.watch;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.watch.WatchFieldLayout;

/**
 * Persists the datafield slot layout chosen on the "Horloge-velden" screen. A setting, not
 * route data, so it lives in the default SharedPreferences; the default layout is stored as
 * "no value" so a reset leaves nothing behind.
 */
public final class WatchFieldLayoutStore {

    public static final String PREF_KEY = "watch_field_layout";

    private final SharedPreferences prefs;

    public WatchFieldLayoutStore(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public WatchFieldLayout load() {
        return WatchFieldLayout.parse(prefs.getString(PREF_KEY, null));
    }

    public void save(WatchFieldLayout layout) {
        if (layout == null || layout.isDefault()) {
            prefs.edit().remove(PREF_KEY).apply();
        } else {
            prefs.edit().putString(PREF_KEY, layout.serialize()).apply();
        }
    }
}
