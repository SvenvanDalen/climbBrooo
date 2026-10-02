package nl.paree.climbpro.ui.social;

import android.content.Context;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.ClimbCatalogIndex;
import nl.paree.climbpro.domain.social.RideBuddyProfile;
import nl.paree.climbpro.domain.social.RideBuddyProfileBuilder;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds your own {@link RideBuddyProfile} from the local ride archive and climb attempts.
 * Shared by the ride-buddy matcher (#242) and the group-ride planner (#195). Does file I/O:
 * call it off the main thread.
 */
final class OwnRideBuddyProfile {

    private OwnRideBuddyProfile() {}

    static RideBuddyProfile build(Context context) {
        Context app = context.getApplicationContext();
        List<StoredClimbAttempt> attempts = new ClimbAttemptRepository(app).loadAll();
        Set<String> ids = new HashSet<>();
        for (StoredClimbAttempt a : attempts) if (a.climbId != null) ids.add(a.climbId);
        Map<String, Integer> gains = new HashMap<>();
        for (Map.Entry<String, List<StoredClimb>> en : ClimbCatalogIndex.resolveAllCopies(
                new RouteRepository(app), ids).entrySet()) {
            for (StoredClimb c : en.getValue()) {
                if (c != null && c.elevationGain > 0) {
                    gains.put(en.getKey(), c.elevationGain);
                    break;
                }
            }
        }
        return RideBuddyProfileBuilder.build(new RideRepository(app).loadAll(),
                attempts, gains, System.currentTimeMillis() / 1000L, ZoneId.systemDefault());
    }
}
