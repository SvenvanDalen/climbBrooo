package nl.paree.climbpro.data.rider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One weight measurement in the weight log (issue #408). Phone-only, never sent to the watch. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class WeightEntry {
    /** ISO date (yyyy-MM-dd) the weight was measured on; one entry per day. */
    public String date;
    public double kg;
    /** {@link #SOURCE_MANUAL} or {@link #SOURCE_HEALTH_CONNECT}. */
    public String source;

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_HEALTH_CONNECT = "health_connect";
}
