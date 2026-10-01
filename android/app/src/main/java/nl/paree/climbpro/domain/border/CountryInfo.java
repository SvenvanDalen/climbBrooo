package nl.paree.climbpro.domain.border;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Static table: country code -> Dutch name, spoken languages and the emergency number to call
 * (issue #209). Pure.
 *
 * <p>112 works on every GSM network in Europe (it's part of the GSM standard), so it is the
 * default and the fallback for unknown codes; where a different national number is the one
 * locals use (UK 999, Belarus 103) it is listed first.
 */
public final class CountryInfo {

    public static final String DEFAULT_EMERGENCY = "112";
    private static final Locale NL = new Locale("nl");

    public final String code;
    public final String nameNl;
    /** Slash-separated languages in Dutch, e.g. "Nederlands/Frans/Duits"; empty when unknown. */
    public final String languages;
    public final String emergency;

    private CountryInfo(String code, String nameNl, String languages, String emergency) {
        this.code = code;
        this.nameNl = nameNl;
        this.languages = languages;
        this.emergency = emergency;
    }

    private static final Map<String, CountryInfo> TABLE = new HashMap<>();

    private static void put(String code, String name, String languages, String emergency) {
        TABLE.put(code, new CountryInfo(code, name, languages, emergency));
    }

    private static void put(String code, String name, String languages) {
        put(code, name, languages, DEFAULT_EMERGENCY);
    }

    static {
        put("NL", "Nederland", "Nederlands");
        put("BE", "België", "Nederlands/Frans/Duits");
        put("DE", "Duitsland", "Duits");
        put("LU", "Luxemburg", "Luxemburgs/Frans/Duits");
        put("FR", "Frankrijk", "Frans");
        put("CH", "Zwitserland", "Duits/Frans/Italiaans/Reto-Romaans");
        put("AT", "Oostenrijk", "Duits");
        put("IT", "Italië", "Italiaans");
        put("ES", "Spanje", "Spaans/Catalaans/Baskisch/Galicisch");
        put("PT", "Portugal", "Portugees");
        put("DK", "Denemarken", "Deens");
        put("CZ", "Tsjechië", "Tsjechisch");
        put("PL", "Polen", "Pools");
        put("SI", "Slovenië", "Sloveens");
        put("LI", "Liechtenstein", "Duits");
        put("AD", "Andorra", "Catalaans");
        put("MC", "Monaco", "Frans");
        put("SM", "San Marino", "Italiaans");
        put("VA", "Vaticaanstad", "Italiaans/Latijn");
        put("GB", "Verenigd Koninkrijk", "Engels/Welsh", "999 / 112");
        put("JE", "Jersey", "Engels/Frans", "999 / 112");
        put("GG", "Guernsey", "Engels/Frans", "999 / 112");
        put("IM", "Isle of Man", "Engels", "999 / 112");
        put("IE", "Ierland", "Engels/Iers", "112 / 999");
        put("SK", "Slowakije", "Slowaaks");
        put("HU", "Hongarije", "Hongaars");
        put("HR", "Kroatië", "Kroatisch");
        put("SE", "Zweden", "Zweeds");
        put("NO", "Noorwegen", "Noors");
        put("FI", "Finland", "Fins/Zweeds");
        put("AX", "Åland", "Zweeds");
        put("EE", "Estland", "Estisch");
        put("LV", "Letland", "Lets");
        put("LT", "Litouwen", "Litouws");
        put("IS", "IJsland", "IJslands");
        put("FO", "Faeröer", "Faeröers/Deens");
        put("GR", "Griekenland", "Grieks");
        put("BG", "Bulgarije", "Bulgaars");
        put("RO", "Roemenië", "Roemeens");
        put("MD", "Moldavië", "Roemeens");
        put("RS", "Servië", "Servisch");
        put("ME", "Montenegro", "Montenegrijns");
        put("MK", "Noord-Macedonië", "Macedonisch/Albanees");
        put("BA", "Bosnië en Herzegovina", "Bosnisch/Kroatisch/Servisch");
        put("AL", "Albanië", "Albanees");
        put("XK", "Kosovo", "Albanees/Servisch");
        put("MT", "Malta", "Maltees/Engels");
        put("UA", "Oekraïne", "Oekraïens");
        put("BY", "Belarus", "Belarussisch/Russisch", "103 / 112");
    }

    /** Never null: unknown codes get the code as name, no languages and 112. */
    public static CountryInfo of(String code) {
        if (code == null) return new CountryInfo("?", "?", "", DEFAULT_EMERGENCY);
        String key = code.trim().toUpperCase(Locale.ROOT);
        CountryInfo info = TABLE.get(key);
        return info != null ? info : new CountryInfo(key, key, "", DEFAULT_EMERGENCY);
    }

    public static boolean isKnown(String code) {
        return code != null && TABLE.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }

    /** E.g. "km 84,3 → België · Nederlands/Frans/Duits · 112". */
    public static String formatCrossing(BorderCrossing c) {
        return String.format(NL, "km %.1f → ", c.distanceM / 1000.0) + describe(of(c.toCountry));
    }

    /** E.g. "Start in Nederland · Nederlands · 112". */
    public static String formatStart(String code) {
        return "Start in " + describe(of(code));
    }

    private static String describe(CountryInfo info) {
        StringBuilder sb = new StringBuilder(info.nameNl);
        if (!info.languages.isEmpty()) sb.append(" · ").append(info.languages);
        sb.append(" · ").append(info.emergency);
        return sb.toString();
    }
}
