package nl.paree.climbpro.data.privacy;

/**
 * Every kind of personal data the app keeps on the phone (privacy dashboard, issue #264).
 * File-backed categories list their paths relative to {@code getFilesDir()} (directories end
 * in {@code /}); preference-backed ones have no paths and are inspected through
 * SharedPreferences by the dashboard. Keep this list in step with new persistence — a store
 * missing here is invisible to the user.
 */
public enum PrivacyCategory {

    ROUTES("Routes en klimmen",
            "Geïmporteerde routes met hun klimmen, namen, notities en ondergrond.",
            "routes/", "catalog.json", "sync_state.json"),
    ATTEMPTS("Klimpogingen",
            "Tijden per klim uit je Strava-activiteiten, met notities.",
            "climb_attempts.json", "incomplete_climb_attempts.json"),
    RIDES("Rittenarchief",
            "Samenvatting per Strava-fietsrit: afstand, tijd, hoogtemeters, snelheid en "
                    + "start- en eindpunt.",
            "rides.json"),
    TIRE_PRESSURE("Bandenspanning-logboek",
            "Je gemeten bandenspanning per datum, met notities en de herinneringsinstellingen.",
            "tire_pressure_log.json"),
    MAINTENANCE("Onderhoud",
            "Je onderdelen met onderhoudsintervallen en de data waarop je ze onderhield.",
            "maintenance.json"),
    COMEBACK_PLAN("Terugkomstplan",
            "Je actieve opbouwplan na een pauze of blessure (startdatum en of het om een "
                    + "blessure gaat).",
            "comeback_plan.json"),
    PHOTOS("Foto's bij pogingen",
            "Foto's die je aan een klimpoging hebt gekoppeld.",
            "attempt_photos/"),
    COLLECTIONS("Collecties",
            "Je eigen groepen van routes en klimmen.",
            "collections.json"),
    PLANNING("Klimplanning",
            "Geplande klimmen en hun herinneringen.",
            "planned_climbs.json"),
    LOCATION("Laatst bekende locatie",
            "Gebruikt voor de radius-modus om klimmen in de buurt te kiezen."),
    RIDER_PROFILE("Rijdersprofiel",
            "FTP, gewicht van jou en je fiets en rit-intensiteit."),
    STRAVA("Strava-koppeling",
            "Versleuteld toegangstoken en de voortgang van de activiteiten-sync."),
    CACHE("Tijdelijke bestanden",
            "Gedeelde exports en downloads in de cache; Android mag deze ook zelf wissen.");

    public final String label;
    public final String description;
    private final String[] paths;

    PrivacyCategory(String label, String description, String... paths) {
        this.label = label;
        this.description = description;
        this.paths = paths;
    }

    /** Paths relative to {@code getFilesDir()}; empty for preference- or cache-backed data. */
    public String[] paths() {
        return paths.clone();
    }
}
