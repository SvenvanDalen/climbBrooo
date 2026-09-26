package nl.paree.climbpro.data.comeback;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The active comeback plan (issue #226) in getFilesDir()/comeback_plan.json. Only the inputs
 * are stored; the weeks are recomputed from the ride archive so actual km stay current.
 * Atomic writes (temp file + rename); a missing or unreadable file means "no plan".
 */
public final class ComebackPlanStore {

    private static final String TAG = "ComebackPlanStore";
    static final String FILE = "comeback_plan.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Saved {
        /** Start of the last ride before the break; defines the baseline. */
        public long lastRideBeforeBreakEpochSec;
        /** First day of week 1. */
        public long planStartEpochSec;
        public boolean injury;
    }

    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();

    public ComebackPlanStore(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    /** The active plan, or null. */
    public Saved load() {
        if (!file.exists()) return null;
        try {
            Saved s = mapper.readValue(file, Saved.class);
            return s != null && s.planStartEpochSec > 0 ? s : null;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load comeback plan", e);
            return null;
        }
    }

    public synchronized void save(long lastRideBeforeBreak, long planStart, boolean injury)
            throws IOException {
        Saved s = new Saved();
        s.lastRideBeforeBreakEpochSec = lastRideBeforeBreak;
        s.planStartEpochSec = planStart;
        s.injury = injury;
        File tmp = new File(file.getParentFile(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mapper.writeValueAsBytes(s));
            out.getFD().sync();
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }

    public synchronized void clear() {
        if (file.exists() && !file.delete()) Log.w(TAG, "Failed to delete comeback plan");
    }
}
