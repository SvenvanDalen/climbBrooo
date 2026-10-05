package nl.paree.climbpro.data.rider;

import android.content.Context;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.rider.WeightHistory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-file persistence of the weight log (issue #408), {@code weight_log.json} in
 * {@code getFilesDir()}. One entry per day: adding a second weight for a day replaces the
 * first. Takes the file directly so it is unit-testable; a corrupt file reads as empty.
 * Writes are atomic (temp file + rename). Not thread-safe: callers use one background thread.
 */
public final class WeightLogStore {

    public static final String FILE_NAME = "weight_log.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;

    public WeightLogStore(File file) {
        this.file = file;
    }

    public static WeightLogStore of(Context context) {
        return new WeightLogStore(new File(context.getApplicationContext().getFilesDir(),
                FILE_NAME));
    }

    /** All valid entries, newest first. */
    public List<WeightEntry> loadAll() {
        List<WeightEntry> out = new ArrayList<>();
        if (!file.exists()) return out;
        try {
            for (WeightEntry e : MAPPER.readValue(file, WeightEntry[].class)) {
                if (e != null && parse(e.date) != null && WeightHistory.isPlausible(e.kg)) {
                    out.add(e);
                }
            }
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
        out.sort((a, b) -> b.date.compareTo(a.date));
        return out;
    }

    /**
     * Stores {@code kg} for {@code date}, replacing that day's entry.
     *
     * @throws IllegalArgumentException for an implausible weight
     */
    public void put(LocalDate date, double kg, String source) throws IOException {
        if (date == null || !WeightHistory.isPlausible(kg)) {
            throw new IllegalArgumentException("Ongeldig gewicht");
        }
        Map<String, WeightEntry> byDate = byDate();
        WeightEntry e = new WeightEntry();
        e.date = date.toString();
        e.kg = Math.round(kg * 10) / 10.0;
        e.source = source;
        byDate.put(e.date, e);
        write(new ArrayList<>(byDate.values()));
    }

    /**
     * Adds measurements from Health Connect for days without a manual entry; a manual weight
     * is never overwritten. Returns the number of days added or changed.
     */
    public int mergeImported(Map<LocalDate, Double> imported) throws IOException {
        if (imported == null || imported.isEmpty()) return 0;
        Map<String, WeightEntry> byDate = byDate();
        int changed = 0;
        for (Map.Entry<LocalDate, Double> in : imported.entrySet()) {
            if (in.getKey() == null || in.getValue() == null
                    || !WeightHistory.isPlausible(in.getValue())) continue;
            String key = in.getKey().toString();
            WeightEntry existing = byDate.get(key);
            if (existing != null && !WeightEntry.SOURCE_HEALTH_CONNECT.equals(existing.source)) {
                continue;
            }
            double kg = Math.round(in.getValue() * 10) / 10.0;
            if (existing != null && existing.kg == kg) continue;
            WeightEntry e = new WeightEntry();
            e.date = key;
            e.kg = kg;
            e.source = WeightEntry.SOURCE_HEALTH_CONNECT;
            byDate.put(key, e);
            changed++;
        }
        if (changed > 0) write(new ArrayList<>(byDate.values()));
        return changed;
    }

    public boolean delete(String date) throws IOException {
        Map<String, WeightEntry> byDate = byDate();
        if (byDate.remove(date) == null) return false;
        write(new ArrayList<>(byDate.values()));
        return true;
    }

    /** The log as a {@link WeightHistory}, with the profile weight as fallback. */
    public WeightHistory history(double profileWeightKg, ZoneId zone) {
        List<WeightHistory.Point> points = new ArrayList<>();
        for (WeightEntry e : loadAll()) points.add(new WeightHistory.Point(parse(e.date), e.kg));
        return new WeightHistory(points, profileWeightKg, zone);
    }

    private Map<String, WeightEntry> byDate() {
        Map<String, WeightEntry> byDate = new LinkedHashMap<>();
        for (WeightEntry e : loadAll()) byDate.put(e.date, e);
        return byDate;
    }

    private static LocalDate parse(String date) {
        if (date == null) return null;
        try {
            return LocalDate.parse(date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void write(List<WeightEntry> entries) throws IOException {
        entries.sort((a, b) -> b.date.compareTo(a.date));
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(MAPPER.writeValueAsBytes(entries));
            out.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
