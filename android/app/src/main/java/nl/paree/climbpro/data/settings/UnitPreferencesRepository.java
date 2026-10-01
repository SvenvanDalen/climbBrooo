package nl.paree.climbpro.data.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.units.UnitFormatter;
import nl.paree.climbpro.domain.units.UnitPreferences;

/**
 * Persists the display-unit choice (issue #262) in default SharedPreferences. Absent keys
 * mean metric, so existing installs are unchanged until the rider picks otherwise.
 */
public final class UnitPreferencesRepository {

    public static final String PREF_IMPERIAL   = "units_imperial";
    public static final String PREF_PSI        = "units_psi";
    public static final String PREF_FAHRENHEIT = "units_fahrenheit";

    private final SharedPreferences prefs;

    public UnitPreferencesRepository(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public UnitPreferences load() {
        return new UnitPreferences(prefs.getBoolean(PREF_IMPERIAL, false),
                prefs.getBoolean(PREF_PSI, false),
                prefs.getBoolean(PREF_FAHRENHEIT, false));
    }

    public void save(UnitPreferences units) {
        prefs.edit()
                .putBoolean(PREF_IMPERIAL, units.imperial)
                .putBoolean(PREF_PSI, units.psi)
                .putBoolean(PREF_FAHRENHEIT, units.fahrenheit)
                .apply();
    }

    /** Formatter for the current choice; re-fetch after the setting may have changed. */
    public static UnitFormatter formatter(Context context) {
        return new UnitFormatter(new UnitPreferencesRepository(context).load());
    }
}
