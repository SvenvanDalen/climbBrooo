package nl.paree.climbpro.domain.weather;

import java.time.Instant;
import java.util.Locale;

/** Valley-versus-summit weather text with a clothing tip (issue #246). Pure. */
public final class SummitWeather {

    private static final Locale NL = new Locale("nl");

    private SummitWeather() {}

    public static String describe(HourlyForecast foot, HourlyForecast top, Instant when,
                                  double footEleM, double topEleM) {
        int f = foot.indexAt(when);
        int t = top.indexAt(when);
        if (f < 0 || t < 0) return null;
        Integer rain = top.rainPct[t];
        return label("Dal", footEleM) + celsius(foot.temperature[f]) + "\n"
                + label("Top", topEleM) + celsius(top.temperature[t])
                + ", voelt als " + celsius(top.apparent[t]) + "\n"
                + "Wind op de top " + (Double.isNaN(top.windKmh[t]) ? "onbekend"
                        : String.format(NL, "%.0f km/u", top.windKmh[t]))
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
    private static String celsius(double c) {
        return Double.isNaN(c) ? "onbekend" : String.format(NL, "%.1f °C", c);
    }

    private static String label(String what, double eleM) {
        return Double.isNaN(eleM) ? what + ": " : String.format(NL, "%s (%.0f m): ", what, eleM);
    }
}
