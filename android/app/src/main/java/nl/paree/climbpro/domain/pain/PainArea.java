package nl.paree.climbpro.domain.pain;

/** Body areas the pain log (issue #232) offers; stored by {@link #name()}. */
public enum PainArea {
    KNEE("Knie"),
    BACK("Onderrug"),
    NECK("Nek / schouders"),
    SADDLE("Zitvlak"),
    HANDS("Handen / polsen"),
    FEET("Voeten"),
    OTHER("Anders");

    public final String label;

    PainArea(String label) {
        this.label = label;
    }

    /** Parses a stored name; null for null or unknown values. */
    public static PainArea fromName(String name) {
        if (name == null) return null;
        for (PainArea a : values()) if (a.name().equals(name)) return a;
        return null;
    }
}
