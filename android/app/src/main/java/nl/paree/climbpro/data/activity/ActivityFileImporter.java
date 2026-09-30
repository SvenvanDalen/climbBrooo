package nl.paree.climbpro.data.activity;

import android.content.Context;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.KnownClimbCatalog;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.domain.activity.ActivityFileReader;
import nl.paree.climbpro.domain.activity.ImportedActivityDedupe;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.matching.ActivityClimbMatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports rides exported from Garmin Connect (issue #253) — FIT, GPX or the "Export original"
 * zip — into the climb logbook: each activity is matched against every known climb exactly
 * like a Strava activity, rides already in the logbook via Strava are skipped, and
 * re-importing the same file adds nothing. Call off the main thread.
 */
public final class ActivityFileImporter {

    /** Outcome for the confirmation message. */
    public static final class Result {
        public final int activities;
        public final int newAttempts;
        public final int alreadyKnown;

        Result(int activities, int newAttempts, int alreadyKnown) {
            this.activities = activities;
            this.newAttempts = newAttempts;
            this.alreadyKnown = alreadyKnown;
        }
    }

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final IncompleteClimbAttemptRepository incompleteRepo;

    public ActivityFileImporter(Context context) {
        this.routeRepo = new RouteRepository(context);
        this.attemptRepo = new ClimbAttemptRepository(context);
        this.incompleteRepo = new IncompleteClimbAttemptRepository(context);
    }

    public Result importFile(String name, byte[] data) throws IOException {
        List<ActivityFileReader.Activity> activities = ActivityFileReader.read(name, data);
        if (activities.isEmpty()) {
            throw new IOException("Geen rit met GPS-tijden gevonden in " + name);
        }
        List<KnownClimb> climbs = KnownClimbCatalog.load(routeRepo);
        List<StoredClimbAttempt> existing = attemptRepo.loadAll();
        int before = existing.size();

        List<StoredClimbAttempt> candidates = new ArrayList<>();
        List<StoredIncompleteClimbAttempt> incomplete = new ArrayList<>();
        for (ActivityFileReader.Activity a : activities) {
            long start = a.startEpochSec();
            candidates.addAll(ActivityClimbMatcher.match(a.track, climbs,
                    ImportedActivityDedupe.activityIdFor(start), start, incomplete));
        }
        List<StoredClimbAttempt> fresh =
                ImportedActivityDedupe.withoutKnownRides(candidates, existing);
        if (!fresh.isEmpty()) attemptRepo.append(fresh);
        List<StoredIncompleteClimbAttempt> freshIncomplete = new ArrayList<>();
        List<StoredIncompleteClimbAttempt> knownIncomplete = incompleteRepo.loadAll();
        for (StoredIncompleteClimbAttempt i : incomplete) {
            boolean known = false;
            for (StoredIncompleteClimbAttempt k : knownIncomplete) {
                if (k.activityId != i.activityId && i.climbId != null && i.climbId.equals(k.climbId)
                        && Math.abs(k.dateEpochSec - i.dateEpochSec)
                                <= ImportedActivityDedupe.SAME_RIDE_WINDOW_SEC) {
                    known = true;
                    break;
                }
            }
            if (!known) freshIncomplete.add(i);
        }
        if (!freshIncomplete.isEmpty()) incompleteRepo.append(freshIncomplete);

        int added = attemptRepo.loadAll().size() - before; // append() also drops re-imports
        return new Result(activities.size(), added, candidates.size() - added);
    }
}
