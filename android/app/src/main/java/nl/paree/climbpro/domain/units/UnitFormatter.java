package nl.paree.climbpro.domain.units;

import java.util.Locale;

/**
 * The one place the phone UI turns metric values into display strings in the rider's chosen
 * units (issue #262). Inputs are always metric (metres, km/h, bar, °C) because storage and
 * all domain computation stay metric; only the rendered text converts.
 *
 * <p>Pure Java (no Android types) so it is unit-testable; UI code gets an instance from
 * {@code UnitPreferencesRepository#formatter(Context)}.
 */
public final class UnitFormatter {

    public static final double METERS_PER_MILE = 1609.344;
    public static final double FEET_PER_METER = 3.28084;
    public static final double PSI_PER_BAR = 14.5038;

    private final UnitPreferences prefs;
    private final Locale locale;

    public UnitFormatter(UnitPreferences prefs) {
        this(prefs, Locale.getDefault());
    }

    public UnitFormatter(UnitPreferences prefs, Locale locale) {
        this.prefs = prefs != null ? prefs : UnitPreferences.METRIC;
        this.locale = locale != null ? locale : Locale.getDefault();
    }

    public UnitPreferences preferences() {
        return prefs;
    }

    // ---- raw conversions ---------------------------------------------------------------

    public static double metersToMiles(double meters) { return meters / METERS_PER_MILE; }
    public static double metersToFeet(double meters)  { return meters * FEET_PER_METER; }
    public static double kmhToMph(double kmh)         { return kmh * 1000.0 / METERS_PER_MILE; }
    public static double barToPsi(double bar)         { return bar * PSI_PER_BAR; }
    public static double psiToBar(double psi)         { return psi / PSI_PER_BAR; }
    public static double celsiusToFahrenheit(double c) { return c * 9.0 / 5.0 + 32.0; }

    // ---- values in the chosen unit (for string resources with their own format) -------

    /** Long distance in km or miles. */
    public double distanceValue(double meters) {
        return prefs.imperial ? metersToMiles(meters) : meters / 1000.0;
    }

    /** Elevation or short length in metres or feet. */
    public double elevationValue(double meters) {
        return prefs.imperial ? metersToFeet(meters) : meters;
    }

    public double speedValue(double kmh) {
        return prefs.imperial ? kmhToMph(kmh) : kmh;
    }

    public double pressureValue(double bar) {
        return prefs.psi ? barToPsi(bar) : bar;
    }

    public double temperatureValue(double celsius) {
        return prefs.fahrenheit ? celsiusToFahrenheit(celsius) : celsius;
    }

    // ---- unit labels ---------------------------------------------------------------------

    public String distanceUnit()    { return prefs.imperial ? "mi" : "km"; }
    public String elevationUnit()   { return prefs.imperial ? "ft" : "m"; }
    public String speedUnit()       { return prefs.imperial ? "mph" : "km/u"; }
    public String pressureUnit()    { return prefs.psi ? "psi" : "bar"; }
    public String temperatureUnit() { return prefs.fahrenheit ? "°F" : "°C"; }

    // ---- formatted strings ---------------------------------------------------------------

    /** Long distance with one decimal: "12.3 km" / "7.6 mi". */
    public String distance(double meters) {
        return String.format(locale, "%.1f %s", distanceValue(meters), distanceUnit());
    }

    /** Elevation, whole: "450 m" / "1476 ft". */
    public String elevation(double meters) {
        return String.format(locale, "%d %s", Math.round(elevationValue(meters)), elevationUnit());
    }

    /** Short length (a segment, a climb under a kilometre), whole: "850 m" / "2789 ft". */
    public String shortDistance(double meters) {
        return elevation(meters);
    }

    /**
     * A climb's length: whole metres in metric ("2400 m", unchanged from before issue #262);
     * imperial shows miles with one decimal from ~0.2 mi ("1.5 mi"), feet below that. The
     * 0.2 mi threshold matches the watch's Units.formatDist.
     */
    public String climbLength(double meters) {
        if (prefs.imperial && meters >= IMPERIAL_MILES_FROM_M) return distance(meters);
        return shortDistance(meters);
    }

    /** From this many metres (~0.2 mi) imperial lengths render in miles instead of feet. */
    public static final int IMPERIAL_MILES_FROM_M = 322;

    /** Speed, whole: "25 km/u" / "16 mph". */
    public String speed(double kmh) {
        return String.format(locale, "%d %s", Math.round(speedValue(kmh)), speedUnit());
    }

    /** Tyre pressure: "4.5 bar" (one decimal) / "65 psi" (whole). */
    public String pressure(double bar) {
        if (prefs.psi) {
            return String.format(locale, "%d psi", Math.round(barToPsi(bar)));
        }
        return String.format(locale, "%.1f bar", bar);
    }

    /** Temperature, whole: "12 °C" / "54 °F". */
    public String temperature(double celsius) {
        return String.format(locale, "%d %s", Math.round(temperatureValue(celsius)),
                temperatureUnit());
    }
}
