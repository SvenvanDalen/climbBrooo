package nl.paree.climbpro.data.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.social.RideBuddyCode;
import nl.paree.climbpro.domain.social.RideBuddyProfile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RideBuddyRepositoryTest {

    private Application app;
    private RideBuddyRepository repo;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        repo = new RideBuddyRepository(app);
        repo.deleteAll();
    }

    private static RideBuddyProfile profile(String id, String name, int dkmh) throws Exception {
        RideBuddyProfile p = new RideBuddyProfile();
        p.riderId = id;
        p.name = name;
        p.flatSpeedDkmh = dkmh;
        double[] c = RideBuddyProfile.snapToCell(52.09, 5.12);
        p.areaLat = c[0];
        p.areaLon = c[1];
        return RideBuddyCode.decode(RideBuddyCode.encode(p));
    }

    @Test
    public void upsert_addsThenReplacesSameRider() throws Exception {
        assertTrue(repo.upsert(profile("a", "Anna", 270)));
        assertTrue(repo.upsert(profile("b", "Bram", 300)));
        assertFalse(repo.upsert(profile("a", "Anna", 290)));
        List<RideBuddyProfile> all = repo.loadAll();
        assertEquals(2, all.size());
        assertEquals("a", all.get(0).riderId);
        assertEquals(290, all.get(0).flatSpeedDkmh);
        assertTrue(all.get(0).hasArea());
    }

    @Test
    public void remove_andDeleteAll() throws Exception {
        repo.upsert(profile("a", "Anna", 270));
        repo.upsert(profile("b", "Bram", 300));
        repo.remove("a");
        assertEquals(1, repo.loadAll().size());
        assertTrue(repo.deleteAll());
        assertTrue(repo.loadAll().isEmpty());
        assertFalse(new File(app.getFilesDir(), RideBuddyRepository.FILE).exists());
    }

    @Test
    public void corruptFile_loadsEmpty() throws Exception {
        try (FileOutputStream out = new FileOutputStream(
                new File(app.getFilesDir(), RideBuddyRepository.FILE))) {
            out.write("{nope".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(repo.loadAll().isEmpty());
    }

    @Test
    public void riderId_isStableAndSeparateFromFriendFeedId() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String id = RideBuddyRepository.riderId(prefs);
        assertEquals(id, RideBuddyRepository.riderId(prefs));
        assertFalse(id.equals(FriendShareIdentity.sharerId(prefs)));
    }
}
