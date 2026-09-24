package nl.paree.climbpro.data.ride;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/**
 * Persists the yearly kilometre goal (issue #157) in default SharedPreferences. One goal that
 * applies to whichever calendar year is current — the rider re-sets it when a new year calls
 * for a different target. Phone-only.
 */
public final class YearlyDistanceGoalRepository {

    /** Goal in whole km; absent or ≤ 0 = no goal. */
    public static final String PREF_YEARLY_GOAL_KM = "yearly_distance_goal_km";

    /** Upper bound for the input dialog — well above any realistic yearly distance. */
    public static final int MAX_GOAL_KM = 100_000;

    private final SharedPreferences prefs;

    public YearlyDistanceGoalRepository(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /** @return the goal in km, or 0 when no goal is set. */
    public int getGoalKm() {
        return Math.max(0, prefs.getInt(PREF_YEARLY_GOAL_KM, 0));
    }

    /** Stores the goal; {@code goalKm ≤ 0} clears it. Values are capped at {@link #MAX_GOAL_KM}. */
    public void setGoalKm(int goalKm) {
        if (goalKm <= 0) {
            prefs.edit().remove(PREF_YEARLY_GOAL_KM).apply();
        } else {
            prefs.edit().putInt(PREF_YEARLY_GOAL_KM, Math.min(goalKm, MAX_GOAL_KM)).apply();
        }
    }
}
