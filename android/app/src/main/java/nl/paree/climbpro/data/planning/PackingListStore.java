package nl.paree.climbpro.data.planning;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * JSON-file persistence for {@link PackingList}s (issue #189). Takes the target file directly
 * so it is unit-testable without Android. A missing file loads the default lists
 * (Training, Toerrit, Bikepacking); once written, an emptied set is not re-seeded. A corrupt
 * file reads as the defaults and is moved aside to {@code .corrupt} on the next write.
 * Writes are atomic (temp file + rename).
 *
 * <p>Not thread-safe: callers serialise access through a single background executor.
 */
public final class PackingListStore {

    public static final String FILE_NAME = "packing_lists.json";
    static final int MAX_TEXT = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;

    public PackingListStore(File file) {
        this.file = file;
    }

    /** Lists in the rider's order. */
    public List<PackingList> loadAll() {
        List<PackingList> all = read();
        return all == null ? defaults() : all;
    }

    public PackingList addList(String name) throws IOException {
        List<PackingList> all = loadForWrite();
        PackingList l = new PackingList();
        l.id = newId();
        l.name = requireText(name);
        all.add(l);
        write(all);
        return l;
    }

    public boolean renameList(String listId, String name) throws IOException {
        String clean = requireText(name);
        return mutate(listId, l -> {
            l.name = clean;
            return true;
        });
    }

    public boolean deleteList(String listId) throws IOException {
        List<PackingList> all = loadForWrite();
        boolean removed = all.removeIf(l -> l.id.equals(listId));
        if (removed) write(all);
        return removed;
    }

    public boolean addItem(String listId, String text) throws IOException {
        String clean = requireText(text);
        return mutate(listId, l -> l.items.add(new PackingList.Item(newId(), clean)));
    }

    public boolean removeItem(String listId, String itemId) throws IOException {
        return mutate(listId, l -> l.items.removeIf(i -> i.id.equals(itemId)));
    }

    public boolean setChecked(String listId, String itemId, boolean checked) throws IOException {
        return mutate(listId, l -> {
            for (PackingList.Item i : l.items) {
                if (i.id.equals(itemId)) {
                    i.checked = checked;
                    return true;
                }
            }
            return false;
        });
    }

    /** Unchecks every item — "klaar voor de volgende rit". */
    public boolean resetChecks(String listId) throws IOException {
        return mutate(listId, l -> {
            for (PackingList.Item i : l.items) i.checked = false;
            return true;
        });
    }

    /** The lists a new install starts with. */
    public static List<PackingList> defaults() {
        List<PackingList> out = new ArrayList<>();
        out.add(seed("Training", "Helm", "Bidon", "Binnenband", "Bandenlichters", "Pomp of CO₂",
                "Multitool", "Telefoon", "Horloge/fietscomputer opgeladen"));
        out.add(seed("Toerrit", "Helm", "2 bidons", "Binnenband", "Bandenlichters", "Pomp of CO₂",
                "Multitool", "Telefoon", "Pinpas en geld", "Repen en gels", "Regenjack",
                "Zonnebrand", "Verlichting", "Route geladen op horloge"));
        out.add(seed("Bikepacking", "Helm", "Bidons", "2 binnenbanden", "Plakset", "Pomp",
                "Multitool met kettingpons", "Kettingsmeer", "Verlichting + powerbank",
                "Laders en kabels", "Regenkleding", "Warme laag", "Slaapzak", "Matje",
                "Tent of bivakzak", "Toilettas", "EHBO-set", "Eten voor onderweg",
                "Identiteitsbewijs", "Pinpas en geld", "Slot"));
        return out;
    }

    private static PackingList seed(String name, String... items) {
        PackingList l = new PackingList();
        l.id = "default-" + name.toLowerCase(java.util.Locale.ROOT);
        l.name = name;
        int n = 0;
        for (String text : items) {
            l.items.add(new PackingList.Item(l.id + "-" + (n++), text));
        }
        return l;
    }

    private interface Mutation {
        boolean apply(PackingList list);
    }

    private boolean mutate(String listId, Mutation m) throws IOException {
        List<PackingList> all = loadForWrite();
        for (PackingList l : all) {
            if (l.id.equals(listId)) {
                if (!m.apply(l)) return false;
                write(all);
                return true;
            }
        }
        return false;
    }

    private static String requireText(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Text is required");
        return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Parsed + filtered content; defaults for a missing file, null when corrupt. */
    private List<PackingList> read() {
        if (!file.exists()) return defaults();
        PackingList[] arr;
        try {
            arr = MAPPER.readValue(file, PackingList[].class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        List<PackingList> out = new ArrayList<>();
        if (arr == null) return out;
        for (PackingList l : Arrays.asList(arr)) {
            if (l == null || l.id == null || l.name == null || l.name.trim().isEmpty()) continue;
            List<PackingList.Item> items = new ArrayList<>();
            if (l.items != null) {
                for (PackingList.Item i : l.items) {
                    if (i != null && i.id != null && i.text != null && !i.text.trim().isEmpty()) {
                        items.add(i);
                    }
                }
            }
            l.items = items;
            out.add(l);
        }
        return out;
    }

    private List<PackingList> loadForWrite() throws IOException {
        List<PackingList> all = read();
        if (all != null) return all;
        File aside = new File(file.getParentFile(), file.getName() + ".corrupt");
        Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return defaults();
    }

    private void write(List<PackingList> all) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(MAPPER.writeValueAsBytes(all));
            out.getFD().sync();
        }
        try {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }
}
