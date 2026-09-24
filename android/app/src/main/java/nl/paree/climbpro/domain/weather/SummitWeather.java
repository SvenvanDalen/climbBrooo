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
        return label("Dal", footEleM) + String.format(NL, "%.1f °C", foot.temperature[f]) + "\n"
                + label("Top", topEleM) + String.format(NL, "%.1f °C, voelt als %.1f °C",
                        top.temperature[t], top.apparent[t]) + "\n"
                + String.format(NL, "Wind op de top %.0f km/u", top.windKmh[t])
                + " · regenkans " + (rain != null ? rain + "%" : "onbekend") + "\n"
                + "Tip: " + clothingTip(top.apparent[t], top.windKmh[t]);
    }

    public static String clothingTip(double topApparentC, double topWindKmh) {
        String tip;
        if (topApparentC < 5) tip = "Winterkleding: lange broek, winterjack en handschoenen";
        else if (topApparentC < 10) tip = "Arm- en beenstukken plus een windjack";
        else if (topApparentC < 15) tip = "Neem een windvestje mee voor de afdaling";
        else tip = "Zomertenue volstaat";
        return topWindKmh >= 30 ? tip + ". Let op: harde wind op de top" : tip;
    }

    private static String label(String what, double eleM) {
        return Double.isNaN(eleM) ? what + ": " : String.format(NL, "%s (%.0f m): ", what, eleM);
    }
}
