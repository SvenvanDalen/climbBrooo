package nl.paree.climbpro.domain.social;

import nl.paree.climbpro.data.social.FriendFeedEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges an imported share code into the stored friends' feed (issue #240): de-duplicates on
 * {@link FriendFeedEntry#dedupeKey()} (the fresh copy wins), carries a friend's new display
 * name over to their older entries, caps each friend at {@link #MAX_ENTRIES_PER_FRIEND}
 * newest entries and sorts the result newest first. Pure; never mutates its inputs.
 */
public final class FriendFeedMerger {

    public static final int MAX_ENTRIES_PER_FRIEND = 100;

    public static final class Result {
        public final List<FriendFeedEntry> entries;
        /** New entries that made it into {@link #entries} (not counting replacements). */
        public final int added;

        Result(List<FriendFeedEntry> entries, int added) {
            this.entries = entries;
            this.added = added;
        }
    }

    private FriendFeedMerger() {}

    public static Result merge(List<FriendFeedEntry> existing, FriendShareCode.Payload incoming) {
        Map<String, FriendFeedEntry> byKey = new LinkedHashMap<>();
        for (FriendFeedEntry e : existing) {
            if (e == null || e.friendId == null || e.kind == null) continue;
            FriendFeedEntry copy = copyOf(e);
            if (copy.friendId.equals(incoming.sharerId)) copy.friendName = incoming.sharerName;
            byKey.put(copy.dedupeKey(), copy);
        }
        Set<String> newKeys = new HashSet<>();
        for (FriendFeedEntry e : incoming.entries) {
            String key = e.dedupeKey();
            if (byKey.put(key, copyOf(e)) == null) newKeys.add(key);
        }
        List<FriendFeedEntry> all = new ArrayList<>(byKey.values());
        sortNewestFirst(all);
        Map<String, Integer> perFriend = new HashMap<>();
        List<FriendFeedEntry> kept = new ArrayList<>();
        int added = 0;
        for (FriendFeedEntry e : all) {
            int n = perFriend.containsKey(e.friendId) ? perFriend.get(e.friendId) + 1 : 1;
            perFriend.put(e.friendId, n);
            if (n > MAX_ENTRIES_PER_FRIEND) continue;
            kept.add(e);
            if (newKeys.contains(e.dedupeKey())) added++;
        }
        return new Result(kept, added);
    }

    public static List<FriendFeedEntry> removeFriend(List<FriendFeedEntry> entries, String friendId) {
        List<FriendFeedEntry> out = new ArrayList<>();
        for (FriendFeedEntry e : entries) {
            if (e != null && !friendId.equals(e.friendId)) out.add(e);
        }
        return out;
    }

    /** Newest first; ties by friend name, then title, so the order is stable across loads. */
    public static void sortNewestFirst(List<FriendFeedEntry> entries) {
        Collections.sort(entries, (a, b) -> {
            int c = Long.compare(b.epochSec, a.epochSec);
            if (c != 0) return c;
            c = String.valueOf(a.friendName).compareTo(String.valueOf(b.friendName));
            if (c != 0) return c;
            return String.valueOf(a.title).compareTo(String.valueOf(b.title));
        });
    }

    private static FriendFeedEntry copyOf(FriendFeedEntry e) {
        FriendFeedEntry c = new FriendFeedEntry(e.kind, e.epochSec, e.title,
                e.distanceM, e.gainM, e.movingSec);
        c.friendId = e.friendId;
        c.friendName = e.friendName;
        return c;
    }
}
