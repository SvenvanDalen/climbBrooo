package nl.paree.climbpro.domain.social;

/**
 * One rider in a group-ride plan (issue #195): a display name plus the few habits the planner
 * needs. Comes from your own derived {@link RideBuddyProfile}, an imported ride-buddy profile
 * code (#242), or a manual entry (name + average speed) for riders without ClimbPro. Every
 * habit is optional: 0 means "unknown" and the planner falls back to a default (speeds) or
 * treats the rider as available (schedule). Immutable.
 */
public final class GroupRideParticipant {

    public final String name;
    /** Typical average speed on flat-ish rides, in 0.1 km/h; 0 = unknown. */
    public final int flatSpeedDkmh;
    /** Typical climbing speed (VAM) in metres per hour; 0 = unknown. */
    public final int vamMph;
    /** Bit 0 = Monday … bit 6 = Sunday; 0 = unknown (available every day). */
    public final int weekdays;
    /** {@link RideBuddyProfile#PART_MORNING} etc.; 0 = unknown (available all day). */
    public final int dayparts;

    public GroupRideParticipant(String name, int flatSpeedDkmh, int vamMph, int weekdays,
                                int dayparts) {
        this.name = name == null ? "" : name.trim();
        this.flatSpeedDkmh = Math.max(0, flatSpeedDkmh);
        this.vamMph = Math.max(0, vamMph);
        this.weekdays = weekdays & 127;
        this.dayparts = dayparts & 7;
    }

    /** A manually entered rider: only a name and an average speed in km/h. */
    public static GroupRideParticipant manual(String name, double avgSpeedKmh) {
        int dkmh = avgSpeedKmh > 0 ? (int) Math.round(avgSpeedKmh * 10) : 0;
        return new GroupRideParticipant(name, dkmh, 0, 0, 0);
    }

    public static GroupRideParticipant fromProfile(RideBuddyProfile p, String nameOverride) {
        String n = nameOverride != null ? nameOverride : p.name;
        return new GroupRideParticipant(n, p.flatSpeedDkmh, p.vamMph, p.weekdays, p.dayparts);
    }

    public boolean ridesOn(int dayIndexMondayZero) {
        return weekdays == 0 || (weekdays & (1 << dayIndexMondayZero)) != 0;
    }

    public boolean ridesIn(int daypartBit) {
        return dayparts == 0 || (dayparts & daypartBit) != 0;
    }
}
