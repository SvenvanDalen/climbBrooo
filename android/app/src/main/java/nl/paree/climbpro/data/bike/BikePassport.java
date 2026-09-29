package nl.paree.climbpro.data.bike;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Bike theft passport (issue #190): everything needed for a police report or insurance claim
 * in one place. Free-text fields on purpose (dates and prices are copied into forms as-is).
 * Photos are filenames under {@code getFilesDir()/}{@link BikePassportPhotoStore#SUBDIR}.
 * Separate from the cost overview's {@link Bike} — a passport needs no km or costs. Phone-only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BikePassport {
    public String id;
    /** The rider's own name for the bike, e.g. "Racefiets". Required. */
    public String name;
    public String brand;
    public String model;
    public String color;
    public String frameNumber;
    /** Free text, e.g. "12-03-2024". */
    public String purchaseDate;
    /** Free text, e.g. "€ 2.499". */
    public String purchasePrice;
    public String shop;
    /** Distinguishing features: stickers, accessories, damage. */
    public String features;
    /** Photos of the bike (and of the frame number). */
    public List<String> photoFileNames = new ArrayList<>();
    /** Photo or scan of the purchase receipt; null when none. */
    public String receiptFileName;
    public long lastModifiedMs;

    /** Every photo file this passport references (bike photos + receipt). */
    public List<String> allFileNames() {
        List<String> out = new ArrayList<>();
        if (photoFileNames != null) out.addAll(photoFileNames);
        if (receiptFileName != null) out.add(receiptFileName);
        return out;
    }
}
