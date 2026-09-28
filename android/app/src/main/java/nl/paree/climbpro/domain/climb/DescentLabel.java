package nl.paree.climbpro.domain.climb;

import java.util.Locale;

/** Dutch one-line summary of a {@link DescentAnalyzer.Descent} for the climb screen (issue #215). */
public final class DescentLabel {

    private DescentLabel() {}

    public static String format(DescentAnalyzer.Descent d) {
        if (d == null) return "Afdaling na de top: op deze route volgt geen noemenswaardige afdaling.";
        Locale nl = new Locale("nl");
        StringBuilder sb = new StringBuilder(String.format(nl,
                "Afdaling na de top: %.1f km · %d m omlaag · gem. %.1f %% · max %.1f %% · %s",
                d.lengthM / 1000.0, d.dropM, d.avgGradient * 100, d.maxGradient * 100,
                twistiness(d.twistiness)));
        if (d.hairpins == 1) {
            sb.append(" · 1 haarspeldbocht");
        } else if (d.hairpins > 1) {
            sb.append(" · ").append(d.hairpins).append(" haarspeldbochten");
        }
        if (d.end == DescentAnalyzer.End.NEXT_CLIMB) {
            sb.append(" (tot de volgende klim)");
        } else if (d.end == DescentAnalyzer.End.ROUTE_END) {
            sb.append(" (tot het einde van de route)");
        }
        return sb.toString();
    }

    static String twistiness(DescentAnalyzer.Twistiness t) {
        switch (t) {
            case VERY_TWISTY: return "zeer bochtig";
            case TWISTY: return "bochtig";
            default: return "vrij recht";
        }
    }
}
