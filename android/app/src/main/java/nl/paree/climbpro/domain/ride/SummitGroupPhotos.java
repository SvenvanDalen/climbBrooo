package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Groepsfoto-moment op de top" (issue #243). The app has no notion of a group ride, so the
 * rider names who rode along on an attempt ({@link StoredClimbAttempt#companions}). An
 * attempt with both a photo and at least one companion is a group summit photo, which the
 * ride archive shows on that ride (joined on the shared Strava {@code activityId}). Pure and
 * phone-only.
 */
public final class SummitGroupPhotos {

    public static final int MAX_COMPANIONS = 20;
    public static final int MAX_NAME_LENGTH = 40;

    /** One group summit photo within a ride. */
    public static final class Moment {
        public final long activityId;
        public final String climbId;
        /** Display name of the climb; null when the climb is no longer known. */
        public final String climbName;
        public final String photoFileName;
        public final List<String> companions;
        final int startOffsetSec;
        final int passIndex;

        Moment(StoredClimbAttempt a, String climbName) {
            this.activityId = a.activityId;
            this.climbId = a.climbId;
            this.climbName = climbName;
            this.photoFileName = a.photoFileName;
            this.companions = Collections.unmodifiableList(companionList(a.companions));
            this.startOffsetSec = a.startOffsetSec;
            this.passIndex = a.passIndex;
        }

        /** E.g. "Groepsfoto op Cauberg met Anna en Bas". */
        public String caption() {
            String where = climbName != null && !climbName.isEmpty() ? climbName : "de top";
            return "Groepsfoto op " + where + " met " + joinNames(companions);
        }
    }

    private SummitGroupPhotos() {}

    /**
     * Cleans free-text input into the stored form. Splits on comma, semicolon or line break,
     * trims and collapses whitespace, caps each name at {@link #MAX_NAME_LENGTH} characters,
     * drops case-insensitive duplicates (keeping the first spelling) and keeps at most
     * {@link #MAX_COMPANIONS} names. Returns null when no name remains.
     */
    public static String normalizeCompanions(String raw) {
        if (raw == null) return null;
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : raw.split("[,;\\r\\n]")) {
            String name = part.trim().replaceAll("\\s+", " ");
            if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH).trim();
            if (name.isEmpty()) continue;
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
            names.add(name);
            if (names.size() == MAX_COMPANIONS) break;
        }
        if (names.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }

    /** The stored names as a list (renormalised, so hand-edited JSON is tolerated); never null. */
    public static List<String> companionList(String stored) {
        String normalized = normalizeCompanions(stored);
        List<String> out = new ArrayList<>();
        if (normalized == null) return out;
        for (String n : normalized.split(", ")) out.add(n);
        return out;
    }

    /** Dutch list style: "Anna", "Anna en Bas", "Anna, Bas en Cees". */
    public static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() - 1; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        return sb.append(" en ").append(names.get(names.size() - 1)).toString();
    }

    /** "Met Anna en Bas" for the climb history row; null when nobody is named. */
    public static String companionsLabel(String stored) {
        List<String> names = companionList(stored);
        return names.isEmpty() ? null : "Met " + joinNames(names);
    }

    public static boolean isGroupPhoto(StoredClimbAttempt a) {
        return a != null && a.photoFileName != null && !a.photoFileName.isEmpty()
                && !companionList(a.companions).isEmpty();
    }

    /**
     * Group summit photos per Strava activity id. Within a ride they're ordered by
     * {@code startOffsetSec} (attempts from before that field existed, -1, go last), then
     * {@code passIndex}, then {@code climbId}. Rides without a group photo are absent.
     *
     * @param namesByClimbId display name per {@code ClimbIdentity} key; may be null
     */
    public static Map<Long, List<Moment>> byActivity(List<StoredClimbAttempt> attempts,
                                                     Map<String, String> namesByClimbId) {
        Map<Long, List<Moment>> out = new HashMap<>();
        if (attempts == null) return out;
        for (StoredClimbAttempt a : attempts) {
            if (!isGroupPhoto(a)) continue;
            String name = namesByClimbId != null ? namesByClimbId.get(a.climbId) : null;
            List<Moment> list = out.get(a.activityId);
            if (list == null) {
                list = new ArrayList<>();
                out.put(a.activityId, list);
            }
            list.add(new Moment(a, name));
        }
        Comparator<Moment> order = Comparator
                .comparingInt((Moment m) -> m.startOffsetSec < 0 ? Integer.MAX_VALUE : m.startOffsetSec)
                .thenComparingInt(m -> m.passIndex)
                .thenComparing(m -> m.climbId != null ? m.climbId : "");
        for (List<Moment> list : out.values()) Collections.sort(list, order);
        return out;
    }

    /** Caption for a ride row: the first moment's caption plus "(+N meer)"; null when none. */
    public static String rideCaption(List<Moment> moments) {
        if (moments == null || moments.isEmpty()) return null;
        String first = moments.get(0).caption();
        return moments.size() > 1 ? first + " (+" + (moments.size() - 1) + " meer)" : first;
    }
}
