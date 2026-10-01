package nl.paree.climbpro.data.history;

import android.content.Context;
import android.util.Log;

import nl.paree.climbpro.domain.history.ClimbFactsParser;
import nl.paree.climbpro.domain.history.FamousClimb;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads the bundled famous-climb dataset ({@code assets/climb_facts.json}, issue #212) once per
 * process. Read-only app data shipped in the APK: offline, no user data, nothing persisted.
 * Call off the main thread the first time.
 */
public final class ClimbFactsRepository {

    static final String ASSET = "climb_facts.json";
    private static final String TAG = "ClimbFacts";

    private static volatile List<FamousClimb> cache;

    private ClimbFactsRepository() {}

    public static List<FamousClimb> load(Context context) {
        List<FamousClimb> c = cache;
        if (c != null) return c;
        synchronized (ClimbFactsRepository.class) {
            if (cache == null) {
                List<FamousClimb> loaded;
                try (InputStream in = context.getApplicationContext().getAssets().open(ASSET)) {
                    loaded = Collections.unmodifiableList(ClimbFactsParser.parse(in));
                } catch (Exception e) {
                    Log.w(TAG, "Kon klimweetjes niet laden", e);
                    loaded = Collections.unmodifiableList(new ArrayList<>());
                }
                cache = loaded;
            }
            return cache;
        }
    }
}
