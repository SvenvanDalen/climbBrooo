package nl.paree.climbpro.data.bike;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One cost of a bike (issue #233): the purchase itself or a part/service. Amount in integer
 * cents — never a float. Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BikeCostEntry {

    public static final String KIND_PURCHASE = "PURCHASE";
    public static final String KIND_PART     = "PART";

    public String id;
    /** {@link #KIND_PURCHASE} or {@link #KIND_PART}; anything else is treated as a part. */
    public String kind;
    public String description;
    /** Amount in euro cents, 1 .. EuroAmount.MAX_CENTS. */
    public long   amountCents;
    /** When the entry was added (epoch seconds); only used for ordering and display. */
    public long   epochSec;

    /** Unknown or missing kinds become {@link #KIND_PART}. */
    public static String cleanKind(String kind) {
        return KIND_PURCHASE.equals(kind) ? KIND_PURCHASE : KIND_PART;
    }

    /** Dutch label: "Aankoop" or "Onderdeel". */
    public static String label(String kind) {
        return KIND_PURCHASE.equals(kind) ? "Aankoop" : "Onderdeel";
    }
}
