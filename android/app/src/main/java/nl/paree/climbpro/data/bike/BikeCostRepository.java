package nl.paree.climbpro.data.bike;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.bike.BikeGarage;
import nl.paree.climbpro.domain.bike.EuroAmount;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the bike garage (issue #187) and its cost overview (issue #233).
 * Layout: getFilesDir()/bike_costs.json — one {@link BikeCostLog}; the name predates the
 * garage. Writes are atomic (temp file + rename), mirroring {@code MaintenanceRepository}. A
 * missing or unreadable file loads as no bikes; nothing is written until the user changes
 * something, except the one-time garage migration ({@link BikeGarage#migrate}) when it had
 * something to carry over.
 *
 * <p>The active bike's weight and gearing are mirrored into the default SharedPreferences the
 * rider profile ({@link RiderProfileRepository#PREF_BIKE_WEIGHT_KG}) and the gear calculator
 * read, so every estimate uses the active bike without those readers knowing the garage.
 */
public final class BikeCostRepository {

    private static final String TAG = "BikeCostRepository";
    static final String FILE = "bike_costs.json";
    /** Upper bound for manually entered km; keeps km * 1000 far from int/long trouble. */
    public static final int MAX_EXTRA_KM = 1_000_000;
    /** Sanity bounds for garage fields; outside them the value counts as unknown (0). */
    public static final double MAX_WEIGHT_KG = 60;
    public static final int MAX_TYRE_WIDTH_MM = 150;
    /** Gear calculator inputs (issue #188) in default SharedPreferences. */
    public static final String PREF_GEAR_CHAINRINGS = "gear_chainrings";
    public static final String PREF_GEAR_CASSETTE = "gear_cassette";

    /** Static: several instances (screen, privacy dashboard) can point at the same file. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final Context app;
    private final File file;
    private final ObjectMapper mapper;

    public BikeCostRepository(Context context) {
        this.app    = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public BikeCostLog load() {
        BikeCostLog log;
        if (!file.exists()) {
            log = new BikeCostLog();
        } else {
            try (FileInputStream in = new FileInputStream(file)) {
                log = sanitize(mapper.readValue(in, BikeCostLog.class));
            } catch (IOException e) {
                // Never migrate over an unreadable file: the write would replace it.
                Log.e(TAG, "Failed to load bike costs", e);
                return new BikeCostLog();
            }
        }
        if (log.version < BikeCostLog.GARAGE_VERSION) migrate(log);
        return log;
    }

    /** One-time garage migration (issue #187); persisted only when it carried data over. */
    private void migrate(BikeCostLog log) {
        WRITE_LOCK.lock();
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
            List<String> passportNames = new ArrayList<>();
            for (BikePassport p : new BikePassportStore(
                    new File(app.getFilesDir(), BikePassportStore.FILE_NAME)).loadAll()) {
                passportNames.add(p.name);
            }
            boolean changed = BikeGarage.migrate(log,
                    prefs.getFloat(RiderProfileRepository.PREF_BIKE_WEIGHT_KG, 0f),
                    passportNames, prefs.getString(PREF_GEAR_CHAINRINGS, null),
                    prefs.getString(PREF_GEAR_CASSETTE, null));
            if (changed) write(log);
        } catch (IOException | RuntimeException e) {
            // The in-memory result is still usable; the migration simply reruns next load.
            Log.e(TAG, "Bike garage migration not saved", e);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Creates ({@code id == null} or unknown) or edits the garage fields of a bike and returns
     * its id; costs and cost-overview settings stay. {@code active} makes it the active bike
     * (unticking never leaves the garage without one); {@code indoor} makes it the bike indoor
     * rides go to, unticking clears that when it was this bike. A Strava gear id can belong to
     * one bike only: another bike holding it loses it.
     */
    public String saveGarageBike(String id, String name, String type, double weightKg,
                                 int tyreWidthMm, String chainrings, String cassette,
                                 String stravaGearId, boolean active, boolean indoor)
            throws IOException {
        WRITE_LOCK.lock();
        try {
            BikeCostLog log = load();
            Bike b = id != null ? findBike(log, id) : null;
            if (b == null) {
                b = new Bike();
                b.id = id != null ? id : UUID.randomUUID().toString();
                log.bikes.add(b);
            }
            b.name         = cleanName(name);
            b.type         = BikeGarage.cleanType(type);
            b.weightKg     = cleanWeight(weightKg);
            b.tyreWidthMm  = cleanTyreWidth(tyreWidthMm);
            b.chainrings   = cleanText(chainrings);
            b.cassette     = cleanText(cassette);
            b.stravaGearId = cleanText(stravaGearId);
            if (b.stravaGearId != null) {
                for (Bike other : log.bikes) {
                    if (other != b && b.stravaGearId.equals(other.stravaGearId)) {
                        other.stravaGearId = null;
                    }
                }
            }
            if (active || BikeGarage.find(log, log.activeBikeId) == null) log.activeBikeId = b.id;
            if (indoor) {
                log.indoorBikeId = b.id;
            } else if (b.id.equals(log.indoorBikeId)) {
                log.indoorBikeId = null;
            }
            write(log);
            return b.id;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Makes {@code id} the active bike; a no-op for an unknown id. */
    public void setActiveBike(String id) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            BikeCostLog log = load();
            if (findBike(log, id) == null) return;
            log.activeBikeId = id;
            write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * The rider profile's bike weight was edited in the settings: store it on the active bike
     * so the garage stays the source of truth. A no-op with an empty garage or when unchanged.
     */
    public void setActiveBikeWeight(double weightKg) throws IOException {
        WRITE_LOCK.lock();
        try {
            BikeCostLog log = load();
            Bike active = BikeGarage.activeBike(log);
            double clean = cleanWeight(weightKg);
            if (active == null || clean <= 0 || Math.abs(active.weightKg - clean) < 1e-6) return;
            active.weightKg = clean;
            write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Creates ({@code id == null} or unknown) or edits a bike and returns its id. Costs stay.
     * {@code retired} marks the bike out of use from {@code nowEpochSec} (an existing retired
     * date is kept); {@code !retired} puts it back in use.
     */
    public String upsertBike(String id, String name, long sinceEpochSec,
                             boolean countArchiveRides, boolean includeVirtualRides,
                             int extraKm, boolean retired, long nowEpochSec) throws IOException {
        WRITE_LOCK.lock();
        try {
            BikeCostLog log = load();
            Bike b = id != null ? findBike(log, id) : null;
            if (b == null) {
                b = new Bike();
                b.id = id != null ? id : UUID.randomUUID().toString();
                log.bikes.add(b);
            }
            b.name                = cleanName(name);
            b.sinceEpochSec       = Math.max(0, sinceEpochSec);
            b.countArchiveRides   = countArchiveRides;
            b.includeVirtualRides = includeVirtualRides;
            b.extraKm             = clampKm(extraKm);
            if (!retired) {
                b.retiredEpochSec = 0;
            } else if (b.retiredEpochSec <= 0) {
                b.retiredEpochSec = Math.max(0, nowEpochSec);
            }
            write(log);
            return b.id;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Adds a cost to a bike and returns its id; {@code null} (nothing written) for an unknown
     * bike or an amount outside {@code [1, EuroAmount.MAX_CENTS]}.
     */
    public String addCost(String bikeId, String kind, String description, long amountCents,
                          long epochSec) throws IOException {
        if (bikeId == null || amountCents <= 0 || amountCents > EuroAmount.MAX_CENTS) return null;
        WRITE_LOCK.lock();
        try {
            BikeCostLog log = load();
            Bike b = findBike(log, bikeId);
            if (b == null) return null;
            BikeCostEntry e = new BikeCostEntry();
            e.id          = UUID.randomUUID().toString();
            e.kind        = BikeCostEntry.cleanKind(kind);
            e.description = cleanDescription(description, e.kind);
            e.amountCents = amountCents;
            e.epochSec    = Math.max(0, epochSec);
            b.costs.add(e);
            write(log);
            return e.id;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Removes one cost; a no-op when the bike or cost is not present. */
    public void deleteCost(String bikeId, String costId) throws IOException {
        if (bikeId == null || costId == null) return;
        WRITE_LOCK.lock();
        try {
            BikeCostLog log = load();
            Bike b = findBike(log, bikeId);
            if (b != null && b.costs.removeIf(c -> costId.equals(c.id))) write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Removes a bike with all its costs; a no-op when it is not present. */
    public void deleteBike(String id) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            BikeCostLog log = load();
            if (log.bikes.removeIf(b -> id.equals(b.id))) write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static Bike findBike(BikeCostLog log, String id) {
        for (Bike b : log.bikes) {
            if (id.equals(b.id)) return b;
        }
        return null;
    }

    static String cleanName(String name) {
        return name != null && !name.trim().isEmpty() ? name.trim() : "Fiets";
    }

    static String cleanDescription(String description, String kind) {
        return description != null && !description.trim().isEmpty()
                ? description.trim() : BikeCostEntry.label(kind);
    }

    private static int clampKm(int km) {
        return Math.max(0, Math.min(km, MAX_EXTRA_KM));
    }

    /** Repairs hand-edited or partially valid data instead of failing the whole screen. */
    private static BikeCostLog sanitize(BikeCostLog log) {
        if (log == null) return new BikeCostLog();
        if (log.bikes == null) log.bikes = new ArrayList<>();
        log.bikes.removeIf(b -> b == null);
        int bikeIndex = 0;
        for (Bike b : log.bikes) {
            // Deterministic fallback ids so an edit on a later load still finds the item.
            if (b.id == null || b.id.isEmpty()) b.id = "bike-" + bikeIndex;
            b.name = cleanName(b.name);
            if (b.sinceEpochSec < 0) b.sinceEpochSec = 0;
            if (b.retiredEpochSec < 0) b.retiredEpochSec = 0;
            b.extraKm = clampKm(b.extraKm);
            if (b.costs == null) b.costs = new ArrayList<>();
            b.costs.removeIf(c -> c == null || c.amountCents <= 0
                    || c.amountCents > EuroAmount.MAX_CENTS);
            int costIndex = 0;
            for (BikeCostEntry c : b.costs) {
                if (c.id == null || c.id.isEmpty()) c.id = b.id + "-cost-" + costIndex;
                c.kind = BikeCostEntry.cleanKind(c.kind);
                c.description = cleanDescription(c.description, c.kind);
                if (c.epochSec < 0) c.epochSec = 0;
                costIndex++;
            }
            // A missing type on a pre-garage file is guessed by the migration instead.
            if (log.version >= BikeCostLog.GARAGE_VERSION) b.type = BikeGarage.cleanType(b.type);
            b.weightKg     = cleanWeight(b.weightKg);
            b.tyreWidthMm  = cleanTyreWidth(b.tyreWidthMm);
            b.chainrings   = cleanText(b.chainrings);
            b.cassette     = cleanText(b.cassette);
            b.stravaGearId = cleanText(b.stravaGearId);
            bikeIndex++;
        }
        if (BikeGarage.find(log, log.activeBikeId) == null) log.activeBikeId = null;
        if (BikeGarage.find(log, log.indoorBikeId) == null) log.indoorBikeId = null;
        return log;
    }

    static double cleanWeight(double kg) {
        return kg > 0 && kg <= MAX_WEIGHT_KG && !Double.isNaN(kg) ? kg : 0;
    }

    static int cleanTyreWidth(int mm) {
        return mm > 0 && mm <= MAX_TYRE_WIDTH_MM ? mm : 0;
    }

    static String cleanText(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void write(BikeCostLog log) throws IOException {
        // Drop links to bikes that are gone (e.g. just deleted) before they are persisted.
        if (BikeGarage.find(log, log.activeBikeId) == null) log.activeBikeId = null;
        if (BikeGarage.find(log, log.indoorBikeId) == null) log.indoorBikeId = null;
        writeAtomic(file, mapper.writeValueAsBytes(log));
        mirrorActiveBike(log);
    }

    /**
     * Copies the active bike's known weight and gearing into the preferences the rider profile
     * and the gear calculator read. Unknown values leave the preference alone.
     */
    private void mirrorActiveBike(BikeCostLog log) {
        Bike active = BikeGarage.activeBike(log);
        if (active == null) return;
        SharedPreferences.Editor ed = PreferenceManager.getDefaultSharedPreferences(app).edit();
        if (active.weightKg > 0) {
            ed.putFloat(RiderProfileRepository.PREF_BIKE_WEIGHT_KG, (float) active.weightKg);
        }
        if (active.chainrings != null) ed.putString(PREF_GEAR_CHAINRINGS, active.chainrings);
        if (active.cassette != null) ed.putString(PREF_GEAR_CASSETTE, active.cassette);
        ed.apply();
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        try {
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }
}
