package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.social.FriendFeedEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FriendFeedMergerTest {

    private static FriendFeedEntry ride(long t, String title) {
        return new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, t, title, 10_000, 100, 1_800);
    }

    private static FriendFeedEntry milestone(long t, String title) {
        return new FriendFeedEntry(FriendFeedEntry.KIND_MILESTONE, t, title, 0, 80, 0);
    }

    /** Round-trips through the real codec so friendId/friendName are stamped like in the app. */
    private static FriendShareCode.Payload payload(String id, String name, FriendFeedEntry... es)
            throws Exception {
        return FriendShareCode.decode(FriendShareCode.encode(id, name, 1, Arrays.asList(es)));
    }

    @Test public void firstImportAddsAllNewestFirst() throws Exception {
        FriendFeedMerger.Result r = FriendFeedMerger.merge(new ArrayList<>(),
                payload("a", "Anna", ride(100, "Oud"), ride(300, "Nieuw"), milestone(200, "Eerste beklimming: X")));
        assertEquals(3, r.added);
        assertEquals(300, r.entries.get(0).epochSec);
        assertEquals(200, r.entries.get(1).epochSec);
        assertEquals(100, r.entries.get(2).epochSec);
    }

    @Test public void reimportingTheSameCodeAddsNothing() throws Exception {
        FriendShareCode.Payload p = payload("a", "Anna", ride(100, "Rit"), milestone(100, "Eerste beklimming: X"));
        List<FriendFeedEntry> once = FriendFeedMerger.merge(new ArrayList<>(), p).entries;
        FriendFeedMerger.Result twice = FriendFeedMerger.merge(once, p);
        assertEquals(0, twice.added);
        assertEquals(2, twice.entries.size());
    }

    @Test public void renamedRideReplacesInsteadOfDuplicating() throws Exception {
        List<FriendFeedEntry> once = FriendFeedMerger.merge(new ArrayList<>(),
                payload("a", "Anna", ride(100, "Middagrit"))).entries;
        FriendFeedMerger.Result r = FriendFeedMerger.merge(once,
                payload("a", "Anna", ride(100, "Rondje Limburg")));
        assertEquals(0, r.added);
        assertEquals(1, r.entries.size());
        assertEquals("Rondje Limburg", r.entries.get(0).title);
    }

    @Test public void twoFirstAscentsInOneRideAreBothKept() throws Exception {
        FriendFeedMerger.Result r = FriendFeedMerger.merge(new ArrayList<>(), payload("a", "Anna",
                milestone(100, "Eerste beklimming: Cauberg"), milestone(100, "Eerste beklimming: Keutenberg")));
        assertEquals(2, r.added);
    }

    @Test public void friendRenameUpdatesOldEntriesAndLeavesOthersAlone() throws Exception {
        List<FriendFeedEntry> feed = FriendFeedMerger.merge(new ArrayList<>(),
                payload("a", "Anna", ride(100, "Oud"))).entries;
        feed = FriendFeedMerger.merge(feed, payload("b", "Bram", ride(100, "Zelfde seconde"))).entries;
        FriendFeedMerger.Result r = FriendFeedMerger.merge(feed,
                payload("a", "Anna V.", ride(200, "Nieuw")));
        assertEquals(3, r.entries.size());
        for (FriendFeedEntry e : r.entries) {
            assertEquals(e.friendId.equals("a") ? "Anna V." : "Bram", e.friendName);
        }
    }

    @Test public void perFriendCapKeepsNewestAndCountsOnlyKeptEntries() throws Exception {
        List<FriendFeedEntry> feed = new ArrayList<>();
        for (int batch = 0; batch < 5; batch++) {
            FriendFeedEntry[] es = new FriendFeedEntry[20];
            for (int i = 0; i < 20; i++) es[i] = ride(1_000 + batch * 20 + i, "R");
            feed = FriendFeedMerger.merge(feed, payload("a", "Anna", es)).entries;
        }
        assertEquals(FriendFeedMerger.MAX_ENTRIES_PER_FRIEND, feed.size());
        // 20 older rides than everything stored: all fall off the cap, none count as added.
        FriendFeedEntry[] old = new FriendFeedEntry[20];
        for (int i = 0; i < 20; i++) old[i] = ride(i + 1, "Oud");
        FriendFeedMerger.Result r = FriendFeedMerger.merge(feed, payload("a", "Anna", old));
        assertEquals(0, r.added);
        assertEquals(FriendFeedMerger.MAX_ENTRIES_PER_FRIEND, r.entries.size());
        assertEquals(1_099, r.entries.get(0).epochSec);
    }

    @Test public void removeFriendDropsOnlyTheirEntries() throws Exception {
        List<FriendFeedEntry> feed = FriendFeedMerger.merge(new ArrayList<>(),
                payload("a", "Anna", ride(100, "A"))).entries;
        feed = FriendFeedMerger.merge(feed, payload("b", "Bram", ride(200, "B"))).entries;
        List<FriendFeedEntry> left = FriendFeedMerger.removeFriend(feed, "a");
        assertEquals(1, left.size());
        assertEquals("b", left.get(0).friendId);
        assertEquals(0, FriendFeedMerger.removeFriend(Collections.<FriendFeedEntry>emptyList(), "a").size());
    }
}
