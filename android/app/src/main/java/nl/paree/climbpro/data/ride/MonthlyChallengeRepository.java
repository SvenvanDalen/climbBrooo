package nl.paree.climbpro.data.ride;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Type;

import java.time.YearMonth;

/**
 * Persists the monthly challenge (issue #192) in default SharedPreferences. A challenge is
 * bound to the month it was set for: once that month is over, {@link #load} returns null so
 * the rider picks (or accepts) a new one. Phone-only.
 */
public final class MonthlyChallengeRepository {

    static final String PREF_TYPE   = "monthly_challenge_type";
    static final String PREF_TARGET = "monthly_challenge_target";
    /** "yyyy-MM" the challenge applies to. */
    static final String PREF_MONTH  = "monthly_challenge_month";

    /** A stored challenge. */
    public static final class Challenge {
        public final Type type;
        public final int target;

        public Challenge(Type type, int target) {
            this.type = type;
            this.target = target;
        }
    }

    private final SharedPreferences prefs;

    public MonthlyChallengeRepository(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /** @return the challenge for {@code month}, or null when none is set for that month. */
    public Challenge load(YearMonth month) {
        if (!month.toString().equals(prefs.getString(PREF_MONTH, null))) return null;
        Type type = Type.fromKey(prefs.getString(PREF_TYPE, null));
        int target = prefs.getInt(PREF_TARGET, 0);
        if (type == null || target <= 0) return null;
        return new Challenge(type, target);
    }

    /** Stores the challenge for {@code month}; {@code target ≤ 0} clears it. */
    public void save(YearMonth month, Type type, int target) {
        if (type == null || target <= 0) {
            clear();
            return;
        }
        prefs.edit()
                .putString(PREF_MONTH, month.toString())
                .putString(PREF_TYPE, type.name())
                .putInt(PREF_TARGET, Math.min(target, MonthlyChallengeCalculator.MAX_TARGET))
                .apply();
    }

    public void clear() {
        prefs.edit().remove(PREF_MONTH).remove(PREF_TYPE).remove(PREF_TARGET).apply();
    }
}
