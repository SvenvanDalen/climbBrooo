package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.SummitGroupPhotos.Moment;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SummitGroupPhotosTest {

    private static StoredClimbAttempt attempt(long activityId, String climbId, String photo,
                                              String companions, int startOffsetSec,
                                              int passIndex) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.activityId = activityId;
        a.climbId = climbId;
        a.photoFileName = photo;
        a.companions = companions;
        a.startOffsetSec = startOffsetSec;
        a.passIndex = passIndex;
        return a;
    }

    @Test
    public void normalizeCompanions_trimsSplitsAndDedupesCaseInsensitively() {
        assertEquals("anna, Bas, Cees van Dam",
                SummitGroupPhotos.normalizeCompanions(" anna ,Bas;; ANNA\nCees  van   Dam "));
    }

    @Test
    public void normalizeCompanions_blankOrNullIsNull() {
        assertNull(SummitGroupPhotos.normalizeCompanions(null));
        assertNull(SummitGroupPhotos.normalizeCompanions("  , ;\n "));
    }

    @Test
    public void normalizeCompanions_capsNameLengthAndCount() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 30; i++) raw.append("Rijder").append(i).append(',');
        List<String> names = SummitGroupPhotos.companionList(
                SummitGroupPhotos.normalizeCompanions(raw.toString()));
        assertEquals(SummitGroupPhotos.MAX_COMPANIONS, names.size());

        String longName = "Abcdefghij Abcdefghij Abcdefghij Abcdefghij Abcdefghij";
        String stored = SummitGroupPhotos.normalizeCompanions(longName);
        assertEquals(SummitGroupPhotos.MAX_NAME_LENGTH, stored.length());
    }

    @Test
    public void companionList_nullIsEmpty_andRenormalisesHandEditedJson() {
        assertTrue(SummitGroupPhotos.companionList(null).isEmpty());
        assertEquals(Arrays.asList("Anna", "Bas"),
                SummitGroupPhotos.companionList("Anna,,  Bas ,anna"));
    }

    @Test
    public void joinNames_dutchListStyle() {
        assertEquals("", SummitGroupPhotos.joinNames(Collections.<String>emptyList()));
        assertEquals("Anna", SummitGroupPhotos.joinNames(Collections.singletonList("Anna")));
        assertEquals("Anna en Bas", SummitGroupPhotos.joinNames(Arrays.asList("Anna", "Bas")));
        assertEquals("Anna, Bas en Cees",
                SummitGroupPhotos.joinNames(Arrays.asList("Anna", "Bas", "Cees")));
    }

    @Test
    public void companionsLabel_nullWhenNobody() {
        assertNull(SummitGroupPhotos.companionsLabel(null));
        assertNull(SummitGroupPhotos.companionsLabel(" "));
        assertEquals("Met Anna en Bas", SummitGroupPhotos.companionsLabel("Anna, Bas"));
    }

    @Test
    public void isGroupPhoto_needsBothPhotoAndCompanions() {
        assertTrue(SummitGroupPhotos.isGroupPhoto(attempt(1, "c", "p.jpg", "Anna", -1, 0)));
        assertFalse(SummitGroupPhotos.isGroupPhoto(attempt(1, "c", null, "Anna", -1, 0)));
        assertFalse(SummitGroupPhotos.isGroupPhoto(attempt(1, "c", "", "Anna", -1, 0)));
        assertFalse(SummitGroupPhotos.isGroupPhoto(attempt(1, "c", "p.jpg", null, -1, 0)));
        assertFalse(SummitGroupPhotos.isGroupPhoto(attempt(1, "c", "p.jpg", " , ", -1, 0)));
        assertFalse(SummitGroupPhotos.isGroupPhoto(null));
    }

    @Test
    public void byActivity_groupsPerRide_ordersByOffsetUnknownLast_andSkipsNonGroup() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt(10, "late", "b.jpg", "Anna", 3600, 0),
                attempt(10, "unknown", "c.jpg", "Anna", -1, 0),
                attempt(10, "early", "a.jpg", "Anna, Bas", 600, 0),
                attempt(10, "solo", "d.jpg", null, 100, 0),          // photo, nobody along
                attempt(20, "early", null, "Cees", 100, 0),           // companions, no photo
                attempt(30, "early", "e.jpg", "Cees", 100, 1));
        Map<String, String> names = new HashMap<>();
        names.put("early", "Cauberg");
        names.put("late", "Keutenberg");

        Map<Long, List<Moment>> byRide = SummitGroupPhotos.byActivity(attempts, names);

        assertEquals(2, byRide.size());
        assertFalse(byRide.containsKey(20L));
        List<Moment> ride10 = byRide.get(10L);
        assertEquals(3, ride10.size());
        assertEquals("a.jpg", ride10.get(0).photoFileName);
        assertEquals("b.jpg", ride10.get(1).photoFileName);
        assertEquals("c.jpg", ride10.get(2).photoFileName);
        assertEquals("Groepsfoto op Cauberg met Anna en Bas", ride10.get(0).caption());
        assertEquals("Groepsfoto op de top met Anna", ride10.get(2).caption());
        assertEquals(Collections.singletonList("Cees"), byRide.get(30L).get(0).companions);
    }

    @Test
    public void byActivity_nullNameMapFallsBackToDeTop() {
        Map<Long, List<Moment>> byRide = SummitGroupPhotos.byActivity(
                Collections.singletonList(attempt(1, "gone", "p.jpg", "Anna", 5, 0)), null);
        assertNull(byRide.get(1L).get(0).climbName);
        assertEquals("Groepsfoto op de top met Anna", byRide.get(1L).get(0).caption());
    }

    @Test
    public void rideCaption_firstMomentPlusCountOfOthers() {
        Map<String, String> names = new HashMap<>();
        names.put("c1", "Cauberg");
        List<Moment> ride = SummitGroupPhotos.byActivity(Arrays.asList(
                attempt(1, "c1", "a.jpg", "Anna", 10, 0),
                attempt(1, "c2", "b.jpg", "Anna", 20, 0),
                attempt(1, "c3", "c.jpg", "Anna", 30, 0)), names).get(1L);

        assertEquals("Groepsfoto op Cauberg met Anna (+2 meer)",
                SummitGroupPhotos.rideCaption(ride));
        assertEquals("Groepsfoto op Cauberg met Anna",
                SummitGroupPhotos.rideCaption(ride.subList(0, 1)));
        assertNull(SummitGroupPhotos.rideCaption(null));
        assertNull(SummitGroupPhotos.rideCaption(Collections.<Moment>emptyList()));
    }
}
