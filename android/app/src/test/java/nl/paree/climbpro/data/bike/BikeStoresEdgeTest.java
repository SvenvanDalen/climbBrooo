package nl.paree.climbpro.data.bike;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.rider.RiderProfileRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Photo store, failed writes and indoor-bike bookkeeping of the bike garage. */
@RunWith(RobolectricTestRunner.class)
public class BikeStoresEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        new File(app.getFilesDir(), BikeCostRepository.FILE).delete();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
    }

    @After
    public void tearDown() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
    }

    private void block(File f) throws IOException {
        f.mkdirs();
        Files.write(new File(f, "child").toPath(), new byte[]{1});
    }

    // ---- BikePassportPhotoStore ----

    private Context contextWith(ContentResolver resolver) {
        Context ctx = mock(Context.class);
        when(ctx.getContentResolver()).thenReturn(resolver);
        when(ctx.getApplicationContext()).thenReturn(app);
        return ctx;
    }

    private String save(String mime, byte[] bytes) throws IOException {
        Uri uri = Uri.parse("content://picker/" + mime);
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.getType(uri)).thenReturn(mime);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(bytes));
        return BikePassportPhotoStore.savePickedPhoto(contextWith(resolver), uri);
    }

    @Test
    public void photo_extensionFollowsMimeType_andBytesAreCopied() throws Exception {
        String png = save("image/png", new byte[]{1, 2});
        String pdf = save("application/pdf", new byte[]{3});
        String jpg = save("image/jpeg", new byte[]{4});
        String unknown = save(null, new byte[]{5});

        assertTrue(png.endsWith(".png"));
        assertTrue(pdf.endsWith(".pdf"));
        assertTrue(jpg.endsWith(".jpg"));
        assertTrue(unknown.endsWith(".jpg"));
        assertArrayEquals(new byte[]{1, 2},
                Files.readAllBytes(BikePassportPhotoStore.fileFor(app, png).toPath()));
    }

    @Test(expected = IOException.class)
    public void photo_unopenableSource_throws() throws Exception {
        Uri uri = Uri.parse("content://picker/x");
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.openInputStream(uri)).thenReturn(null);
        BikePassportPhotoStore.savePickedPhoto(contextWith(resolver), uri);
    }

    @Test
    public void photo_delete_ignoresBlankNames() throws Exception {
        String name = save("image/png", new byte[]{1});
        BikePassportPhotoStore.delete(app, null);
        BikePassportPhotoStore.delete(app, "");
        assertTrue(BikePassportPhotoStore.fileFor(app, name).exists());
        BikePassportPhotoStore.delete(app, name);
        assertFalse(BikePassportPhotoStore.fileFor(app, name).exists());
    }

    @Test
    public void photo_cleanupOrphans_keepsPhotosAndReceiptsOfPassports() throws Exception {
        BikePassportPhotoStore.cleanupOrphans(app, Collections.<BikePassport>emptyList()); // no dir

        String photo = save("image/png", new byte[]{1});
        String receipt = save("application/pdf", new byte[]{2});
        String orphan = save("image/jpeg", new byte[]{3});
        BikePassport p = new BikePassport();
        p.photoFileNames = Collections.singletonList(photo);
        p.receiptFileName = receipt;

        BikePassportPhotoStore.cleanupOrphans(app, Collections.singletonList(p));

        assertTrue(BikePassportPhotoStore.fileFor(app, photo).exists());
        assertTrue(BikePassportPhotoStore.fileFor(app, receipt).exists());
        assertFalse(BikePassportPhotoStore.fileFor(app, orphan).exists());
    }

    // ---- BikePassportStore ----

    @Test
    public void passport_getUnknownId_returnsNull() throws Exception {
        BikePassportStore store =
                new BikePassportStore(new File(app.getFilesDir(), BikePassportStore.FILE_NAME));
        BikePassport p = new BikePassport();
        p.name = "Racefiets";
        store.save(p);

        assertNull(store.get("nope"));
        assertEquals("Racefiets", store.get(p.id).name);
    }

    // ---- BikeCostRepository ----

    @Test
    public void garage_savingIndoorBikeAsOutdoor_clearsIndoorLink() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        String trainer = repo.saveGarageBike(null, "Trainer", "trainer", 9.0, 0, null, null,
                null, false, true);
        assertEquals(trainer, repo.load().indoorBikeId);

        repo.saveGarageBike(trainer, "Trainer", "road", 9.0, 25, null, null, null, false, false);

        assertNull(repo.load().indoorBikeId);
    }

    @Test
    public void garage_failedWrite_throwsAndLeavesNoTmp() throws Exception {
        File file = new File(app.getFilesDir(), BikeCostRepository.FILE);
        block(file);
        try {
            new BikeCostRepository(app).saveGarageBike(null, "Fiets", "road", 8, 25, null, null,
                    null, true, false);
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(new File(app.getFilesDir(), BikeCostRepository.FILE + ".tmp").exists());
    }

    @Test
    public void garage_migrationThatCannotBeSaved_stillReturnsMigratedLog() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putFloat(RiderProfileRepository.PREF_BIKE_WEIGHT_KG, 8.2f).commit();
        block(new File(app.getFilesDir(), BikeCostRepository.FILE + ".tmp"));

        BikeCostLog log = new BikeCostRepository(app).load();

        assertEquals(1, log.bikes.size());
        assertEquals(8.2, log.bikes.get(0).weightKg, 1e-6);
        assertFalse(new File(app.getFilesDir(), BikeCostRepository.FILE).exists());
        assertEquals(Arrays.asList(log.bikes.get(0).id), Arrays.asList(log.activeBikeId));
    }
}
