package nl.paree.climbpro.data.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/** Everything in {@code event_calendar.json} (issue #241). */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EventCalendar {

    /** An iCal feed the rider subscribed to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Feed {
        public String url;
        public String name;
        /** Last fetch problem shown in the feed list; null when the last fetch worked. */
        public String lastError;
    }

    public static final int DEFAULT_RADIUS_KM = 75;

    public List<Feed> feeds = new ArrayList<>();
    /** Events entered by hand; never touched by a refresh. */
    public List<CyclingEvent> manualEvents = new ArrayList<>();
    /** Last fetched events of all feeds; replaced per feed on refresh. */
    public List<CyclingEvent> feedEvents = new ArrayList<>();
    public long lastFetchMs;
    public int radiusKm = DEFAULT_RADIUS_KM;
}
