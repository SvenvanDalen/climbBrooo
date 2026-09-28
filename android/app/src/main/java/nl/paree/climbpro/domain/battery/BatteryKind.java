package nl.paree.climbpro.domain.battery;

/**
 * Device categories for the battery tracker (issue #238), each with a sensible default
 * recharge interval the rider can override. Stored by {@link #name()}.
 */
public enum BatteryKind {
    E_SHIFTING("E-shifting", 45),
    LIGHT("Verlichting", 14),
    POWER_METER("Powermeter", 90),
    HEAD_UNIT("Fietscomputer / sensor", 30),
    OTHER("Overig", 30);

    public final String label;
    public final int defaultIntervalDays;

    BatteryKind(String label, int defaultIntervalDays) {
        this.label = label;
        this.defaultIntervalDays = defaultIntervalDays;
    }

    /** Parses a stored name; null or unknown values fall back to {@link #OTHER}. */
    public static BatteryKind fromName(String name) {
        if (name != null) {
            for (BatteryKind k : values()) if (k.name().equals(name)) return k;
        }
        return OTHER;
    }
}
