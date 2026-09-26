package nl.paree.climbpro.data.safehome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object of {@code safe_home.json} (issue #231 "Ik ben veilig thuis"-bericht): who gets
 * the message, what it says, and which rides were already reported. Phone-only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SafeHomeSettings {

    public static final String DEFAULT_MESSAGE =
            "Ik ben veilig thuis na mijn fietsrit van {km} km.";

    public boolean enabled;
    /** Display name of the picked contact; null when the number was typed in. */
    public String  contactName;
    public String  phoneNumber;
    /** Template; {@code {km}} and {@code {naam}} are replaced by the ride's values. */
    public String  message = DEFAULT_MESSAGE;
    /** Send the SMS without asking (needs SEND_SMS); otherwise a one-tap notification. */
    public boolean autoSms;
    /**
     * Moment the feature was (re-)enabled. Rides that ended before it are never reported, so
     * switching the feature on doesn't message about this morning's ride.
     */
    public long    armedSinceEpochSec;
    /** Strava activity ids already reported, newest last, capped. */
    public List<Long> reportedActivityIds = new ArrayList<>();
}
