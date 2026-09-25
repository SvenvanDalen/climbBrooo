package nl.paree.climbpro.data.bike;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.bike.EuroAmount;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the bike cost overview (issue #233). Layout:
 * getFilesDir()/bike_costs.json — one {@link BikeCostLog}. Writes are atomic (temp file +
 * rename), mirroring {@code MaintenanceRepository}. A missing or unreadable file loads as no
 * bikes; nothing is written until the user changes something.
 */
public final class BikeCostRepository {

    private static final String TAG = "BikeCostRepository";
    static final String FILE = "bike_costs.json";
    /** Upper bound for manually entered km; keeps km * 1000 far from int/long trouble. */
    public static final int MAX_EXTRA_KM = 1_000_000;

    /** Static: several instances (screen, privacy dashboard) can point at the same file. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public BikeCostRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public BikeCostLog load() {
        if (!file.exists()) return new BikeCostLog();
        try (FileInputStream in = new FileInputStream(file)) {
            return sanitize(mapper.readValue(in, BikeCostLog.class));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load bike costs", e);
            return new BikeCostLog();
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
            bikeIndex++;
        }
        return log;
    }

    private void write(BikeCostLog log) throws IOException {
        writeAtomic(file, mapper.writeValueAsBytes(log));
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
