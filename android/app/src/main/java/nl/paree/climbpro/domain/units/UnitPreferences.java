package nl.paree.climbpro.domain.units;

/**
 * The rider's display-unit choice (issue #262): distance/speed/elevation metric or imperial,
 * tyre pressure in bar or psi, temperature in °C or °F. Storage and computation always stay
 * metric; only display converts (see {@link UnitFormatter}).
 *
 * <p>Sent to the watch as the optional top-level wire key {@code un}, a bitmask
 * ({@link #toWireFlags()}). Absent or 0 means all-metric, so old payloads and old watch
 * builds keep working unchanged.
 */
public final class UnitPreferences {

    /** Wire bit: distance, elevation and speed in miles/feet/mph. */
    public static final int FLAG_IMPERIAL = 1;
    /** Wire bit: tyre pressure in psi. */
    public static final int FLAG_PSI = 2;
    /** Wire bit: temperature in °F. */
    public static final int FLAG_FAHRENHEIT = 4;

    public static final UnitPreferences METRIC = new UnitPreferences(false, false, false);

    public final boolean imperial;
    public final boolean psi;
    public final boolean fahrenheit;

    public UnitPreferences(boolean imperial, boolean psi, boolean fahrenheit) {
        this.imperial = imperial;
        this.psi = psi;
        this.fahrenheit = fahrenheit;
    }

    /** Compact bitmask for the watch payload key {@code un}; 0 = all metric. */
    public int toWireFlags() {
        return (imperial ? FLAG_IMPERIAL : 0)
                | (psi ? FLAG_PSI : 0)
                | (fahrenheit ? FLAG_FAHRENHEIT : 0);
    }

    /** Inverse of {@link #toWireFlags()}; unknown bits are ignored. */
    public static UnitPreferences fromWireFlags(int flags) {
        return new UnitPreferences((flags & FLAG_IMPERIAL) != 0,
                (flags & FLAG_PSI) != 0,
                (flags & FLAG_FAHRENHEIT) != 0);
    }

    /** Stable signature for sync hashes: a unit change must trigger a watch resync. */
    public String signature() {
        return "un" + toWireFlags();
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof UnitPreferences)) return false;
        return toWireFlags() == ((UnitPreferences) o).toWireFlags();
    }

    @Override public int hashCode() {
        return toWireFlags();
    }
}
