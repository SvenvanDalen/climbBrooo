package nl.paree.climbpro.data.social;

import android.content.SharedPreferences;

import java.util.UUID;

/**
 * Who you are in a share code (issue #240): a random install id (lets friends' phones group and
 * de-duplicate your entries; not personal data) and the display name you chose. Both live in the
 * default SharedPreferences, so the backup carries them to a new phone.
 */
public final class FriendShareIdentity {

    public static final String PREF_ID = "friend_share_id";
    public static final String PREF_NAME = "friend_share_name";

    private FriendShareIdentity() {}

    public static String sharerId(SharedPreferences prefs) {
        String id = prefs.getString(PREF_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(PREF_ID, id).apply();
        }
        return id;
    }

    public static String name(SharedPreferences prefs) {
        String n = prefs.getString(PREF_NAME, "");
        return n == null ? "" : n.trim();
    }

    public static void setName(SharedPreferences prefs, String name) {
        prefs.edit().putString(PREF_NAME, name == null ? "" : name.trim()).apply();
    }
}
