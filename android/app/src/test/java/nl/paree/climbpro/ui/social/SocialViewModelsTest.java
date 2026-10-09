package nl.paree.climbpro.ui.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.social.FriendFeedEntry;
import nl.paree.climbpro.data.social.FriendShareIdentity;
import nl.paree.climbpro.data.social.RideBuddyRepository;
import nl.paree.climbpro.domain.social.RideBuddyMatcher;
import nl.paree.climbpro.domain.social.RideBuddyProfile;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.List;

/** Friend feed and ride-buddy ViewModels: share, import (own / other / invalid), remove. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SocialViewModelsTest {

    private Application app;
    private SharedPreferences prefs;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
    }

    private static String codeOf(String shareText, String intro) {
        assertTrue(shareText.startsWith(intro));
        return shareText.substring(intro.length());
    }

    // --- FriendFeedViewModel ---

    @Test
    public void friendFeed_shareImportOwnThenAsOtherRider() {
        FriendFeedViewModel vm = new FriendFeedViewModel(app);
        vm.share("  Sven ");
        assertEquals("Sven", vm.shareName());
        String text = UiTestEnv.awaitValue(vm.shareText(), t -> true);
        assertNotNull(text);
        vm.consumeShareText();
        assertNull(vm.shareText().getValue());
        String code = codeOf(text, FriendFeedViewModel.SHARE_INTRO);

        vm.importCode(text);
        assertEquals("Dit is je eigen deelcode.", UiTestEnv.awaitValue(vm.message(), m -> true));
        vm.consumeMessage();

        // On another phone (other sharer id) the same code is a friend's feed.
        prefs.edit().remove(FriendShareIdentity.PREF_ID).commit();
        vm.importCode(code);
        String msg = UiTestEnv.awaitValue(vm.message(), m -> m.contains("nieuw(e) item(s) van Sven"));
        assertNotNull(msg);
        List<FriendFeedEntry> entries = UiTestEnv.awaitValue(vm.entries(), l -> !l.isEmpty());
        String friendId = entries.get(0).friendId;

        vm.importCode(code);
        assertNotNull(UiTestEnv.awaitValue(vm.message(), m -> m.equals("Geen nieuwe items van Sven")));

        vm.removeFriend(friendId);
        assertNotNull(UiTestEnv.awaitValue(vm.entries(), List::isEmpty));
        vm.load();
        assertTrue(UiTestEnv.awaitValue(vm.entries(), l -> true).isEmpty());
        vm.onCleared();
    }

    @Test
    public void friendFeed_invalidCode_reportsReason() {
        FriendFeedViewModel vm = new FriendFeedViewModel(app);
        vm.importCode("geen code");
        String m = UiTestEnv.awaitValue(vm.message(), x -> true);
        assertNotNull(m);
        assertFalse(m.isEmpty());
        vm.onCleared();
    }

    @Test
    public void friendFeed_nothingRecentToShare_explains() {
        new nl.paree.climbpro.data.ride.RideRepository(app).deleteAll();
        new nl.paree.climbpro.data.route.ClimbAttemptRepository(app).deleteAll();
        FriendFeedViewModel vm = new FriendFeedViewModel(app);
        vm.share("Sven");
        assertTrue(UiTestEnv.awaitValue(vm.message(), x -> true).startsWith("Nog geen ritten"));
        assertNull(vm.shareText().getValue());
        vm.onCleared();
    }

    // --- RideBuddyViewModel ---

    @Test
    public void rideBuddy_prepareConfirmImportAndRemove() {
        RideBuddyViewModel vm = new RideBuddyViewModel(app);
        vm.prepareShare("Sven", RideBuddyProfile.ALL_FIELDS); // before load: nothing to share
        vm.load();
        assertNotNull(UiTestEnv.awaitValue(vm.own(), p -> true));
        assertTrue(UiTestEnv.awaitValue(vm.matches(), l -> true).isEmpty());

        vm.prepareShare("Sven", RideBuddyProfile.FIELD_PACE | RideBuddyProfile.FIELD_CLIMB);
        RideBuddyViewModel.Preview preview = UiTestEnv.awaitValue(vm.preview(), p -> true);
        assertFalse(preview.lines.isEmpty());
        assertEquals("Sven", vm.shareName());
        vm.consumePreview();
        vm.confirmShare(preview.code);
        assertEquals(RideBuddyViewModel.SHARE_INTRO + preview.code, vm.shareText().getValue());
        vm.consumeShareText();

        vm.importCode(preview.code);
        assertEquals("Dit is je eigen profielcode.", UiTestEnv.awaitValue(vm.message(), m -> true));
        vm.consumeMessage();

        prefs.edit().remove(RideBuddyRepository.PREF_ID).commit();
        vm.importCode(preview.code);
        assertNotNull(UiTestEnv.awaitValue(vm.message(), m -> m.equals("Sven toegevoegd")));
        List<RideBuddyMatcher.Match> matches = UiTestEnv.awaitValue(vm.matches(), l -> l.size() == 1);
        vm.importCode(preview.code);
        assertNotNull(UiTestEnv.awaitValue(vm.message(), m -> m.equals("Profiel van Sven bijgewerkt")));

        vm.remove(matches.get(0).buddy.riderId);
        assertNotNull(UiTestEnv.awaitValue(vm.matches(), List::isEmpty));
        vm.onCleared();
    }

    @Test
    public void rideBuddy_invalidCode_reportsReason() {
        RideBuddyViewModel vm = new RideBuddyViewModel(app);
        vm.importCode("onzin");
        assertNotNull(UiTestEnv.awaitValue(vm.message(), m -> !m.isEmpty()));
        vm.onCleared();
    }
}
