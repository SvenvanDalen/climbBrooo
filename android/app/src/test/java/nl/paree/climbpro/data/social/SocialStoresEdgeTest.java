package nl.paree.climbpro.data.social;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import nl.paree.climbpro.domain.social.FriendShareCode;
import nl.paree.climbpro.domain.social.RideBuddyCode;
import nl.paree.climbpro.domain.social.RideBuddyProfile;

import java.util.Collections;

/** Failed writes of the friend feed and ride-buddy stores. */
@RunWith(RobolectricTestRunner.class)
public class SocialStoresEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        new FriendFeedRepository(app).deleteAll();
        new RideBuddyRepository(app).deleteAll();
    }

    private File file(String name) {
        return new File(app.getFilesDir(), name);
    }

    private void writeRaw(String name, String content) throws IOException {
        Files.write(file(name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** Turns {@code name} into a non-empty directory so replacing it must fail. */
    private void block(String name) throws IOException {
        file(name).mkdirs();
        Files.write(new File(file(name), "child").toPath(), new byte[]{1});
    }

    private interface Write { void run() throws IOException; }

    private void assertWriteFails(String name, Write write) throws IOException {
        block(name);
        try {
            write.run();
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse("temp file cleaned up", file(name + ".tmp").exists());
    }

    @Test
    public void friendFeed_failedWrite_throwsAndCleansTmp() throws Exception {
        FriendShareCode.Payload p = FriendShareCode.decode(FriendShareCode.encode("a", "Anna", 1,
                Collections.singletonList(new FriendFeedEntry(
                        FriendFeedEntry.KIND_RIDE, 100, "Rit", 1_000, 10, 60))));
        assertWriteFails(FriendFeedRepository.FILE,
                () -> new FriendFeedRepository(app).importPayload(p));
    }

    @Test
    public void rideBuddies_failedWrite_throwsAndCleansTmp() throws Exception {
        RideBuddyProfile p = new RideBuddyProfile();
        p.riderId = "a";
        p.name = "Anna";
        p.flatSpeedDkmh = 270;
        RideBuddyProfile decoded = RideBuddyCode.decode(RideBuddyCode.encode(p));
        assertWriteFails(RideBuddyRepository.FILE,
                () -> new RideBuddyRepository(app).upsert(decoded));
    }
}
