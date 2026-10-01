package nl.paree.climbpro.data.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One tour ride / gran fondo in the event calendar (issue #241), from an iCal feed or entered
 * by hand. Distances and elevation come from the event text when the organiser mentions them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CyclingEvent {

    /** Feed UID, or a generated id for manual events. */
    public String uid;
    public String name;
    /** Event day as ISO-8601 local date, e.g. {@code 2027-06-14}. */
    public String date;
    public String location;
    /** Coordinates from the feed's GEO, or geocoded from {@link #location}; null = unknown. */
    public Double lat;
    public Double lon;
    public String url;
    public String description;
    /** Route options in km, ascending (e.g. 60, 100, 150); empty = unknown. */
    public List<Integer> distancesKm = new ArrayList<>();
    /** Elevation of the longest option in metres; null = unknown. */
    public Integer elevationM;
    /** Feed URL the event came from; null for a manual event. */
    public String feedUrl;
}
