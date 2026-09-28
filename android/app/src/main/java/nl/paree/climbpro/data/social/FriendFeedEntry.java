package nl.paree.climbpro.data.social;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One item in the friends' feed (issue #240): a ride or a milestone that a friend shared with a
 * share code. Also the in-memory form of your own entries before encoding (friend fields empty
 * then). Deliberately carries no coordinates or Strava ids. Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FriendFeedEntry {

    public static final String KIND_RIDE = "ride";
    public static final String KIND_MILESTONE = "milestone";

    /** Random id of the sharer's install (not personal data); groups a friend's entries. */
    public String friendId;
    /** Display name the friend chose when sharing. */
    public String friendName;
    public String kind;
    public long   epochSec;
    public String title;
    public int    distanceM;
    public int    gainM;
    public int    movingSec;

    public FriendFeedEntry() {}

    public FriendFeedEntry(String kind, long epochSec, String title,
                           int distanceM, int gainM, int movingSec) {
        this.kind = kind;
        this.epochSec = epochSec;
        this.title = title;
        this.distanceM = distanceM;
        this.gainM = gainM;
        this.movingSec = movingSec;
    }

    public boolean isRide() {
        return KIND_RIDE.equals(kind);
    }

    /**
     * Identity for de-duplication on re-import. A ride is its start second (so a ride renamed in
     * Strava replaces the old entry); milestones add the title because two first ascents of one
     * ride share the activity start time.
     */
    public String dedupeKey() {
        return isRide()
                ? friendId + "|" + kind + "|" + epochSec
                : friendId + "|" + kind + "|" + epochSec + "|" + title;
    }
}
