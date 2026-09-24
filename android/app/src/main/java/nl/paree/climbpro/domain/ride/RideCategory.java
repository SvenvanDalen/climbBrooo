package nl.paree.climbpro.domain.ride;

/** Automatic ride classification for the phone-side ride archive (issue #160). */
public enum RideCategory {
    /** Woon-werk: Strava commute flag, or a repeated short point-to-point A-to-B ride. */
    COMMUTE,
    /** Training: everything that is neither commute nor tour, including indoor rides. */
    TRAINING,
    /** Toerrit: long in distance or time. */
    TOUR
}
