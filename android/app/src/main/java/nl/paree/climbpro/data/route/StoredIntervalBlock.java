package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serialised interval block attached to a climb (issue #180): a power band as percent of FTP.
 * Always read through {@code domain.power.IntervalBlock#fromStored}, which validates it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredIntervalBlock {

    /** {@code IntervalBlock.Preset} name, e.g. "DREMPEL" or "CUSTOM". */
    public String preset;
    public int lowPct;
    public int highPct;
}
