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
            "Tijden per klim uit je Strava-activiteiten, met notities en de namen van wie er "
                    + "meereed.",
            "climb_attempts.json", "incomplete_climb_attempts.json"),
    RIDES("Rittenarchief",
            "Samenvatting per Strava-fietsrit: afstand, tijd, hoogtemeters, snelheid, "
                    + "gemiddeld vermogen en start- en eindpunt, de gemeten regen voor de "
                    + "schoonmaakherinnering en je snelste 10, 40 en 100 km, je beste sprint, "
                    + "gemiddelde hartslag, hartslag-drift, vermogenscurve en tijd per "
                    + "hartslag- en vermogensniveau per rit, en je herstel-check na de rit "
                    + "(hoe zwaar de rit voelde, je slaap en notities), en voor de "
                    + "ontdek-de-regio-kaart welke vakken van ~150 m je buiten hebt gereden "
                    + "(geen volledige GPS-sporen).",
            "rides.json", "wet_ride_checks.json", "ride_stream_stats.json",
            "recovery_checks.json", "explore_tiles.json"),
    TIRE_PRESSURE("Bandenspanning-logboek",
            "Je gemeten bandenspanning per datum, met notities en de herinneringsinstellingen.",
            "tire_pressure_log.json"),
    MAINTENANCE("Onderhoud",
            "Je onderdelen met onderhoudsintervallen en de data waarop je ze onderhield.",
            "maintenance.json"),
    TORQUE("Aanhaalmomenten",
            "Je eigen aanhaalmomenten per fiets en onderdeel, met notities.",
            "torque_values.json"),
    BIKE_COSTS("Fietsgarage en fietskosten",
            "Je fietsen met soort, gewicht, banden, versnellingen en Strava-fiets-id, plus "
                    + "aankopen, onderdelen en hun bedragen.",
            "bike_costs.json"),
    BIKE_PASSPORTS("Fietspaspoort",
            "Framenummer, merk, model, aankoopgegevens, foto's en aankoopbewijs van je fietsen.",
            "bike_passports.json", "bike_passport_photos/"),
    BATTERIES("Accu's",
            "Je accu's (e-shifting, verlichting, powermeter) met de laatste laaddatum en "
                    + "herinneringsinterval.",
            "battery_status.json"),
    PAIN_LOG("Pijnlogboek",
            "Je gelogde klachten per rit, met fiets, afstelling en notities.",
            "pain_log.json"),
    SWEAT_LOSS("Zweetverlies-metingen",
            "Je gewicht voor en na ritten, hoeveel je onderweg dronk en je notities.",
            "sweat_loss_log.json"),
    SAFE_HOME("Veilig thuis-bericht",
            "Het telefoonnummer en de naam van je contact, je berichttekst en welke ritten al "
                    + "gemeld zijn.",
            "safe_home.json"),
    MEDICAL_ID("Medische ID",
            "Je naam, bloedgroep, allergieën, medicatie, noodcontact en overige info, en of "
                    + "ze op het vergrendelscherm staan. Ook als kopie in de horloge-widget.",
            "medical_id.json"),
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
    PACKING_LISTS("Paklijsten",
            "Je paklijsten per rittype en wat je hebt afgevinkt.",
            "packing_lists.json"),
    FRIENDS("Vriendenfeed",
            "Ritten en mijlpalen die vrienden met een deelcode met je deelden, en de naam "
                    + "waaronder je zelf deelt.",
            "friend_feed.json"),
    RIDE_BUDDIES("Ritmaatjes",
            "Rijdersprofielen die anderen met een profielcode met je deelden: naam, tempo, "
                    + "klimsnelheid, ritlengte, rittype, rijdagen en eventueel een grof gebied "
                    + "(vak van ~5 km). Je eigen profiel wordt niet bewaard.",
            "ride_buddies.json"),
    GOAL_EVENT("Doelevenement",
            "Naam, datum, afstand en hoogtemeters van je doelevenement.",
            "goal_event.json"),
    EVENT_CALENDAR("Evenementenkalender",
            "De agenda-links (iCal) die je toevoegde, de daaruit opgehaalde evenementen en je "
                    + "zelf ingevoerde evenementen, met de zoekstraal.",
            "event_calendar.json"),
    FAVORITE_START_POINTS("Favoriete startpunten",
            "Door jou opgeslagen startpunten (naam en coördinaten) voor de planning.",
            "favorite_start_points.json"),
    CLIMATE("Klimaatgegevens bij klimmen",
            "Weerhistorie (temperatuur, wind, regen per maand en dagdeel) rond je klimmen, "
                    + "voor het beste moment om te rijden. Wordt opnieuw opgehaald als je het "
                    + "wist.",
            "climate/"),
    OFFLINE_PACKAGES("Offline-pakketten",
            "Vooraf gedownload weer en water-, eet-, toilet- en fietspunten langs routes, voor "
                    + "gebieden zonder bereik. Openbare gegevens; opnieuw op te halen.",
            "offline/"),
    ROUTE_POIS("Bezienswaardigheden bij routes",
            "Uitzichtpunten, monumenten en andere bezienswaardigheden langs je routes, uit "
                    + "OpenStreetMap. Wordt opnieuw opgehaald als je het wist.",
            "route_pois/"),
    LOCATION("Laatst bekende locatie",
            "Gebruikt voor de radius-modus om klimmen in de buurt te kiezen."),
    RIDER_PROFILE("Rijdersprofiel",
            "FTP, gewicht van jou en je fiets, rit-intensiteit en maximale hartslag, en wanneer "
                    + "je een FTP-test exporteerde en welke testrit je al verwerkte."),
    STRAVA("Strava-koppeling",
            "Versleuteld toegangstoken en de voortgang van de activiteiten-sync."),
    INTERVALS_ICU("intervals.icu-koppeling",
            "Versleutelde persoonlijke API-sleutel en atleet-id om workouts naar je "
                    + "intervals.icu-kalender te sturen."),
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
