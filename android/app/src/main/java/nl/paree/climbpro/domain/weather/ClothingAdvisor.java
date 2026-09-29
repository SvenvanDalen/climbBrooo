package nl.paree.climbpro.domain.weather;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clothing advice for a planned ride (issue #196): from the hourly forecast over the ride
 * window, the coldest and warmest "feels like on the bike" temperature (wind chill with the
 * riding speed added to the wind), mapped to a kit per temperature band, plus rain and wind
 * extras and a hint to bring removable layers when the temperature swings. Pure.
 */
public final class ClothingAdvisor {

    /** Typical riding speed added to the forecast wind for the wind-chill calculation. */
    static final double RIDING_SPEED_KMH = 25;
    /** Rain probability from which a rain jacket goes on / goes in the back pocket. */
    static final int RAIN_WEAR_PCT = 50;
    static final int RAIN_POCKET_PCT = 30;
    /** Wind from which a wind vest is worth taking regardless of temperature. */
    static final double WINDY_KMH = 30;
    /** Coldest-to-warmest spread from which removable layers are advised. */
    static final double SWING_C = 6;

    private ClothingAdvisor() {}

    public static final class Advice {
        /** False when the forecast has no temperature for the ride window. */
        public final boolean covered;
        public final double coldestFeelC;
        public final double warmestFeelC;
        public final int maxRainPct;
        public final double maxWindKmh;
        /** What to wear, base layer to outer layer. */
        public final List<String> kit;
        /** Extra remarks: rain, wind, removable layers, ice. */
        public final List<String> notes;

        Advice(boolean covered, double coldestFeelC, double warmestFeelC, int maxRainPct,
               double maxWindKmh, List<String> kit, List<String> notes) {
            this.covered = covered;
            this.coldestFeelC = coldestFeelC;
            this.warmestFeelC = warmestFeelC;
            this.maxRainPct = maxRainPct;
            this.maxWindKmh = maxWindKmh;
            this.kit = kit;
            this.notes = notes;
        }
    }

    /** Hours overlapping {@code [start, start + durationSec)}. */
    public static Advice advise(HourlyForecast f, Instant start, long durationSec) {
        Instant end = start.plusSeconds(durationSec);
        double coldest = Double.NaN;
        double warmest = Double.NaN;
        int maxRain = 0;
        double maxWind = 0;
        for (int i = 0; i < f.times.length; i++) {
            Instant h = f.times[i];
            if (!h.plusSeconds(3600).isAfter(start) || !h.isBefore(end)) continue;
            double t = f.temperature[i];
            if (Double.isNaN(t)) continue;
            double wind = Double.isNaN(f.windKmh[i]) ? 0 : f.windKmh[i];
            double feel = feelsOnBike(t, wind);
            coldest = Double.isNaN(coldest) ? feel : Math.min(coldest, feel);
            warmest = Double.isNaN(warmest) ? feel : Math.max(warmest, feel);
            if (f.rainPct[i] != null) maxRain = Math.max(maxRain, f.rainPct[i]);
            maxWind = Math.max(maxWind, wind);
        }
        if (Double.isNaN(coldest)) {
            return new Advice(false, Double.NaN, Double.NaN, 0, 0,
                    new ArrayList<>(), new ArrayList<>());
        }
        List<String> kit = kitFor(coldest);
        List<String> notes = new ArrayList<>();
        if (warmest - coldest >= SWING_C) {
            notes.add(String.format(Locale.GERMANY,
                    "Het verschil is groot (%.0f tot %.0f °C): kies lagen die je onderweg uit kunt "
                            + "doen, zoals arm- en beenstukken en een vest.", coldest, warmest));
        }
        if (maxRain >= RAIN_WEAR_PCT) {
            notes.add("Grote kans op regen (" + maxRain + "%): trek een regenjack aan"
                    + (coldest < 12 ? " en neem overschoenen." : "."));
        } else if (maxRain >= RAIN_POCKET_PCT) {
            notes.add("Kans op een bui (" + maxRain + "%): stop een regenjack in je achterzak.");
        }
        if (maxWind >= WINDY_KMH && coldest >= 12) {
            notes.add(String.format(Locale.GERMANY,
                    "Stevige wind (tot %.0f km/u): een windvest is fijn op open stukken.", maxWind));
        }
        if (coldest < 1) {
            notes.add("Rond het vriespunt: kans op gladheid, vooral op bruggen en in de schaduw.");
        }
        return new Advice(true, coldest, warmest, maxRain, maxWind, kit, notes);
    }

    /**
     * Wind chill (JAG/TI, as used by the KNMI) with the riding speed added to the wind; above
     * 10 °C wind chill is not defined and the air temperature is used as is.
     */
    static double feelsOnBike(double tempC, double windKmh) {
        if (tempC > 10) return tempC;
        double v = windKmh + RIDING_SPEED_KMH;
        double pow = Math.pow(v, 0.16);
        return 13.12 + 0.6215 * tempC - 11.37 * pow + 0.3965 * tempC * pow;
    }

    /** Kit for the coldest moment of the ride, base layer to outer layer. */
    static List<String> kitFor(double feelC) {
        List<String> kit = new ArrayList<>();
        if (feelC >= 22) {
            kit.add("Zomershirt met korte mouwen, korte broek");
            kit.add("Korte handschoenen en een pet of zonnebril");
        } else if (feelC >= 17) {
            kit.add("Dun onderhemd");
            kit.add("Shirt met korte mouwen, korte broek");
        } else if (feelC >= 12) {
            kit.add("Onderhemd");
            kit.add("Shirt met korte mouwen plus armstukken, korte broek");
            kit.add("Windvest mee");
        } else if (feelC >= 8) {
            kit.add("Onderhemd met lange of korte mouwen");
            kit.add("Shirt met lange mouwen of armstukken, beenstukken of kniestukken");
            kit.add("Windvest of licht windjack");
            kit.add("Dunne lange handschoenen");
        } else if (feelC >= 3) {
            kit.add("Thermisch onderhemd met lange mouwen");
            kit.add("Lange broek of beenstukken");
            kit.add("Winddicht jack");
            kit.add("Lange handschoenen en overschoenen");
            kit.add("Dunne muts of buff onder de helm");
        } else {
            kit.add("Thermisch onderhemd met lange mouwen");
            kit.add("Winterbroek met fleece");
            kit.add("Winterjack");
            kit.add("Winterhandschoenen en dichte overschoenen");
            kit.add("Muts onder de helm en een buff");
        }
        return kit;
    }

    public static String headline(Advice a) {
        if (!a.covered) return "Geen weersverwachting voor dit tijdstip";
        if (Math.round(a.coldestFeelC) == Math.round(a.warmestFeelC)) {
            return String.format(Locale.GERMANY, "Op de fiets voelt het als %.0f °C",
                    a.coldestFeelC);
        }
        return String.format(Locale.GERMANY, "Op de fiets voelt het als %.0f tot %.0f °C",
                a.coldestFeelC, a.warmestFeelC);
    }

    public static String detail(Advice a) {
        if (!a.covered) return "";
        StringBuilder sb = new StringBuilder();
        for (String k : a.kit) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(k);
        }
        if (!a.notes.isEmpty()) sb.append('\n');
        for (String n : a.notes) sb.append('\n').append(n);
        return sb.toString();
    }
}
