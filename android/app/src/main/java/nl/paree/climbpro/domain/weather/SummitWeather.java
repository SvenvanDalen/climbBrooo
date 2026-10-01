package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.domain.units.UnitFormatter;
import nl.paree.climbpro.domain.units.UnitPreferences;

import java.time.Instant;
import java.util.Locale;

/** Valley-versus-summit weather text with a clothing tip (issue #246). Pure. */
public final class SummitWeather {

    private static final Locale NL = new Locale("nl");

    private SummitWeather() {}

    public static String describe(HourlyForecast foot, HourlyForecast top, Instant when,
                                  double footEleM, double topEleM) {
        return describe(foot, top, when, footEleM, topEleM, UnitPreferences.METRIC);
    }

    /** As above, rendered in the rider's display units (issue #262); thresholds stay metric. */
    public static String describe(HourlyForecast foot, HourlyForecast top, Instant when,
                                  double footEleM, double topEleM, UnitPreferences units) {
        UnitFormatter fmt = new UnitFormatter(units, NL);
        int f = foot.indexAt(when);
        int t = top.indexAt(when);
        if (f < 0 || t < 0) return null;
        Integer rain = top.rainPct[t];
        return label("Dal", footEleM, fmt) + temperature(foot.temperature[f], fmt) + "\n"
                + label("Top", topEleM, fmt) + temperature(top.temperature[t], fmt)
                + ", voelt als " + temperature(top.apparent[t], fmt) + "\n"
                + "Wind op de top " + (Double.isNaN(top.windKmh[t]) ? "onbekend"
                        : String.format(NL, "%.0f %s", fmt.speedValue(top.windKmh[t]),
                                fmt.speedUnit()))
                + " · regenkans " + (rain != null ? rain + "%" : "onbekend") + "\n"
                + "Tip: " + clothingTip(top.apparent[t], top.windKmh[t]);
    }

    public static String clothingTip(double topApparentC, double topWindKmh) {
        String tip;
        if (Double.isNaN(topApparentC)) tip = "Geen kledingadvies, gevoelstemperatuur onbekend";
        else if (topApparentC < 5) tip = "Winterkleding: lange broek, winterjack en handschoenen";
        else if (topApparentC < 10) tip = "Arm- en beenstukken plus een windjack";
        else if (topApparentC < 15) tip = "Neem een windvestje mee voor de afdaling";
        else tip = "Zomertenue volstaat";
        return topWindKmh >= 30 ? tip + ". Let op: harde wind op de top" : tip;
    }

    /** Open-Meteo sends null for missing hours; show "onbekend" rather than "NaN °C". */
    private static String temperature(double c, UnitFormatter fmt) {
        return Double.isNaN(c) ? "onbekend"
                : String.format(NL, "%.1f %s", fmt.temperatureValue(c), fmt.temperatureUnit());
    }

    private static String label(String what, double eleM, UnitFormatter fmt) {
        return Double.isNaN(eleM) ? what + ": "
                : String.format(NL, "%s (%.0f %s): ", what, fmt.elevationValue(eleM),
                        fmt.elevationUnit());
    }
}
