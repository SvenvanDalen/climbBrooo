package nl.paree.climbpro.domain.bike;

import nl.paree.climbpro.data.bike.BikePassport;

/**
 * Plain-text rendering of a {@link BikePassport} (issue #190) for a police report or insurance
 * claim, shared together with the photos. Only filled-in fields appear. Pure.
 */
public final class BikePassportText {

    private BikePassportText() {}

    public static String format(BikePassport p) {
        StringBuilder sb = new StringBuilder("Fietspaspoort: ").append(p.name).append('\n');
        line(sb, "Merk", p.brand);
        line(sb, "Model", p.model);
        line(sb, "Kleur", p.color);
        line(sb, "Framenummer", p.frameNumber);
        line(sb, "Aankoopdatum", p.purchaseDate);
        line(sb, "Aankoopprijs", p.purchasePrice);
        line(sb, "Gekocht bij", p.shop);
        line(sb, "Kenmerken", p.features);
        int photos = p.photoFileNames == null ? 0 : p.photoFileNames.size();
        if (photos > 0 || p.receiptFileName != null) {
            sb.append('\n').append("Bijlagen: ");
            if (photos > 0) sb.append(photos == 1 ? "1 foto" : photos + " foto's");
            if (photos > 0 && p.receiptFileName != null) sb.append(" en ");
            if (p.receiptFileName != null) sb.append("aankoopbewijs");
            sb.append('\n');
        }
        return sb.toString();
    }

    /** One-line summary for the list: "Canyon Ultimate · frame WAC1234" or a nudge. */
    public static String summary(BikePassport p) {
        StringBuilder sb = new StringBuilder();
        if (p.brand != null) sb.append(p.brand);
        if (p.model != null) sb.append(sb.length() > 0 ? " " : "").append(p.model);
        if (p.frameNumber != null) {
            sb.append(sb.length() > 0 ? " · " : "").append("frame ").append(p.frameNumber);
        } else {
            sb.append(sb.length() > 0 ? " · " : "").append("framenummer ontbreekt");
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }
}
